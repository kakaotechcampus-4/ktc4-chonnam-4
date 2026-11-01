import type { ScenarioApprovalState } from '../api'

/** 승인 대기함 상태 이름. 배지와 상태 필터가 같은 이름을 쓴다. */
export const APPROVAL_STATE_LABELS: Record<ScenarioApprovalState, string> = {
  CANDIDATE_AVAILABLE: '검토 대기',
  DRAFT_IN_PROGRESS: '수정 중',
  NO_CANDIDATE: '후보 없음',
}
