import * as React from 'react'
import { Link, useLocation } from 'react-router-dom'
import { cardClass, tabBaseClass, tabIdleClass, tabSelectedClass } from '../components/styles'
import { cn } from '@/lib/utils'

const AUTH_TABS = [
  { to: '/login', label: '로그인' },
  { to: '/signup', label: '가입' },
]

/**
 * 로그인·가입 화면의 셸. 시안 T-AUTH-01/02 ver2(탭 통합)를 따른다: 옅은 바탕 가운데 카드 하나, 위에 브랜드 줄,
 * 그 아래 로그인·가입 탭. 탭은 각 화면 주소로 가는 링크라 새로고침·뒤로 가기가 그대로 동작한다.
 * 로그인 전이라 강사 사이드바(사용자 이름·로그아웃)는 보이지 않는다. 마스코트 로고 원본이 아직 없어 원형 자리표시로 둔다.
 */
function AuthLayout({
  title,
  description,
  children,
}: {
  title: string
  description?: string
  children: React.ReactNode
}) {
  const { pathname } = useLocation()

  return (
    <div className="instructor-scope flex min-h-svh items-center justify-center bg-[var(--instructor-auth-bg)] px-6 py-12">
      <main className={cn(cardClass, 'w-full max-w-[470px] px-9 pt-9 pb-8')}>
        <div className="mb-5 flex items-center gap-2.5">
          <span className="size-[30px] rounded-full bg-[var(--instructor-avatar)]" aria-hidden />
          <span className="text-xl font-bold">느링고 강사</span>
        </div>

        <nav aria-label="로그인·가입" className="flex border-b border-[var(--instructor-border)]">
          {AUTH_TABS.map((tab) => {
            const selected = pathname === tab.to
            return (
              <Link
                key={tab.to}
                to={tab.to}
                aria-current={selected ? 'page' : undefined}
                className={cn(tabBaseClass, selected ? tabSelectedClass : tabIdleClass)}
              >
                {tab.label}
              </Link>
            )
          })}
        </nav>

        {/* 탭이 화면 이름을 보여 주므로 제목은 화면 낭독기에만 읽힌다. */}
        <h1 className="sr-only">{title}</h1>
        {description && <div className="mt-4 text-xs text-[var(--instructor-text-subtle)]">{description}</div>}
        <div className="mt-5">{children}</div>
      </main>
    </div>
  )
}

export { AuthLayout }
