import * as React from "react"
import { Link, useLocation, useNavigate } from "react-router-dom"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { getCurrentUser, logout } from "../api"
import { INSTRUCTOR_NAV_ITEMS, isNavActive } from "./instructorNav"
import { focusRingClass } from "../components/styles"
import { cn } from "@/lib/utils"

/**
 * 학급·아동 관리 화면이 공유하는 레이아웃 셸 (왼쪽 사이드바 + 위쪽 제목 헤더).
 * features/child 의 ChildLayout 과 같은 규칙을 따른다 — 라우터는 평평하게 두고
 * 각 페이지가 이 셸을 직접 감싸므로, 라우트 구조와 레이아웃이 서로 얽히지 않는다.
 * 배치(사이드바 190px, 헤더 55px, 본문 여백 26·22px)는 와이어프레임 T-HOME-01·T-CLS-01 을,
 * 메뉴 항목 모양(높이 40, 모서리 8, 선택 시 옅은 보라)은 디자인 시스템의 Side Navigation 을 따른다.
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
      <aside className="flex w-[190px] shrink-0 flex-col border-r border-[var(--instructor-border)] bg-[var(--instructor-sidebar)] px-2.5 pt-4 pb-5">
        <div className="mb-3 flex items-center gap-2 px-2 pt-0.5 pb-1.5">
          <span className="size-6 rounded-full bg-[var(--instructor-avatar)]" aria-hidden />
          <span className="text-base font-bold">느링고</span>
        </div>

        <nav aria-label="강사 메뉴" className="flex flex-col gap-1">
          {INSTRUCTOR_NAV_ITEMS.map((item) => {
            const itemClass = cn(
              "flex h-10 items-center rounded-[var(--instructor-radius-control)] px-3 text-sm",
              focusRingClass
            )
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
                  active
                    ? "bg-[var(--instructor-nav-active)] font-semibold text-[var(--instructor-nav-active-fg)]"
                    : "text-[var(--instructor-nav-fg)] hover:bg-[var(--instructor-primary-subtle)]"
                )}
              >
                {item.label}
              </Link>
            )
          })}
        </nav>

        <div className="mt-3 border-t border-[var(--instructor-border)] px-2 pt-3 text-xs text-[var(--instructor-text-subtle)]">
          <div className="flex items-center gap-2">
            <span className="size-[22px] shrink-0 rounded-full bg-[var(--instructor-avatar)]" aria-hidden />
            <span className="break-keep">{userLabel}</span>
          </div>
          {/* 탈퇴는 되돌릴 수 없는 작업이라 로그아웃 옆에 두지 않고 계정 화면 안에 둔다. */}
          <Link
            to="/account"
            aria-current={isNavActive(pathname, "/account") ? "page" : undefined}
            className={cn("mt-3 block underline-offset-2 hover:underline", focusRingClass)}
          >
            계정
          </Link>
          <button
            type="button"
            onClick={() => logoutMutation.mutate()}
            disabled={logoutMutation.isPending}
            className={cn("mt-2 underline-offset-2 hover:underline disabled:opacity-50", focusRingClass)}
          >
            {logoutMutation.isPending ? "로그아웃 중..." : "로그아웃"}
          </button>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex min-h-[55px] items-center justify-between gap-4 border-b border-[var(--instructor-border)] bg-[var(--instructor-surface)] px-[26px]">
          <h1 className="text-base font-semibold">{title}</h1>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </header>
        {subheader && (
          <div className="border-b border-[var(--instructor-border)] bg-[var(--instructor-surface)] px-[26px]">{subheader}</div>
        )}

        <main className="flex-1 px-[26px] py-[22px]">{children}</main>
      </div>
    </div>
  )
}

export { InstructorLayout }
