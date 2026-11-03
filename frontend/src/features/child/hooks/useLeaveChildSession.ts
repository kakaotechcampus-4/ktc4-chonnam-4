import { useNavigate } from "react-router-dom"
import { useQueryClient } from "@tanstack/react-query"
import { useChildSessionStore } from "../store/childSessionStore"

/**
 * 이 브라우저의 아동 상태·캐시를 지우고 코드 입력 화면으로 돌아간다.
 * 사용 종료가 서버에서 끝났거나, 서버에 아동 세션이 이미 없을 때(401) 쓴다 (VS-003, API-IAM-09).
 */
export function useLeaveChildSession() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const endSession = useChildSessionStore((state) => state.endSession)

  return () => {
    // 아직 오가는 조회를 끊어, 늦게 온 응답이 다음 아동 화면의 캐시에 남지 않게 한다.
    void queryClient.cancelQueries({ queryKey: ["child"] })
    endSession()
    // 뒤로 가기로 활동 화면이 다시 열리지 않도록 replace 로 이동한다.
    navigate("/child", { replace: true })
    queryClient.removeQueries({ queryKey: ["child"] })
  }
}
