import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getChild, getClassroom, listChildActivities, listLearningGoals } from '../api'
import { InstructorLayout } from '../layout/InstructorLayout'
import { ActivityStatusBadge } from '../components/ActivityStatusBadge'
import { PageTabs } from '../components/PageTabs'
import { cardClass, errorTextClass, primaryButtonClass } from '../components/styles'
import { formatDateTime } from '../lib/dateTime'
import { cn } from '@/lib/utils'

const HISTORY_TAB = '활동 이력'

/**
 * 아동 상세 (시안 T-LRN-03~05). S1 은 활동 이력 탭만 연다(ADR 2026-10-03 D5).
 * 활동을 누르면 활동 리포트로 간다. 개요·학습 상태와 접근 코드·QR 은 후속 범위라 탭만 보인다.
 */
function ChildDetailPage() {
  const { classId, childId } = useParams<{ classId: string; childId: string }>()

  const childQuery = useQuery({
    queryKey: ['children', childId],
    queryFn: () => getChild(childId!),
    enabled: !!childId,
  })

  const classroomQuery = useQuery({
    queryKey: ['classrooms', classId],
    queryFn: () => getClassroom(classId!),
    enabled: !!classId,
  })

  const activitiesQuery = useQuery({
    queryKey: ['children', childId, 'activities'],
    queryFn: () => listChildActivities(childId!),
    enabled: !!childId,
  })

  // 활동에는 goalId 만 있어 목표 목록을 한 번 받아 제목을 붙인다.
  const goalsQuery = useQuery({
    queryKey: ['children', childId, 'learning-goals'],
    queryFn: () => listLearningGoals(childId!),
    enabled: !!childId,
  })

  const child = childQuery.data
  const classroom = classroomQuery.data
  const activities = activitiesQuery.data
  const goalTitles = new Map(goalsQuery.data?.map((goal) => [goal.goalId, goal.title]))
  // 주소의 학급과 아동의 실제 학급이 다르면 잘못된 주소다. 다른 학급 이름을 붙여 보이지 않는다.
  const isWrongClassroom = child !== undefined && child.classId !== classId

  const title = child
    ? `아동 상세 · ${child.displayName}${classroom && !isWrongClassroom ? ` (${classroom.name})` : ''}`
    : '아동 상세'

  return (
    <InstructorLayout
      title={title}
      actions={
        child &&
        !isWrongClassroom && (
          <Link
            to={`/activities/new?classId=${child.classId}&childId=${child.childId}`}
            className={cn(primaryButtonClass, 'h-9 px-4')}
          >
            활동 만들기
          </Link>
        )
      }
      subheader={
        <PageTabs
          label="아동 상세"
          selected={HISTORY_TAB}
          tabs={[
            { label: '개요 · 학습 상태' },
            { label: '접근 코드 · QR' },
            { label: HISTORY_TAB, available: true },
          ]}
        />
      }
    >
      <Link
        to={`/classrooms/${classId}?tab=children`}
        className="mb-4 inline-block text-sm text-[var(--instructor-text-muted)] hover:underline"
      >
        ← 아동 목록
      </Link>

      {childQuery.isLoading ? (
        <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
      ) : childQuery.isError || isWrongClassroom ? (
        <p className={errorTextClass}>
          {childQuery.error instanceof Error ? childQuery.error.message : '아동을 찾을 수 없습니다.'}
        </p>
      ) : (
        <section role="tabpanel" aria-label={HISTORY_TAB}>
          {activitiesQuery.isLoading ? (
            <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
          ) : activitiesQuery.isError ? (
            <p className={errorTextClass}>
              {activitiesQuery.error instanceof Error
                ? activitiesQuery.error.message
                : '활동 이력을 불러오지 못했습니다.'}
            </p>
          ) : activities?.length === 0 ? (
            <p className="text-sm text-[var(--instructor-text-muted)]">아직 배정된 활동이 없습니다.</p>
          ) : (
            <div className={cn(cardClass, 'overflow-hidden')}>
              <table className="w-full text-sm">
                <thead className="bg-[var(--instructor-surface-muted)] text-xs text-[var(--instructor-text-muted)]">
                  <tr>
                    <th scope="col" className="px-4 py-3 text-left font-semibold">
                      학습 목표
                    </th>
                    <th scope="col" className="px-4 py-3 text-left font-semibold">
                      상태
                    </th>
                    <th scope="col" className="px-4 py-3 text-left font-semibold">
                      배정
                    </th>
                    <th scope="col" className="px-4 py-3 text-left font-semibold">
                      완료
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {activities?.map((activity) => (
                    <tr key={activity.activityId} className="border-t border-[var(--instructor-border)]">
                      <td className="px-4 py-3.5">
                        <Link
                          to={`/activities/${activity.activityId}/report`}
                          className="font-semibold hover:underline"
                        >
                          {goalTitles.get(activity.goalId) ?? '학습 목표'}
                        </Link>
                      </td>
                      <td className="px-4 py-3.5">
                        <ActivityStatusBadge status={activity.status} />
                      </td>
                      <td className="px-4 py-3.5 text-[var(--instructor-text-muted)]">
                        {formatDateTime(activity.assignedAt)}
                      </td>
                      <td className="px-4 py-3.5 text-[var(--instructor-text-muted)]">
                        {formatDateTime(activity.completedAt)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </InstructorLayout>
  )
}

export { ChildDetailPage }
