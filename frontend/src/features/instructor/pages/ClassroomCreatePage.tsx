import { useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createClassroom } from '../api'
import { describeError } from '../errorMessage'
import { InstructorLayout } from '../layout/InstructorLayout'
import { cn } from '@/lib/utils'
import {
  cardClass,
  errorTextClass,
  fieldLabelClass,
  inputClass,
  outlineButtonClass,
  primaryButtonClass,
} from '../components/styles'

/**
 * 학급 생성 화면 (시안 T-CLS-02).
 * 시안의 대상 연령·운영 기관·담당 강사·메모·보관 처리·아동 접근 기본값은 데이터 모델에 없거나(연령·메모)
 * 후속 범위(공동 강사, 학급 수정)라, API 가 받는 학급명만 입력받는다.
 */
function ClassroomCreatePage() {
  const [name, setName] = useState('')
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const createMutation = useMutation({
    mutationFn: createClassroom,
    onSuccess: (classroom) => {
      queryClient.invalidateQueries({ queryKey: ['classrooms'] })
      navigate(`/classrooms/${classroom.classId}`, { replace: true })
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
    <InstructorLayout title="학급 생성">
      <form onSubmit={handleSubmit} className="flex max-w-2xl flex-col gap-4">
        <section className={cn(cardClass, 'flex flex-col gap-5 p-6')}>
          <h2 className="text-base font-bold">기본 정보</h2>
          <label htmlFor="classroom-name" className={fieldLabelClass}>
            <span>
              학급명 <span className="text-[var(--instructor-danger-fg)]">*</span>
            </span>
            <input
              id="classroom-name"
              required
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="예: 햇살반"
              className={inputClass}
            />
          </label>

          {createMutation.isError && (
            <p role="alert" className={errorTextClass}>
              {describeError(createMutation.error, '학급 생성에 실패했습니다.')}
            </p>
          )}
        </section>

        <div className="flex justify-end gap-2">
          <Link to="/classrooms" className={outlineButtonClass}>
            취소
          </Link>
          <button type="submit" disabled={isSubmitDisabled} className={cn(primaryButtonClass, 'min-w-32')}>
            {createMutation.isPending ? '저장 중...' : '저장'}
          </button>
        </div>
      </form>
    </InstructorLayout>
  )
}

export { ClassroomCreatePage }
