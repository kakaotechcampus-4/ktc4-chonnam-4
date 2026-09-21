import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createClassroom, listClassrooms } from '@/lib/api'
import { Button } from '@/components/ui/button'

export default function ClassroomListPage() {
  const [name, setName] = useState('')
  const queryClient = useQueryClient()

  const { data: classrooms, isLoading } = useQuery({
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
    <div className="p-6">
      <form onSubmit={handleSubmit} className="mb-4 flex gap-2">
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="학급 이름"
          className="rounded-lg border border-border px-2.5 py-1 text-sm"
        />
        <Button type="submit">학급 생성</Button>
      </form>

      {isLoading ? (
        <p>불러오는 중...</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {classrooms?.map((classroom) => (
            <li key={classroom.classId}>
              <Link to={`classrooms/${classroom.classId}`}>
                {classroom.name} ({classroom.status})
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
