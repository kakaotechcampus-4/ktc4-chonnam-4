import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { listScenarioApprovals, type ScenarioApprovalItem, type ScenarioApprovalState } from '../api'
import { InstructorLayout } from '../layout/InstructorLayout'
import { PageTabs } from '../components/PageTabs'
import { SearchInput } from '../components/SearchInput'
import { ApprovalStateBadge } from '../components/ScenarioBadges'
import { APPROVAL_STATE_LABELS } from '../lib/scenarioLabels'
import { filterButtonClass, tableClass, tableHeadRowClass, tableRowClass } from '../components/styles'
import { Notice } from '../components/Feedback'
import { formatDateTime } from '../lib/dateTime'
import { describeError } from '../errorMessage'
import { cn } from '@/lib/utils'

const INBOX_TAB = '승인 대기함'

type StateFilter = 'ALL' | ScenarioApprovalState

const STATE_FILTERS: StateFilter[] = ['ALL', 'CANDIDATE_AVAILABLE', 'DRAFT_IN_PROGRESS', 'NO_CANDIDATE']

// 시안처럼 검토할 수 있는 줄(검토 대기·수정 중)을 위로 올린다. 같은 묶음 안은 서버가 준 기다린 순서 그대로다.
const STATE_ORDER: Record<ScenarioApprovalState, number> = {
  CANDIDATE_AVAILABLE: 0,
  DRAFT_IN_PROGRESS: 0,
  NO_CANDIDATE: 1,
}

const linkClass =
  'text-[var(--instructor-link)] hover:underline focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-[var(--instructor-focus)]'

/**
 * 콘텐츠·승인 관리 → 승인 대기함(시안 T-CNT). 시나리오를 기다리는 최초 결합 활동을 한 줄씩 보여 준다.
 * 재사용 후보는 이미 있는 공용 시나리오라 이 아동을 위한 시나리오가 따로 없어, 시안의 시나리오 중심 열 대신 아동·목표 중심으로 보인다.
 * 시나리오 목록·상세·버전 이력 탭과 일괄 승인은 아직 없다.
 */
function ContentApprovalPage() {
  const [stateFilter, setStateFilter] = useState<StateFilter>('ALL')
  const [search, setSearch] = useState('')
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['scenario-approvals'],
    queryFn: listScenarioApprovals,
  })

  const items = data ?? []
  const keyword = search.trim()
  const visible = items
    .filter(
      (item) =>
        (stateFilter === 'ALL' || item.state === stateFilter) &&
        (keyword === '' ||
          item.childName.includes(keyword) ||
          item.goalTitle.includes(keyword) ||
          item.className.includes(keyword)),
    )
    .map((item, index) => ({ item, index }))
    .sort((a, b) => STATE_ORDER[a.item.state] - STATE_ORDER[b.item.state] || a.index - b.index)
    .map(({ item }) => item)
  const countOf = (filter: StateFilter) =>
    filter === 'ALL' ? items.length : items.filter((item) => item.state === filter).length

  return (
    <InstructorLayout
      title="콘텐츠 · 승인 관리"
      subheader={
        <PageTabs
          label="콘텐츠 · 승인 관리"
          selected={INBOX_TAB}
          tabs={[{ label: '시나리오 목록' }, { label: INBOX_TAB, available: true }, { label: '상세 · 버전 이력' }]}
        />
      }
    >
      <section role="tabpanel" aria-label={INBOX_TAB}>
        <div className="mb-4 flex flex-wrap items-center gap-2">
          <div role="group" aria-label="승인 상태" className="flex gap-2">
            {STATE_FILTERS.map((filter) => (
              <button
                key={filter}
                type="button"
                aria-pressed={stateFilter === filter}
                onClick={() => setStateFilter(filter)}
                className={filterButtonClass}
              >
                {filter === 'ALL' ? '전체' : APPROVAL_STATE_LABELS[filter]} {countOf(filter)}
              </button>
            ))}
          </div>
          <SearchInput
            id="approval-search"
            label="아동·목표·학급 검색"
            value={search}
            onChange={setSearch}
            placeholder="아동·목표·학급 검색"
            className="ml-auto w-[246px]"
          />
        </div>

        {isLoading ? (
          <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
        ) : isError ? (
          <Notice tone="error">{describeError(error, '승인 대기 목록을 불러오지 못했습니다.')}</Notice>
        ) : items.length === 0 ? (
          <p className="text-sm text-[var(--instructor-text-muted)]">
            승인을 기다리는 활동이 없습니다. 아동이 첫 활동의 퀴즈를 마치면 여기에 보입니다.
          </p>
        ) : (
          <>
            <div className={tableClass}>
              <table className="w-full">
                <thead>
                  <tr className={tableHeadRowClass}>
                    <th scope="col" className="px-2 py-2.5 font-normal">
                      아동 · 목표
                    </th>
                    <th scope="col" className="px-2 py-2.5 font-normal">
                      상태
                    </th>
                    <th scope="col" className="px-2 py-2.5 font-normal">
                      학급
                    </th>
                    <th scope="col" className="px-2 py-2.5 font-normal">
                      난이도
                    </th>
                    <th scope="col" className="px-2 py-2.5 font-normal">
                      대기 시작
                    </th>
                    <th scope="col" className="px-2 py-2.5">
                      <span className="sr-only">처리</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {visible.map((item) => (
                    <tr key={item.activityId} className={cn(tableRowClass, 'text-[var(--instructor-text-muted)]')}>
                      <td className="px-2 py-3">
                        <p className="text-[var(--instructor-text)]">{item.goalTitle}</p>
                        <p className="text-xs">{item.childName}</p>
                      </td>
                      <td className="px-2 py-3">
                        <ApprovalStateBadge state={item.state} />
                      </td>
                      <td className="px-2 py-3">{item.className}</td>
                      <td className="px-2 py-3">{item.scenarioLevel}</td>
                      <td className="px-2 py-3">{formatDateTime(item.waitingSince)}</td>
                      <td className="px-2 py-3 text-right">
                        <RowAction item={item} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {visible.length === 0 && (
                <p className="border-t border-[var(--instructor-border-subtle)] px-5 py-6 text-center text-sm text-[var(--instructor-text-muted)]">
                  조건에 맞는 활동이 없습니다.
                </p>
              )}
            </div>
            <p className="mt-4 text-xs text-[var(--instructor-text-subtle)]">
              승인 대기함은 검토 대기와 수정 중인 항목을 위로 정렬합니다. 후보가 없는 활동은 시나리오 생성이 들어오면 새로 만들 수 있습니다.
            </p>
          </>
        )}
      </section>
    </InstructorLayout>
  )
}

function RowAction({ item }: { item: ScenarioApprovalItem }) {
  if (item.state === 'DRAFT_IN_PROGRESS' && item.draftScenarioId) {
    return (
      <Link
        to={`/scenarios/${item.draftScenarioId}/edit`}
        className={linkClass}
        aria-label={`${item.childName} ${item.goalTitle} 수정`}
      >
        수정
      </Link>
    )
  }
  if (item.state === 'CANDIDATE_AVAILABLE') {
    return (
      <Link
        to={`/activities/${item.activityId}/scenario`}
        className={linkClass}
        aria-label={`${item.childName} ${item.goalTitle} 검토`}
      >
        검토
      </Link>
    )
  }
  return <span className="text-[var(--instructor-text-disabled)]">대기</span>
}

export { ContentApprovalPage }
