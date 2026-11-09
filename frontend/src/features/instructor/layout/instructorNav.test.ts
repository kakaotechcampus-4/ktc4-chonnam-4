import { describe, expect, it } from 'vitest'
import { isNavActive } from './instructorNav'

describe('강사 메뉴 선택', () => {
  it('하위 화면에서도 상위 메뉴를 고르되, 이름만 겹치는 경로는 고르지 않는다', () => {
    expect(isNavActive('/classrooms/abc', '/classrooms')).toBe(true)
    expect(isNavActive('/classrooms-archive', '/classrooms')).toBe(false)
  })

  it('exact 메뉴는 주소가 같을 때만 고른다(활동 관리가 활동 만들기·리포트에서 함께 켜지지 않게)', () => {
    expect(isNavActive('/activities', '/activities', true)).toBe(true)
    expect(isNavActive('/activities/new', '/activities', true)).toBe(false)
    expect(isNavActive('/activities/abc/report', '/activities', true)).toBe(false)
  })
})
