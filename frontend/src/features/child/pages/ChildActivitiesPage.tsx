import { Link } from "react-router-dom"
import { ChildLayout } from "../layout/ChildLayout"
import { LoadingState } from "../components/state"

/** 배정 활동 목록 자리표시자. 실제 목록 조회는 S1-BAE-02에서 연결한다. */
function ChildActivitiesPage() {
  return (
    <ChildLayout activityTitle="내 활동">
      <div className="flex flex-1 flex-col items-center justify-center gap-6">
        <LoadingState message="활동을 준비하고 있어요" />
        <div className="flex gap-3 text-sm text-[var(--child-text-muted)]">
          <Link className="underline" to="/child/quiz/demo">
            퀴즈 화면 보기
          </Link>
          <Link className="underline" to="/child/roleplay/demo">
            역할극 화면 보기
          </Link>
        </div>
      </div>
    </ChildLayout>
  )
}

export { ChildActivitiesPage }
