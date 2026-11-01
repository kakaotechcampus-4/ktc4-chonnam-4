import { useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { listChildren, listClassrooms } from '../api'
import { InstructorLayout } from '../layout/InstructorLayout'
import { ChildRecommendations } from '../components/report/ChildLatestLearning'
import { cardClass, fieldLabelClass, selectClass } from '../components/styles'
import { Notice } from '../components/Feedback'
import { cn } from '@/lib/utils'

/**
 * 추천·다음목표. 학급을 고르면 아동마다 가장 최근 역할극의 다음 난이도와 다음 수업 제안을 보인다.
 * 제안을 고르면 활동 만들기(역할극)로 이어지고, 배정은 그 화면에서 강사가 한다(자동 배정 금지).
 * 학급 단위 API 가 없어 아동마다 계약 API 를 부른다. 학급 인원이 적다고 보고 이렇게 둔다.
 */
function RecommendationsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const classroomsQuery = useQuery({ queryKey: ['classrooms'], queryFn: listClassrooms })
  const classrooms = classroomsQuery.data ?? []
  const requested = searchParams.get('classId')
  const classId = classrooms.some((c) => c.classId === requested) ? requested : (classrooms[0]?.classId ?? null)
  const childrenQuery = useQuery({
    queryKey: ['classrooms', classId, 'children'],
    queryFn: () => listChildren(classId!),
    enabled: !!classId,
  })
  const children = (childrenQuery.data ?? []).filter((child) => child.status !== 'REMOVED')

  return (
    <InstructorLayout title="추천·다음목표">
      <div className="flex max-w-4xl flex-col gap-5">
        {classroomsQuery.isLoading ? (
          <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
        ) : classroomsQuery.isError ? (
          <Notice tone="error">{classroomsQuery.error.message}</Notice>
        ) : classrooms.length === 0 ? (
          <p className="text-sm text-[var(--instructor-text-muted)]">학급을 먼저 만들어 주세요.</p>
        ) : (
          <>
            <label className={cn(fieldLabelClass, 'max-w-xs')}>
              학급
              <select
                value={classId ?? ''}
                onChange={(e) => setSearchParams({ classId: e.target.value }, { replace: true })}
                className={selectClass}
              >
                {classrooms.map((classroom) => (
                  <option key={classroom.classId} value={classroom.classId}>
                    {classroom.name}
                  </option>
                ))}
              </select>
            </label>
            <p className="text-sm text-[var(--instructor-text-muted)]">
              역할극을 마친 아동마다 다음 난이도와 제안이 보여요. 제안을 고르면 목표가 채워진 활동 만들기로 이어지고, 배정은 선생님이 해요.
            </p>
            {childrenQuery.isLoading ? (
              <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
            ) : children.length === 0 ? (
              <p className="text-sm text-[var(--instructor-text-muted)]">이 학급에 아동이 없어요.</p>
            ) : (
              children.map((child) => (
                <div key={child.childId} className={cn(cardClass, 'p-5')}>
                  <ChildRecommendations child={child} />
                </div>
              ))
            )}
          </>
        )}
      </div>
    </InstructorLayout>
  )
}

export { RecommendationsPage }
