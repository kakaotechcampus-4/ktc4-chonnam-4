import type { ScenarioApprovalState } from '../api'

/** 승인 대기함 상태 이름. 배지와 상태 필터가 같은 이름을 쓴다. */
export const APPROVAL_STATE_LABELS: Record<ScenarioApprovalState, string> = {
  WAITING: '검토 대기',
  IN_REVIEW: '검토 중',
}
