import type { ClassroomStatus } from '../api'
import { badgeClass } from './styles'
import { cn } from '@/lib/utils'

const STATUS_LABELS: Record<ClassroomStatus, string> = {
  ACTIVE: '운영중',
  ARCHIVED: '보관',
}

function ClassroomStatusBadge({ status }: { status: ClassroomStatus }) {
  return (
    <span
      className={cn(
        badgeClass,
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
