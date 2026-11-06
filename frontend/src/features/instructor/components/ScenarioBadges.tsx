import type { ScenarioApprovalState } from '../api'
import { badgeClass } from './styles'
import { APPROVAL_STATE_LABELS } from '../lib/scenarioLabels'
import { cn } from '@/lib/utils'

const APPROVAL_STATE_COLORS: Record<ScenarioApprovalState, string> = {
  WAITING: 'bg-[var(--instructor-info-bg)] text-[var(--instructor-info-fg)]',
  IN_REVIEW: 'bg-[var(--instructor-warning-bg)] text-[var(--instructor-warning-fg)]',
}

/** 승인 대기함 한 줄의 상태. 색과 함께 글자로 알린다. */
function ApprovalStateBadge({ state }: { state: ScenarioApprovalState }) {
  return <span className={cn(badgeClass, APPROVAL_STATE_COLORS[state])}>{APPROVAL_STATE_LABELS[state]}</span>
}

export { ApprovalStateBadge }
