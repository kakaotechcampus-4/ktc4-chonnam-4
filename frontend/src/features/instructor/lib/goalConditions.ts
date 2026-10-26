/**
 * 역할극 추가 조건(시안 T-ACT-04)의 보기와 모순 규칙.
 * 필수 요소는 아이가 대화에서 해 볼 행동이고, 보기는 시안의 세 항목이다. 관계도 시안의 세 보기다. 목록 관리가 생기면 이 파일만 바꾼다.
 * 서버는 두 목록 모두 값 목록을 검사하지 않고 문자열로 받는다.
 */

export const REQUIRED_ELEMENT_PRESETS = ['감정을 확인하는 질문 1회', '위로 표현 1회', '함께 할 행동 제안'] as const

export const RELATION_OPTIONS = ['같은 반 친구', '형제·자매', '선생님'] as const

export const SITUATION_MAX = 200
export const CHARACTER_NAME_MAX = 50

/** 등장인물. 관계는 그 인물이 아이에게 누구인지다. */
export type GoalCharacter = { name: string; relation?: string }

/**
 * 필수 요소 안에 들어 있는 피할 표현의 위치. 서버(GoalConditions)와 같은 규칙이다: 공백을 지우고 소문자로 바꿔 부분 문자열로 비교한다.
 * 시나리오 생성 결과 검사가 같은 기준으로 피할 표현을 찾으므로, 여기 걸리는 조건은 생성이 계속 실패한다.
 */
export function contradictingForbidden(required: readonly string[], forbidden: readonly string[]): number[] {
  const elements = required.map(compact)
  return forbidden.flatMap((expression, index) => {
    const needle = compact(expression)
    return needle && elements.some((element) => element.includes(needle)) ? [index] : []
  })
}

function compact(value: string): string {
  return value.replace(/\s+/g, '').toLowerCase()
}
