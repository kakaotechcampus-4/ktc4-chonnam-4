import type { ActivityStatus } from '../api'
import { cn } from '@/lib/utils'

const STATUS_LABELS: Record<ActivityStatus, string> = {
  NOT_STARTED: '시작 전',
  IN_PROGRESS: '진행 중',
  PAUSED: '일시 중지',
  RECOVERY_NEEDED: '복구 필요',
  COMPLETED: '완료',
}

const STATUS_COLORS: Record<ActivityStatus, string> = {
  NOT_STARTED: 'bg-[var(--instructor-neutral-bg)] text-[var(--instructor-neutral-fg)]',
  IN_PROGRESS: 'bg-[var(--instructor-info-bg)] text-[var(--instructor-info-fg)]',
  PAUSED: 'bg-[var(--instructor-neutral-bg)] text-[var(--instructor-neutral-fg)]',
  RECOVERY_NEEDED: 'bg-[var(--instructor-warning-bg)] text-[var(--instructor-warning-fg)]',
  COMPLETED: 'bg-[var(--instructor-success-bg)] text-[var(--instructor-success-fg)]',
}

function ActivityStatusBadge({ status }: { status: ActivityStatus }) {
  return (
    <span className={cn('inline-flex rounded-full px-2.5 py-0.5 text-xs font-semibold', STATUS_COLORS[status])}>
      {STATUS_LABELS[status]}
    </span>
  )
}

export { ActivityStatusBadge }
