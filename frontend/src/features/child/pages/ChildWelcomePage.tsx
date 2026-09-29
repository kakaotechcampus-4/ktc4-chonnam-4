import { useNavigate } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { ListChecks, Smile } from "lucide-react"
import { ChildLayout } from "../layout/ChildLayout"
import { ChildButton } from "../components/ChildButton"
import { Character } from "../components/Character"
import { SpeechBubble } from "../components/SpeechBubble"
import { ErrorState, LoadingState, StateDialog } from "../components/state"
import { getMyActivities, myActivitiesQueryKey } from "../api"
import { useChildSessionStore } from "../store/childSessionStore"
import { withVocative } from "../lib/childName"

/**
 * C-ACC-02 본인 활동 확인 (VS-003).
 * 코드 입력 직후 실명으로 인사해, 아동이 자기 자리로 들어왔는지 확인하게 한다.
 */
function ChildWelcomePage() {
  const navigate = useNavigate()
  const childId = useChildSessionStore((state) => state.childId)
  const childName = useChildSessionStore((state) => state.childName) ?? ""

  const activitiesQuery = useQuery({
    queryKey: myActivitiesQueryKey(childId),
    queryFn: getMyActivities,
  })

  const todoActivities = (activitiesQuery.data ?? []).filter(
    (activity) => activity.status !== "COMPLETED"
  )
  const firstActivity = todoActivities[0]
  const isLoading = activitiesQuery.isPending || (activitiesQuery.isError && activitiesQuery.isFetching)
  const showError = activitiesQuery.isError && !activitiesQuery.isFetching

  return (
    <ChildLayout>
      <div className="grid flex-1 items-center gap-10 md:grid-cols-[1fr_1.4fr]">
        <section className="flex flex-col items-center gap-3 text-center">
          <Character name="turtle" size="lg" />
          <p className="text-3xl font-bold break-keep text-[var(--child-text)]">
            안녕, {withVocative(childName)}!
          </p>
          <p className="text-lg text-[var(--child-text-muted)]">오늘도 만나서 반가워</p>
        </section>

        <section className="flex flex-col gap-4">
          {isLoading ? (
            <LoadingState message="오늘 할 활동을 찾고 있어요" />
          ) : firstActivity ? (
            <>
              <p className="flex w-fit items-center gap-2 rounded-[var(--child-radius-pill)] bg-[var(--child-surface)] px-4 py-2 text-base font-semibold shadow-sm">
                <ListChecks className="size-5 text-[var(--child-primary)]" />
                오늘 할 활동이 {todoActivities.length}개 있어
              </p>
              <div className="flex items-center gap-4 rounded-[var(--child-radius-card)] bg-[var(--child-warning-bg)] p-5">
                <div className="flex size-14 shrink-0 items-center justify-center rounded-full bg-[#F0CA50] text-[var(--child-text)]">
                  <Smile className="size-8" />
                </div>
                <div className="flex flex-col gap-1 text-left">
                  <p className="text-xl font-bold text-[var(--child-text)]">{firstActivity.title}</p>
                  <p className="text-base text-[var(--child-text-muted)]">
                    표정 퀴즈 {firstActivity.quizCount}개 + 역할극 {firstActivity.roleplayCount}개
                  </p>
                </div>
              </div>
              <ChildButton className="h-16 w-full text-xl" onClick={() => navigate("/child/activities")}>
                시작할래요!
              </ChildButton>
            </>
          ) : activitiesQuery.isSuccess ? (
            <SpeechBubble character="turtle">
              오늘은 할 활동이 없어요. 선생님께 물어봐
            </SpeechBubble>
          ) : null}
        </section>
      </div>

      <StateDialog open={showError}>
        <ErrorState message="활동을 불러오지 못했어요" onRetry={() => activitiesQuery.refetch()} />
      </StateDialog>
    </ChildLayout>
  )
}

export { ChildWelcomePage }
