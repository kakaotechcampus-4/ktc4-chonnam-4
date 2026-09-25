import { useParams } from "react-router-dom"
import { ChildLayout } from "../layout/ChildLayout"

/** 표정 퀴즈 화면 자리표시자. 실제 문항·판정 로직은 S1-BAE-01/S1-JIN-01에서 구현한다. */
function ChildQuizPage() {
  const { activityId } = useParams()

  return (
    <ChildLayout activityTitle="표정 퀴즈" stepLabel="1/3 문항">
      <div className="flex flex-1 flex-col items-center justify-center gap-2 text-center">
        <p className="text-lg text-[var(--child-text-muted)]">
          활동 {activityId}의 퀴즈 화면은 스프린트 1에서 구현돼요.
        </p>
      </div>
    </ChildLayout>
  )
}

export { ChildQuizPage }
