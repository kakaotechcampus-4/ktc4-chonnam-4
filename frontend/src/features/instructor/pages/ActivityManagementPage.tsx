import { Link, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { listClassrooms, type ClassroomActivity } from '../api'
import { describeError } from '../errorMessage'
import { InstructorLayout } from '../layout/InstructorLayout'
import { ActivityStatusBadge } from '../components/ActivityStatusBadge'
import { Notice } from '../components/Feedback'
import { PageTabs } from '../components/PageTabs'
import { cardClass, fieldLabelClass, selectClass, tableClass, tableHeadRowClass, tableRowClass } from '../components/styles'
import { useActivitiesOfClassrooms } from '../hooks/classroomQueries'
import { countEachStatus, STATUS_ORDER } from '../lib/activitySummary'
import { ACTIVITY_TYPE_LABELS } from '../lib/activityTypes'
import { formatDateTime } from '../lib/dateTime'
import { cn } from '@/lib/utils'

const LIST_TAB = '활동 목록'
const ATTENTION_TAB = '미완료 · 복구'

type ManagedActivity = ClassroomActivity & { classId: string; className: string }

function needsAttention(activity: ManagedActivity) {
  return activity.status === 'PAUSED' || activity.status === 'RECOVERY_NEEDED'
}

/**
 * 활동 관리 (시안 T-MGT-01~03 축소판). 담당 학급 전체의 배정 활동을 모아 보고, 일시 중지·복구 필요 활동을 따로 모은다.
 * 활동은 학급마다 학급 활동 목록(API-ACT-05)을 한 번씩 불러 합친다(대시보드와 같은 캐시).
 * 서버에 복구·재배정 같은 조치 API 가 없어, 줄마다 확인할 곳(아동 상세·활동 리포트)과 새 배정(활동 만들기)으로 가는 링크를 둔다.
 * 활동 상세는 활동 리포트 화면이 맡아 따로 탭을 두지 않는다. 진행 턴·원인·장기 미완료는 서버가 주지 않아 넣지 않는다.
 * 학급 필터는 ?classId= (없으면 전체 학급), 탭은 ?tab=attention 으로 주소에 남긴다.
 */
function ActivityManagementPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const classroomsQuery = useQuery({ queryKey: ['classrooms'], queryFn: listClassrooms })
  const classrooms = classroomsQuery.data ?? []
  const activityResults = useActivitiesOfClassrooms(classroomsQuery.data)

  const requested = searchParams.get('classId')
  // 없는 학급(지운 학급, 잘못된 주소)이면 전체 학급으로 본다.
  const classId = classrooms.some((c) => c.classId === requested) ? requested : null
  const tab = searchParams.get('tab') === 'attention' ? ATTENTION_TAB : LIST_TAB

  const activities: ManagedActivity[] = activityResults
    .flatMap((result, index) =>
      (result.data ?? []).map((activity) => ({
        ...activity,
        classId: classrooms[index].classId,
        className: classrooms[index].name,
      })),
    )
    .filter((activity) => !classId || activity.classId === classId)
    // 학급마다 최근 배정 순으로 오므로, 합친 뒤 다시 최근 배정 순으로 맞춘다.
    .sort((a, b) => Date.parse(b.assignedAt) - Date.parse(a.assignedAt))
  // 학급 하나가 실패해도 나머지 학급으로 그린다.
  const activitiesReady = activityResults.every((result) => !result.isPending)
  const activityError = activityResults.find((result) => result.isError)?.error
  const attention = activities.filter(needsAttention)
  const counts = countEachStatus(activities)

  function updateParams(change: (params: URLSearchParams) => void) {
    const params = new URLSearchParams(searchParams)
    change(params)
    setSearchParams(params, { replace: true })
  }

  function selectTab(label: string) {
    updateParams((params) => (label === ATTENTION_TAB ? params.set('tab', 'attention') : params.delete('tab')))
  }

  function selectClassroom(next: string) {
    updateParams((params) => (next ? params.set('classId', next) : params.delete('classId')))
  }

  const shown = tab === ATTENTION_TAB ? attention : activities

  return (
    <InstructorLayout
      title="활동 관리"
      subheader={
        <PageTabs
          label="활동 관리"
          selected={tab}
          onSelect={selectTab}
          tabs={[
            { label: LIST_TAB, available: true, count: activitiesReady ? activities.length : undefined },
            { label: ATTENTION_TAB, available: true, count: activitiesReady ? attention.length : undefined },
          ]}
        />
      }
    >
      {classroomsQuery.isLoading ? (
        <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
      ) : classroomsQuery.isError ? (
        <Notice tone="error">{describeError(classroomsQuery.error, '학급을 불러오지 못했습니다.')}</Notice>
      ) : classrooms.length === 0 ? (
        <Notice tone="info">
          아직 학급이 없어요.{' '}
          <Link to="/classrooms/new" className="font-semibold underline underline-offset-2">
            첫 학급 만들기
          </Link>
        </Notice>
      ) : (
        <div className="flex flex-col gap-4">
          <label className={fieldLabelClass}>
            학급
            <select
              value={classId ?? ''}
              onChange={(e) => selectClassroom(e.target.value)}
              className={`${selectClass} max-w-[246px]`}
            >
              <option value="">전체 학급</option>
              {classrooms.map((c) => (
                <option key={c.classId} value={c.classId}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>

          {activityError && (
            <Notice tone="error">
              {describeError(activityError, '활동을 불러오지 못했습니다.')} 불러온 학급의 활동만 보여요.
            </Notice>
          )}

          {tab === ATTENTION_TAB && activitiesReady && attention.length > 0 && (
            <Notice tone="warning">
              <span className="font-semibold">챙길 활동 {attention.length}건</span> · 일시 중지 {counts.PAUSED} · 복구
              필요 {counts.RECOVERY_NEEDED}. 아동 상세나 리포트에서 진행 상황을 확인하고, 필요하면 새 활동을 배정하세요.
            </Notice>
          )}

          {!activitiesReady ? (
            <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
          ) : shown.length === 0 ? (
            <p className="text-sm text-[var(--instructor-text-muted)]">
              {tab === ATTENTION_TAB ? '일시 중지되거나 복구가 필요한 활동이 없어요.' : '아직 배정한 활동이 없어요.'}
            </p>
          ) : (
            <ActivityManagementTable activities={shown} />
          )}

          {activitiesReady && activities.length > 0 && (
            <section aria-labelledby="distribution-heading" className={cn(cardClass, 'flex flex-col gap-3 p-5')}>
              <h2 id="distribution-heading" className="text-sm font-semibold">
                전체 활동 상태 분포
              </h2>
              <ul className="flex flex-col gap-2.5">
                {STATUS_ORDER.map((status) => (
                  <li key={status} className="grid grid-cols-[88px_minmax(0,1fr)_40px] items-center gap-3 text-sm">
                    <ActivityStatusBadge status={status} />
                    <span aria-hidden className="h-2 overflow-hidden rounded-full bg-[var(--instructor-surface-muted)]">
                      <span
                        className="block h-full rounded-full bg-[var(--instructor-primary)]"
                        style={{ width: `${(counts[status] / activities.length) * 100}%` }}
                      />
                    </span>
                    <span className="text-right">{counts[status]}</span>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </div>
      )}
    </InstructorLayout>
  )
}

/** 활동·아동, 유형, 상태, 배정·시작·완료 시각, 바로가기. 목표를 누르면 활동 리포트(활동 상세)로 간다. */
function ActivityManagementTable({ activities }: { activities: ManagedActivity[] }) {
  return (
    <div className={tableClass}>
      <table className="w-full">
        <thead>
          <tr className={tableHeadRowClass}>
            <th scope="col" className="px-2 py-2.5 font-normal">
              활동 · 아동
            </th>
            <th scope="col" className="px-2 py-2.5 font-normal">
              유형
            </th>
            <th scope="col" className="px-2 py-2.5 font-normal">
              상태
            </th>
            <th scope="col" className="px-2 py-2.5 font-normal">
              배정
            </th>
            <th scope="col" className="px-2 py-2.5 font-normal">
              시작
            </th>
            <th scope="col" className="px-2 py-2.5 font-normal">
              완료
            </th>
            <th scope="col" className="px-2 py-2.5 font-normal">
              바로가기
            </th>
          </tr>
        </thead>
        <tbody>
          {activities.map((activity) => {
            const title = activity.goalTitle ?? ACTIVITY_TYPE_LABELS[activity.activityType]
            const childPath = `/classrooms/${activity.classId}/children/${activity.childId}`
            return (
              <tr key={activity.activityId} className={tableRowClass}>
                <td className="px-2 py-3">
                  <Link to={`/activities/${activity.activityId}/report`} className="font-semibold hover:underline">
                    {title}
                  </Link>
                  <p className="text-xs text-[var(--instructor-text-muted)]">
                    {activity.childName} · {activity.className}
                  </p>
                </td>
                <td className="px-2 py-3 text-[var(--instructor-text-muted)]">
                  {ACTIVITY_TYPE_LABELS[activity.activityType]}
                </td>
                <td className="px-2 py-3">
                  <ActivityStatusBadge status={activity.status} />
                </td>
                <td className="px-2 py-3 text-[var(--instructor-text-muted)]">{formatDateTime(activity.assignedAt)}</td>
                <td className="px-2 py-3 text-[var(--instructor-text-muted)]">{formatDateTime(activity.startedAt)}</td>
                <td className="px-2 py-3 text-[var(--instructor-text-muted)]">{formatDateTime(activity.completedAt)}</td>
                <td className="px-2 py-3">
                  <div className="flex flex-wrap gap-x-3 gap-y-1 text-[var(--instructor-link)]">
                    <Link to={childPath} aria-label={`${activity.childName} 아동 상세`} className="hover:underline">
                      아동 보기
                    </Link>
                    {needsAttention(activity) && (
                      <Link
                        to={`/activities/new?classId=${activity.classId}&childId=${activity.childId}`}
                        aria-label={`${activity.childName}에게 새 활동 배정`}
                        className="hover:underline"
                      >
                        새 활동 배정
                      </Link>
                    )}
                  </div>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

export { ActivityManagementPage }
