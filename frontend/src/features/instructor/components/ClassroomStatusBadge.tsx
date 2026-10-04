import type { ClassroomStatus } from '../api'
import { cn } from '@/lib/utils'

const STATUS_LABELS: Record<ClassroomStatus, string> = {
  ACTIVE: '운영중',
  ARCHIVED: '보관',
}

function ClassroomStatusBadge({ status }: { status: ClassroomStatus }) {
  return (
    <span
      className={cn(
        'inline-flex rounded-full px-2.5 py-0.5 text-xs font-semibold',
        status === 'ACTIVE'
          ? 'bg-[var(--instructor-success-bg)] text-[var(--instructor-success-fg)]'
          : 'bg-[var(--instructor-neutral-bg)] text-[var(--instructor-neutral-fg)]'
      )}
    >
      {STATUS_LABELS[status]}
    </span>
  )
}

export { ClassroomStatusBadge }
