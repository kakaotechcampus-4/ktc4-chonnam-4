import { useState } from 'react'
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

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (name !== '') {
      createMutation.mutate(name)
    }
  }

  return (
    <InstructorLayout>
      <form onSubmit={handleSubmit} className="mb-4 flex gap-2">
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="학급 이름"
          className="rounded-lg border border-border px-2.5 py-1 text-sm"
        />
        <Button type="submit">학급 생성</Button>
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
