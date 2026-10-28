import { useNavigate } from "react-router-dom"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { logout } from "../api"

/** 사이드바와 설정 화면의 로그아웃 버튼이 함께 쓴다. */
function useLogout() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: logout,
    // 서버 요청이 실패해도 토큰은 지워졌으므로 로그인 화면으로 보낸다. 다음 강사에게 이전 데이터가 보이지 않게 캐시를 비운다.
    onSettled: () => {
      queryClient.clear()
      navigate("/login", { replace: true })
    },
  })
}

export { useLogout }
