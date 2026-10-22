import * as React from "react"
import { Link, useLocation } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { getCurrentUser } from "../api"
import { INSTRUCTOR_NAV_ITEMS, isNavActive } from "./instructorNav"
import { useLogout } from "./useLogout"
import { MaterialIcon } from "../components/MaterialIcon"
import { focusRingClass } from "../components/styles"
import { cn } from "@/lib/utils"

/**
 * 학급·아동 관리 화면이 공유하는 레이아웃 셸 (왼쪽 사이드바 + 위쪽 제목 헤더).
 * features/child 의 ChildLayout 과 같은 규칙을 따른다 — 라우터는 평평하게 두고
 * 각 페이지가 이 셸을 직접 감싸므로, 라우트 구조와 레이아웃이 서로 얽히지 않는다.
 * 사이드바는 디자인 시스템 Side Navigation 예시를 따른다: 폭 220, 안쪽 위 18·옆 14, 맨 위 "느링고 강사" 표시,
 * 메뉴 항목 높이 40·안쪽 12·아이콘 20(간격 10)·모서리 8, 선택 시 옅은 보라.
 * 헤더 높이 55px 는 와이어프레임 T-HOME-01·T-CLS-01 을, 여백은 디자인 시스템 Space(4px 단위)를 따른다.
 *
 * actions: 헤더 오른쪽 버튼 자리, subheader: 헤더 바로 아래 흰 띠(탭 등) 자리.
 * headerTools: 시안 헤더 맨 오른쪽의 알림·프로필 자리(각 26px). 둘 다 아직 기능이 없어 알림은 비활성 버튼, 프로필은 꾸밈 원이다.
 *   활동 만들기 시안처럼 단계 표시만 있는 화면은 false 로 끈다.
 * fill: 본문 카드를 화면 아래까지 늘이는 화면(활동 만들기·학급 생성 시안)에서 켠다. 본문의 바로 아래 자식이 남은 높이를 채운다.
 */
function InstructorLayout({
  title,
  actions,
  subheader,
  headerTools = true,
  fill = false,
  children,
}: {
  title: string
  actions?: React.ReactNode
  subheader?: React.ReactNode
  headerTools?: boolean
  fill?: boolean
  children: React.ReactNode
}) {
  const { pathname } = useLocation()

  // 사이드바에 이름을 보이면서, 화면에 들어올 때 토큰이 아직 유효한지도 확인한다(무효면 api.ts 가 로그인 화면으로 보낸다).
  const { data: user } = useQuery({
    queryKey: ["auth", "session"],
    queryFn: getCurrentUser,
    staleTime: Infinity,
  })

  const logoutMutation = useLogout()

  const userLabel = user ? [user.name, user.orgName].filter(Boolean).join(" · ") : ""

  return (
    <div className="instructor-scope flex min-h-svh bg-[var(--instructor-bg)]">
      <aside className="flex w-[220px] shrink-0 flex-col gap-1 border-r border-[var(--instructor-border)] bg-[var(--instructor-sidebar)] px-[14px] pt-[18px] pb-5">
        {/* 예시의 글자색 #777382 는 옅은 바탕에서 12px 대비가 모자라 Text/Secondary 를 쓴다(보조 글자색 관례). */}
        <span className="px-3 py-2 text-xs text-[var(--instructor-text-muted)]">느링고 강사</span>

        <nav aria-label="강사 메뉴" className="flex flex-col gap-1">
          {INSTRUCTOR_NAV_ITEMS.map((item) => {
            const itemClass = cn(
              "flex h-10 items-center gap-[10px] rounded-[var(--instructor-radius-control)] px-3 text-sm",
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
                  <MaterialIcon name={item.icon} className="size-5 shrink-0" />
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
                <MaterialIcon name={item.icon} className="size-5 shrink-0" />
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
          {/* 계정 정보와 탈퇴는 메뉴의 "설정" 화면에 있다. 탈퇴는 되돌릴 수 없어 로그아웃 옆에 두지 않는다. */}
          <button
            type="button"
            onClick={() => logoutMutation.mutate()}
            disabled={logoutMutation.isPending}
            className={cn("mt-3 underline-offset-2 hover:underline disabled:opacity-50", focusRingClass)}
          >
            {logoutMutation.isPending ? "로그아웃 중..." : "로그아웃"}
          </button>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex min-h-[55px] items-center justify-between gap-4 border-b border-[var(--instructor-border)] bg-[var(--instructor-surface)] px-6">
          <h1 className="text-base font-semibold">{title}</h1>
          {(actions || headerTools) && (
            <div className="flex items-center gap-4">
              {actions && <div className="flex items-center gap-2">{actions}</div>}
              {headerTools && (
                <div className="flex items-center gap-4">
                  <button
                    type="button"
                    disabled
                    aria-label="알림 (준비 중)"
                    title="준비 중인 기능입니다"
                    className="flex size-[26px] items-center justify-center rounded-full border border-[var(--instructor-border-strong)] text-[var(--instructor-icon-subtle)] disabled:cursor-not-allowed"
                  >
                    <MaterialIcon name="notifications" className="size-4" />
                  </button>
                  <span aria-hidden className="size-[26px] rounded-full bg-[var(--instructor-avatar)]" />
                </div>
              )}
            </div>
          )}
        </header>
        {subheader && (
          <div className="border-b border-[var(--instructor-border)] bg-[var(--instructor-surface)] px-6">{subheader}</div>
        )}

        <main className={cn("flex-1 px-6 py-6", fill && "flex flex-col *:flex-1")}>{children}</main>
      </div>
    </div>
  )
}

export { InstructorLayout }
