import { useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createChild, getClassroom, listChildren } from '../api'
import { Button } from '@/components/ui/button'
import { InstructorLayout } from '../layout/InstructorLayout'

function ClassroomDetailPage() {
  const { classId } = useParams<{ classId: string }>()
  const [displayName, setDisplayName] = useState('')
  const queryClient = useQueryClient()

  const {
    data: classroom,
    isLoading: isClassroomLoading,
    isError: isClassroomError,
    error: classroomError,
  } = useQuery({
    queryKey: ['classrooms', classId],
    queryFn: () => getClassroom(classId!),
    enabled: !!classId,
  })

  const {
    data: children,
    isLoading: isChildrenLoading,
    isError: isChildrenError,
    error: childrenError,
  } = useQuery({
    queryKey: ['classrooms', classId, 'children'],
    queryFn: () => listChildren(classId!),
    enabled: !!classId,
  })

  const createChildMutation = useMutation({
    mutationFn: (name: string) => createChild(classId!, name),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['classrooms', classId, 'children'] })
      setDisplayName('')
    },
  })

  const trimmedDisplayName = displayName.trim()
  // 요청 중 재클릭·Enter 로 같은 아동이 여러 번 등록되지 않게 막는다. 서버 중복 검증을 대신하지는 않는다.
  const isSubmitDisabled = createChildMutation.isPending || trimmedDisplayName === ''
  // isPending 은 다음 렌더부터 반영되어, 같은 순간 들어온 두 번째 제출은 통과할 수 있다. ref 로 즉시 잠근다.
  const isSubmittingRef = useRef(false)

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (isSubmitDisabled || isSubmittingRef.current) {
      return
    }
    isSubmittingRef.current = true
    createChildMutation.mutate(trimmedDisplayName, {
      onSettled: () => {
        isSubmittingRef.current = false
      },
    })
  }

  return (
    <InstructorLayout>
      <Link to="/classrooms" className="mb-4 inline-block text-sm underline">
        ← 학급 목록
      </Link>

      {isClassroomLoading ? (
        <p>불러오는 중...</p>
      ) : isClassroomError ? (
        <p className="text-sm text-red-600">
          {classroomError instanceof Error
            ? classroomError.message
            : '학급 정보를 불러오지 못했습니다.'}
        </p>
      ) : (
        <h1 className="mb-4 text-lg font-semibold">
          {classroom?.name} ({classroom?.status})
        </h1>
      )}

      <form onSubmit={handleSubmit} className="mb-4 flex gap-2">
        <label htmlFor="child-display-name" className="sr-only">
          아동 이름
        </label>
        <input
          id="child-display-name"
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
          placeholder="아동 이름"
          className="rounded-lg border border-border px-2.5 py-1 text-sm"
        />
        <Button type="submit" disabled={isSubmitDisabled}>
          {createChildMutation.isPending ? '등록 중...' : '아동 등록'}
        </Button>
      </form>

      {createChildMutation.isError && (
        <p className="mb-4 text-sm text-red-600">
          {createChildMutation.error instanceof Error
            ? createChildMutation.error.message
            : '아동 등록에 실패했습니다.'}
        </p>
      )}

      {isChildrenLoading ? (
        <p>불러오는 중...</p>
      ) : isChildrenError ? (
        <p className="text-sm text-red-600">
          {childrenError instanceof Error ? childrenError.message : '아동 목록을 불러오지 못했습니다.'}
        </p>
      ) : (
        <ul className="flex flex-col gap-2">
          {children?.map((child) => (
            <li key={child.childId}>
              {child.displayName} ({child.status})
            </li>
          ))}
        </ul>
      )}
    </InstructorLayout>
  )
}

export { ClassroomDetailPage }
