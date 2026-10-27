import { useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createClassroom, getCurrentUser } from '../api'
import { describeError } from '../errorMessage'
import { InstructorLayout } from '../layout/InstructorLayout'
import { cn } from '@/lib/utils'
import {
  cardClass,
  fieldLabelClass,
  helperTextClass,
  hitAreaClass,
  inputClass,
  outlineButtonClass,
  primaryButtonClass,
  selectClass,
} from '../components/styles'
import { Notice } from '../components/Feedback'
import { ButtonLabel } from '../components/ButtonLabel'

/**
 * 학급 생성 화면 (시안 T-CLS-02).
 * 배치는 시안의 두 칸(왼쪽 기본 정보 1.4 : 오른쪽 1, 화면 아래까지)을 따르고, 버튼은 시안처럼 오른쪽 칸 아래에 둔다.
 * API 가 받는 것은 학급명뿐이다. 운영 기관(내 계정의 소속)과 담당 강사(나)는 있는 값을 보여 주기만 하고,
 * 데이터 모델에 없거나(대상 연령·메모) 후속 범위인 것(공동 강사, 보관 처리, 아동 접근 방식)은 시안 자리에 비활성으로 둔다.
 */
function ClassroomCreatePage() {
  const [name, setName] = useState('')
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  // 사이드바(InstructorLayout)와 같은 키라 이미 받은 값을 그대로 쓴다.
  const { data: user } = useQuery({ queryKey: ['auth', 'session'], queryFn: getCurrentUser, staleTime: Infinity })

  // 목록 캐시는 화면을 떠났어도 갱신한다(학급은 이미 만들어졌다).
  const createMutation = useMutation({
    mutationFn: createClassroom,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['classrooms'] })
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
      // 상세 이동은 mutate 쪽 콜백에 둔다. 이 콜백은 화면이 사라지면 실행되지 않아, 저장 중 취소하고 다른 화면(또는 새 생성 화면)으로
      // 옮긴 뒤 이전 응답이 도착해도 강제로 이동시키지 않는다. useMutation 쪽 onSuccess 는 화면이 사라져도 실행된다.
      onSuccess: (classroom) => {
        navigate(`/classrooms/${classroom.classId}`, { replace: true })
      },
      onSettled: () => {
        isSubmittingRef.current = false
      },
    })
  }

  return (
    <InstructorLayout title="학급 생성" fill>
      <form onSubmit={handleSubmit} className="grid grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)] gap-4">
        <section className={cn(cardClass, 'flex flex-col gap-4 px-6 py-6')} aria-labelledby="basic-heading">
          <h2 id="basic-heading" className="text-base font-semibold">
            기본 정보
          </h2>
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

          <div className="grid grid-cols-2 gap-4">
            <label className={fieldLabelClass}>
              대상 연령
              <select disabled defaultValue="" className={selectClass}>
                <option value="">선택</option>
              </select>
            </label>
            <div className={fieldLabelClass}>
              <span id="org-label">운영 기관</span>
              <span
                aria-labelledby="org-label"
                className="flex h-[42px] items-center rounded-[var(--instructor-radius-control)] border border-[var(--instructor-input-border)] bg-[var(--instructor-disabled-bg)] px-3 text-base"
              >
                {user?.orgName ?? '—'}
              </span>
            </div>
          </div>

          <div className={fieldLabelClass}>
            <span id="teacher-label">담당 강사</span>
            <div aria-labelledby="teacher-label" className="flex flex-wrap items-center gap-2">
              <span className="inline-flex h-[34px] items-center rounded-full bg-[var(--instructor-surface-muted)] px-3 text-sm">
                {user?.name}
              </span>
              <button
                type="button"
                disabled
                className={cn('relative inline-flex h-[34px] items-center rounded-full border border-dashed border-[var(--instructor-border-strong)] px-3 text-sm text-[var(--instructor-text-disabled)] disabled:cursor-not-allowed', hitAreaClass)}
              >
                + 강사 추가
              </button>
            </div>
          </div>

          <label className={fieldLabelClass}>
            메모
            <textarea disabled rows={3} className={cn(inputClass, 'h-[90px] resize-none py-3')} />
          </label>

          <p className={helperTextClass}>대상 연령, 강사 추가, 메모는 준비 중이에요.</p>

          {createMutation.isError && (
            <Notice tone="error" role="alert">
              {describeError(createMutation.error, '학급 생성에 실패했습니다.')}
            </Notice>
          )}
        </section>

        <div className="flex flex-col gap-4">
          <section className={cn(cardClass, 'flex flex-col gap-4 px-6 py-6')} aria-labelledby="archive-heading">
            <h2 id="archive-heading" className="text-base font-semibold">
              보관 처리
            </h2>
            <label className="flex items-start gap-3 text-sm text-[var(--instructor-text-muted)]">
              <input type="checkbox" disabled />
              <span>이 학급을 보관 처리합니다. 새 활동 배정은 중단되고 기존 기록은 조회만 가능합니다. 준비 중이에요.</span>
            </label>
          </section>

          <section className={cn(cardClass, 'flex flex-1 flex-col gap-4 px-6 py-6')} aria-labelledby="access-heading">
            <h2 id="access-heading" className="text-base font-semibold">
              아동 접근 기본값
            </h2>
            {/* 지금은 접근 코드만 있어 그 선택지가 고정이다. QR 은 후속 범위라 비활성으로 둔다. */}
            <fieldset disabled className="flex flex-col gap-3 text-sm">
              <legend className="sr-only">아동 접근 기본값</legend>
              {(['접근 코드', 'QR 코드', '둘 다 허용'] as const).map((option) => (
                <label key={option} className="flex items-center gap-3">
                  <input type="radio" name="access-default" defaultChecked={option === '접근 코드'} />
                  {option}
                </label>
              ))}
            </fieldset>
            <p className={helperTextClass}>지금은 접근 코드로만 입장해요. QR 코드는 준비 중이에요.</p>
          </section>

          <div className="flex gap-2.5">
            <Link to="/classrooms" className={cn(outlineButtonClass, 'w-28')}>
              취소
            </Link>
            <button type="submit" disabled={isSubmitDisabled} className={cn(primaryButtonClass, 'flex-1')}>
              <ButtonLabel pending={createMutation.isPending} label="저장" pendingLabel="저장 중..." />
            </button>
          </div>
        </div>
      </form>
    </InstructorLayout>
  )
}

export { ClassroomCreatePage }
