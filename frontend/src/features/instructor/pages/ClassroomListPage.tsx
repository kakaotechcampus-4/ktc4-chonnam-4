import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { listClassrooms, type ClassroomStatus } from '../api'
import { InstructorLayout } from '../layout/InstructorLayout'
import { ClassroomStatusBadge } from '../components/ClassroomStatusBadge'
import { cardClass, errorTextClass, inputClass, primaryButtonClass } from '../components/styles'
import { cn } from '@/lib/utils'

type StatusFilter = 'ALL' | ClassroomStatus

const STATUS_FILTERS: { value: StatusFilter; label: string }[] = [
  { value: 'ALL', label: '전체' },
  { value: 'ACTIVE', label: '운영중' },
  { value: 'ARCHIVED', label: '보관' },
]

// 시안(T-CLS-01)의 열 중 목록 API가 아직 주지 않는 값. 데이터가 생기면 "—" 대신 값을 채운다.
// 시안 순서(학급명·아동 수·진행중 활동·승인 대기·상태·최근 학습)를 지키려고 상태 열 앞뒤로 나눈다.
const PENDING_COLUMNS_BEFORE_STATUS = ['아동 수', '진행중 활동', '승인 대기']
const PENDING_COLUMNS_AFTER_STATUS = ['최근 학습']

function ClassroomListPage() {
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL')
  const [search, setSearch] = useState('')

  const {
    data: classrooms,
    isLoading,
    isError: isListError,
    error: listError,
  } = useQuery({
    queryKey: ['classrooms'],
    queryFn: listClassrooms,
  })

  // 담당 학급 수가 적어 전체를 받아 화면에서 거른다. 학급이 많아지면 API 의 status 조회 조건으로 옮긴다.
  const keyword = search.trim()
  const visibleClassrooms = (classrooms ?? []).filter(
    (classroom) =>
      (statusFilter === 'ALL' || classroom.status === statusFilter) &&
      (keyword === '' || classroom.name.includes(keyword))
  )

  return (
    <InstructorLayout title="학급 목록">
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <div role="group" aria-label="학급 상태" className="flex gap-2">
          {STATUS_FILTERS.map((filter) => (
            <button
              key={filter.value}
              type="button"
              aria-pressed={statusFilter === filter.value}
              onClick={() => setStatusFilter(filter.value)}
              className={cn(
                'h-10 rounded-[var(--instructor-radius-control)] border px-4 text-sm',
                statusFilter === filter.value
                  ? 'border-[var(--instructor-primary)] bg-[var(--instructor-primary)] text-[var(--instructor-primary-foreground)]'
                  : 'border-[var(--instructor-input-border)] bg-[var(--instructor-surface)]'
              )}
            >
              {filter.label}
            </button>
          ))}
        </div>
        <label htmlFor="classroom-search" className="sr-only">
          학급명 검색
        </label>
        <input
          id="classroom-search"
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="학급명 검색"
          className={cn(inputClass, 'h-10 w-56')}
        />
        <Link to="/classrooms/new" className={cn(primaryButtonClass, 'ml-auto')}>
          학급 생성
        </Link>
      </div>

      {isLoading ? (
        <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
      ) : isListError ? (
        <p className={errorTextClass}>
          {listError instanceof Error ? listError.message : '학급 목록을 불러오지 못했습니다.'}
        </p>
      ) : classrooms?.length === 0 ? (
        <p className="text-sm text-[var(--instructor-text-muted)]">
          아직 학급이 없습니다. 학급 생성으로 첫 학급을 만들어 보세요.
        </p>
      ) : (
        <>
          <div className={cn(cardClass, 'overflow-hidden')}>
            <table className="w-full text-sm">
              <thead className="bg-[var(--instructor-surface-muted)] text-xs text-[var(--instructor-text-muted)]">
                <tr>
                  <th scope="col" className="px-4 py-3 text-left font-semibold">
                    학급명
                  </th>
                  {PENDING_COLUMNS_BEFORE_STATUS.map((column) => (
                    <th key={column} scope="col" className="px-4 py-3 text-left font-semibold">
                      {column}
                    </th>
                  ))}
                  <th scope="col" className="px-4 py-3 text-left font-semibold">
                    상태
                  </th>
                  {PENDING_COLUMNS_AFTER_STATUS.map((column) => (
                    <th key={column} scope="col" className="px-4 py-3 text-left font-semibold">
                      {column}
                    </th>
                  ))}
                  <th scope="col" className="px-4 py-3">
                    <span className="sr-only">상세</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {visibleClassrooms.map((classroom) => {
                  const isArchived = classroom.status === 'ARCHIVED'
                  return (
                    <tr
                      key={classroom.classId}
                      className={cn(
                        'border-t border-[var(--instructor-border)]',
                        isArchived && 'text-[var(--instructor-text-muted)]'
                      )}
                    >
                      <td className={cn('px-4 py-3.5', !isArchived && 'font-semibold')}>{classroom.name}</td>
                      {PENDING_COLUMNS_BEFORE_STATUS.map((column) => (
                        <td key={column} className="px-4 py-3.5 text-[var(--instructor-text-disabled)]">
                          —
                        </td>
                      ))}
                      <td className="px-4 py-3.5">
                        <ClassroomStatusBadge status={classroom.status} />
                      </td>
                      {PENDING_COLUMNS_AFTER_STATUS.map((column) => (
                        <td key={column} className="px-4 py-3.5 text-[var(--instructor-text-disabled)]">
                          —
                        </td>
                      ))}
                      <td className="px-4 py-3.5 text-right">
                        <Link
                          to={`/classrooms/${classroom.classId}`}
                          className="text-[var(--instructor-link)] hover:underline"
                          aria-label={`${classroom.name} 상세`}
                        >
                          상세
                        </Link>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
            {visibleClassrooms.length === 0 && (
              <p className="border-t border-[var(--instructor-border)] px-4 py-6 text-center text-sm text-[var(--instructor-text-muted)]">
                조건에 맞는 학급이 없습니다.
              </p>
            )}
          </div>
          <div className="mt-3 text-xs text-[var(--instructor-text-muted)]">보관된 학급의 기록은 조회만 가능합니다.</div>
        </>
      )}
    </InstructorLayout>
  )
}

export { ClassroomListPage }
