import { useRef, useState } from "react"
import { Navigate, useNavigate, useParams } from "react-router-dom"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import themeparkBackgroundUrl from "@/assets/child/themepark-background.svg"
import { ChildLayout } from "../layout/ChildLayout"
import { ChildButton } from "../components/ChildButton"
import { Character } from "../components/Character"
import { SpeechBubble } from "../components/SpeechBubble"
import { EmotionChoices } from "../components/quiz/EmotionChoices"
import { QuizGuideBubble } from "../components/quiz/QuizGuideBubble"
import { SelfCameraGate } from "../components/quiz/SelfCameraGate"
import {
  EXPRESSION_LABELS,
  finalMatchResult,
  judgeExpression,
  MAX_RETAKES,
  type ExpressionVerdict,
} from "../camera/expressionJudge"
import { CAMERA_MODEL_VERSION } from "../camera/faceExpressionModel"
import { useExpressionCamera } from "../hooks/useExpressionCamera"
import { ErrorState, LoadErrorDialog, LoadingState, StateDialog } from "../components/state"
import {
  ChildApiError,
  getActivityStatus,
  getMyActivities,
  getQuizQuestions,
  myActivitiesQueryKey,
  NO_MORE_HINTS,
  nextActivityPath,
  quizQuestionsQueryKey,
  requestQuizHint,
  submitQuizAnswer,
  type QuizAttempt,
  type QuizCameraFields,
  type QuizHint,
  type QuizItemWithAttempt,
  type QuizType,
} from "../api"
import { useMyActivities } from "../hooks/useMyActivities"
import { WaitingForTeacher } from "../components/WaitingForTeacher"
import { rememberChildSession, useChildSessionStore } from "../store/childSessionStore"

const CHOICE_PROMPTS: Record<QuizType, string> = {
  SELF_EMOTION_SITUATION: "지금 마음은 어떤 표정일까?",
  OTHER_EMOTION_SITUATION: "친구 얼굴을 골라줘",
  OTHER_EMOTION_IMAGE: "알맞은 마음을 골라줘",
}

/** 문항 진행 단계. 서버는 첫 답 → (틀리면) 힌트 최대 2개 → 최종 답 1번 순서로 받는다 (API-QUIZ-04·06). */
type Phase =
  | { kind: "first" }
  | {
      kind: "retry"
      firstResponse: string
      /** 첫 답과 함께 보낸 카메라 판정. 최종 답에도 그대로 다시 보낸다. */
      camera: QuizCameraFields | null
      hints: QuizHint[]
      issuedHints: number
      noMoreHints: boolean
    }
  | { kind: "done"; result: "correct" | "resolved" | "wrong" | "excluded" }

function phaseFromAttempt(attempt: QuizAttempt | null): Phase {
  if (!attempt) return { kind: "first" }
  if (attempt.status === "FINALIZED") return { kind: "done", result: resultOf(attempt, false) }
  // 다시 들어온 문항. 받은 힌트 글자는 서버가 다시 주지 않아 개수만 이어 간다.
  return {
    kind: "retry",
    firstResponse: attempt.firstResponse ?? "",
    camera: attempt.camera,
    hints: [],
    issuedHints: attempt.hintCount,
    noMoreHints: attempt.hintCount >= 2,
  }
}

/** 다시 찍기 전에 들려주는 말. 정답 여부는 서버만 알므로 표정 이야기만 한다. */
function retakeMessage(verdict: ExpressionVerdict, choice: string) {
  if (verdict.kind === "no-face") return "얼굴이 잘 안 보여. 동그라미 안에 얼굴을 맞춰 볼까?"
  if (verdict.kind === "mismatch" && verdict.detected !== "neutral") {
    return `지금은 '${EXPRESSION_LABELS[verdict.detected]}' 표정처럼 보여. '${choice}' 표정을 다시 지어 볼까?`
  }
  return `'${choice}' 표정을 조금 더 크게 지어 볼까?`
}

function resultOf(attempt: QuizAttempt, afterHint: boolean): "correct" | "resolved" | "wrong" | "excluded" {
  if (attempt.excludedFromScoring) return "excluded"
  if (!attempt.itemCorrect) return "wrong"
  return afterHint ? "resolved" : "correct"
}

/**
 * 표정 퀴즈 한 문항 (C-QZ-01~04, VS-006).
 * 판정은 서버가 한다. 첫 답이 틀리면 힌트를 보여 주고(최대 2개) 최종 답을 한 번 더 고르게 한다.
 * 최종 답도 틀리면 정답·점수 없이 다음 문제로 넘어간다.
 *
 * 내 감정 문항은 첫 답 동안 셀프 카메라를 켜고, 고른 표정을 아이가 따라 지었는지 브라우저 안에서 본다.
 * 표정 확률이 50% 미만이거나 다른 표정이면 첫 제출 전에 최대 2번 다시 찍게 하고(API-QUIZ-04),
 * 그래도 안 되면 그때 판정(MISMATCH·NOT_ANALYZABLE)으로 보낸다.
 */
function QuizQuestionView({
  question,
  isLast,
  onNext,
}: {
  question: QuizItemWithAttempt
  isLast: boolean
  onNext: () => void
}) {
  const [phase, setPhase] = useState<Phase>(() => phaseFromAttempt(question.attempt))
  const [selected, setSelected] = useState<string | null>(null)
  const isSelf = question.type === "SELF_EMOTION_SITUATION"
  // 카메라 판정은 첫 답에만 쓴다. 첫 답을 낸 뒤에는 바로 끈다.
  const camera = useExpressionCamera(isSelf && phase.kind === "first")
  const [retakes, setRetakes] = useState(0)
  const [retakeHint, setRetakeHint] = useState<string | null>(null)
  // 힌트 순서마다 요청 키 하나. 응답을 못 받아 다시 보내도 같은 힌트가 한 번만 발급된다.
  const hintKeys = useRef(new Map<number, string>())

  const hintMutation = useMutation({
    mutationFn: requestQuizHint,
    onSuccess: (hint) => {
      setPhase((prev) =>
        prev.kind === "retry"
          ? { ...prev, hints: [...prev.hints, hint], issuedHints: hint.order, noMoreHints: hint.order >= 2 }
          : prev
      )
    },
    onError: (error) => {
      // 문항에 준비된 힌트가 더 없으면 오류가 아니라 힌트 없이 다시 고르게 한다.
      if (error instanceof ChildApiError && error.code === NO_MORE_HINTS) {
        setPhase((prev) => (prev.kind === "retry" ? { ...prev, noMoreHints: true } : prev))
      }
    },
  })

  const askHint = (issuedHints: number) => {
    const order = issuedHints + 1
    if (!hintKeys.current.has(order)) hintKeys.current.set(order, crypto.randomUUID())
    hintMutation.mutate({ activityQuizId: question.activityQuizId, requestKey: hintKeys.current.get(order)! })
  }

  const submitMutation = useMutation({
    mutationFn: submitQuizAnswer,
    onSuccess: (attempt, input) => {
      setSelected(null)
      if (attempt.status === "FINALIZED") {
        setPhase({ kind: "done", result: resultOf(attempt, input.finalResponse !== undefined) })
        return
      }
      // 첫 답이 틀렸다. 첫 힌트를 바로 받아 보여 준다.
      setPhase({
        kind: "retry",
        firstResponse: input.firstResponse,
        camera: input.camera ?? null,
        hints: [],
        issuedHints: 0,
        noMoreHints: false,
      })
      askHint(0)
    },
  })

  const isAnswering = phase.kind !== "done"
  const isBusy = submitMutation.isPending || hintMutation.isPending
  const latestHint = phase.kind === "retry" ? phase.hints.at(-1) : undefined
  // 기술 오류는 오답이 아니므로 같은 요청을 그대로 다시 보낸다 (VS-006 기술 제외).
  const showSubmitError = submitMutation.isError && !submitMutation.isPending
  const hintFailed =
    hintMutation.isError &&
    !hintMutation.isPending &&
    !(hintMutation.error instanceof ChildApiError && hintMutation.error.code === NO_MORE_HINTS)

  /** 내 감정 문항 첫 답의 카메라 판정. 다시 찍게 할 때는 null 을 주고 제출하지 않는다. */
  const judgeCamera = (choice: string): QuizCameraFields | null => {
    if (camera.status === "denied") return { technicalFailureReason: "CAMERA_PERMISSION_DENIED" }
    if (camera.status !== "ready") {
      return { expressionMatchResult: "NOT_ANALYZABLE", cameraModelVersion: CAMERA_MODEL_VERSION }
    }
    const verdict = judgeExpression(choice, camera.readScores())
    if (verdict.kind !== "match" && verdict.kind !== "unsupported" && retakes < MAX_RETAKES) {
      setRetakes(retakes + 1)
      setRetakeHint(retakeMessage(verdict, choice))
      return null
    }
    return { expressionMatchResult: finalMatchResult(verdict), cameraModelVersion: CAMERA_MODEL_VERSION }
  }

  const submit = () => {
    if (!selected) return
    if (phase.kind === "retry") {
      submitMutation.mutate({
        activityQuizId: question.activityQuizId,
        type: question.type,
        firstResponse: phase.firstResponse,
        finalResponse: selected,
        camera: phase.camera,
      })
      return
    }
    let cameraFields: QuizCameraFields | undefined
    if (isSelf) {
      const judged = judgeCamera(selected)
      if (!judged) return
      cameraFields = judged
    }
    setRetakeHint(null)
    submitMutation.mutate({
      activityQuizId: question.activityQuizId,
      type: question.type,
      firstResponse: selected,
      camera: cameraFields,
    })
  }

  // 카메라가 켜지는 동안은 판정할 수 없으니 기다린다. 권한을 묻는 창이 떠 있는 동안도 여기에 든다.
  const cameraStarting = isSelf && phase.kind === "first" && camera.status === "starting"

  const answerPanel = (
    <div className="flex flex-col gap-4 text-left">
      <div className="flex flex-col gap-1">
        <p className="font-child-display text-2xl font-extrabold text-[#252331]">{CHOICE_PROMPTS[question.type]}</p>
        {isSelf ? <p className="text-sm text-[#6B6776]">표정을 선택하고 똑같이 따라 해보세요</p> : null}
      </div>
      <EmotionChoices
        choices={question.choices}
        selectedValue={selected}
        onSelect={setSelected}
        disabled={!isAnswering || isBusy}
      />

      <div aria-live="polite">
        {phase.kind === "retry" ? (
          <SpeechBubble character="rabbit" mood="sad" characterAlign="top">
            괜찮아, 다시 한번 살펴볼까?
            {latestHint ? (
              <span className="mt-1 block font-extrabold text-[var(--child-text)]">
                힌트 {latestHint.order}: {latestHint.text}
              </span>
            ) : hintMutation.isPending ? (
              <span className="mt-1 block text-base text-[var(--child-text-muted)]">힌트를 가져오고 있어요…</span>
            ) : null}
          </SpeechBubble>
        ) : phase.kind === "done" ? (
          phase.result === "correct" ? (
            <SpeechBubble character="turtle" mood="happy">맞아! 잘 찾았어</SpeechBubble>
          ) : phase.result === "resolved" ? (
            <SpeechBubble character="turtle" mood="happy">맞아! 힌트를 보고 잘 찾았어</SpeechBubble>
          ) : phase.result === "excluded" ? (
            <SpeechBubble character="turtle" mood="happy">네 마음을 알려줘서 고마워!</SpeechBubble>
          ) : (
            <SpeechBubble character="turtle" mood="wink">괜찮아! 다음 문제로 가볼까?</SpeechBubble>
          )
        ) : retakeHint ? (
          <SpeechBubble character="rabbit" mood="wink" characterAlign="top">
            {retakeHint}
            <span className="mt-1 block text-base text-[var(--child-text-muted)]">
              {/* retakes 는 지금까지 준 다시 찍기 수. 방금 준 기회도 아직 쓰지 않았으므로 남은 수에 넣는다. */}
              다시 찍을 수 있는 기회: {MAX_RETAKES - retakes + 1}번 남았어요
            </span>
          </SpeechBubble>
        ) : null}
      </div>

      {isAnswering ? (
        <div className="flex flex-col gap-3">
          <ChildButton
            className="h-16 w-full font-child-display text-xl font-extrabold"
            onClick={submit}
            disabled={!selected || isBusy || cameraStarting}
          >
            {submitMutation.isPending
              ? "확인하고 있어요…"
              : cameraStarting
                ? "카메라를 준비하고 있어요…"
              : phase.kind === "retry"
                ? "다시 골라볼래요"
                : "이 표정으로 확인할래요"}
          </ChildButton>
          {phase.kind === "retry" && !phase.noMoreHints ? (
            <ChildButton
              variant="outline"
              className="h-14 w-full text-lg"
              onClick={() => askHint(phase.issuedHints)}
              disabled={isBusy}
            >
              {phase.issuedHints === 0 ? "힌트 볼래요" : "힌트 하나 더 볼래요"}
            </ChildButton>
          ) : null}
        </div>
      ) : (
        <ChildButton className="h-16 w-full font-child-display text-xl font-extrabold" onClick={onNext}>
          {isLast ? "다 풀었어요" : "다음 문제"}
        </ChildButton>
      )}

      {isSelf ? (
        <div className="flex items-start justify-between gap-3">
          <p className="text-sm text-[#6B6776]">다른 마음도 괜찮아요. 내 마음과 가장 가까운 표정을 골라요.</p>
          {/* Figma 오른쪽 아래 깡총이 자리. 장식이라 읽지 않는다. */}
          <div aria-hidden="true" className="hidden shrink-0 md:block">
            <Character name="rabbit" size="md" />
          </div>
        </div>
      ) : null}
    </div>
  )

  return (
    // break-keep은 상속되므로 말풍선·보기 문구가 단어 중간에서 줄바꿈되지 않는다.
    <div className="mx-auto flex w-full max-w-[56rem] flex-col gap-6 break-keep">
      <QuizGuideBubble question={question.questionText} />

      {/* 포토 게이트는 Figma 신버전 시안이 있는 내 감정 문항(C-QZ-01)에만 둔다.
          친구 감정 문항(C-QZ-02·03)은 신버전 시안이 없어 와이어프레임 배치를 따르고, 깡총이는 힌트 때만 나온다. */}
      {isSelf ? (
        <div className="grid items-start gap-6 md:grid-cols-[minmax(0,22.5rem)_1fr] md:gap-8">
          <SelfCameraGate
            status={camera.status}
            faceFound={camera.faceFound}
            attachVideo={camera.attachVideo}
            onRestart={camera.restart}
          />
          {answerPanel}
        </div>
      ) : question.type === "OTHER_EMOTION_IMAGE" && question.imageUrl ? (
        <div className="grid items-center gap-6 md:grid-cols-[minmax(0,16rem)_1fr] md:gap-8">
          <div className="mx-auto flex size-56 items-center justify-center overflow-hidden rounded-full bg-white shadow-sm md:size-64">
            {/* 서버 문항 이미지. 정답이 드러나지 않도록 감정 이름 대신 "친구 얼굴"로 읽게 한다. */}
            <img src={question.imageUrl} alt="친구 얼굴" className="size-[72%] object-contain" />
          </div>
          {answerPanel}
        </div>
      ) : (
        <div className="mx-auto w-full max-w-[43rem]">{answerPanel}</div>
      )}

      <StateDialog open={showSubmitError}>
        <ErrorState
          onRetry={() => {
            if (submitMutation.variables) submitMutation.mutate(submitMutation.variables)
          }}
        />
      </StateDialog>
      <StateDialog open={hintFailed}>
        {/* 같은 키로 다시 요청해 힌트가 두 번 발급되지 않게 한다. */}
        <ErrorState
          message="힌트를 가져오지 못했어요"
          onRetry={() => {
            if (hintMutation.variables) hintMutation.mutate(hintMutation.variables)
          }}
        />
      </StateDialog>
    </div>
  )
}

/**
 * C-QZ-05 퀴즈 완료. 정확도는 보여주지 않는다.
 * 최초 결합은 역할극으로 이어 가고, 독립 퀴즈는 마지막 문항 확정 때 서버가 이미 완료·스탬프를 저장했으므로 스탬프 화면으로 간다.
 */
function QuizDoneView({
  total,
  hasRoleplay,
  isPending,
  onContinue,
}: {
  total: number
  hasRoleplay: boolean
  isPending: boolean
  onContinue: () => void
}) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center">
      <div className="flex w-full max-w-lg flex-col items-center gap-4 rounded-[var(--child-radius-card)] bg-[var(--child-surface)]/95 px-8 py-10 text-center shadow-sm">
        <div className="flex items-end gap-2">
          <Character name="turtle" size="md" />
          <Character name="rabbit" size="md" />
        </div>
        <div className="flex flex-col gap-1">
          <p className="font-child-display font-extrabold text-3xl break-keep text-[var(--child-text)]">
            표정 퀴즈 {total}개를 다 풀었어!
          </p>
          <p className="text-lg text-[var(--child-text-muted)]">친구 마음을 잘 살펴봤구나</p>
        </div>
        {hasRoleplay ? (
          <p className="text-lg break-keep text-[var(--child-text)]">
            이제 친구랑 이야기해보는 역할극을 해볼 거야
          </p>
        ) : null}
        <ChildButton className="h-16 w-full text-xl" onClick={onContinue} disabled={isPending}>
          {isPending ? (hasRoleplay ? "확인하고 있어요…" : "스탬프를 준비하고 있어요…") : hasRoleplay ? "역할극 하러 가기" : "스탬프 받으러 가기"}
        </ChildButton>
      </div>
    </div>
  )
}

/** 표정 퀴즈 화면 (C-QZ-01~05). 배정된 문항을 순서대로 풀고, 이미 확정한 문항은 건너뛴다 (VS-005·006). */
function ChildQuizPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { activityId } = useParams()
  const childId = useChildSessionStore((state) => state.childId)
  // 처음 그릴 때 이어서 풀 문항을 정한다. 그 뒤로는 목록을 다시 받아도 아이가 보고 있는 문항을 바꾸지 않는다.
  const [questionIndex, setQuestionIndex] = useState<number | null>(null)

  const activitiesQuery = useMyActivities()
  const questionsQuery = useQuery({
    queryKey: quizQuestionsQueryKey(childId, activityId),
    queryFn: ({ signal }) => getQuizQuestions(activityId ?? "", signal),
    enabled: Boolean(activityId),
  })

  // 독립 퀴즈는 따로 완료 요청이 없다. 서버가 완료를 확인해 준 뒤에만 스탬프 화면으로 간다 (API-ACT-04).
  const completionMutation = useMutation({
    mutationFn: async (id: string) => {
      const status = await getActivityStatus(id)
      if (status !== "COMPLETED") throw new Error("활동이 아직 완료되지 않았습니다.")
      return id
    },
    onMutate: () => ({ isSameSession: rememberChildSession() }),
    onSuccess: (id, _input, context) => {
      // 응답을 기다리는 사이 사용 종료했거나 다른 아동이 들어왔으면 완료 화면으로 가지 않는다.
      if (!context.isSameSession()) return
      queryClient.invalidateQueries({ queryKey: myActivitiesQueryKey(childId) })
      navigate(`/child/done/${id}`, { state: { completion: { activityId: id } } })
    },
  })

  // 최초 결합은 퀴즈 뒤 선생님이 시나리오를 확정해야 역할극이 열린다(API-SCN-04). 목록을 다시 받아 서버의 다음 동작으로 간다.
  const [waiting, setWaiting] = useState<"wait" | "blocked" | null>(null)
  const nextMutation = useMutation({
    mutationFn: async (id: string) => {
      const list = await getMyActivities(childId ?? "")
      queryClient.setQueryData(myActivitiesQueryKey(childId), list)
      const found = list.find((item) => item.activityId === id)
      if (!found) throw new Error("활동을 찾지 못했습니다.")
      return found
    },
    onMutate: () => ({ isSameSession: rememberChildSession() }),
    onSuccess: (found, _id, context) => {
      if (!context.isSameSession()) return
      const path = nextActivityPath(found)
      if (path && found.nextAction === "ROLEPLAY") navigate(path)
      else setWaiting(found.nextAction === "BLOCKED" ? "blocked" : "wait")
    },
  })

  const activity = activitiesQuery.data?.find((item) => item.activityId === activityId)
  const questions = questionsQuery.data ?? []
  const firstUnfinished = questions.findIndex((item) => item.attempt?.status !== "FINALIZED")
  const currentIndex = questionIndex ?? (firstUnfinished === -1 ? questions.length : firstUnfinished)
  const isDone = questionsQuery.isSuccess && currentIndex >= questions.length
  const question = questions[currentIndex]

  const isLoading =
    activitiesQuery.isPending ||
    questionsQuery.isPending ||
    ((activitiesQuery.isError || questionsQuery.isError) &&
      (activitiesQuery.isFetching || questionsQuery.isFetching))
  const showLoadError =
    (activitiesQuery.isError || questionsQuery.isError) &&
    !activitiesQuery.isFetching &&
    !questionsQuery.isFetching
  const showCompleteError =
    (completionMutation.isError && !completionMutation.isPending) || (nextMutation.isError && !nextMutation.isPending)

  // 내 배정 목록(서버가 이 아동 것만 준다)에 없거나 이미 끝낸 활동이면 목록으로 돌려보낸다 (VS-003).
  // 독립 퀴즈는 마지막 답으로 완료되므로, 완료 확인을 기다리는 동안에는 돌려보내지 않는다.
  if (
    activitiesQuery.isSuccess &&
    (!activity || (activity.status === "COMPLETED" && !completionMutation.isPending && !completionMutation.isSuccess))
  ) {
    return <Navigate to="/child/activities" replace />
  }

  const goNext = () => setQuestionIndex(currentIndex + 1)
  const hasRoleplay = (activity?.roleplayCount ?? 0) > 0

  const retryLoad = () => {
    if (activitiesQuery.isError) activitiesQuery.refetch()
    if (questionsQuery.isError) questionsQuery.refetch()
  }

  return (
    <ChildLayout
      headerCenter={
        <p className="flex items-center gap-2 rounded-[var(--child-radius-pill)] bg-[#6654D9] px-5 py-2 text-sm font-bold text-white shadow-sm">
          <span>표정 퀴즈</span>
          <span>{isDone ? "완료" : questions.length ? `${currentIndex + 1}/${questions.length} 문항` : null}</span>
        </p>
      }
      backgroundImage={themeparkBackgroundUrl}
      dimBackground
      wide
    >
      {isLoading ? (
        <div className="flex flex-1 items-center justify-center">
          <LoadingState message="퀴즈를 준비하고 있어요" />
        </div>
      ) : waiting ? (
        <WaitingForTeacher blocked={waiting === "blocked"} />
      ) : isDone && activityId ? (
        <QuizDoneView
          total={questions.length}
          hasRoleplay={hasRoleplay}
          isPending={completionMutation.isPending || nextMutation.isPending}
          onContinue={() => {
            if (hasRoleplay) nextMutation.mutate(activityId)
            else completionMutation.mutate(activityId)
          }}
        />
      ) : question ? (
        <QuizQuestionView
          key={question.activityQuizId}
          question={question}
          isLast={currentIndex + 1 >= questions.length}
          onNext={goNext}
        />
      ) : null}

      <LoadErrorDialog
        open={showLoadError}
        error={activitiesQuery.error ?? questionsQuery.error}
        message="퀴즈를 불러오지 못했어요"
        onRetry={retryLoad}
      />
      <StateDialog open={showCompleteError}>
        <ErrorState
          onRetry={() => {
            if (!activityId) return
            if (hasRoleplay) nextMutation.mutate(activityId)
            else completionMutation.mutate(activityId)
          }}
        />
      </StateDialog>
    </ChildLayout>
  )
}

export { ChildQuizPage }
