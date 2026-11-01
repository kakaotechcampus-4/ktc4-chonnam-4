import type { ScenarioApprovalState, ScenarioStatus } from '../api'
import { badgeClass } from './styles'
import { APPROVAL_STATE_LABELS } from '../lib/scenarioLabels'
import { cn } from '@/lib/utils'

const APPROVAL_STATE_COLORS: Record<ScenarioApprovalState, string> = {
  CANDIDATE_AVAILABLE: 'bg-[var(--instructor-info-bg)] text-[var(--instructor-info-fg)]',
  DRAFT_IN_PROGRESS: 'bg-[var(--instructor-warning-bg)] text-[var(--instructor-warning-fg)]',
  NO_CANDIDATE: 'bg-[var(--instructor-neutral-bg)] text-[var(--instructor-neutral-fg)]',
}

/** 승인 대기함 한 줄의 상태. 색과 함께 글자로 알린다. */
function ApprovalStateBadge({ state }: { state: ScenarioApprovalState }) {
  return <span className={cn(badgeClass, APPROVAL_STATE_COLORS[state])}>{APPROVAL_STATE_LABELS[state]}</span>
}

const SCENARIO_STATUS_LABELS: Record<ScenarioStatus, string> = {
  DRAFT: '초안',
  REVISION_REQUIRED: '수정 필요',
  REJECTED: '반려',
  APPROVED: '승인',
}

const SCENARIO_STATUS_COLORS: Record<ScenarioStatus, string> = {
  DRAFT: 'bg-[var(--instructor-warning-bg)] text-[var(--instructor-warning-fg)]',
  REVISION_REQUIRED: 'bg-[var(--instructor-warning-bg)] text-[var(--instructor-warning-fg)]',
  REJECTED: 'bg-[var(--instructor-neutral-bg)] text-[var(--instructor-neutral-fg)]',
  APPROVED: 'bg-[var(--instructor-success-bg)] text-[var(--instructor-success-fg)]',
}

function ScenarioStatusBadge({ status }: { status: ScenarioStatus }) {
  return <span className={cn(badgeClass, SCENARIO_STATUS_COLORS[status])}>{SCENARIO_STATUS_LABELS[status]}</span>
}

export { ApprovalStateBadge, ScenarioStatusBadge }
