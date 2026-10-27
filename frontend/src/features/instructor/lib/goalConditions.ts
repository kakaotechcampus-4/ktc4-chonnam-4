/**
 * 역할극 추가 조건(시안 T-ACT-04)의 보기와 모순 규칙.
 * 필수 요소는 아이가 대화에서 해 볼 행동이고, 보기는 시안의 세 항목이다. 관계도 시안의 세 보기이고, 고른 관계가 등장인물 id 가 된다.
 * 목록 관리가 생기면 이 파일만 바꾼다.
 * 서버는 두 목록 모두 값 목록을 검사하지 않고 문자열로 받는다.
 */

export const REQUIRED_ELEMENT_PRESETS = ['감정을 확인하는 질문 1회', '위로 표현 1회', '함께 할 행동 제안'] as const

/** 관계 보기와 그 관계의 등장인물 id. id 는 시나리오 생성 입력·출력과 같은 영문 대문자 코드다. */
export const RELATION_OPTIONS = [
  { id: 'FRIEND', label: '같은 반 친구' },
  { id: 'SIBLING', label: '형제·자매' },
  { id: 'TEACHER', label: '선생님' },
] as const

/** 관계를 고르지 않은 등장인물의 id. 서버도 id 가 비면 이 값으로 저장한다. */
export const OTHER_CHARACTER_ID = 'OTHER'

export const SITUATION_MAX = 200
export const CHARACTER_ROLE_MAX = 50

/**
 * 등장인물. 팀 명세 예시·시나리오 생성과 같은 {id, role} 모양이다. role 은 인물 설명(예: "넘어진 친구"), id 는 아이와의 관계 종류다.
 * 실제 이름은 받지 않는다. 서버는 id 를 비워도 받지만 화면은 늘 채워 보낸다.
 */
export type GoalCharacter = { id?: string; role: string }

/** 관계 id 의 화면 이름. 보기에 없는 id(OTHER 등)는 null 이다. */
export function relationLabel(id: string | undefined): string | null {
  return RELATION_OPTIONS.find((option) => option.id === id)?.label ?? null
}

/**
 * 필수 요소나 등장인물 role 안에 들어 있는 피할 표현의 위치. 서버(GoalConditions)와 같은 규칙이다: 공백을 지우고 소문자로 바꿔 부분 문자열로 비교한다.
 * 시나리오 생성 결과 검사가 같은 기준으로(등장인물 role 포함) 피할 표현을 찾으므로, 여기 걸리는 조건은 생성이 계속 실패한다.
 */
export function contradictingForbidden(
  required: readonly string[],
  forbidden: readonly string[],
  roles: readonly string[] = []
): number[] {
  const elements = [...required, ...roles].map(compact)
  return forbidden.flatMap((expression, index) => {
    const needle = compact(expression)
    return needle && elements.some((element) => element.includes(needle)) ? [index] : []
  })
}

function compact(value: string): string {
  return value.replace(/\s+/g, '').toLowerCase()
}
