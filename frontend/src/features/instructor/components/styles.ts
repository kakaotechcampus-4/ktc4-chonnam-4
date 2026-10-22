/**
 * 강사 화면들이 공유하는 Tailwind 클래스 묶음.
 * 색·모서리 값은 index.css 의 .instructor-scope 토큰을 따르므로, 시안 값이 바뀌면 토큰만 고친다.
 * 크기는 디자인 시스템의 Teacher 부품을 따른다: 버튼 40px(안쪽 18, 글씨 버튼 14)·입력 42px·필터 34px, 모서리 8, 포커스는 3px 링(2px 떨어짐).
 */

const focusRingClass =
  "focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-[var(--instructor-focus)]"

const inputClass =
  "h-[42px] w-full rounded-[var(--instructor-radius-control)] border border-[var(--instructor-input-border)] bg-[var(--instructor-surface)] px-3 text-base text-[var(--instructor-text)] placeholder:text-[var(--instructor-text-disabled)] focus-visible:border-[var(--instructor-focus)] focus-visible:outline-none disabled:bg-[var(--instructor-disabled-bg)] disabled:text-[var(--instructor-text-disabled)]"

const fieldLabelClass = "flex flex-col gap-1.5 text-sm text-[var(--instructor-text)]"

// 입력칸 아래 도움말
const helperTextClass = "text-xs text-[var(--instructor-text-subtle)]"

const buttonBaseClass = `inline-flex h-10 shrink-0 items-center justify-center gap-2 rounded-[var(--instructor-radius-control)] px-[18px] text-sm font-semibold ${focusRingClass} disabled:cursor-not-allowed`

const primaryButtonClass = `${buttonBaseClass} bg-[var(--instructor-primary)] text-[var(--instructor-primary-foreground)] hover:bg-[var(--instructor-primary-hover)] active:bg-[var(--instructor-primary-pressed)] disabled:bg-[var(--instructor-disabled-bg)] disabled:text-[var(--instructor-text-disabled)]`

const outlineButtonClass = `${buttonBaseClass} border border-[var(--instructor-border-strong)] bg-[var(--instructor-surface)] text-[var(--instructor-text)] hover:bg-[var(--instructor-surface-muted)] disabled:border-[var(--instructor-border)] disabled:text-[var(--instructor-text-disabled)] disabled:hover:bg-[var(--instructor-surface)]`

// 글씨만 있는 버튼(Tertiary). "자세히 보기"처럼 덜 중요한 행동에 쓴다.
const textButtonClass = `${buttonBaseClass} px-3.5 text-[var(--instructor-link)] hover:bg-[var(--instructor-primary-subtle)] disabled:text-[var(--instructor-text-disabled)] disabled:hover:bg-transparent`

// 되돌릴 수 없는 동작(삭제)의 버튼. 실행 버튼은 채운 빨강, 여는 버튼은 빨간 글씨 테두리로 구분한다.
const dangerButtonClass = `${buttonBaseClass} bg-[var(--instructor-danger-fg)] text-white hover:bg-[var(--instructor-danger-text)] disabled:bg-[var(--instructor-disabled-bg)] disabled:text-[var(--instructor-text-disabled)]`

const dangerOutlineButtonClass = `${buttonBaseClass} border border-[var(--instructor-border-strong)] bg-[var(--instructor-surface)] text-[var(--instructor-danger-fg)] hover:bg-[var(--instructor-danger-bg)] disabled:border-[var(--instructor-border)] disabled:text-[var(--instructor-text-disabled)] disabled:hover:bg-[var(--instructor-surface)]`

// 필터 막대의 낮은 컨트롤. 디자인 시스템 Filter Bar 예시대로 테두리 있는 컨트롤은 34px(Compact 32 + 테두리). 상태 필터 버튼은 aria-pressed 로 선택을 나타내고, 선택되면 Primary 로 채운다.
const filterButtonClass = `inline-flex h-[34px] shrink-0 items-center rounded-[var(--instructor-radius-control)] border border-[var(--instructor-input-border)] bg-[var(--instructor-surface)] px-3 text-sm text-[var(--instructor-text)] hover:bg-[var(--instructor-surface-muted)] ${focusRingClass} aria-pressed:border-[var(--instructor-primary)] aria-pressed:bg-[var(--instructor-primary)] aria-pressed:text-[var(--instructor-primary-foreground)]`

const cardClass =
  "rounded-[var(--instructor-radius-card)] border border-[var(--instructor-border)] bg-[var(--instructor-surface)] shadow-[var(--instructor-shadow-1)]"

// 상태 배지(진행 중·완료 등). 디자인 시스템 Badge: 높이 22, 안쪽 10, 모서리 4. 색과 함께 글자로 상태를 알린다.
const badgeClass =
  "inline-flex h-[22px] items-center rounded-[var(--instructor-radius-badge)] px-2.5 text-xs whitespace-nowrap"

// 탭(디자인 시스템 Tabs): 선택된 탭은 색과 함께 2px 밑줄로도 알린다(색만으로 구분하지 않는다).
const tabBaseClass = `relative -mb-px border-b-2 px-3.5 pt-2.5 pb-3 text-sm ${focusRingClass}`
const tabSelectedClass = 'border-[var(--instructor-link)] font-semibold text-[var(--instructor-link)]'
const tabIdleClass = 'border-transparent text-[var(--instructor-text-subtle)] hover:text-[var(--instructor-text)]'
const tabDisabledClass = 'cursor-not-allowed border-transparent text-[var(--instructor-border-strong)]'

// 표: 바깥 테두리 12 모서리, 머리줄은 옅은 바탕 12px, 행은 14px 와 옅은 구분선.
const tableClass =
  "w-full overflow-hidden rounded-[var(--instructor-radius-card)] border border-[var(--instructor-border)] bg-[var(--instructor-surface)] text-sm"
const tableHeadRowClass =
  "border-b border-[var(--instructor-border)] bg-[var(--instructor-surface-muted)] text-left text-xs text-[var(--instructor-text-subtle)]"
const tableRowClass = "border-b border-[var(--instructor-border-subtle)] last:border-b-0"

export {
  badgeClass,
  cardClass,
  dangerButtonClass,
  dangerOutlineButtonClass,
  fieldLabelClass,
  filterButtonClass,
  focusRingClass,
  helperTextClass,
  inputClass,
  outlineButtonClass,
  primaryButtonClass,
  tableClass,
  tableHeadRowClass,
  tableRowClass,
  tabBaseClass,
  tabDisabledClass,
  tabIdleClass,
  tabSelectedClass,
  textButtonClass,
}
