import { useNavigate } from "react-router-dom"
import { ChildButton } from "../components/ChildButton"
import { useChildSessionStore } from "../store/childSessionStore"

/**
 * C-ACC-01 코드·QR 입장 화면의 레이아웃 자리표시자.
 * 실제 4자리 코드 입력·검증은 S1-JIN-01/S1-YOON-01에서 구현한다.
 */
function ChildAccessPage() {
  const navigate = useNavigate()
  const startSession = useChildSessionStore((state) => state.startSession)

  const handleTemporaryEnter = () => {
    startSession({ childName: "테스트 아동", accessCode: "0000" })
    navigate("/child/activities")
  }

  return (
    <div className="child-scope flex min-h-svh flex-col items-center justify-center gap-6 px-6 text-center">
      <div className="flex flex-col gap-2">
        <p className="text-2xl font-bold text-[var(--child-text)]">
          입장 코드를 입력해줘!
        </p>
        <p className="text-lg text-[var(--child-text-muted)]">
          선생님이 알려준 숫자를 눌러봐
        </p>
      </div>

      <div className="flex h-14 w-56 items-center justify-center rounded-[var(--child-radius-card)] border-2 border-dashed border-[var(--child-border)] text-[var(--child-text-muted)]">
        숫자 입력판 (예정)
      </div>

      <ChildButton onClick={handleTemporaryEnter}>임시로 들어가보기</ChildButton>
    </div>
  )
}

export { ChildAccessPage }
