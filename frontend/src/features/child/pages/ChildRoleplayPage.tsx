import { useEffect, useRef, useState } from "react"
import { Navigate, useNavigate, useParams } from "react-router-dom"
import { useMutation, useQuery } from "@tanstack/react-query"
import { Keyboard, Mic } from "lucide-react"
import themeparkBackgroundUrl from "@/assets/child/themepark-background.svg"
import { ChildLayout } from "../layout/ChildLayout"
import { ChildButton } from "../components/ChildButton"
import { Character } from "../components/Character"
import { SpeechBubble } from "../components/SpeechBubble"
import { RoleplayComposer, type RoleplayInputMode } from "../components/roleplay/RoleplayComposer"
import { ErrorState, LoadingState, StateDialog } from "../components/state"
import {
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

function ThreadBubble({ message }: { message: ThreadMessage }) {
  if (message.speaker === "child") {
    return (
      <div className="flex items-center justify-end gap-2">
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
  // 깡총이(힌트·도움)는 Figma C-RP-02처럼 오른쪽에 선다.
  return message.speaker === "rabbit" ? (
    <SpeechBubble character="rabbit" side="right" characterAlign="top" className="justify-start">
      {message.text}
    </SpeechBubble>
  ) : (
    <SpeechBubble character="turtle">{message.text}</SpeechBubble>
  )
}

/** C-RP-06 역할극 마무리. 점수 없이 아이가 스스로 한 말을 되돌려 준다. */
function RoleplayWrapUpView({
  childName,
  lastReply,
  onNext,
}: {
  childName: string
  lastReply: string | null
  onNext: () => void
}) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center">
      <div className="flex w-full max-w-lg flex-col items-center gap-4 rounded-[var(--child-radius-card)] bg-[var(--child-surface)]/95 px-8 py-10 text-center break-keep shadow-sm">
        <div className="flex items-end gap-2">
          <Character name="turtle" size="md" />
          <Character name="rabbit" size="md" />
        </div>
        <div className="flex flex-col gap-1">
          <p className="text-3xl font-bold text-[var(--child-text)]">정말 잘 이야기했어!</p>
          <p className="text-lg text-[var(--child-text-muted)]">
            친구 마음을 헤아려서 말해준 게 {withNameSuffix(childName)}는 참 멋있었어
          </p>
        </div>
        {lastReply ? (
          <p className="rounded-2xl bg-[#EDE9FE] px-5 py-3 text-lg text-[var(--child-text)]">
            “{lastReply}” 라고 스스로 말했어요
          </p>
        ) : null}
        <ChildButton className="h-16 w-full text-xl" onClick={onNext}>
          다음으로
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

  // 내 배정 목록에 없거나 이미 끝낸 활동이면 목록으로 돌려보낸다 (VS-003, 최종 차단은 서버 403).
  if (activitiesQuery.isSuccess && (!activity || activity.status === "COMPLETED")) {
    return <Navigate to="/child/activities" replace />
  }

  const send = (text: string, inputMode: RoleplayInputMode) => {
    if (!activityId) return
    setReplies((prev) => [...prev, { id: `child-${prev.length}`, speaker: "child", text, inputMode }])
    turnMutation.mutate({ activityId, turnIndex, text, requestId: crypto.randomUUID() })
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
    >
      {isLoading ? (
        <div className="flex flex-1 items-center justify-center">
          <LoadingState message="역할극을 준비하고 있어요" />
        </div>
      ) : isWrappedUp ? (
        // 완료·스탬프 화면(C-DONE-02)은 5단계에서 연결한다. 그 전까지는 내 활동으로 돌아간다.
        <RoleplayWrapUpView
          childName={childName}
          lastReply={lastDeliveredReply}
          onNext={() => navigate("/child/activities")}
        />
      ) : scenarioQuery.data ? (
        <div className="flex flex-1 flex-col gap-4 break-keep">
          <div className="flex flex-1 flex-col gap-4" aria-live="polite">
            {thread.map((message) => (
              <ThreadBubble key={message.id} message={message} />
            ))}
            {turnMutation.isPending ? (
              <SpeechBubble character="turtle">생각하고 있어요…</SpeechBubble>
            ) : null}
            <div ref={threadEndRef} />
          </div>

          {isFinished ? (
            <ChildButton className="h-16 w-full text-xl" onClick={() => setIsWrappedUp(true)}>
              이야기 마무리하기
            </ChildButton>
          ) : (
            <RoleplayComposer onSend={send} disabled={turnMutation.isPending} />
          )}
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
    </ChildLayout>
  )
}

export { ChildRoleplayPage }
