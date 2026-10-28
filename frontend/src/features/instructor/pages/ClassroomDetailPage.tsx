import { useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createChild, deleteClassroom, getClassroom, listChildren, type ChildStatus } from '../api'
import { describeError } from '../errorMessage'
import { InstructorLayout } from '../layout/InstructorLayout'
import { ClassroomStatusBadge } from '../components/ClassroomStatusBadge'
import { DeleteConfirmDialog } from '../components/DeleteConfirmDialog'
import {
  cardClass,
  dangerOutlineButtonClass,
  inputClass,
  outlineButtonClass,
  primaryButtonClass,
  tabBaseClass,
  tabDisabledClass,
  tabIdleClass,
  tabSelectedClass,
  tableClass,
  tableHeadRowClass,
  tableRowClass,
} from '../components/styles'
import { cn } from '@/lib/utils'
import { Notice } from '../components/Feedback'
import { ButtonLabel } from '../components/ButtonLabel'

type DetailTab = 'overview' | 'children'

const CHILD_STATUS_LABELS: Record<ChildStatus, string> = {
  ACTIVE: '참여중',
  PAUSED: '일시 중지',
  REMOVED: '제외됨',
}

// 시안(T-CLS-03~06)의 탭 중 배정 활동·리포트는 아직 화면이 없어 비활성으로 보인다.
const PENDING_TABS = ['배정 활동', '리포트']

// 시안 개요의 통계 중 아직 서버가 주지 않는 값. 활동 데이터가 학급 단위로 모이면 "—" 대신 채운다.
const PENDING_STATS = ['진행중', '완료', '복구 필요']

// 헤더의 작은 버튼(디자인 시스템 Compact 32px)
const compactButtonClass = 'h-8 px-3.5'

/**
 * 학급 상세 (시안 T-CLS-03 개요 탭 + 아동 목록 탭).
 * 탭은 주소의 ?tab= 으로 관리해, 개요의 "아동 등록" 버튼이 아동 목록 탭으로 바로 넘어가게 한다.
 * 개요 배치는 시안을 따른다: 위에 통계 4칸, 아래 두 칸(왼쪽 최근 학습 요약 1.3 : 오른쪽 활동 현황·바로 하기 1).
 * 통계는 아동 수만 실제 값이고, 최근 학습·활동 현황은 학급 단위 활동 데이터가 생긴 뒤 채운다.
 * 학급 삭제는 아동 여러 명의 기록이 함께 사라져, 학급 이름을 입력해야 실행된다(ADR 2026-10-04 D4).
 */
function ClassroomDetailPage() {
  const { classId } = useParams<{ classId: string }>()
  const [searchParams, setSearchParams] = useSearchParams()
  const tab: DetailTab = searchParams.get('tab') === 'children' ? 'children' : 'overview'
  const [displayName, setDisplayName] = useState('')
  const [isDeleteOpen, setIsDeleteOpen] = useState(false)
  const navigate = useNavigate()
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

  const deleteMutation = useMutation({
    mutationFn: () => deleteClassroom(classId!),
    onSuccess: () => {
      // 지운 학급의 화면을 먼저 떠난 뒤 목록을 새로 받는다. 학급·아동 캐시는 모두 지운다.
      navigate('/classrooms', { replace: true })
      queryClient.removeQueries({ queryKey: ['classrooms', classId] })
      queryClient.removeQueries({ queryKey: ['children'] })
      queryClient.invalidateQueries({ queryKey: ['classrooms'] })
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

  function selectTab(next: DetailTab) {
    setSearchParams(next === 'overview' ? {} : { tab: next }, { replace: true })
  }

  return (
    <InstructorLayout
      title={classroom ? `학급 상세 · ${classroom.name}` : '학급 상세'}
      actions={
        <div className="flex gap-2">
          {classroom && (
            <button
              type="button"
              onClick={() => setIsDeleteOpen(true)}
              className={cn(dangerOutlineButtonClass, compactButtonClass)}
            >
              학급 삭제
            </button>
          )}
          {/* 학급 수정은 S4-BAE-01 범위라 자리만 둔다. */}
          <button type="button" disabled title="준비 중인 기능입니다" className={cn(outlineButtonClass, compactButtonClass)}>
            학급 수정
          </button>
        </div>
      }
      subheader={
        <div role="tablist" aria-label="학급 상세" className="flex gap-1">
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'overview'}
            onClick={() => selectTab('overview')}
            className={cn(tabBaseClass, tab === 'overview' ? tabSelectedClass : tabIdleClass)}
          >
            개요
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'children'}
            onClick={() => selectTab('children')}
            className={cn(tabBaseClass, tab === 'children' ? tabSelectedClass : tabIdleClass)}
          >
            아동 목록
            {children && <span className="ml-1 font-normal text-[var(--instructor-text-muted)]">{children.length}</span>}
          </button>
          {PENDING_TABS.map((label) => (
            <span
              key={label}
              role="tab"
              aria-disabled="true"
              aria-selected={false}
              title="준비 중인 화면입니다"
              className={cn(tabBaseClass, tabDisabledClass)}
            >
              {label}
            </span>
          ))}
        </div>
      }
    >
      <nav aria-label="현재 위치" className="mb-4 text-xs text-[var(--instructor-text-subtle)]">
        <Link to="/classrooms" className="hover:underline">
          학급 목록
        </Link>
        {classroom && (
          <>
            <span aria-hidden className="mx-1 text-[var(--instructor-border-strong)]">
              ›
            </span>
            <span aria-current="page">{classroom.name}</span>
          </>
        )}
      </nav>

      {isClassroomLoading ? (
        <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
      ) : isClassroomError ? (
        <Notice tone="error">
          {classroomError instanceof Error
            ? classroomError.message
            : '학급 정보를 불러오지 못했습니다.'}
        </Notice>
      ) : tab === 'overview' ? (
        <section role="tabpanel" aria-label="개요" className="flex flex-col gap-4">
          <dl className="flex gap-4">
            <div className={cn(cardClass, 'flex flex-1 flex-col gap-2 p-5')}>
              <dt className="text-xs text-[var(--instructor-text-subtle)]">아동 수</dt>
              <dd className="text-2xl font-semibold">{children ? children.length : '—'}</dd>
            </div>
            {PENDING_STATS.map((label) => (
              <div key={label} className={cn(cardClass, 'flex flex-1 flex-col gap-2 p-5')}>
                <dt className="text-xs text-[var(--instructor-text-subtle)]">{label}</dt>
                <dd className="text-2xl font-semibold text-[var(--instructor-text-disabled)]">—</dd>
              </div>
            ))}
          </dl>

          <div className="grid grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)] gap-4">
            <section aria-labelledby="recent-heading" className={cn(cardClass, 'flex flex-col gap-4 p-5')}>
              <h2 id="recent-heading" className="text-sm font-semibold">
                최근 학습 요약
              </h2>
              <p className="text-sm text-[var(--instructor-text-subtle)]">
                아동이 활동을 하면 최근 학습이 여기에 모여요. 지금은 아동 상세에서 활동별로 볼 수 있어요.
              </p>
            </section>

            <div className="flex flex-col gap-4">
              <section aria-labelledby="activity-stats-heading" className={cn(cardClass, 'flex flex-col gap-4 p-5')}>
                <h2 id="activity-stats-heading" className="text-sm font-semibold">
                  활동 현황
                </h2>
                <p className="text-sm text-[var(--instructor-text-subtle)]">유형별 완료 현황은 준비 중이에요.</p>
              </section>

              <section aria-labelledby="quick-heading" className={cn(cardClass, 'flex flex-1 flex-col gap-3 p-5')}>
                <div className="mb-1 flex items-center justify-between">
                  <h2 id="quick-heading" className="text-sm font-semibold">
                    바로 하기
                  </h2>
                  {classroom && <ClassroomStatusBadge status={classroom.status} />}
                </div>
                {/* 학급 일괄 배정은 아직 없어, 이 학급을 골라 둔 채 아동 한 명을 고르는 활동 만들기로 보낸다. 접근 코드 출력은 후속 범위라 자리만 둔다. */}
                <Link to={`/activities/new?classId=${classId}`} className={primaryButtonClass}>
                  이 학급에 활동 만들기
                </Link>
                <button type="button" onClick={() => selectTab('children')} className={outlineButtonClass}>
                  아동 등록
                </button>
                <button type="button" disabled title="준비 중인 기능입니다" className={outlineButtonClass}>
                  접근 코드 출력
                </button>
              </section>
            </div>
          </div>
        </section>
      ) : (
        <section role="tabpanel" aria-label="아동 목록" className="flex flex-col gap-4">
          <form onSubmit={handleSubmit} className="flex items-center gap-2">
            <label htmlFor="child-display-name" className="sr-only">
              아동 이름
            </label>
            <input
              id="child-display-name"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              placeholder="아동 이름"
              className={cn(inputClass, 'w-[246px]')}
            />
            <button type="submit" disabled={isSubmitDisabled} className={primaryButtonClass}>
              <ButtonLabel pending={createChildMutation.isPending} label="아동 등록" pendingLabel="등록 중..." />
            </button>
          </form>

          {createChildMutation.isError && (
            <Notice tone="error" role="alert">
              {describeError(createChildMutation.error, '아동 등록에 실패했습니다.')}
            </Notice>
          )}

          {isChildrenLoading ? (
            <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
          ) : isChildrenError ? (
            <Notice tone="error">
              {childrenError instanceof Error ? childrenError.message : '아동 목록을 불러오지 못했습니다.'}
            </Notice>
          ) : children?.length === 0 ? (
            <p className="text-sm text-[var(--instructor-text-muted)]">아직 등록된 아동이 없습니다.</p>
          ) : (
            <div className={tableClass}>
              <table className="w-full">
                <thead>
                  <tr className={tableHeadRowClass}>
                    <th scope="col" className="px-2 py-2.5 font-normal">
                      이름
                    </th>
                    <th scope="col" className="px-2 py-2.5 font-normal">
                      상태
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {children?.map((child) => (
                    <tr key={child.childId} className={tableRowClass}>
                      <td className="px-2 py-3">
                        <Link to={`/classrooms/${classId}/children/${child.childId}`} className="hover:underline">
                          {child.displayName}
                        </Link>
                      </td>
                      <td className="px-2 py-3 text-[var(--instructor-text-muted)]">
                        {CHILD_STATUS_LABELS[child.status]}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}

      {classroom && (
        <DeleteConfirmDialog
          open={isDeleteOpen}
          onOpenChange={(open) => {
            setIsDeleteOpen(open)
            if (!open) deleteMutation.reset()
          }}
          title={`${classroom.name} 학급을 삭제할까요?`}
          description={
            <>
              <p>
                {children === undefined
                  ? '이 학급의 아동과 그 기록이 모두 영구 삭제돼요.'
                  : children.length === 0
                    ? '등록된 아동은 없어요. 학급만 삭제돼요.'
                    : `아동 ${children.length}명과 그 아동들의 활동·퀴즈 결과·학습 목표·입장 코드가 모두 영구 삭제돼요.`}
              </p>
              <p>삭제하면 되돌릴 수 없어요.</p>
            </>
          }
          confirmText={classroom.name}
          isPending={deleteMutation.isPending}
          error={deleteMutation.isError ? describeError(deleteMutation.error, '학급을 삭제하지 못했습니다.') : null}
          onConfirm={() => deleteMutation.mutate()}
        />
      )}
    </InstructorLayout>
  )
}

export { ClassroomDetailPage }
