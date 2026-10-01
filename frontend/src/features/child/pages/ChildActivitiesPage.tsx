import { useLocation, useNavigate } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { cn } from "@/lib/utils"
import homeBackgroundUrl from "@/assets/child/home-background.svg"
import { ChildLayout } from "../layout/ChildLayout"
import { HotAirBalloon, type BalloonColor, type BalloonPassenger } from "../components/HotAirBalloon"
import { SpeechBubble } from "../components/SpeechBubble"
import { NextActivityDialog } from "../components/NextActivityDialog"
import { ErrorState, LoadingState, StateDialog } from "../components/state"
import { getMyActivities, myActivitiesQueryKey, type ChildActivityStatus } from "../api"
import { useChildSessionStore } from "../store/childSessionStore"
import { withNameSuffix } from "../lib/childName"

const BALLOON_COLOR_ORDER: BalloonColor[] = ["pink", "yellow", "purple"]
// 원본 시안처럼 가운데 열기구가 가장 높이 뜨도록 높이를 번갈아 준다. 한 줄로 쌓이는 모바일에서는 주지 않는다.
const BALLOON_OFFSETS = ["sm:mt-12", "sm:mt-0", "sm:mt-16"]
// 완료한 순서대로 번갈아 태운다.
const PASSENGER_ORDER: BalloonPassenger[] = ["turtle", "rabbit"]

const STATUS_BADGES: Record<ChildActivityStatus, { label: string; className: string }> = {
  NOT_STARTED: {
    label: "안 했어요",
    className: "bg-[var(--child-surface-muted)] text-[var(--child-text-muted)]",
  },
  IN_PROGRESS: {
    label: "진행 중",
    className: "bg-[var(--child-warning-bg)] text-[var(--child-warning-fg)]",
  },
  COMPLETED: {
    label: "완료!",
    className: "bg-[var(--child-success-bg)] text-[var(--child-success-fg)]",
  },
}

/**
 * C-HOME-01 내 활동 (VS-003·VS-009).
 * 배정된 활동마다 열기구 하나를 띄우고, 누르면 활동 소개(C-ACT-01)로 이동한다.
 * 완료한 활동은 바구니에 캐릭터를 태워 보여주고 다시 누를 수 없다.
 */
function ChildActivitiesPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const childId = useChildSessionStore((state) => state.childId)
  const childName = useChildSessionStore((state) => state.childName) ?? ""

  const activitiesQuery = useQuery({
    queryKey: myActivitiesQueryKey(childId),
    queryFn: getMyActivities,
  })

  const activities = activitiesQuery.data ?? []
  const isLoading = activitiesQuery.isPending || (activitiesQuery.isError && activitiesQuery.isFetching)
  const showError = activitiesQuery.isError && !activitiesQuery.isFetching

  // 완료 화면(C-DONE-02)에서 넘어왔으면 남은 활동을 제안한다. 진행 중인 활동을 먼저 고른다.
  const cameFromCompletion = Boolean(
    (location.state as { completedActivityId?: string } | null)?.completedActivityId
  )
  const nextActivity = cameFromCompletion && activitiesQuery.isSuccess
    ? activities.find((item) => item.status === "IN_PROGRESS") ??
      activities.find((item) => item.status === "NOT_STARTED") ??
      null
    : null

  // 한 번 닫거나 이동하면 뒤로 가기·재진입 때 다시 뜨지 않도록 기록을 지운다.
  const clearCompletionState = () => navigate(location.pathname, { replace: true, state: null })

  // 목록 순서가 아니라 완료한 순서대로 태워야, 새 활동을 끝내도 이미 탄 캐릭터가 바뀌지 않는다.
  const completionOrder = activities
    .filter((item) => item.status === "COMPLETED")
    .sort((a, b) => (a.completedAt ?? "").localeCompare(b.completedAt ?? ""))
    .map((item) => item.activityId)
  const balloons = activities.map((activity, index) => {
    const isCompleted = activity.status === "COMPLETED"
    const completedIndex = completionOrder.indexOf(activity.activityId)
    const passenger = isCompleted
      ? PASSENGER_ORDER[completedIndex % PASSENGER_ORDER.length]
      : undefined
    return {
      activity,
      isCompleted,
      passenger,
      color: BALLOON_COLOR_ORDER[index % BALLOON_COLOR_ORDER.length],
      offset: BALLOON_OFFSETS[index % BALLOON_OFFSETS.length],
    }
  })

  return (
    <ChildLayout
      headerCenter={
        <p className="rounded-[var(--child-radius-pill)] bg-[var(--child-surface)] px-6 py-2 text-2xl font-bold text-[var(--child-text)] shadow-sm">
          {withNameSuffix(childName)}의 활동
        </p>
      }
      backgroundImage={homeBackgroundUrl}
      wide
    >
      <div
        className="flex flex-1 flex-col items-center justify-center gap-6"
        // 배경은 cover·아래 정렬이라 잔디(원본 1008 중 아래 168.8)가 화면 높이의 16.75% 또는 폭의 10.55%를 차지한다.
        // 그만큼 아래를 비워 열기구가 헤더 제목과 잔디 윗선 사이 가운데에 오게 한다.
        // (main의 pb-10 2.5rem은 빼고, 헤더 아래 여백 1rem만큼 더해 위아래 간격을 맞춘다.)
        style={{ paddingBottom: "calc(max(16.75svh, 10.55vw) - 1.5rem)" }}
      >
        {isLoading ? (
          <div className="flex flex-1 items-center">
            <LoadingState message="활동을 불러오고 있어요" />
          </div>
        ) : activitiesQuery.isSuccess && activities.length === 0 ? (
          <div className="flex flex-1 items-center">
            <SpeechBubble character="turtle">
              오늘은 할 활동이 없어요. 선생님께 물어봐
            </SpeechBubble>
          </div>
        ) : (
          <>
            {/* 원본 시안처럼 열기구를 화면 폭의 약 27% 크기로 두고 화면 전체에 고르게 펼친다. */}
            <ul className="flex w-full flex-wrap items-start justify-evenly gap-y-4">
              {balloons.map(({ activity, isCompleted, passenger, color, offset }) => {
                const badge = STATUS_BADGES[activity.status]
                return (
                  <li key={activity.activityId} className={cn("w-[27%] min-w-40 max-w-64", offset)}>
                    <button
                      type="button"
                      disabled={isCompleted}
                      onClick={() => navigate(`/child/activities/${activity.activityId}`)}
                      className="group flex w-full flex-col items-center gap-2 rounded-[var(--child-radius-card)] outline-none focus-visible:ring-4 focus-visible:ring-[var(--child-primary)]/50"
                    >
                      <HotAirBalloon
                        color={color}
                        passenger={passenger}
                        className="transition-transform duration-300 group-enabled:group-hover:-translate-y-2 group-enabled:group-active:scale-95"
                      />
                      <span className="flex w-full flex-col items-center gap-1 rounded-2xl bg-[var(--child-surface)] px-3 py-2 shadow-sm">
                        <span className="text-lg font-bold break-keep text-[var(--child-text)]">
                          {activity.title}
                        </span>
                        <span
                          className={cn(
                            "rounded-[var(--child-radius-pill)] px-3 py-0.5 text-sm font-semibold",
                            badge.className
                          )}
                        >
                          {badge.label}
                        </span>
                      </span>
                    </button>
                  </li>
                )
              })}
            </ul>
          </>
        )}
      </div>

      <NextActivityDialog
        activity={nextActivity}
        onClose={clearCompletionState}
        onStart={(activity) => {
          clearCompletionState()
          navigate(`/child/activities/${activity.activityId}`)
        }}
      />

      <StateDialog open={showError}>
        <ErrorState message="활동을 불러오지 못했어요" onRetry={() => activitiesQuery.refetch()} />
      </StateDialog>
    </ChildLayout>
  )
}

export { ChildActivitiesPage }
