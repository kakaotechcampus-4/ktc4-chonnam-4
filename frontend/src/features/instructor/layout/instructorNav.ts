import type { MaterialIconName } from '../components/MaterialIcon'

/**
 * 강사 사이드바 메뉴. 순서와 이름은 시안(T-HOME-01 등)을 따른다.
 * `to`가 없는 메뉴는 아직 화면이 없어 비활성으로 보인다. 화면이 생기면 `to`만 채운다.
 * 아이콘은 디자인 시스템 Side Navigation 예시(학급 groups, 활동 assignment, 검토 fact_check, 리포트 insert_chart)를 따르고,
 * 예시에 없는 메뉴는 같은 Material Symbols Rounded 에서 뜻이 가까운 것을 골랐다.
 */
type InstructorNavItem = {
  label: string
  icon: MaterialIconName
  to?: string
}

const INSTRUCTOR_NAV_ITEMS: InstructorNavItem[] = [
  { label: '대시보드', icon: 'dashboard' },
  { label: '학급', icon: 'groups', to: '/classrooms' },
  { label: '활동 만들기', icon: 'post_add', to: '/activities/new' },
  { label: '콘텐츠·승인', icon: 'fact_check' },
  { label: '활동 관리', icon: 'assignment' },
  { label: '리포트', icon: 'insert_chart' },
  { label: '추천·다음목표', icon: 'flag' },
  { label: '알림', icon: 'notifications' },
  { label: '설정', icon: 'settings', to: '/account' },
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
