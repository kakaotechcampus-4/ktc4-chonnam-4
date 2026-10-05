import { Navigate, useNavigate, useParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { ChevronRight, MessagesSquare, Smile, Stamp } from "lucide-react"
import { ChildLayout } from "../layout/ChildLayout"
import { ChildButton } from "../components/ChildButton"
import { SpeechBubble } from "../components/SpeechBubble"
import { ErrorState, LoadingState, StateDialog } from "../components/state"
import { getMyActivities, myActivitiesQueryKey } from "../api"
import { useChildSessionStore } from "../store/childSessionStore"

/**
 * C-ACT-01 활동 소개.
 * 퀴즈를 시작하기 전에 이번 활동에서 할 일(퀴즈 → 역할극 → 스탬프)을 미리 보여준다.
 */
function ChildActivityIntroPage() {
  const navigate = useNavigate()
  const { activityId } = useParams()
  const childId = useChildSessionStore((state) => state.childId)

  const activitiesQuery = useQuery({
    queryKey: myActivitiesQueryKey(childId),
    queryFn: getMyActivities,
  })

  const isLoading = activitiesQuery.isPending || (activitiesQuery.isError && activitiesQuery.isFetching)
  const showError = activitiesQuery.isError && !activitiesQuery.isFetching
  const activity = activitiesQuery.data?.find((item) => item.activityId === activityId)

  // 내 배정 목록에 없거나 이미 끝낸 활동이면 목록으로 돌려보낸다.
  // 다른 아동의 활동 ID 차단은 최종적으로 서버 응답(403)으로 처리해야 한다 (VS-003, S4).
  if (activitiesQuery.isSuccess && (!activity || activity.status === "COMPLETED")) {
    return <Navigate to="/child/activities" replace />
  }

  const steps = [
    { icon: <Smile />, label: "표정 퀴즈", detail: `${activity?.quizCount ?? 0}문제` },
    { icon: <MessagesSquare />, label: "역할극", detail: "대화하기" },
    { icon: <Stamp />, label: "스탬프", detail: "받기" },
  ]

  return (
    <ChildLayout activityTitle="활동 소개">
      {isLoading || !activity ? (
        <div className="flex flex-1 items-center justify-center">
          {isLoading ? <LoadingState message="활동을 준비하고 있어요" /> : null}
        </div>
      ) : (
        <div className="flex flex-1 flex-col items-center justify-center gap-8 text-center">
          <div className="flex flex-col items-center gap-4">
            <p className="font-child-display font-extrabold text-3xl break-keep text-[var(--child-text)]">{activity.title}</p>
            <SpeechBubble character="turtle">{activity.description}</SpeechBubble>
          </div>

          <ol className="flex flex-wrap items-center justify-center gap-2">
            {steps.map((step, index) => (
              <li key={step.label} className="flex items-center gap-2">
                <div className="flex w-32 flex-col items-center gap-2 rounded-[var(--child-radius-card)] bg-[var(--child-surface)] px-3 py-4 shadow-sm">
                  <div className="flex size-12 items-center justify-center rounded-full bg-[var(--child-surface-muted)] text-[var(--child-primary)] [&_svg]:size-6">
                    {step.icon}
                  </div>
                  <p className="text-lg font-bold text-[var(--child-text)]">{step.label}</p>
                  <p className="text-sm text-[var(--child-text-muted)]">{step.detail}</p>
                </div>
                {index < steps.length - 1 ? (
                  <ChevronRight className="size-6 text-[var(--child-text-muted)]" aria-hidden="true" />
                ) : null}
              </li>
            ))}
          </ol>

          <ChildButton
            className="h-16 w-full max-w-sm text-xl"
            onClick={() => navigate(`/child/quiz/${activity.activityId}`)}
          >
            {activity.status === "IN_PROGRESS" ? "이어서 하기" : "시작하기"}
          </ChildButton>
        </div>
      )}

      <StateDialog open={showError}>
        <ErrorState message="활동을 불러오지 못했어요" onRetry={() => activitiesQuery.refetch()} />
      </StateDialog>
    </ChildLayout>
  )
}

export { ChildActivityIntroPage }
