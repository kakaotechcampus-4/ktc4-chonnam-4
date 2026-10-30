import { useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { deleteChild, getChild, getClassroom, issueChildCode, listChildActivities, listLearningGoals } from '../api'
import { describeError } from '../errorMessage'
import { InstructorLayout } from '../layout/InstructorLayout'
import { ActivityStatusBadge } from '../components/ActivityStatusBadge'
import { DeleteConfirmDialog } from '../components/DeleteConfirmDialog'
import { ButtonLabel } from '../components/ButtonLabel'
import {
  cardClass,
  dangerOutlineButtonClass,
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
import { ACTIVITY_TYPE_LABELS } from '../lib/activityTypes'
import { formatDateTime } from '../lib/dateTime'
import { cn } from '@/lib/utils'
import { Notice } from '../components/Feedback'

type DetailTab = 'history' | 'access-code'

/**
 * 아동 상세 (시안 T-LRN-03~05). 활동 이력과 접근 코드 탭을 연다. 개요·학습 상태는 후속 범위라 탭만 보인다.
 * 탭은 주소의 ?tab= 으로 관리한다(학급 상세와 같은 방식). 시안의 QR 은 MVP 범위 밖이라 탭 이름에서도 뺀다.
 * 활동을 누르면 활동 리포트로 간다.
 * 아동 삭제는 기록과 함께 영구 삭제라, 확인 창에서 함께 지워지는 활동 수를 알린다(ADR 2026-10-04 D3·D4).
 */
function ChildDetailPage() {
  const { classId, childId } = useParams<{ classId: string; childId: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [isDeleteOpen, setIsDeleteOpen] = useState(false)
  const [searchParams, setSearchParams] = useSearchParams()
  const tab: DetailTab = searchParams.get('tab') === 'access-code' ? 'access-code' : 'history'

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

  const activitiesQuery = useQuery({
    queryKey: ['children', childId, 'activities'],
    queryFn: () => listChildActivities(childId!),
    enabled: !!childId,
  })

  // 활동에는 goalId 만 있어 목표 목록을 한 번 받아 제목을 붙인다.
  const goalsQuery = useQuery({
    queryKey: ['children', childId, 'learning-goals'],
    queryFn: () => listLearningGoals(childId!),
    enabled: !!childId,
  })

  const deleteMutation = useMutation({
    mutationFn: () => deleteChild(childId!),
    onSuccess: () => {
      // 지운 아동의 화면을 먼저 떠난 뒤 목록을 새로 받는다. 남은 캐시가 404 를 다시 부르지 않게 지운다.
      navigate(`/classrooms/${classId}?tab=children`, { replace: true })
      queryClient.removeQueries({ queryKey: ['children', childId] })
      queryClient.invalidateQueries({ queryKey: ['classrooms', classId, 'children'] })
    },
  })

  const child = childQuery.data
  const classroom = classroomQuery.data
  const activities = activitiesQuery.data
  const goalTitles = new Map(goalsQuery.data?.map((goal) => [goal.goalId, goal.title]))
  // 주소의 학급과 아동의 실제 학급이 다르면 잘못된 주소다. 다른 학급 이름을 붙여 보이지 않는다.
  const isWrongClassroom = child !== undefined && child.classId !== classId

  function selectTab(next: DetailTab) {
    setSearchParams(next === 'history' ? {} : { tab: next }, { replace: true })
  }

  const title = child
    ? `아동 상세 · ${child.displayName}${classroom && !isWrongClassroom ? ` (${classroom.name})` : ''}`
    : '아동 상세'

  return (
    <InstructorLayout
      title={title}
      actions={
        child &&
        !isWrongClassroom && (
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => setIsDeleteOpen(true)}
              className={cn(dangerOutlineButtonClass, 'h-8 px-3.5')}
            >
              아동 삭제
            </button>
            <Link
              to={`/activities/new?classId=${child.classId}&childId=${child.childId}`}
              className={cn(primaryButtonClass, 'h-8 px-3.5')}
            >
              활동 만들기
            </Link>
          </div>
        )
      }
      subheader={
        <div role="tablist" aria-label="아동 상세" className="flex gap-1">
          <span
            role="tab"
            aria-disabled="true"
            aria-selected={false}
            title="준비 중인 화면입니다"
            className={cn(tabBaseClass, tabDisabledClass)}
          >
            개요 · 학습 상태
          </span>
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'access-code'}
            onClick={() => selectTab('access-code')}
            className={cn(tabBaseClass, tab === 'access-code' ? tabSelectedClass : tabIdleClass)}
          >
            접근 코드
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'history'}
            onClick={() => selectTab('history')}
            className={cn(tabBaseClass, tab === 'history' ? tabSelectedClass : tabIdleClass)}
          >
            활동 이력
          </button>
        </div>
      }
    >
      <nav aria-label="현재 위치" className="mb-4 text-xs text-[var(--instructor-text-subtle)]">
        <Link to="/classrooms" className="hover:underline">
          학급 목록
        </Link>
        <span aria-hidden className="mx-1 text-[var(--instructor-border-strong)]">
          ›
        </span>
        <Link to={`/classrooms/${classId}?tab=children`} className="hover:underline">
          {classroom && !isWrongClassroom ? classroom.name : '아동 목록'}
        </Link>
        {child && !isWrongClassroom && (
          <>
            <span aria-hidden className="mx-1 text-[var(--instructor-border-strong)]">
              ›
            </span>
            <span aria-current="page">{child.displayName}</span>
          </>
        )}
      </nav>

      {childQuery.isLoading ? (
        <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
      ) : childQuery.isError || isWrongClassroom ? (
        <Notice tone="error">
          {childQuery.error instanceof Error ? childQuery.error.message : '아동을 찾을 수 없습니다.'}
        </Notice>
      ) : (
        <>
          {/* 받은 코드는 다시 조회할 수 없어, 탭을 오가도 사라지지 않게 접근 코드 패널은 숨기기만 한다. */}
          <AccessCodePanel childId={childId!} hidden={tab !== 'access-code'} />
          {tab === 'history' && (
            <section role="tabpanel" aria-label="활동 이력">
              {activitiesQuery.isLoading ? (
                <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
              ) : activitiesQuery.isError ? (
                <Notice tone="error">
                  {activitiesQuery.error instanceof Error
                    ? activitiesQuery.error.message
                    : '활동 이력을 불러오지 못했습니다.'}
                </Notice>
              ) : activities?.length === 0 ? (
                <p className="text-sm text-[var(--instructor-text-muted)]">아직 배정된 활동이 없습니다.</p>
              ) : (
                <div className={tableClass}>
                  <table className="w-full">
                    <thead>
                      <tr className={tableHeadRowClass}>
                        <th scope="col" className="px-2 py-2.5 font-normal">
                          학습 목표
                        </th>
                        <th scope="col" className="px-2 py-2.5 font-normal">
                          상태
                        </th>
                        <th scope="col" className="px-2 py-2.5 font-normal">
                          배정
                        </th>
                        <th scope="col" className="px-2 py-2.5 font-normal">
                          완료
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {activities?.map((activity) => (
                        <tr key={activity.activityId} className={tableRowClass}>
                          <td className="px-2 py-3">
                            <Link to={`/activities/${activity.activityId}/report`} className="hover:underline">
                              {activity.goalId
                                ? (goalTitles.get(activity.goalId) ?? '학습 목표')
                                : ACTIVITY_TYPE_LABELS[activity.activityType]}
                            </Link>
                          </td>
                          <td className="px-2 py-3">
                            <ActivityStatusBadge status={activity.status} />
                          </td>
                          <td className="px-2 py-3 text-[var(--instructor-text-muted)]">
                            {formatDateTime(activity.assignedAt)}
                          </td>
                          <td className="px-2 py-3 text-[var(--instructor-text-muted)]">
                            {formatDateTime(activity.completedAt)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          )}
        </>
      )}

      {child && (
        <DeleteConfirmDialog
          open={isDeleteOpen}
          onOpenChange={(open) => {
            setIsDeleteOpen(open)
            if (!open) deleteMutation.reset()
          }}
          title={`${child.displayName} 아동을 삭제할까요?`}
          description={
            <>
              <p>{describeRecords(activities)}</p>
              <p>삭제하면 되돌릴 수 없어요.</p>
            </>
          }
          isPending={deleteMutation.isPending}
          error={deleteMutation.isError ? describeError(deleteMutation.error, '아동을 삭제하지 못했습니다.') : null}
          onConfirm={() => deleteMutation.mutate()}
        />
      )}
    </InstructorLayout>
  )
}

/**
 * 접근 코드 카드(시안 T-LRN-04 오른쪽 카드). 서버는 코드 원문을 저장하지 않아, 받은 직후에만 코드를 보여 줄 수 있다.
 * 요청 키는 "코드 받기" 한 번에 하나다. 받은 뒤 다시 누르면 새 코드이고, 실패해서 다시 누르면 같은 키로 보내
 * 서버가 이미 만든 코드가 있으면 그 코드를 돌려받는다(한 번 누름에 코드가 두 번 바뀌지 않는다).
 */
function AccessCodePanel({ childId, hidden }: { childId: string; hidden: boolean }) {
  const requestKeyRef = useRef<string | null>(null)
  const [copied, setCopied] = useState(false)

  const issueMutation = useMutation({
    mutationFn: () => {
      requestKeyRef.current ??= crypto.randomUUID()
      return issueChildCode(childId, requestKeyRef.current)
    },
    onSuccess: () => {
      requestKeyRef.current = null
      setCopied(false)
    },
  })

  const issued = issueMutation.data

  async function handleCopy() {
    if (!issued) return
    try {
      await navigator.clipboard.writeText(issued.accessCode)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  return (
    <section
      role="tabpanel"
      aria-label="접근 코드"
      hidden={hidden}
      className={cn(cardClass, 'flex max-w-[540px] flex-col gap-4 p-5')}
    >
      <h2 className="text-sm font-semibold">접근 코드</h2>
      <div
        aria-live="polite"
        className="flex flex-col items-center gap-1 rounded-[var(--instructor-radius-control)] bg-[var(--instructor-surface-muted)] px-4 py-6"
      >
        {issued ? (
          <>
            <p className="font-mono text-4xl font-semibold tracking-[0.4em] text-[var(--instructor-text)]">
              {issued.accessCode}
            </p>
            <p className="text-xs text-[var(--instructor-text-subtle)]">
              {formatDateTime(issued.expiresAt)}까지 쓸 수 있어요
            </p>
          </>
        ) : (
          <p className="text-sm text-[var(--instructor-text-muted)]">
            코드를 받으면 아이가 입장 화면에 숫자 4자리를 넣어 들어와요.
          </p>
        )}
      </div>
      <div className="grid grid-cols-2 gap-2">
        <button
          type="button"
          onClick={() => issueMutation.mutate()}
          disabled={issueMutation.isPending}
          className={issued ? outlineButtonClass : primaryButtonClass}
        >
          <ButtonLabel
            pending={issueMutation.isPending}
            label={issued ? '코드 다시 받기' : '코드 받기'}
            pendingLabel="받는 중..."
          />
        </button>
        <button type="button" onClick={handleCopy} disabled={!issued} className={outlineButtonClass}>
          {copied ? '복사했어요' : '복사'}
        </button>
      </div>
      {issueMutation.isError && (
        <Notice tone="error" role="alert">
          {describeError(issueMutation.error, '코드를 받지 못했습니다.')}
        </Notice>
      )}
      <Notice tone="warning">
        다시 받으면 이전 코드는 바로 쓸 수 없어요. 코드는 지금만 볼 수 있으니 아이에게 전해 주세요.
      </Notice>
    </section>
  )
}

/** 확인 창에 보일 "함께 지워지는 기록" 문구. 목록을 아직 못 받았으면 개수 없이 알린다. */
function describeRecords(activities: { status: string }[] | undefined): string {
  const others = '학습 목표, 퀴즈 응답·결과, 입장 코드가 함께 영구 삭제돼요.'
  if (activities === undefined) return `이 아동의 활동과 ${others}`
  if (activities.length === 0) return `배정된 활동은 없어요. ${others}`
  const completed = activities.filter((activity) => activity.status === 'COMPLETED').length
  const inProgress = activities.filter((activity) => activity.status === 'IN_PROGRESS').length
  return `활동 ${activities.length}개(완료 ${completed}, 진행 중 ${inProgress})와 ${others}`
}

export { ChildDetailPage }
