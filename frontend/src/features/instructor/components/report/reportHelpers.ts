import type { LearningReport, Recommendation, ScenarioLevel, SupportLevel } from '../../api'

/** 다음 역할극에 쓸 난이도. 배정할 때 활동에 고정되므로 어느 제안을 골라도 같다. */
type NextLevel = { scenarioLevel: ScenarioLevel; supportLevel: SupportLevel }

/** 채택하며 고친 목표가 있으면 그것을, 없으면 제안한 목표를 보인다. 확장은 강사가 쓸 때까지 제목이 없다. */
function recommendationTitle(recommendation: Recommendation): string {
  return (recommendation.decidedGoal ?? recommendation.suggestedGoal).title ?? '넓힐 목표를 골라 주세요'
}

/** 추천 화면 주소. 아동을 고른 채로, recommendationId 가 있으면 추천 상세(T-REC-02)로 연다. */
function recommendationsPath({
  classId,
  childId,
  recommendationId,
}: {
  classId: string
  childId: string
  recommendationId?: string
}): string {
  const params = new URLSearchParams({ classId, childId })
  if (recommendationId) params.set('recommendationId', recommendationId)
  return `/recommendations?${params.toString()}`
}

const SUPPORT_ORDER: SupportLevel[] = ['S0', 'S1', 'S2', 'S3']
const STEP_WORDS = ['', '한', '두', '세']

/** 시안 T-REC-02 예상 조건의 "L2 (유지)" 괄호. 서버가 정한 다음 레벨 방향이다. */
const LEVEL_CHANGE_LABELS = { LOWER: '한 단계 하향', KEEP: '유지', RAISE: '한 단계 상향' } as const

/** 시안 T-REC-02 예상 조건의 "S1 (한 단계 하향)" 괄호. 이번 역할극 시작 지원과 다음 시작 지원의 차이다. */
function supportChangeLabel(previous: SupportLevel, next: SupportLevel): string {
  const steps = SUPPORT_ORDER.indexOf(next) - SUPPORT_ORDER.indexOf(previous)
  if (steps === 0) return '유지'
  return `${STEP_WORDS[Math.abs(steps)]} 단계 ${steps < 0 ? '하향' : '상향'}`
}

/** 기술 기록 탭에 따로 보이는 건수(기술 오류 + 판단할 답이 없어 뺀 목표). 탭 이름 옆 숫자로도 쓴다. */
function technicalCount(report: LearningReport): number {
  return (
    report.roleplay.technicalExclusions.length +
    report.roleplay.goals.filter((goal) => goal.status === 'EXCLUDED').length
  )
}

export { LEVEL_CHANGE_LABELS, recommendationTitle, recommendationsPath, supportChangeLabel, technicalCount }
export type { NextLevel }
