import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { deleteChild, getChild, getClassroom, listChildActivities, listLearningGoals } from '../api'
import { describeError } from '../errorMessage'
import { InstructorLayout } from '../layout/InstructorLayout'
import { ActivityStatusBadge } from '../components/ActivityStatusBadge'
import { DeleteConfirmDialog } from '../components/DeleteConfirmDialog'
import { PageTabs } from '../components/PageTabs'
import { cardClass, dangerOutlineButtonClass, errorTextClass, primaryButtonClass } from '../components/styles'
import { formatDateTime } from '../lib/dateTime'
import { cn } from '@/lib/utils'

const HISTORY_TAB = '활동 이력'

/**
 * 아동 상세 (시안 T-LRN-03~05). S1 은 활동 이력 탭만 연다(ADR 2026-10-03 D5).
 * 활동을 누르면 활동 리포트로 간다. 개요·학습 상태와 접근 코드·QR 은 후속 범위라 탭만 보인다.
 * 아동 삭제는 기록과 함께 영구 삭제라, 확인 창에서 함께 지워지는 활동 수를 알린다(ADR 2026-10-04 D3·D4).
 */
function ChildDetailPage() {
  const { classId, childId } = useParams<{ classId: string; childId: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [isDeleteOpen, setIsDeleteOpen] = useState(false)

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

  const deleteMutation = useMutation({
    mutationFn: () => deleteChild(childId!),
    onSuccess: () => {
      // 지운 아동의 화면을 먼저 떠난 뒤 목록을 새로 받는다. 남은 캐시가 404 를 다시 부르지 않게 지운다.
      navigate(`/classrooms/${classId}?tab=children`, { replace: true })
      queryClient.removeQueries({ queryKey: ['children', childId] })
      queryClient.invalidateQueries({ queryKey: ['classrooms', classId, 'children'] })
    },
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
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => setIsDeleteOpen(true)}
              className={cn(dangerOutlineButtonClass, 'h-9 px-4')}
            >
              아동 삭제
            </button>
            <Link
              to={`/activities/new?classId=${child.classId}&childId=${child.childId}`}
              className={cn(primaryButtonClass, 'h-9 px-4')}
            >
              활동 만들기
            </Link>
          </div>
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

      {child && (
        <DeleteConfirmDialog
          open={isDeleteOpen}
          onOpenChange={(open) => {
            setIsDeleteOpen(open)
            if (!open) deleteMutation.reset()
          }}
          title={`${child.displayName} 아동을 삭제할까요?`}
          description={
            <>
              <p>{describeRecords(activities)}</p>
              <p>삭제하면 되돌릴 수 없어요.</p>
            </>
          }
          isPending={deleteMutation.isPending}
          error={deleteMutation.isError ? describeError(deleteMutation.error, '아동을 삭제하지 못했습니다.') : null}
          onConfirm={() => deleteMutation.mutate()}
        />
      )}
    </InstructorLayout>
  )
}

/** 확인 창에 보일 "함께 지워지는 기록" 문구. 목록을 아직 못 받았으면 개수 없이 알린다. */
function describeRecords(activities: { status: string }[] | undefined): string {
  const others = '학습 목표, 퀴즈 응답·결과, 입장 코드가 함께 영구 삭제돼요.'
  if (activities === undefined) return `이 아동의 활동과 ${others}`
  if (activities.length === 0) return `배정된 활동은 없어요. ${others}`
  const completed = activities.filter((activity) => activity.status === 'COMPLETED').length
  const inProgress = activities.filter((activity) => activity.status === 'IN_PROGRESS').length
  return `활동 ${activities.length}개(완료 ${completed}, 진행 중 ${inProgress})와 ${others}`
}

export { ChildDetailPage }
