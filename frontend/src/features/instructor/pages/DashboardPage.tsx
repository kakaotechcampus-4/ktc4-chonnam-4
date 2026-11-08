import { Link } from 'react-router-dom'
import { useQueries, useQuery } from '@tanstack/react-query'
import { listChildren, listClassrooms, listScenarioApprovals } from '../api'
import { describeError } from '../errorMessage'
import { InstructorLayout } from '../layout/InstructorLayout'
import { ActivityStatusBadge } from '../components/ActivityStatusBadge'
import { Notice } from '../components/Feedback'
import { StatCard } from '../components/StatCard'
import { cardClass, textButtonClass } from '../components/styles'
import { useActivitiesOfClassrooms } from '../hooks/classroomQueries'
import { countByStatus, EVENT_LABELS, recentEvents } from '../lib/activitySummary'
import { ACTIVITY_TYPE_LABELS } from '../lib/activityTypes'
import { formatDate, formatDateTime } from '../lib/dateTime'
import { cn } from '@/lib/utils'

const APPROVAL_PREVIEW = 4
const TIMELINE_SIZE = 6

/**
 * 강사 대시보드 (시안 T-HOME-01). 담당 학급·아동 수, 활동 상태 합계, 승인 대기 시나리오, 최근 학습 타임라인,
 * 챙길 활동(일시 중지·복구 필요), 학급 요약을 한 화면에 모은다.
 * 활동은 학급마다 학급 활동 목록(API-ACT-05)을 한 번씩 불러 합친다. 서버가 주지 않는 "장기 미완료"·"이번 주 신규 아동"은 넣지 않는다.
 */
function DashboardPage() {
  const classroomsQuery = useQuery({ queryKey: ['classrooms'], queryFn: listClassrooms })
  const classrooms = classroomsQuery.data ?? []
  const approvalsQuery = useQuery({ queryKey: ['scenario-approvals'], queryFn: listScenarioApprovals })
  const childCounts = useQueries({
    queries: classrooms.map((classroom) => ({
      queryKey: ['classrooms', classroom.classId, 'children'],
      queryFn: () => listChildren(classroom.classId),
    })),
    combine: (results) => results.map((result) => result.data?.length),
  })
  const activityResults = useActivitiesOfClassrooms(classroomsQuery.data)

  const classNames = new Map(classrooms.map((c) => [c.classId, c.name]))
  const activities = activityResults.flatMap((result, index) =>
    (result.data ?? []).map((activity) => ({ ...activity, classId: classrooms[index].classId })),
  )
  const activitiesReady = activityResults.every((result) => result.isSuccess)
  const activityError = activityResults.find((result) => result.isError)?.error
  const counts = activitiesReady ? countByStatus(activities) : null
  const totalChildren = childCounts.every((count) => count !== undefined)
    ? childCounts.reduce<number>((sum, count) => sum + (count ?? 0), 0)
    : null
  const attention = activities.filter((a) => a.status === 'PAUSED' || a.status === 'RECOVERY_NEEDED')
  const approvals = approvalsQuery.data ?? []

  return (
    <InstructorLayout title="대시보드">
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
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <StatCard label="담당 학급" value={String(classrooms.length)} note={classrooms.map((c) => c.name).join(' · ')} />
            <StatCard label="등록 아동" value={totalChildren === null ? '…' : `${totalChildren}명`} />
            <StatCard
              label="활동 현황"
              value={counts ? `진행 중 ${counts.inProgress} · 완료 ${counts.completed}` : '…'}
              note={counts ? `복구 필요 ${counts.needsAttention} · 시작 전 ${counts.notStarted}` : undefined}
            />
            <StatCard label="승인 대기" value={approvalsQuery.data ? `${approvals.length}건` : '…'} />
          </div>

          {activityError && <Notice tone="error">{describeError(activityError, '활동을 불러오지 못했습니다.')}</Notice>}

          <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)]">
            <div className="flex flex-col gap-4">
              <section aria-labelledby="approvals-heading" className={cn(cardClass, 'flex flex-col gap-3 p-5')}>
                <div className="flex items-center justify-between">
                  <h2 id="approvals-heading" className="text-sm font-semibold">
                    승인 대기 시나리오
                  </h2>
                  <Link to="/content" className={textButtonClass}>
                    전체 보기
                  </Link>
                </div>
                {approvalsQuery.isError ? (
                  <Notice tone="error">{describeError(approvalsQuery.error, '승인 대기를 불러오지 못했습니다.')}</Notice>
                ) : approvals.length === 0 ? (
                  <p className="text-sm text-[var(--instructor-text-muted)]">
                    {approvalsQuery.isLoading ? '불러오는 중...' : '승인을 기다리는 시나리오가 없어요.'}
                  </p>
                ) : (
                  <ul className="flex flex-col gap-2 text-sm">
                    {approvals.slice(0, APPROVAL_PREVIEW).map((item) => (
                      <li key={item.activityId} className="flex items-center justify-between gap-3">
                        <Link to={`/activities/${item.activityId}/scenario`} className="hover:underline">
                          {item.className} · {item.childName} · {item.goalTitle}
                        </Link>
                        <span className="shrink-0 text-xs text-[var(--instructor-text-subtle)]">
                          {item.scenarioLevel} · {formatDate(item.waitingSince)}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </section>

              <section aria-labelledby="timeline-heading" className={cn(cardClass, 'flex flex-col gap-3 p-5')}>
                <h2 id="timeline-heading" className="text-sm font-semibold">
                  최근 학습 타임라인
                </h2>
                {!activitiesReady ? (
                  <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
                ) : activities.length === 0 ? (
                  <p className="text-sm text-[var(--instructor-text-muted)]">아직 배정한 활동이 없어요.</p>
                ) : (
                  <ul className="flex flex-col gap-2 text-sm">
                    {recentEvents(activities, TIMELINE_SIZE).map(({ activity, kind, at }) => (
                      <li key={activity.activityId} className="flex items-center justify-between gap-3">
                        <Link to={`/activities/${activity.activityId}/report`} className="hover:underline">
                          {activity.childName} · {activity.goalTitle ?? ACTIVITY_TYPE_LABELS[activity.activityType]}{' '}
                          {EVENT_LABELS[kind]}
                        </Link>
                        <span className="shrink-0 text-xs text-[var(--instructor-text-subtle)]">{formatDateTime(at)}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            </div>

            <div className="flex flex-col gap-4">
              <section aria-labelledby="attention-heading" className={cn(cardClass, 'flex flex-col gap-3 p-5')}>
                <h2 id="attention-heading" className="text-sm font-semibold">
                  챙길 활동
                </h2>
                {attention.length === 0 ? (
                  <p className="text-sm text-[var(--instructor-text-muted)]">
                    {activitiesReady ? '일시 중지되거나 복구가 필요한 활동이 없어요.' : '불러오는 중...'}
                  </p>
                ) : (
                  <ul className="flex flex-col gap-2 text-sm">
                    {attention.map((activity) => (
                      <li key={activity.activityId} className="flex items-center justify-between gap-3">
                        <Link
                          to={`/classrooms/${activity.classId}/children/${activity.childId}`}
                          className="hover:underline"
                        >
                          {activity.childName} · {activity.goalTitle ?? ACTIVITY_TYPE_LABELS[activity.activityType]}
                        </Link>
                        <ActivityStatusBadge status={activity.status} />
                      </li>
                    ))}
                  </ul>
                )}
              </section>

              <section aria-labelledby="classes-heading" className={cn(cardClass, 'flex flex-col gap-3 p-5')}>
                <h2 id="classes-heading" className="text-sm font-semibold">
                  담당 학급 요약
                </h2>
                <ul className="flex flex-col gap-2 text-sm">
                  {classrooms.map((classroom, index) => {
                    const ofClass = activityResults[index]?.data
                    const classCounts = ofClass ? countByStatus(ofClass) : null
                    return (
                      <li key={classroom.classId} className="flex items-center justify-between gap-3">
                        <Link to={`/classrooms/${classroom.classId}`} className="hover:underline">
                          {classNames.get(classroom.classId)}
                        </Link>
                        <span className="text-xs text-[var(--instructor-text-subtle)]">
                          아동 {childCounts[index] ?? '…'} · 활동 완료{' '}
                          {classCounts ? `${classCounts.completed}/${classCounts.total}` : '…'}
                        </span>
                      </li>
                    )
                  })}
                </ul>
              </section>
            </div>
          </div>
        </div>
      )}
    </InstructorLayout>
  )
}

export { DashboardPage }
