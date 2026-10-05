import { Navigate, useLocation, useNavigate, useParams } from "react-router-dom"
import { Star } from "lucide-react"
import themeparkBackgroundUrl from "@/assets/child/themepark-background.svg"
import { ChildLayout } from "../layout/ChildLayout"
import { ChildButton } from "../components/ChildButton"
import { Character } from "../components/Character"
import type { ActivityCompletion } from "../api"

/**
 * C-DONE-02 스탬프 지급 (VS-013).
 * 정확도와 무관하게 같은 가치의 스탬프 1개를 보여준다. 누적 개수·모아보기(C-DONE-03)는 정책 확정 후 범위다.
 * 잘한 점·다음 연습(C-DONE-01)은 학습 기록 분석이 필요해(S7, VS-014) 지금은 고정 칭찬 문구만 둔다.
 */
function ChildDonePage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { activityId } = useParams()
  const completion = (location.state as { completion?: ActivityCompletion } | null)?.completion

  // 완료 요청 결과 없이 주소로 바로 들어오면 보여줄 내용이 없으므로 내 활동으로 보낸다.
  if (!completion || completion.activityId !== activityId) {
    return <Navigate to="/child/activities" replace />
  }

  const goHome = () => {
    // 홈에서 다음 활동 팝업을 띄울 수 있도록 방금 끝낸 활동을 알려준다.
    navigate("/child/activities", { replace: true, state: { completedActivityId: completion.activityId } })
  }

  return (
    <ChildLayout activityTitle="활동 완료" backgroundImage={themeparkBackgroundUrl}>
      <div className="flex flex-1 flex-col items-center justify-center">
        <div className="flex w-full max-w-lg flex-col items-center gap-5 rounded-[var(--child-radius-card)] bg-[var(--child-surface)]/95 px-8 py-10 text-center break-keep shadow-sm">
          <div className="flex items-end gap-3">
            <Character name="turtle" size="md" />
            <div
              role="img"
              aria-label="스탬프 1개"
              className="flex size-24 -rotate-12 items-center justify-center rounded-full border-4 border-dashed border-[var(--child-primary)] bg-[#EDE9FE] text-[var(--child-primary)]"
            >
              <Star className="size-12 fill-current" />
            </div>
            <Character name="rabbit" size="md" />
          </div>
          <div className="flex flex-col gap-1">
            <p className="font-child-display font-extrabold text-3xl text-[var(--child-text)]">스탬프를 받았어요!</p>
            <p className="font-child-display text-lg font-bold text-[var(--child-text-muted)]">끝까지 해냈어! 친구 마음을 잘 살펴봤어</p>
          </div>
          <ChildButton className="h-16 w-full text-xl" onClick={goHome}>
            확인했어요
          </ChildButton>
        </div>
      </div>
    </ChildLayout>
  )
}

export { ChildDonePage }
