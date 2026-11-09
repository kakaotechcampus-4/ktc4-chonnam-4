import { useEffect, useRef, useState } from "react"
import { Navigate, useNavigate, useParams } from "react-router-dom"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Keyboard, Mic, Volume2 } from "lucide-react"
import themeparkBackgroundUrl from "@/assets/child/themepark-background.svg"
import { ChildLayout } from "../layout/ChildLayout"
import { ChildButton } from "../components/ChildButton"
import { Character } from "../components/Character"
import { SpeechBubble } from "../components/SpeechBubble"
import {
  RoleplayComposer,
  type RoleplayInputMode,
  type VoiceResult,
} from "../components/roleplay/RoleplayComposer"
import type { VoiceRecorderError } from "../lib/voiceRecorder"
import { playSpeech, stopSpeech } from "../lib/speechPlayer"
import { splitStageDirection } from "../lib/stageDirection"
import { RabbitHintBubble } from "../components/roleplay/RabbitHintBubble"
import { ErrorState, LoadErrorDialog, LoadingState, StateDialog } from "../components/state"
import { SessionLostDialog } from "../components/state/LoadErrorDialog"
import { isSessionLost } from "../components/state/sessionLost"
import {
  ChildApiError,
  getActivityStatus,
  myActivitiesQueryKey,
  roleplaySessionQueryKey,
  sendRoleplayTurn,
  startRoleplay,
  type RoleplaySpeaker,
  type RoleplayTurnInput,
} from "../api"
import { useMyActivities } from "../hooks/useMyActivities"
import { WaitingForTeacher } from "../components/WaitingForTeacher"
import { useChildSessionStore } from "../store/childSessionStore"

type ThreadMessage = {
  id: string
  speaker: RoleplaySpeaker | "child"
  text: string
  /** 아이 답의 입력 방식. 말풍선 옆 아이콘(마이크·키보드)으로 보여준다. */
  inputMode?: RoleplayInputMode
  /** 거북이 대사의 음성(TTS). 있으면 도착할 때 한 번 재생하고 "다시 듣기"로 또 들을 수 있다. */
  audioUrl?: string
}

// 깡총이가 화면 끝까지 닿도록 레이아웃을 wide로 쓰고, 나머지 대화·입력은 기존 본문 폭(688px)에 맞춘다.
const COLUMN = "mx-auto w-full max-w-[43rem]"

function ThreadBubble({ message, characterName }: { message: ThreadMessage; characterName: string | null }) {
  // 깡총이(힌트·도움)는 Figma C-RP-02처럼 화면 오른쪽 끝에서 튀어나온다. 역할극에서만 이렇게 보여 준다.
  if (message.speaker === "rabbit") return <RabbitHintBubble>{message.text}</RabbitHintBubble>

  if (message.speaker === "child") {
    return (
      <div className={`${COLUMN} flex items-center justify-end gap-2`}>
        {/* 띄어쓰기 없이 긴 답("ㅋㅋㅋ…" 200자 등)도 말풍선 안에서 줄을 바꾼다. 안 그러면 화면이 가로로 넘친다. */}
        <p className="max-w-[80%] rounded-[var(--child-radius-card)] rounded-br-md bg-[#EDE9FE] px-5 py-3 text-lg font-medium wrap-anywhere text-[var(--child-text)] shadow-sm">
          “{message.text}”
        </p>
        <span
          className="flex size-9 shrink-0 items-center justify-center rounded-full bg-[var(--child-primary)] text-white [&_svg]:size-4"
          aria-label={message.inputMode === "voice" ? "말로 답함" : "글자로 답함"}
          role="img"
        >
          {message.inputMode === "voice" ? <Mic /> : <Keyboard />}
        </span>
      </div>
    )
  }
  // 캐릭터 대사의 괄호 지문은 괄호 없이 대사 위 작은 글씨로 보여 준다.
  const { action, speech } = splitStageDirection(message.text)
  if (!message.audioUrl) {
    return (
      <SpeechBubble character="turtle" className={COLUMN} name={characterName} action={action}>
        {speech}
      </SpeechBubble>
    )
  }
  const audioUrl = message.audioUrl
  return (
    <div className={`${COLUMN} flex items-end gap-2`}>
      <SpeechBubble character="turtle" name={characterName} action={action}>
        {speech}
      </SpeechBubble>
      <button
        type="button"
        onClick={() => void playSpeech(audioUrl)}
        aria-label="다시 듣기"
        className="mb-1 flex size-11 shrink-0 items-center justify-center rounded-full bg-[var(--child-surface)] text-[var(--child-primary)] shadow-sm outline-none focus-visible:ring-3 focus-visible:ring-[var(--child-primary)]/50 [&_svg]:size-5"
      >
        <Volume2 />
      </button>
    </div>
  )
}

/**
 * C-RP-06 역할극 마무리. 점수 없이 칭찬만 한다.
 * 완료되면 아이 답(표준 발화) 사본을 지우는 규칙(API-RPL-04 acceptedInput)에 따라 아이가 한 말은 되돌려 보여 주지 않는다.
 * 완료·스탬프는 서버가 이미 저장했으므로 "다음으로"는 스탬프 화면으로 가기만 한다.
 */
function RoleplayWrapUpView({ childName, onNext }: { childName: string; onNext: () => void }) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center">
      <div className="flex w-full max-w-lg flex-col items-center gap-4 rounded-[var(--child-radius-card)] bg-[var(--child-surface)]/95 px-8 py-10 text-center break-keep shadow-sm">
        <div className="flex items-end gap-2">
          <Character name="turtle" size="md" />
          <Character name="rabbit" size="md" />
        </div>
        <div className="flex flex-col gap-1">
          <p className="font-child-display font-extrabold text-3xl text-[var(--child-text)]">정말 잘 이야기했어!</p>
          <p className="text-lg text-[var(--child-text-muted)]">
            {childName}, 친구 마음을 헤아려서 말해준 게 참 멋있었어
          </p>
        </div>
        <ChildButton className="h-16 w-full text-xl" onClick={onNext}>
          다음으로
        </ChildButton>
      </div>
    </div>
  )
}

/** 역할극을 더 이어 갈 수 없을 때(안전 중단·일시 중단 등). 다시 시작하지 않고 내 활동으로 돌아가게 한다. */
function RoleplayStoppedView({ onHome }: { onHome: () => void }) {
  return (
    <div className="flex w-full flex-col items-center gap-3 rounded-[var(--child-radius-card)] bg-[var(--child-surface)]/95 px-6 py-6 text-center break-keep shadow-sm">
      <p className="text-lg text-[var(--child-text)]">오늘 이야기는 여기까지 할게. 선생님과 함께 다시 해보자!</p>
      <ChildButton className="h-14 w-full text-lg" onClick={onHome}>
        내 활동으로 돌아가기
      </ChildButton>
    </div>
  )
}

// 서버 고정 안내 문구가 비어 올 때(NOTICE_UNAVAILABLE) 대신 보여 주는 깡총이 말. API-RPL-04 공통 재입력 문구와 같다.
const FALLBACK_NOTICE = "다시 한 번만 말해줄래?"

// 녹음이 서버에 가기 전에 끝났을 때 깡총이가 하는 말.
const VOICE_TIMEOUT_NOTICE = "시간이 다 됐어. 조금 짧게 다시 말해줄래?"
const VOICE_UNAVAILABLE_NOTICE: Record<VoiceRecorderError, string> = {
  denied: "마이크를 쓸 수 없어. 선생님께 마이크를 켜 달라고 하거나 글자로 알려줄래?",
  unsupported: "여기서는 말로 답할 수 없어. 글자로 알려줄래?",
  failed: "마이크를 찾지 못했어. 글자로 알려줄래?",
}

/**
 * 역할극 화면 (C-RP-02·04·06, VS-010·011·013).
 * 시작·이어 하기는 API-RPL-01, 말로 답하기는 API-RPL-04(녹음 파일 → 서버 STT), 글자 답은 API-RPL-05 로 보낸다.
 * 완료·스탬프는 서버가 정하므로 대화가 이어질 때마다 활동 상태(API-ACT-04)를 읽어 COMPLETED 면 마무리한다.
 * 음성 실패가 쌓여 서버가 글자를 권하면(SUGGEST_TEXT_KEEP_VOICE) 글자 모드로 바꾸고 마이크는 그대로 둔다. 일시 중단(C-RP-05)은 아직 연결하지 않았다.
 */
function ChildRoleplayPage() {
  const navigate = useNavigate()
  const { activityId } = useParams()
  const childId = useChildSessionStore((state) => state.childId)
  const childName = useChildSessionStore((state) => state.childName) ?? ""

  const [replies, setReplies] = useState<ThreadMessage[]>([])
  const [inputMode, setInputMode] = useState<RoleplayInputMode>("voice")
  const [isFinished, setIsFinished] = useState(false)
  const [isStopped, setIsStopped] = useState(false)
  const [isWrappedUp, setIsWrappedUp] = useState(false)
  const messageCountRef = useRef(0)
  const threadEndRef = useRef<HTMLDivElement>(null)
  const queryClient = useQueryClient()
  // 시작 요청 키는 화면에 머무는 동안 하나다. 응답을 못 받아 다시 불러도 세션은 하나만 생긴다.
  const [startKey] = useState(() => crypto.randomUUID())

  const activitiesQuery = useMyActivities()
  const listedActivity = activitiesQuery.data?.find((item) => item.activityId === activityId)
  const sessionQuery = useQuery({
    queryKey: roleplaySessionQueryKey(childId, activityId),
    queryFn: () => startRoleplay(activityId ?? "", startKey),
    // 서버가 역할극을 열어 준 활동(nextAction ROLEPLAY)만 시작한다. 선생님 확정 대기 중에는 시작 요청을 보내지 않는다.
    enabled: Boolean(activityId) && listedActivity?.nextAction === "ROLEPLAY",
    // 시작은 변경 요청이라 화면에 머무는 동안 다시 보내지 않는다. 다시 들어오면 새로 불러 서버 세션을 이어 간다.
    staleTime: Infinity,
    gcTime: 0,
    refetchOnWindowFocus: false,
  })

  const nextId = (prefix: string) => {
    messageCountRef.current += 1
    return `${prefix}-${messageCountRef.current}`
  }

  // 깡총이 힌트는 다음 답을 위한 참고라 기록에 쌓지 않는다. 새 힌트가 오면 이전 힌트를 지우고 마지막 힌트 1개만 남긴다.
  // 아이가 그 힌트를 보고 답했다는 흐름이 보이도록, 마지막 힌트는 나온 자리에 그대로 둔다.
  const showRabbitHint = (text: string) => {
    const hint: ThreadMessage = { id: nextId("hint"), speaker: "rabbit", text }
    setReplies((prev) => [...prev.filter((message) => message.speaker !== "rabbit"), hint])
  }

  const turnMutation = useMutation({
    mutationFn: async (input: RoleplayTurnInput) => {
      const result = await sendRoleplayTurn(input)
      // 대화가 이어졌으면 서버가 활동을 끝냈는지 본다. 실패하면 같은 키로 다시 보내 저장된 결과를 받는다.
      const completed = result.status === "DELIVERED" && (await getActivityStatus(activityId ?? "")) === "COMPLETED"
      return { result, completed }
    },
    onSuccess: ({ result, completed }, input) => {
      // 말로 답한 턴은 서버가 알아들은 문장(표준 발화)을 아이 말풍선으로 보여 준다. 글자 답은 보낼 때 이미 보여 줬다.
      // 같은 키 재전송(replayed)은 acceptedInput 이 null 이라 아이 말풍선 없이 대답만 이어 간다.
      if (input.kind === "voice" && result.acceptedInput) {
        const spoken = result.acceptedInput
        setReplies((prev) => [...prev, { id: nextId("child"), speaker: "child", text: spoken, inputMode: "voice" }])
      }
      let latestAudio: string | undefined
      for (const message of result.messages) {
        if (message.speaker === "rabbit") showRabbitHint(message.text)
        else {
          setReplies((prev) => [...prev, { id: nextId("reply"), ...message }])
          if (message.audioUrl) latestAudio = message.audioUrl
        }
      }
      // 거북이 대사는 소리로도 들려준다. 아이가 방금 버튼을 눌렀으므로 브라우저가 재생을 허락한다(막히면 "다시 듣기").
      if (latestAudio) void playSpeech(latestAudio)
      if (result.status === "NOTICE_UNAVAILABLE" && result.messages.length === 0 && result.action !== "STOP_DIALOGUE") {
        showRabbitHint(FALLBACK_NOTICE)
      }
      // 음성 실패가 3번 쌓이면 서버가 글자를 권한다. 글자 모드로 바꾸되 마이크 버튼은 남겨 다시 말할 수 있게 한다.
      if (result.action === "SUGGEST_TEXT_KEEP_VOICE") setInputMode("text")
      if (result.status === "STOPPED" || result.action === "STOP_DIALOGUE") setIsStopped(true)
      if (completed) {
        setIsFinished(true)
        // 홈으로 돌아갔을 때 완료 상태(열기구 탑승)가 바로 보이도록 목록을 다시 받는다.
        queryClient.invalidateQueries({ queryKey: myActivitiesQueryKey(childId) })
      }
    },
  })

  // 화면을 떠나면 울리던 대사를 멈춘다.
  useEffect(() => stopSpeech, [])

  // 새 말풍선이 생기면 맨 아래(입력창 아래)로 내려, 최신 말풍선과 입력창이 함께 보이게 한다.
  // 대화 끝으로만 내리면 입력창이 화면 밖으로 밀려 아이가 직접 스크롤해야 했다.
  useEffect(() => {
    threadEndRef.current?.scrollIntoView({ behavior: "smooth", block: "end" })
  }, [replies.length, turnMutation.isPending])

  const session = sessionQuery.data
  const activity = listedActivity
  const startErrorCode = sessionQuery.error instanceof ChildApiError ? sessionQuery.error.code : null
  // 세션이 없을 때만 대기·차단을 본다. 시작한 뒤 목록이 바뀌어도(완료 등) 대화 화면을 유지한다.
  const waiting: "wait" | "blocked" | null = session
    ? null
    : activity?.nextAction === "WAIT_FOR_SCENARIO" || startErrorCode === "INITIAL_ROLEPLAY_NOT_READY"
      ? "wait"
      : activity?.nextAction === "BLOCKED" || startErrorCode === "ROLEPLAY_RESUME_NOT_READY"
        ? "blocked"
        : null
  const isLoading =
    activitiesQuery.isPending ||
    (sessionQuery.isPending && sessionQuery.fetchStatus !== "idle") ||
    ((activitiesQuery.isError || sessionQuery.isError) &&
      (activitiesQuery.isFetching || sessionQuery.isFetching))
  // 최초 결합 퀴즈를 다 풀기 전에는 서버가 역할극을 시작하지 않는다(409). 퀴즈가 있는 활동이면 오류 대신 퀴즈로 돌려보낸다.
  const quizNotFinished =
    sessionQuery.error instanceof ChildApiError && sessionQuery.error.code === "INITIAL_DIFFICULTY_NOT_READY"
  const showLoadError =
    (activitiesQuery.isError || (sessionQuery.isError && waiting === null)) &&
    !activitiesQuery.isFetching &&
    !sessionQuery.isFetching
  const showSendError = turnMutation.isError && !turnMutation.isPending
  // 서버 세션이 진행 중이 아니면(일시 중단·복구 필요 등) 메시지 없이 온다. 자동으로 다시 시작하지 않는다.
  const cannotContinue = session !== undefined && (session.status !== "IN_PROGRESS" || session.messages.length === 0)

  // 내 배정 목록(서버가 이 아동 것만 준다)에 없거나 이미 끝낸 활동이면 목록으로 돌려보낸다 (VS-003).
  // 이 화면에서 방금 끝낸 활동은 마무리 화면을 보여 줘야 하므로 돌려보내지 않는다.
  if (activitiesQuery.isSuccess && (!activity || (activity.status === "COMPLETED" && !isFinished))) {
    return <Navigate to="/child/activities" replace />
  }
  if (
    !session &&
    (activity?.nextAction === "QUIZ" || (quizNotFinished && (activity?.quizCount ?? 0) > 0))
  ) {
    return <Navigate to={`/child/quiz/${activityId}`} replace />
  }

  const sendText = (text: string) => {
    if (!session) return
    setReplies((prev) => [...prev, { id: nextId("child"), speaker: "child", text, inputMode: "text" }])
    // 새 답은 새 키. 응답을 못 받아 다시 보낼 때만 같은 키를 쓴다.
    turnMutation.mutate({ kind: "text", sessionId: session.sessionId, text, requestKey: crypto.randomUUID() })
  }

  // 녹음이 끝나면 음성 파일을 서버로 보낸다(API-RPL-04). 새 녹음은 새 키, 다시 보내기는 같은 키·같은 파일이다.
  const handleVoiceResult = (result: VoiceResult) => {
    if (result.type === "audio") {
      if (!session) return
      turnMutation.mutate({ kind: "voice", sessionId: session.sessionId, audio: result.audio, requestKey: crypto.randomUUID() })
      return
    }
    if (result.type === "timeout") {
      showRabbitHint(VOICE_TIMEOUT_NOTICE)
      return
    }
    showRabbitHint(VOICE_UNAVAILABLE_NOTICE[result.reason])
    setInputMode("text")
  }

  const goHome = () => navigate("/child/activities", { replace: true })

  const retryLoad = () => {
    if (activitiesQuery.isError) activitiesQuery.refetch()
    if (sessionQuery.isError) sessionQuery.refetch()
  }

  const thread: ThreadMessage[] = [...(session?.messages ?? []), ...replies]

  return (
    <ChildLayout activityTitle="역할극" backgroundImage={themeparkBackgroundUrl} dimBackground wide>
      {isLoading ? (
        <div className="flex flex-1 items-center justify-center">
          <LoadingState message="역할극을 준비하고 있어요" />
        </div>
      ) : waiting ? (
        <WaitingForTeacher blocked={waiting === "blocked"} />
      ) : isWrappedUp && activityId ? (
        <RoleplayWrapUpView
          childName={childName}
          onNext={() => navigate(`/child/done/${activityId}`, { state: { completion: { activityId } } })}
        />
      ) : session ? (
        <div className="flex flex-1 flex-col gap-4 break-keep">
          <div className="flex flex-1 flex-col gap-4" aria-live="polite">
            {thread.map((message) => (
              <ThreadBubble key={message.id} message={message} characterName={session.characterName} />
            ))}
            {turnMutation.isPending ? (
              <SpeechBubble character="turtle" className={COLUMN} name={session.characterName}>
                생각하고 있어요…
              </SpeechBubble>
            ) : null}
          </div>

          <div className={COLUMN}>
            {isFinished ? (
              <ChildButton className="h-16 w-full text-xl" onClick={() => setIsWrappedUp(true)}>
                이야기 마무리하기
              </ChildButton>
            ) : isStopped || cannotContinue ? (
              <RoleplayStoppedView onHome={goHome} />
            ) : (
              <RoleplayComposer
                mode={inputMode}
                onModeChange={setInputMode}
                onSend={sendText}
                onVoiceResult={handleVoiceResult}
                onRecordingStart={stopSpeech}
                disabled={turnMutation.isPending}
              />
            )}
          </div>
          <div ref={threadEndRef} />
        </div>
      ) : null}

      <LoadErrorDialog
        open={showLoadError}
        error={activitiesQuery.error ?? sessionQuery.error}
        message="역할극을 불러오지 못했어요"
        onRetry={retryLoad}
      />
      <StateDialog open={showSendError && !isSessionLost(turnMutation.error)}>
        {/* 같은 키로 다시 보내 턴이 중복 생성되지 않게 한다 (VS-011 재전송). 처리 중(202)이었어도 같은 키로 결과를 받는다. */}
        <ErrorState
          onRetry={() => {
            if (turnMutation.variables) turnMutation.mutate(turnMutation.variables)
          }}
        />
      </StateDialog>
      {/* 대화를 보내다 세션이 끝나면(401) 다시 하기로는 빠져나갈 수 없어 다시 입장하게 한다. */}
      <SessionLostDialog open={showSendError && isSessionLost(turnMutation.error)} />
    </ChildLayout>
  )
}

export { ChildRoleplayPage }
