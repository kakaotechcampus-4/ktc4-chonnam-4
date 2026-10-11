import { cn } from '@/lib/utils'

type PageTab = {
  label: string
  // 없으면 아직 화면이 없는 탭이라 비활성으로 보인다(instructorNav.ts 의 메뉴와 같은 규칙).
  available?: boolean
}

const tabClass = 'relative -mb-px border-b-2 px-4 py-3 text-sm'

/**
 * 헤더 아래 탭 띠. 시안의 탭을 모두 보여 주되, 화면이 있는 탭만 고를 수 있다.
 * 열린 탭이 하나뿐인 화면(아동 상세·활동 리포트)용이라 선택 상태는 `selected` 하나로 받는다.
 */
function PageTabs({ label, tabs, selected }: { label: string; tabs: PageTab[]; selected: string }) {
  return (
    <div role="tablist" aria-label={label} className="flex">
      {tabs.map((tab) =>
        tab.available ? (
          <span
            key={tab.label}
            role="tab"
            aria-selected={tab.label === selected}
            className={cn(
              tabClass,
              tab.label === selected
                ? 'border-[var(--instructor-primary)] font-semibold'
                : 'border-transparent text-[var(--instructor-text-muted)]'
            )}
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
            className={cn(tabClass, 'cursor-not-allowed border-transparent text-[var(--instructor-text-disabled)]')}
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
