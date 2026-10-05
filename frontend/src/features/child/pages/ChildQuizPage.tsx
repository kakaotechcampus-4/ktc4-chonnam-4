import { useState } from "react"
import { Navigate, useNavigate, useParams } from "react-router-dom"
import { useMutation, useQuery } from "@tanstack/react-query"
import { ScanFace } from "lucide-react"
import themeparkBackgroundUrl from "@/assets/child/themepark-background.svg"
import { ChildLayout } from "../layout/ChildLayout"
import { ChildButton } from "../components/ChildButton"
import { Character } from "../components/Character"
import { EmotionFace } from "../components/EmotionFace"
import { SpeechBubble } from "../components/SpeechBubble"
import { EmotionChoices } from "../components/quiz/EmotionChoices"
import { ErrorState, LoadingState, StateDialog } from "../components/state"
import {
  getMyActivities,
  getQuizQuestions,
  myActivitiesQueryKey,
  quizQuestionsQueryKey,
  submitQuizAnswer,
  type QuizHint,
  type QuizQuestion,
  type QuizType,
} from "../api"
import { useChildSessionStore } from "../store/childSessionStore"

const CHOICE_PROMPTS: Record<QuizType, string> = {
  SELF_EMOTION_SITUATION: "지금 마음은 어떤 표정일까?",
  OTHER_EMOTION_SITUATION: "친구 얼굴을 골라줘",
  OTHER_EMOTION_IMAGE: "알맞은 마음을 골라줘",
}

type Feedback =
  | { kind: "none" }
  | { kind: "hint"; hint: QuizHint }
  | { kind: "correct" }
  | { kind: "final" }

/**
 * 표정 퀴즈 한 문항 (C-QZ-01~04, VS-006).
 * 판정은 서버가 하고, 오답이면 힌트와 함께 보기를 2개로 줄여 최대 두 번 더 고르게 한다.
 * 힌트를 모두 써도 틀리면 정답·점수 없이 다음 문제로 넘어간다.
 */
function QuizQuestionView({
  activityId,
  question,
  isLast,
  onNext,
}: {
  activityId: string
  question: QuizQuestion
  isLast: boolean
  onNext: () => void
}) {
  const [selectedChoiceId, setSelectedChoiceId] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(1)
  const [visibleChoiceIds, setVisibleChoiceIds] = useState<string[] | null>(null)
  const [feedback, setFeedback] = useState<Feedback>({ kind: "none" })

  const submitMutation = useMutation({
    mutationFn: submitQuizAnswer,
    onSuccess: (answer) => {
      if (answer.result === "CORRECT") {
        setFeedback({ kind: "correct" })
      } else if (answer.result === "INCORRECT") {
        setFeedback({ kind: "hint", hint: answer.hint })
        setVisibleChoiceIds(answer.hint.choiceIds)
        setAttempt((prev) => prev + 1)
        setSelectedChoiceId(null)
      } else {
        setFeedback({ kind: "final" })
      }
    },
  })

  const isAnswering = feedback.kind === "none" || feedback.kind === "hint"
  const choices = visibleChoiceIds
    ? question.choices.filter((choice) => visibleChoiceIds.includes(choice.choiceId))
    : question.choices
  // 기술 오류는 오답이 아니므로 같은 응답을 그대로 다시 보낸다 (VS-006 기술 제외).
  const showSubmitError = submitMutation.isError && !submitMutation.isPending

  const submit = () => {
    if (!selectedChoiceId) return
    submitMutation.mutate({ activityId, questionId: question.questionId, choiceId: selectedChoiceId, attempt })
  }

  const visual =
    question.type === "SELF_EMOTION_SITUATION" ? (
      // 표정 인식 기능 도입 여부가 미정이라 Figma의 얼굴 프레임만 둔다.
      <div className="flex flex-col items-center gap-3 rounded-[var(--child-radius-card)] bg-[var(--child-surface)]/90 p-5 shadow-sm">
        <div className="flex h-44 w-36 items-center justify-center rounded-[50%] border-4 border-dashed border-[var(--child-primary)]/60 text-[var(--child-primary)]/60">
          <ScanFace className="size-14" />
        </div>
        <p className="text-base font-semibold break-keep text-[var(--child-text)]">
          얼굴을 화면 안에 맞추고 표정을 지어봐!
        </p>
      </div>
    ) : question.type === "OTHER_EMOTION_IMAGE" && question.imageEmotion ? (
      <div className="flex items-center justify-center rounded-[var(--child-radius-card)] bg-[var(--child-surface)]/90 p-6 shadow-sm">
        {/* 정답이 드러나지 않도록 감정 이름 대신 "친구 얼굴"로 읽게 한다. */}
        <EmotionFace emotion={question.imageEmotion} label="친구 얼굴" className="size-40" />
      </div>
    ) : null

  return (
    // break-keep은 상속되므로 말풍선·보기 문구가 단어 중간에서 줄바꿈되지 않는다.
    <div className="flex flex-col gap-5 break-keep">
      <SpeechBubble character="turtle">
        {question.situation ? `${question.situation} ` : ""}
        {question.question}
      </SpeechBubble>

      <div className={visual ? "grid items-start gap-5 md:grid-cols-[1fr_1.5fr]" : "flex flex-col gap-5"}>
        {visual}

        <div className="flex flex-col gap-4">
          <p className="text-xl font-bold text-[var(--child-text)]">{CHOICE_PROMPTS[question.type]}</p>
          <EmotionChoices
            choices={choices}
            selectedChoiceId={selectedChoiceId}
            onSelect={setSelectedChoiceId}
            disabled={!isAnswering || submitMutation.isPending}
          />

          <div aria-live="polite">
            {feedback.kind === "hint" ? (
              <SpeechBubble character="rabbit" characterAlign="top">
                괜찮아, 다시 한번 살펴볼까?
                <span className="mt-1 block font-extrabold text-[var(--child-text)]">
                  힌트 {feedback.hint.level}: {feedback.hint.text}
                </span>
                <span className="mt-1 block text-base text-[var(--child-text-muted)]">
                  보기가 두 개로 줄었어! 다시 골라봐
                </span>
              </SpeechBubble>
            ) : feedback.kind === "correct" ? (
              <SpeechBubble character="turtle">맞아! 잘 찾았어</SpeechBubble>
            ) : feedback.kind === "final" ? (
              <SpeechBubble character="turtle">괜찮아! 다음 문제로 가볼까?</SpeechBubble>
            ) : null}
          </div>

          {isAnswering ? (
            <ChildButton
              className="h-16 w-full text-xl"
              onClick={submit}
              disabled={!selectedChoiceId || submitMutation.isPending}
            >
              {submitMutation.isPending
                ? "확인하고 있어요…"
                : feedback.kind === "hint"
                  ? "다시 골라볼래요"
                  : "확인했어요"}
            </ChildButton>
          ) : (
            <ChildButton className="h-16 w-full text-xl" onClick={onNext}>
              {isLast ? "다 풀었어요" : "다음 문제"}
            </ChildButton>
          )}
        </div>
      </div>

      <StateDialog open={showSubmitError}>
        <ErrorState
          onRetry={() => {
            if (submitMutation.variables) submitMutation.mutate(submitMutation.variables)
          }}
        />
      </StateDialog>
    </div>
  )
}

/** C-QZ-05 퀴즈 완료. 정확도는 보여주지 않고 다음 활동(역할극)으로 이어 준다. */
function QuizDoneView({ total, onContinue }: { total: number; onContinue: () => void }) {
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
        <p className="text-lg break-keep text-[var(--child-text)]">
          이제 친구랑 이야기해보는 역할극을 해볼 거야
        </p>
        <ChildButton className="h-16 w-full text-xl" onClick={onContinue}>
          역할극 하러 가기
        </ChildButton>
      </div>
    </div>
  )
}

/** 표정 퀴즈 화면 (C-QZ-01~05). 최대 3문항을 순서대로 푼다 (VS-005·006). */
function ChildQuizPage() {
  const navigate = useNavigate()
  const { activityId } = useParams()
  const childId = useChildSessionStore((state) => state.childId)
  const [questionIndex, setQuestionIndex] = useState(0)
  const [isDone, setIsDone] = useState(false)

  const activitiesQuery = useQuery({
    queryKey: myActivitiesQueryKey(childId),
    queryFn: getMyActivities,
  })
  const questionsQuery = useQuery({
    queryKey: quizQuestionsQueryKey(childId, activityId),
    queryFn: () => getQuizQuestions(activityId ?? ""),
    enabled: Boolean(activityId),
  })

  const activity = activitiesQuery.data?.find((item) => item.activityId === activityId)
  const questions = questionsQuery.data ?? []
  const question = questions[questionIndex]

  const isLoading =
    activitiesQuery.isPending ||
    questionsQuery.isPending ||
    ((activitiesQuery.isError || questionsQuery.isError) &&
      (activitiesQuery.isFetching || questionsQuery.isFetching))
  const showLoadError =
    (activitiesQuery.isError || questionsQuery.isError) &&
    !activitiesQuery.isFetching &&
    !questionsQuery.isFetching

  // 내 배정 목록에 없거나 이미 끝낸 활동이면 목록으로 돌려보낸다 (VS-003, 최종 차단은 서버 403).
  if (activitiesQuery.isSuccess && (!activity || activity.status === "COMPLETED")) {
    return <Navigate to="/child/activities" replace />
  }

  const goNext = () => {
    if (questionIndex + 1 >= questions.length) setIsDone(true)
    else setQuestionIndex((prev) => prev + 1)
  }

  const retryLoad = () => {
    if (activitiesQuery.isError) activitiesQuery.refetch()
    if (questionsQuery.isError) questionsQuery.refetch()
  }

  return (
    <ChildLayout
      activityTitle="표정 퀴즈"
      stepLabel={isDone ? "완료" : questions.length ? `${questionIndex + 1}/${questions.length} 문항` : undefined}
      backgroundImage={themeparkBackgroundUrl}
    >
      {isLoading ? (
        <div className="flex flex-1 items-center justify-center">
          <LoadingState message="퀴즈를 준비하고 있어요" />
        </div>
      ) : isDone ? (
        <QuizDoneView
          total={questions.length}
          onContinue={() => navigate(`/child/roleplay/${activityId}`)}
        />
      ) : question && activityId ? (
        <QuizQuestionView
          key={question.questionId}
          activityId={activityId}
          question={question}
          isLast={questionIndex + 1 >= questions.length}
          onNext={goNext}
        />
      ) : null}

      <StateDialog open={showLoadError}>
        <ErrorState message="퀴즈를 불러오지 못했어요" onRetry={retryLoad} />
      </StateDialog>
    </ChildLayout>
  )
}

export { ChildQuizPage }
