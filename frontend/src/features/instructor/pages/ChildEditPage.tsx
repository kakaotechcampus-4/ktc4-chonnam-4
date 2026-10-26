import { useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getChild, getClassroom, updateChild, type Child } from '../api'
import { describeError } from '../errorMessage'
import { InstructorLayout } from '../layout/InstructorLayout'
import { cn } from '@/lib/utils'
import {
  cardClass,
  dangerOutlineButtonClass,
  fieldLabelClass,
  helperTextClass,
  inputClass,
  outlineButtonClass,
  primaryButtonClass,
} from '../components/styles'
import { Notice } from '../components/Feedback'
import { ButtonLabel } from '../components/ButtonLabel'

const TITLE = '아동 수정'

/**
 * 아동 정보 수정 (시안 T-LRN-06). API 가 고치는 것은 표시 이름뿐이다.
 * 학급은 지금 학급을 보여 주기만 하고(학급 이동은 진행 중 활동과 학급이 어긋나 열지 않는다), 연령은 실명만 받는 원칙과 맞지 않아,
 * 접근 방식은 접근 코드뿐이라 비활성으로 둔다. 오른쪽 "학급에서 제외(기록 보관)"는 삭제가 기록과 함께 영구 삭제로 정해져 자리만 둔다.
 */
function ChildEditPage() {
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

  const child = childQuery.data
  // 주소의 학급과 아동의 실제 학급이 다르면 잘못된 주소다(아동 상세와 같다).
  const isWrongClassroom = child !== undefined && child.classId !== classId

  if (childQuery.isLoading) {
    return (
      <InstructorLayout title={TITLE}>
        <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
      </InstructorLayout>
    )
  }
  if (!child || isWrongClassroom) {
    return (
      <InstructorLayout title={TITLE}>
        <Notice tone="error">{describeError(childQuery.error, '아동을 찾을 수 없습니다.')}</Notice>
      </InstructorLayout>
    )
  }
  return <ChildEditForm child={child} classroomName={classroomQuery.data?.name} />
}

function ChildEditForm({ child, classroomName }: { child: Child; classroomName: string | undefined }) {
  const [displayName, setDisplayName] = useState(child.displayName)
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const detailPath = `/classrooms/${child.classId}/children/${child.childId}`

  // 상세·학급의 아동 목록 캐시는 화면을 떠났어도 갱신한다(저장은 이미 끝났다).
  const updateMutation = useMutation({
    mutationFn: (name: string) => updateChild(child.childId, name),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['children', child.childId] })
      queryClient.invalidateQueries({ queryKey: ['classrooms', child.classId, 'children'] })
    },
  })

  const trimmedName = displayName.trim()
  const isSubmitDisabled = updateMutation.isPending || trimmedName === '' || trimmedName === child.displayName
  // isPending 은 다음 렌더부터 반영되어, 같은 순간 들어온 두 번째 제출은 통과할 수 있다. ref 로 즉시 잠근다.
  const isSubmittingRef = useRef(false)

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (isSubmitDisabled || isSubmittingRef.current) {
      return
    }
    isSubmittingRef.current = true
    updateMutation.mutate(trimmedName, {
      // 화면이 사라진 뒤 도착한 응답으로 강제 이동시키지 않게 mutate 쪽 콜백에 둔다(학급 생성과 같다).
      onSuccess: () => {
        navigate(detailPath, { replace: true })
      },
      onSettled: () => {
        isSubmittingRef.current = false
      },
    })
  }

  return (
    <InstructorLayout title={`${TITLE} · ${child.displayName}`} fill>
      <div className="grid grid-cols-2 gap-4">
        <form
          onSubmit={handleSubmit}
          className={cn(cardClass, 'flex flex-col gap-4 self-stretch px-6 py-6')}
          aria-labelledby="info-heading"
        >
          <h2 id="info-heading" className="text-base font-semibold">
            정보 수정
          </h2>
          <label htmlFor="child-display-name" className={fieldLabelClass}>
            <span>
              표시 이름 <span className="text-[var(--instructor-danger-fg)]">*</span>
            </span>
            <input
              id="child-display-name"
              required
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              className={inputClass}
            />
          </label>

          <div className="grid grid-cols-2 gap-4">
            <label className={fieldLabelClass}>
              학급
              <select disabled value="current" className={inputClass}>
                <option value="current">{classroomName ?? '현재 학급'}</option>
              </select>
            </label>
            <label className={fieldLabelClass}>
              연령
              <select disabled defaultValue="" className={inputClass}>
                <option value="">선택</option>
              </select>
            </label>
          </div>

          <label className={fieldLabelClass}>
            접근 방식
            <select disabled defaultValue="code" className={inputClass}>
              <option value="code">접근 코드</option>
            </select>
          </label>

          <p className={helperTextClass}>학급 이동, 연령, 접근 방식은 준비 중이에요.</p>

          {updateMutation.isError && (
            <Notice tone="error" role="alert">
              {describeError(updateMutation.error, '아동 정보를 수정하지 못했습니다.')}
            </Notice>
          )}

          <div className="flex gap-2.5">
            <Link to={detailPath} className={cn(outlineButtonClass, 'w-28')}>
              취소
            </Link>
            <button type="submit" disabled={isSubmitDisabled} className={cn(primaryButtonClass, 'flex-1')}>
              <ButtonLabel pending={updateMutation.isPending} label="저장" pendingLabel="저장 중..." />
            </button>
          </div>
        </form>

        <section className={cn(cardClass, 'flex flex-col gap-4 px-6 py-6')} aria-labelledby="exclude-heading">
          <h2 id="exclude-heading" className="text-base font-semibold">
            학급에서 제외
          </h2>
          <p className={helperTextClass}>
            학급에서 제외하고 기록을 보관하는 기능은 준비 중이에요. 아동과 기록을 지우려면 아동 상세의 아동 삭제를 쓰세요.
          </p>
          <button type="button" disabled className={cn(dangerOutlineButtonClass, 'mt-auto')}>
            학급에서 제외
          </button>
        </section>
      </div>
    </InstructorLayout>
  )
}

export { ChildEditPage }
