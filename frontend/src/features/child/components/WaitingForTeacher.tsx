import { useNavigate } from "react-router-dom"
import { ChildButton } from "./ChildButton"
import { Character } from "./Character"

/**
 * 아이가 지금 이어 갈 수 없는 활동(API-ACT-02 nextAction).
 * - WAIT_FOR_SCENARIO: 퀴즈를 마쳤고 선생님이 역할극을 확정하기를 기다린다. 정상 대기라 오류로 보이지 않게 한다.
 * - BLOCKED: 이어 가는 데 필요한 서버 자료가 맞지 않는다. 아이가 고칠 수 없으니 선생님께 알리게 한다.
 */
function WaitingForTeacher({ blocked = false }: { blocked?: boolean }) {
  const navigate = useNavigate()
  return (
    <div className="flex flex-1 flex-col items-center justify-center">
      <div
        role="status"
        className="flex w-full max-w-lg flex-col items-center gap-4 rounded-[var(--child-radius-card)] bg-[var(--child-surface)]/95 px-8 py-10 text-center break-keep shadow-sm"
      >
        <div className="flex items-end gap-2">
          <Character name="turtle" size="md" />
          <Character name="rabbit" size="md" />
        </div>
        <div className="flex flex-col gap-1">
          <p className="font-child-display font-extrabold text-2xl text-[var(--child-text)]">
            {blocked ? "지금은 이 활동을 할 수 없어" : "선생님이 역할극을 준비하고 있어"}
          </p>
          <p className="text-lg text-[var(--child-text-muted)]">
            {blocked ? "선생님께 알려 줄래?" : "준비가 끝나면 이어서 할 수 있어. 조금만 기다려 줘!"}
          </p>
        </div>
        <ChildButton className="h-16 w-full text-xl" onClick={() => navigate("/child/activities", { replace: true })}>
          내 활동으로 돌아가기
        </ChildButton>
      </div>
    </div>
  )
}

export { WaitingForTeacher }
