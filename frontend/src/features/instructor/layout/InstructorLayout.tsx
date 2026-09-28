import * as React from "react"
import { useNavigate } from "react-router-dom"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { getCurrentUser, logout } from "../api"
import { Button } from "@/components/ui/button"

/**
 * 학급·아동 관리 화면이 공유하는 레이아웃 셸.
 * features/child 의 ChildLayout 과 같은 규칙을 따른다 — 라우터는 평평하게 두고
 * 각 페이지가 이 셸을 직접 감싸므로, 라우트 구조와 레이아웃이 서로 얽히지 않는다.
 * 강사용 화면 시안이 나오면 이 파일만 교체한다.
 */
function InstructorLayout({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  // 헤더에 이름을 보이면서, 화면에 들어올 때 토큰이 아직 유효한지도 확인한다(무효면 api.ts 가 로그인 화면으로 보낸다).
  const { data: user } = useQuery({
    queryKey: ["auth", "session"],
    queryFn: getCurrentUser,
    staleTime: Infinity,
  })

  const logoutMutation = useMutation({
    mutationFn: logout,
    // 서버 요청이 실패해도 토큰은 지워졌으므로 로그인 화면으로 보낸다. 다음 강사에게 이전 데이터가 보이지 않게 캐시를 비운다.
    onSettled: () => {
      queryClient.clear()
      navigate("/login", { replace: true })
    },
  })

  return (
    <div className="flex min-h-svh flex-col">
      <header className="border-border flex items-center justify-between border-b px-6 py-4">
        <span className="text-sm font-semibold">느링고 강사용</span>
        <div className="flex items-center gap-3 text-sm">
          {user && <span>{user.name} 님</span>}
          <Button
            variant="outline"
            size="sm"
            onClick={() => logoutMutation.mutate()}
            disabled={logoutMutation.isPending}
          >
            {logoutMutation.isPending ? "로그아웃 중..." : "로그아웃"}
          </Button>
        </div>
      </header>

      <main className="mx-auto w-full max-w-3xl flex-1 px-6 py-6">
        {children}
      </main>
    </div>
  )
}

export { InstructorLayout }
