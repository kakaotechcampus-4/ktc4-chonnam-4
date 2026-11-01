import { tabBaseClass, tabDisabledClass, tabIdleClass, tabSelectedClass } from './styles'
import { cn } from '@/lib/utils'

type PageTab = {
  label: string
  // 없으면 아직 화면이 없는 탭이라 비활성으로 보인다(instructorNav.ts 의 메뉴와 같은 규칙).
  available?: boolean
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
          </button>
        ) : tab.available ? (
          <span
            key={tab.label}
            role="tab"
            aria-selected={tab.label === selected}
            className={cn(tabBaseClass, tab.label === selected ? tabSelectedClass : tabIdleClass)}
          >
            {tab.label}
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
