/**
 * 강사 사이드바 메뉴. 순서와 이름은 시안(T-HOME-01 등)을 따른다.
 * `to`가 없는 메뉴는 아직 화면이 없어 비활성으로 보인다. 화면이 생기면 `to`만 채운다.
 */
type InstructorNavItem = {
  label: string
  to?: string
}

const INSTRUCTOR_NAV_ITEMS: InstructorNavItem[] = [
  { label: '대시보드' },
  { label: '학급', to: '/classrooms' },
  { label: '활동 만들기' },
  { label: '콘텐츠·승인' },
  { label: '활동 관리' },
  { label: '리포트' },
  { label: '추천·다음목표' },
  { label: '알림' },
  { label: '설정' },
]

/**
 * 하위 화면(`/classrooms/abc`, `/classrooms/new`)에서도 상위 메뉴를 선택된 상태로 보인다.
 * `/classrooms-archive` 같은 이름이 겹치는 경로는 제외하려고 `/` 경계까지 확인한다.
 */
function isNavActive(pathname: string, to: string): boolean {
  return pathname === to || pathname.startsWith(`${to}/`)
}

export { INSTRUCTOR_NAV_ITEMS, isNavActive }
export type { InstructorNavItem }
