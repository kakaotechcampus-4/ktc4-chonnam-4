/**
 * 활동 만들기 "목표 목록에서 선택" 탭의 목표 목록 (ADR 2026-10-03 D3).
 * 백엔드에 목표 목록 API 가 없어 S1 은 프론트 상수로 둔다. 문구·분류·권장 난이도는 시안 T-ACT-03 의 예시이며,
 * 느린 학습자에게 맞는지는 팀 검토 전이다. 목록 관리 기능이 생기면 이 파일만 API 로 바꾼다.
 *
 * 분류는 목표의 category 로 저장한다. 서버는 자유 문자열(30자 이내)로 받고, 값 목록이 아직 정해지지 않아 아래 키를 쓴다.
 */

export type GoalCategory = 'EMOTION_RECOGNITION' | 'EMPATHY_EXPRESSION' | 'SITUATION_COPING'

export const GOAL_CATEGORY_LABELS: Record<GoalCategory, string> = {
  EMOTION_RECOGNITION: '감정 인식',
  EMPATHY_EXPRESSION: '공감 표현',
  SITUATION_COPING: '상황 대처',
}

export const GOAL_CATEGORIES = Object.keys(GOAL_CATEGORY_LABELS) as GoalCategory[]

export type GoalPreset = {
  id: string
  title: string
  category: GoalCategory
  recommendedLevel: 'L1' | 'L2' | 'L3'
}

export const GOAL_PRESETS: GoalPreset[] = [
  { id: 'comfort-friend', title: '친구가 속상할 때 위로하는 말을 한다', category: 'EMPATHY_EXPRESSION', recommendedLevel: 'L2' },
  { id: 'tell-emotions', title: '표정에서 기쁨·슬픔·화남을 구분한다', category: 'EMOTION_RECOGNITION', recommendedLevel: 'L1' },
  { id: 'ask-for-help', title: '도움이 필요할 때 말로 요청한다', category: 'SITUATION_COPING', recommendedLevel: 'L2' },
  { id: 'wait-turn', title: '순서를 기다리며 기분을 말로 표현한다', category: 'SITUATION_COPING', recommendedLevel: 'L3' },
]

export function isGoalCategory(value: string | null): value is GoalCategory {
  return value !== null && value in GOAL_CATEGORY_LABELS
}
