import type { ReactNode } from 'react'
import { MaterialIcon, type MaterialIconName } from './MaterialIcon'
import { cn } from '@/lib/utils'

type Tone = 'info' | 'success' | 'warning' | 'error'

// 디자인 시스템 Semantic color modes(Teacher)의 Feedback 값: 바탕 / 글자 / 아이콘.
const TONES: Record<Tone, { box: string; icon: string; name: MaterialIconName }> = {
  info: {
    box: 'bg-[var(--instructor-info-bg)] text-[var(--instructor-info-fg)]',
    icon: 'text-[var(--instructor-info-icon)]',
    name: 'info',
  },
  success: {
    box: 'bg-[var(--instructor-success-bg)] text-[var(--instructor-success-fg)]',
    icon: 'text-[var(--instructor-success-icon)]',
    name: 'check_circle',
  },
  warning: {
    box: 'bg-[var(--instructor-warning-bg)] text-[var(--instructor-warning-fg)]',
    icon: 'text-[var(--instructor-warning-icon)]',
    name: 'warning',
  },
  error: {
    box: 'bg-[var(--instructor-danger-bg)] text-[var(--instructor-danger-text)]',
    icon: 'text-[var(--instructor-danger-fg)]',
    name: 'error',
  },
}

/**
 * 안내 상자(디자인 시스템 05 Feedback). 바탕색 + 20px 아이콘 + 14/22.4 글자, 안쪽 16·14, 아이콘과 글자 간격 10, 모서리 8.
 * 상태를 색만으로 알리지 않도록 아이콘을 함께 둔다. 불러오기·저장 실패처럼 요청 단위의 결과에 쓴다.
 * role 은 화면이 정한다: 사용자가 한 행동의 결과면 alert/status, 화면을 열 때부터 있던 오류면 주지 않는다.
 */
function Notice({
  tone,
  role,
  className,
  children,
}: {
  tone: Tone
  role?: 'alert' | 'status'
  className?: string
  children: ReactNode
}) {
  const style = TONES[tone]
  return (
    <div
      role={role}
      className={cn(
        'flex items-start gap-2.5 rounded-[var(--instructor-radius-control)] px-4 py-3.5 text-sm leading-[22.4px]',
        style.box,
        className
      )}
    >
      <MaterialIcon name={style.name} className={cn('mt-px size-5 shrink-0', style.icon)} />
      <div className="min-w-0 break-keep">{children}</div>
    </div>
  )
}

/** 입력칸 아래 오류(디자인 시스템 Form Field 의 Error). 16px error 아이콘 + 12px 글자, 간격 4. */
function FieldError({ id, children }: { id?: string; children: ReactNode }) {
  return (
    <p id={id} className="flex items-center gap-1 text-xs text-[var(--instructor-danger-text)]">
      <MaterialIcon name="error" className="size-4 shrink-0 text-[var(--instructor-danger-fg)]" />
      {children}
    </p>
  )
}

export { FieldError, Notice }
