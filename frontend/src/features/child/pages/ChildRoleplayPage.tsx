import { useEffect, useRef, useState } from "react"
import { Navigate, useNavigate, useParams } from "react-router-dom"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Keyboard, Loader2, Mic } from "lucide-react"
import themeparkBackgroundUrl from "@/assets/child/themepark-background.svg"
import { ChildLayout } from "../layout/ChildLayout"
import { ChildButton } from "../components/ChildButton"
import { Character } from "../components/Character"
import { SpeechBubble } from "../components/SpeechBubble"
import { RoleplayComposer, type RoleplayInputMode } from "../components/roleplay/RoleplayComposer"
import { RabbitHintBubble } from "../components/roleplay/RabbitHintBubble"
import { ErrorState, LoadingState, StateDialog } from "../components/state"
import {
  completeActivity,
  getMyActivities,
  getRoleplayScenario,
  myActivitiesQueryKey,
  roleplayScenarioQueryKey,
  sendRoleplayTurn,
  type RoleplaySpeaker,
} from "../api"
import { useChildSessionStore } from "../store/childSessionStore"
import { withNameSuffix } from "../lib/childName"

type ThreadMessage = {
  id: string
  speaker: RoleplaySpeaker | "child"
  text: string
  /** 아이 답의 입력 방식. 말풍선 옆 아이콘(마이크·키보드)으로 보여준다. */
  inputMode?: RoleplayInputMode
}

// 깡총이가 화면 끝까지 닿도록 레이아웃을 wide로 쓰고, 나머지 대화·입력은 기존 본문 폭(688px)에 맞춘다.
const COLUMN = "mx-auto w-full max-w-[43rem]"

function ThreadBubble({ message }: { message: ThreadMessage }) {
  // 깡총이(힌트·도움)는 Figma C-RP-02처럼 화면 오른쪽 끝에서 튀어나온다. 역할극에서만 이렇게 보여 준다.
  if (message.speaker === "rabbit") return <RabbitHintBubble>{message.text}</RabbitHintBubble>

  if (message.speaker === "child") {
    return (
      <div className={`${COLUMN} flex items-center justify-end gap-2`}>
        <p className="max-w-[80%] rounded-[var(--child-radius-card)] rounded-br-md bg-[#EDE9FE] px-5 py-3 text-lg font-medium text-[var(--child-text)] shadow-sm">
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
  return (
    <SpeechBubble character="turtle" className={COLUMN}>
      {message.text}
    </SpeechBubble>
  )
}

/** C-RP-06 역할극 마무리. 점수 없이 아이가 스스로 한 말을 되돌려 준다. */
function RoleplayWrapUpView({
  childName,
  lastReply,
  onNext,
  isPending,
}: {
  childName: string
  lastReply: string | null
  onNext: () => void
  isPending: boolean
}) {
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
            친구 마음을 헤아려서 말해준 게 {withNameSuffix(childName)}는 참 멋있었어
          </p>
        </div>
        {lastReply ? (
          <p className="rounded-2xl bg-[#EDE9FE] px-5 py-3 text-lg text-[var(--child-text)]">
            “{lastReply}” 라고 스스로 말했어요
          </p>
        ) : null}
        {/* 완료 요청 중에는 다시 누르지 못하게 하고 로딩 아이콘만 보여준다. */}
        <ChildButton className="h-16 w-full text-xl" onClick={onNext} disabled={isPending}>
          {isPending ? <Loader2 className="size-6 animate-spin" aria-label="처리 중" /> : "다음으로"}
        </ChildButton>
      </div>
    </div>
  )
}

/**
 * 역할극 화면 (C-RP-02·04·06, VS-010·011·013).
 * S1은 글자 입력만 받는다. 음성 입력·입력 확인(C-RP-03)·일시 중단(C-RP-05)은 이후 스프린트 범위다.
 */
function ChildRoleplayPage() {
  const navigate = useNavigate()
  const { activityId } = useParams()
  const childId = useChildSessionStore((state) => state.childId)
  const childName = useChildSessionStore((state) => state.childName) ?? ""

  const [replies, setReplies] = useState<ThreadMessage[]>([])
  const [turnIndex, setTurnIndex] = useState(0)
  const [isFinished, setIsFinished] = useState(false)
  const [isWrappedUp, setIsWrappedUp] = useState(false)
  const [lastDeliveredReply, setLastDeliveredReply] = useState<string | null>(null)
  const threadEndRef = useRef<HTMLDivElement>(null)
  const queryClient = useQueryClient()
  // 완료 요청을 다시 보내도 스탬프가 두 번 지급되지 않도록 화면에 머무는 동안 같은 값을 쓴다.
  const [completionRequestId] = useState(() => crypto.randomUUID())

  const activitiesQuery = useQuery({
    queryKey: myActivitiesQueryKey(childId),
    queryFn: getMyActivities,
  })
  const scenarioQuery = useQuery({
    queryKey: roleplayScenarioQueryKey(childId, activityId),
    queryFn: () => getRoleplayScenario(activityId ?? ""),
    enabled: Boolean(activityId),
  })

  const turnMutation = useMutation({
    mutationFn: sendRoleplayTurn,
    onSuccess: (result, input) => {
      setReplies((prev) => [...prev, { id: `reply-${prev.length}`, ...result.reply }])
      if (result.status === "DELIVERED") {
        setLastDeliveredReply(input.text)
        setTurnIndex((prev) => prev + 1)
        if (result.isFinished) setIsFinished(true)
      }
    },
  })

  const completeMutation = useMutation({
    mutationFn: completeActivity,
    onSuccess: (completion) => {
      navigate(`/child/done/${completion.activityId}`, { state: { completion } })
      // 홈으로 돌아갔을 때 완료 상태(열기구 탑승)가 바로 보이도록 목록을 다시 받는다.
      queryClient.invalidateQueries({ queryKey: myActivitiesQueryKey(childId) })
    },
  })

  // 새 말풍선이 생기면 맨 아래로 내려 보여준다.
  useEffect(() => {
    threadEndRef.current?.scrollIntoView({ behavior: "smooth", block: "end" })
  }, [replies.length, turnMutation.isPending])

  const activity = activitiesQuery.data?.find((item) => item.activityId === activityId)
  const isLoading =
    activitiesQuery.isPending ||
    scenarioQuery.isPending ||
    ((activitiesQuery.isError || scenarioQuery.isError) &&
      (activitiesQuery.isFetching || scenarioQuery.isFetching))
  const showLoadError =
    (activitiesQuery.isError || scenarioQuery.isError) &&
    !activitiesQuery.isFetching &&
    !scenarioQuery.isFetching
  const showSendError = turnMutation.isError && !turnMutation.isPending
  const showCompleteError = completeMutation.isError && !completeMutation.isPending

  // 내 배정 목록에 없거나 이미 끝낸 활동이면 목록으로 돌려보낸다 (VS-003, 최종 차단은 서버 403).
  if (activitiesQuery.isSuccess && (!activity || activity.status === "COMPLETED")) {
    return <Navigate to="/child/activities" replace />
  }

  const send = (text: string, inputMode: RoleplayInputMode) => {
    if (!activityId) return
    setReplies((prev) => [...prev, { id: `child-${prev.length}`, speaker: "child", text, inputMode }])
    turnMutation.mutate({ activityId, turnIndex, text, requestId: crypto.randomUUID() })
  }

  // 녹음·STT(S6) 전이라 "듣고 있어요" 화면이 끝나도 보낼 음성이 없다. 깡총이가 글자로 답하도록 안내한다.
  const handleVoiceFinish = () => {
    setReplies((prev) => [
      ...prev,
      { id: `voice-${prev.length}`, speaker: "rabbit", text: "말로 답하기는 아직 준비 중이야. 글자로 알려줄래?" },
    ])
  }

  const retryLoad = () => {
    if (activitiesQuery.isError) activitiesQuery.refetch()
    if (scenarioQuery.isError) scenarioQuery.refetch()
  }

  const opening = scenarioQuery.data?.opening
  const thread: ThreadMessage[] = opening ? [{ id: "opening", ...opening }, ...replies] : replies

  return (
    <ChildLayout
      activityTitle="역할극"
      stepLabel={scenarioQuery.data?.place}
      backgroundImage={themeparkBackgroundUrl}
      dimBackground
      wide
    >
      {isLoading ? (
        <div className="flex flex-1 items-center justify-center">
          <LoadingState message="역할극을 준비하고 있어요" />
        </div>
      ) : isWrappedUp ? (
        <RoleplayWrapUpView
          childName={childName}
          lastReply={lastDeliveredReply}
          isPending={completeMutation.isPending}
          onNext={() => {
            if (activityId) completeMutation.mutate({ activityId, requestId: completionRequestId })
          }}
        />
      ) : scenarioQuery.data ? (
        <div className="flex flex-1 flex-col gap-4 break-keep">
          <div className="flex flex-1 flex-col gap-4" aria-live="polite">
            {thread.map((message) => (
              <ThreadBubble key={message.id} message={message} />
            ))}
            {turnMutation.isPending ? (
              <SpeechBubble character="turtle" className={COLUMN}>
                생각하고 있어요…
              </SpeechBubble>
            ) : null}
            <div ref={threadEndRef} />
          </div>

          <div className={COLUMN}>
            {isFinished ? (
              <ChildButton className="h-16 w-full text-xl" onClick={() => setIsWrappedUp(true)}>
                이야기 마무리하기
              </ChildButton>
            ) : (
              <RoleplayComposer
                onSend={send}
                onVoiceFinish={handleVoiceFinish}
                disabled={turnMutation.isPending}
              />
            )}
          </div>
        </div>
      ) : null}

      <StateDialog open={showLoadError}>
        <ErrorState message="역할극을 불러오지 못했어요" onRetry={retryLoad} />
      </StateDialog>
      <StateDialog open={showSendError}>
        {/* 같은 requestId로 다시 보내 턴이 중복 생성되지 않게 한다 (VS-011 재전송). */}
        <ErrorState
          onRetry={() => {
            if (turnMutation.variables) turnMutation.mutate(turnMutation.variables)
          }}
        />
      </StateDialog>
      <StateDialog open={showCompleteError}>
        <ErrorState
          onRetry={() => {
            if (completeMutation.variables) completeMutation.mutate(completeMutation.variables)
          }}
        />
      </StateDialog>
    </ChildLayout>
  )
}

export { ChildRoleplayPage }
