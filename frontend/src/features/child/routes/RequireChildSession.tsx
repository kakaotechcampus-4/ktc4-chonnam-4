import { Navigate, Outlet } from "react-router-dom"
import { useChildSessionStore } from "../store/childSessionStore"

/**
 * 아동 세션이 있어야 열리는 라우트의 가드 (VS-003).
 * 주소를 직접 열거나, 사용 종료 뒤 뒤로 가기로 돌아와도 코드 입력 화면으로 보낸다.
 * 세션 "존재"만 확인하므로, 다른 아동의 활동 ID 차단은 서버 응답(403)으로 처리해야 한다.
 */
function RequireChildSession() {
  const hasSession = useChildSessionStore((state) => state.childName !== null)

  if (!hasSession) return <Navigate to="/child" replace />
  return <Outlet />
}

export { RequireChildSession }
