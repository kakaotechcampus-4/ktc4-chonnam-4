import { tabBaseClass, tabDisabledClass, tabIdleClass, tabSelectedClass } from './styles'
import { cn } from '@/lib/utils'

type PageTab = {
  label: string
  // 없으면 아직 화면이 없는 탭이라 비활성으로 보인다(instructorNav.ts 의 메뉴와 같은 규칙).
  available?: boolean
  // 시안의 탭 옆 숫자(예: 기술 기록 ②). 0 이면 보이지 않는다.
  count?: number
}

function TabCount({ count }: { count?: number }) {
  if (!count) return null
  // 앞의 빈칸은 화면 읽기 이름을 "기술 기록 2건"으로 띄우려고 둔다. 숫자 동그라미는 읽지 않고 "2건"을 읽는다.
  return (
    <>
      {' '}
      <span
        aria-hidden
        className="ml-0.5 inline-flex h-5 min-w-5 items-center justify-center rounded-full bg-[var(--instructor-neutral-bg)] px-1.5 text-xs font-normal text-[var(--instructor-neutral-fg)]"
      >
        {count}
      </span>
      <span className="sr-only">{count}건</span>
    </>
  )
}

/**
 * 헤더 아래 탭 띠. 시안의 탭을 모두 보여 주되, 화면이 있는 탭만 고를 수 있다.
 * `onSelect` 를 주면 열린 탭이 버튼이 되어 고를 수 있다(학급 상세처럼 ?tab= 으로 남긴다). 주지 않으면 열린 탭 하나를 보여 주기만 한다.
 */
function PageTabs({
  label,
  tabs,
  selected,
  onSelect,
}: {
  label: string
  tabs: PageTab[]
  selected: string
  onSelect?: (label: string) => void
}) {
  return (
    <div role="tablist" aria-label={label} className="flex gap-1">
      {tabs.map((tab) =>
        tab.available && onSelect ? (
          <button
            key={tab.label}
            type="button"
            role="tab"
            aria-selected={tab.label === selected}
            onClick={() => onSelect(tab.label)}
            className={cn(tabBaseClass, tab.label === selected ? tabSelectedClass : tabIdleClass)}
          >
            {tab.label}
            <TabCount count={tab.count} />
          </button>
        ) : tab.available ? (
          <span
            key={tab.label}
            role="tab"
            aria-selected={tab.label === selected}
            className={cn(tabBaseClass, tab.label === selected ? tabSelectedClass : tabIdleClass)}
          >
            {tab.label}
            <TabCount count={tab.count} />
          </span>
        ) : (
          <span
            key={tab.label}
            role="tab"
            aria-disabled="true"
            aria-selected={false}
            title="준비 중인 화면입니다"
            className={cn(tabBaseClass, tabDisabledClass)}
          >
            {tab.label}
          </span>
        )
      )}
    </div>
  )
}

export { PageTabs }
export type { PageTab }
