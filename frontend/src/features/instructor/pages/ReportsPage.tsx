import { useEffect } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { listClassrooms } from '../api'
import { describeError } from '../errorMessage'
import { InstructorLayout } from '../layout/InstructorLayout'
import { Notice } from '../components/Feedback'
import { ClassroomReportList } from '../components/report/ClassroomReportList'
import { fieldLabelClass, selectClass } from '../components/styles'

/**
 * 리포트 목록 (시안 T-RPT-01). 학급을 고르면 그 학급 아동 전체의 완료 리포트를 보인다.
 * 고른 학급은 주소의 ?classId= 에 남긴다. 없으면 첫 학급을 고른다. 학급을 바꾸면 아동 필터는 비운다(다른 반 아동이라).
 */
function ReportsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const classroomsQuery = useQuery({ queryKey: ['classrooms'], queryFn: listClassrooms })
  const classrooms = classroomsQuery.data
  const requested = searchParams.get('classId')
  const classId = classrooms?.some((c) => c.classId === requested) ? requested : (classrooms?.[0]?.classId ?? null)

  useEffect(() => {
    if (classId && classId !== requested) {
      const next = new URLSearchParams(searchParams)
      next.set('classId', classId)
      setSearchParams(next, { replace: true })
    }
  }, [classId, requested, searchParams, setSearchParams])

  function selectClassroom(next: string) {
    const params = new URLSearchParams(searchParams)
    params.set('classId', next)
    params.delete('childId')
    setSearchParams(params, { replace: true })
  }

  return (
    <InstructorLayout title="리포트 목록">
      {classroomsQuery.isLoading ? (
        <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
      ) : classroomsQuery.isError ? (
        <Notice tone="error">{describeError(classroomsQuery.error, '학급을 불러오지 못했습니다.')}</Notice>
      ) : !classId ? (
        <p className="text-sm text-[var(--instructor-text-muted)]">학급을 만들면 리포트를 볼 수 있어요.</p>
      ) : (
        <div className="flex flex-col gap-4">
          <label className={fieldLabelClass}>
            학급
            <select value={classId} onChange={(e) => selectClassroom(e.target.value)} className={`${selectClass} max-w-[246px]`}>
              {classrooms?.map((c) => (
                <option key={c.classId} value={c.classId}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <ClassroomReportList key={classId} classId={classId} />
        </div>
      )}
    </InstructorLayout>
  )
}

export { ReportsPage }
