import * as React from 'react'
import { cardClass } from '../components/styles'
import { cn } from '@/lib/utils'

/**
 * 로그인·가입 화면의 셸 (위쪽 로고 띠 + 회색 바탕 + 가운데 흰 카드). 시안 T-AUTH-01·02 를 따른다.
 * 로그인 전이라 강사 사이드바(사용자 이름·로그아웃)는 보이지 않는다.
 * 마스코트 로고 원본이 아직 없어 원형 자리표시로 둔다.
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
  return (
    <div className="instructor-scope flex min-h-svh flex-col bg-[var(--instructor-auth-bg)]">
      <header className="flex h-16 items-center border-b border-[var(--instructor-border)] bg-[var(--instructor-surface)] px-8">
        <span className="flex items-center gap-2.5">
          <span className="size-8 rounded-full bg-[var(--instructor-avatar)]" aria-hidden />
          <span className="text-lg font-bold">느링고</span>
        </span>
      </header>

      <main className="flex flex-1 items-start justify-center px-6 py-12">
        <div className={cn(cardClass, 'w-full max-w-[360px] px-8 py-9')}>
          <h1 className="text-2xl font-bold">{title}</h1>
          {description && (
            <div className="mt-2 text-sm text-[var(--instructor-text-muted)]">{description}</div>
          )}
          <div className="mt-7">{children}</div>
        </div>
      </main>
    </div>
  )
}

export { AuthLayout }
