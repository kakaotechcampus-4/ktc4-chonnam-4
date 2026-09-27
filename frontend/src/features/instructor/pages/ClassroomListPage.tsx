import { useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createClassroom, listClassrooms } from '../api'
import { Button } from '@/components/ui/button'
import { InstructorLayout } from '../layout/InstructorLayout'

function ClassroomListPage() {
  const [name, setName] = useState('')
  const queryClient = useQueryClient()

  const {
    data: classrooms,
    isLoading,
    isError: isListError,
    error: listError,
  } = useQuery({
    queryKey: ['classrooms'],
    queryFn: listClassrooms,
  })

  const createMutation = useMutation({
    mutationFn: createClassroom,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['classrooms'] })
      setName('')
    },
  })

  const trimmedName = name.trim()
  // 요청 중 재클릭·Enter 로 같은 학급이 여러 번 생성되지 않게 막는다. 서버 중복 검증을 대신하지는 않는다.
  const isSubmitDisabled = createMutation.isPending || trimmedName === ''
  // isPending 은 다음 렌더부터 반영되어, 같은 순간 들어온 두 번째 제출은 통과할 수 있다. ref 로 즉시 잠근다.
  const isSubmittingRef = useRef(false)

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (isSubmitDisabled || isSubmittingRef.current) {
      return
    }
    isSubmittingRef.current = true
    createMutation.mutate(trimmedName, {
      onSettled: () => {
        isSubmittingRef.current = false
      },
    })
  }

  return (
    <InstructorLayout>
      <form onSubmit={handleSubmit} className="mb-4 flex gap-2">
        <label htmlFor="classroom-name" className="sr-only">
          학급 이름
        </label>
        <input
          id="classroom-name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="학급 이름"
          className="rounded-lg border border-border px-2.5 py-1 text-sm"
        />
        <Button type="submit" disabled={isSubmitDisabled}>
          {createMutation.isPending ? '생성 중...' : '학급 생성'}
        </Button>
      </form>

      {createMutation.isError && (
        <p className="mb-4 text-sm text-red-600">
          {createMutation.error instanceof Error
            ? createMutation.error.message
            : '학급 생성에 실패했습니다.'}
        </p>
      )}

      {isLoading ? (
        <p>불러오는 중...</p>
      ) : isListError ? (
        <p className="text-sm text-red-600">
          {listError instanceof Error ? listError.message : '학급 목록을 불러오지 못했습니다.'}
        </p>
      ) : (
        <ul className="flex flex-col gap-2">
          {classrooms?.map((classroom) => (
            <li key={classroom.classId}>
              <Link to={`/classrooms/${classroom.classId}`}>
                {classroom.name} ({classroom.status})
              </Link>
            </li>
          ))}
        </ul>
      )}
    </InstructorLayout>
  )
}

export { ClassroomListPage }
