import { useParams } from "react-router-dom"
import { ChildLayout } from "../layout/ChildLayout"

/** 역할극 화면 자리표시자. 실제 세션·음성 입력은 S1-JIN-02/S1-YOON-01에서 구현한다. */
function ChildRoleplayPage() {
  const { activityId } = useParams()

  return (
    <ChildLayout activityTitle="역할극" stepLabel="1턴">
      <div className="flex flex-1 flex-col items-center justify-center gap-2 text-center">
        <p className="text-lg text-[var(--child-text-muted)]">
          활동 {activityId}의 역할극 화면은 스프린트 1에서 구현돼요.
        </p>
      </div>
    </ChildLayout>
  )
}

export { ChildRoleplayPage }
