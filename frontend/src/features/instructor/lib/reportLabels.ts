import type {
  GoalStatus,
  ObservationStatus,
  RatioView,
  RecommendationType,
  RejectionReason,
  SupportLevel,
} from '../api'

/**
 * 리포트·추천 화면 문구. 아이 화면이 "스스로 말했어요"처럼 무엇을 혼자 했는지로 말하므로,
 * 강사 화면도 지원 단계를 기호(S0~S3) 대신 받은 도움으로 쓴다.
 */

/** 표의 열 순서. S0 은 처음 열린 질문만으로 답한 것이라 "혼자"로 쓴다. */
const SUPPORT_COLUMNS: { level: SupportLevel; label: string }[] = [
  { level: 'S0', label: '혼자' },
  { level: 'S1', label: '상황 단서' },
  { level: 'S2', label: '두 선택지' },
  { level: 'S3', label: '답 알려 주기' },
]

const SUPPORT_LABELS: Record<SupportLevel, string> = {
  S0: '열린 질문',
  S1: '상황 단서',
  S2: '두 선택지',
  S3: '답 알려 주기',
}

const GOAL_STATUS_LABELS: Record<GoalStatus, string> = {
  NO_SUPPORT_NEEDED: '혼자 도달',
  REACHED_AT_START: '처음 지원으로 도달',
  EXTRA_SUPPORT: '지원을 더 받고 도달',
  ANSWER_GIVEN: '답을 듣고 따라 말함',
  NOT_REACHED: '아직 도달하지 못함',
  EXCLUDED: '결과에서 뺌',
}

const OBSERVATION_LABELS: Record<ObservationStatus, string> = {
  EXPLICIT: '분명히 말함',
  IMPLICIT: '간접적으로 말함',
  NOT_EXPRESSED: '말하지 않음',
  MISUNDERSTOOD: '잘못 이해함',
  CONTRADICTORY: '상황과 다르게 말함',
}

const RECOMMENDATION_TYPE_LABELS: Record<RecommendationType, string> = {
  REINFORCE: '보완',
  REPEAT: '반복',
  APPLY: '적용',
  EXTEND: '확장',
}

const REJECTION_REASON_LABELS: Record<RejectionReason, string> = {
  NOT_NOW: '이번에는 하지 않음',
  NOT_SUITABLE: '아동에게 맞지 않음',
  ALREADY_PLANNED: '이미 계획한 활동이 있음',
  OTHER: '그 밖의 이유',
}

const DIRECTION_LABELS = { LOWER: '한 단계 낮춤', KEEP: '그대로', RAISE: '한 단계 올림' } as const

/** 다음 난이도 결정 이유(FollowUpDifficultyDecision.Reason). 모르는 코드는 그대로 보인다. */
const DIFFICULTY_REASON_LABELS: Record<string, string> = {
  SCORE_BELOW_THRESHOLD: '점수율이 50%보다 낮아요.',
  ANSWER_OR_MISSED_MAJORITY: '답을 듣거나 도달하지 못한 목표가 절반 이상이에요.',
  SCORE_IN_KEEP_RANGE: '점수율이 50% 이상 80% 미만이에요.',
  HIGH_SCORE_WITH_ANSWER_OR_MISSED: '점수율은 높지만 답을 듣거나 도달하지 못한 목표가 있어요.',
  UPGRADE_CANDIDATE_PENDING: '상향 후보예요. 한 번 더 이어지면 올려요.',
  UPGRADE_CANDIDATE_CONFIRMED: '상향 후보가 두 번 이어졌어요.',
  FAST_UPGRADE: '점수율이 빠른 상향 기준을 넘었어요.',
  ALREADY_LOWEST: '낮출 조건이지만 이미 가장 낮은 단계예요.',
  ALREADY_HIGHEST: '올릴 조건이지만 이미 가장 높은 단계예요.',
  INSUFFICIENT_VALID_GOALS: '판단할 수 있는 목표가 2개보다 적어요.',
  CALCULATION_FAILED: '기록으로 계산하지 못해 그대로 둬요.',
}

/** 기술 오류 코드. 아동의 어려움으로 읽지 않게 따로 보인다. */
const TECHNICAL_LABELS: Record<string, string> = {
  UNCLEAR_INPUT: '말소리를 알아듣지 못한 답이 있었어요',
}

/** 분모가 작을 때 퍼센트는 한 문항 차이로 크게 흔들려서 개수로 쓴다. */
function countText(ratio: RatioView | null, unit: string): string {
  if (!ratio) return '-'
  return `${ratio.denominator}${unit} 중 ${ratio.numerator}${unit}`
}

function percentText(ratio: RatioView | null): string {
  return ratio ? `${Math.round(ratio.value * 100)}%` : '-'
}

export {
  DIFFICULTY_REASON_LABELS,
  DIRECTION_LABELS,
  GOAL_STATUS_LABELS,
  OBSERVATION_LABELS,
  RECOMMENDATION_TYPE_LABELS,
  REJECTION_REASON_LABELS,
  SUPPORT_COLUMNS,
  SUPPORT_LABELS,
  TECHNICAL_LABELS,
  countText,
  percentText,
}
