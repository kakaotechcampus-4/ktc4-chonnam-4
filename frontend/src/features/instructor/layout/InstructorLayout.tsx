import * as React from "react"
import { Link, useLocation, useNavigate } from "react-router-dom"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { getCurrentUser, logout } from "../api"
import { INSTRUCTOR_NAV_ITEMS, isNavActive } from "./instructorNav"
import { cn } from "@/lib/utils"

/**
 * 학급·아동 관리 화면이 공유하는 레이아웃 셸 (왼쪽 사이드바 + 위쪽 제목 헤더).
 * features/child 의 ChildLayout 과 같은 규칙을 따른다 — 라우터는 평평하게 두고
 * 각 페이지가 이 셸을 직접 감싸므로, 라우트 구조와 레이아웃이 서로 얽히지 않는다.
 * 구조는 시안 T-HOME-01·T-CLS-01 을 따른다.
 *
 * actions: 헤더 오른쪽 버튼 자리, subheader: 헤더 바로 아래 흰 띠(탭 등) 자리.
 */
function InstructorLayout({
  title,
  actions,
  subheader,
  children,
}: {
  title: string
  actions?: React.ReactNode
  subheader?: React.ReactNode
  children: React.ReactNode
}) {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const queryClient = useQueryClient()

  // 사이드바에 이름을 보이면서, 화면에 들어올 때 토큰이 아직 유효한지도 확인한다(무효면 api.ts 가 로그인 화면으로 보낸다).
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

  const userLabel = user ? [user.name, user.orgName].filter(Boolean).join(" · ") : ""

  return (
    <div className="instructor-scope flex min-h-svh bg-[var(--instructor-bg)]">
      <aside className="flex w-[150px] shrink-0 flex-col border-r border-[var(--instructor-border)] bg-[var(--instructor-sidebar)] px-2.5 py-5">
        <div className="mb-6 flex items-center gap-2 px-2">
          <span className="size-5 rounded-full bg-[var(--instructor-avatar)]" aria-hidden />
          <span className="text-base font-bold">느링고</span>
        </div>

        <nav aria-label="강사 메뉴" className="flex flex-col gap-1">
          {INSTRUCTOR_NAV_ITEMS.map((item) => {
            const itemClass = "rounded-[var(--instructor-radius-control)] px-2 py-2 text-sm"
            if (!item.to) {
              return (
                <span
                  key={item.label}
                  aria-disabled="true"
                  title="준비 중인 화면입니다"
                  className={cn(itemClass, "cursor-not-allowed text-[var(--instructor-text-disabled)]")}
                >
                  {item.label}
                </span>
              )
            }
            const active = isNavActive(pathname, item.to)
            return (
              <Link
                key={item.label}
                to={item.to}
                aria-current={active ? "page" : undefined}
                className={cn(
                  itemClass,
                  active ? "bg-[var(--instructor-nav-active)] font-semibold" : "hover:bg-[var(--instructor-nav-active)]/60"
                )}
              >
                {item.label}
              </Link>
            )
          })}
        </nav>

        <div className="mt-4 border-t border-[var(--instructor-border)] px-2 pt-4 text-xs text-[var(--instructor-text-muted)]">
          <div className="flex items-center gap-2">
            <span className="size-5 shrink-0 rounded-full bg-[var(--instructor-avatar)]" aria-hidden />
            <span className="break-keep">{userLabel}</span>
          </div>
          <button
            type="button"
            onClick={() => logoutMutation.mutate()}
            disabled={logoutMutation.isPending}
            className="mt-3 underline-offset-2 hover:underline disabled:opacity-50"
          >
            {logoutMutation.isPending ? "로그아웃 중..." : "로그아웃"}
          </button>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex min-h-16 items-center justify-between gap-4 border-b border-[var(--instructor-border)] bg-[var(--instructor-surface)] px-5">
          <h1 className="text-base font-bold">{title}</h1>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </header>
        {subheader && (
          <div className="border-b border-[var(--instructor-border)] bg-[var(--instructor-surface)] px-5">{subheader}</div>
        )}

        <main className="flex-1 px-5 py-5">{children}</main>
      </div>
    </div>
  )
}

export { InstructorLayout }
