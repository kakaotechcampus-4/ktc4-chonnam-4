import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { listChildren, listClassrooms } from '../api'
import { InstructorLayout } from '../layout/InstructorLayout'
import { RecommendationBoard } from '../components/report/RecommendationBoard'
import { RecommendationDetail } from '../components/report/RecommendationDetail'
import { useActivityGoal, useChildLatestLearning } from '../components/report/learningQueries'
import type { NextLevel } from '../components/report/reportHelpers'
import { cardClass, fieldLabelClass, selectClass } from '../components/styles'
import { Notice } from '../components/Feedback'
import { formatDate } from '../lib/dateTime'
import { cn } from '@/lib/utils'

const mutedTextClass = 'text-sm text-[var(--instructor-text-muted)]'

/**
 * 추천·다음목표(시안 T-REC-01 추천 학습 목록, T-REC-02 추천 상세). 학급과 아동을 고르면 그 아동의 가장 최근 역할극에서 나온
 * 제안을 카드로 보이고, 하나를 골라 상세에서 결정한다(?recommendationId=). 기준 리포트는 헤더 오른쪽에서 연다.
 * 제안을 고르면 활동 만들기(역할극)로 이어지고, 배정은 그 화면에서 강사가 한다(자동 배정 금지).
 * 학급 단위 API 가 없어 고른 아동 한 명의 계약 API 만 부른다.
 */
function RecommendationsPage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const classroomsQuery = useQuery({ queryKey: ['classrooms'], queryFn: listClassrooms })
  const classrooms = classroomsQuery.data ?? []
  const requestedClass = searchParams.get('classId')
  const classId = classrooms.some((c) => c.classId === requestedClass)
    ? requestedClass
    : (classrooms[0]?.classId ?? null)
  const childrenQuery = useQuery({
    queryKey: ['classrooms', classId, 'children'],
    queryFn: () => listChildren(classId!),
    enabled: !!classId,
  })
  const children = (childrenQuery.data ?? []).filter((child) => child.status !== 'REMOVED')
  const requestedChild = searchParams.get('childId')
  const child = children.find((c) => c.childId === requestedChild) ?? children[0] ?? null

  const { difficulty, recommendations, isLoading, error } = useChildLatestLearning(child?.childId ?? null)
  const basis = useActivityGoal(difficulty?.sourceActivityId ?? null)
  const level: NextLevel | null = difficulty
    ? { scenarioLevel: difficulty.scenarioLevel, supportLevel: difficulty.initialSupportLevel }
    : null

  // 고른 카드. 처음에는 아직 결정하지 않은 첫 제안을 골라 둔다(시안처럼 한 장이 골라진 채로 연다).
  const [picked, setPicked] = useState<string | null>(null)
  const selectedId = recommendations.some((r) => r.recommendationId === picked)
    ? picked
    : (recommendations.find((r) => r.decision === null)?.recommendationId ?? recommendations[0]?.recommendationId ?? null)
  const detail = recommendations.find((r) => r.recommendationId === searchParams.get('recommendationId')) ?? null

  function show(next: { classId: string; childId?: string; recommendationId?: string }, replace = true) {
    setSearchParams(
      Object.fromEntries(Object.entries(next).filter(([, value]) => value)) as Record<string, string>,
      { replace },
    )
  }

  function goManual() {
    if (!classId || !child) return
    const params = new URLSearchParams({ step: 'goal', classId, childId: child.childId, type: 'ROLEPLAY' })
    navigate(`/activities/new?${params.toString()}`)
  }

  const title = detail ? '추천 상세' : child ? `추천 학습 목록 · ${child.displayName}` : '추천·다음목표'

  return (
    <InstructorLayout
      title={title}
      actions={
        difficulty ? (
          <Link
            to={`/activities/${difficulty.sourceActivityId}/report`}
            className="text-sm text-[var(--instructor-text-subtle)] hover:underline"
          >
            기준 리포트 · {formatDate(basis.activity?.completedAt ?? difficulty.decidedAt)}
            {basis.goalTitle && ` ${basis.goalTitle}`}
          </Link>
        ) : undefined
      }
    >
      <div className="flex flex-col gap-5">
        {classroomsQuery.isLoading ? (
          <p className={mutedTextClass}>불러오는 중...</p>
        ) : classroomsQuery.isError ? (
          <Notice tone="error">{classroomsQuery.error.message}</Notice>
        ) : classrooms.length === 0 || !classId ? (
          <p className={mutedTextClass}>학급을 먼저 만들어 주세요.</p>
        ) : (
          <>
            <div className={cn(cardClass, 'flex flex-wrap gap-4 p-4')}>
              <label className={cn(fieldLabelClass, 'w-60')}>
                학급
                <select
                  value={classId}
                  onChange={(e) => show({ classId: e.target.value })}
                  className={selectClass}
                >
                  {classrooms.map((classroom) => (
                    <option key={classroom.classId} value={classroom.classId}>
                      {classroom.name}
                    </option>
                  ))}
                </select>
              </label>
              {children.length > 0 && (
                <label className={cn(fieldLabelClass, 'w-60')}>
                  아동
                  <select
                    value={child?.childId ?? ''}
                    onChange={(e) => show({ classId, childId: e.target.value })}
                    className={selectClass}
                  >
                    {children.map((c) => (
                      <option key={c.childId} value={c.childId}>
                        {c.displayName}
                      </option>
                    ))}
                  </select>
                </label>
              )}
            </div>

            {childrenQuery.isLoading ? (
              <p className={mutedTextClass}>불러오는 중...</p>
            ) : !child ? (
              <p className={mutedTextClass}>이 학급에 아동이 없어요.</p>
            ) : isLoading ? (
              <p className={mutedTextClass}>불러오는 중...</p>
            ) : error ? (
              <Notice tone="error">{error.message}</Notice>
            ) : !difficulty ? (
              <p className={mutedTextClass}>아직 마친 역할극이 없어요.</p>
            ) : recommendations.length === 0 ? (
              <p className={mutedTextClass}>만든 제안이 없어요.</p>
            ) : detail ? (
              <RecommendationDetail
                key={detail.recommendationId}
                recommendation={detail}
                others={recommendations.filter((r) => r.recommendationId !== detail.recommendationId)}
                level={level}
                classId={classId}
                onBack={() => show({ classId, childId: child.childId })}
                onOpenOther={(recommendationId) => show({ classId, childId: child.childId, recommendationId })}
              />
            ) : (
              <RecommendationBoard
                recommendations={recommendations}
                level={level}
                selectedId={selectedId}
                onSelect={setPicked}
                onOpen={() =>
                  selectedId && show({ classId, childId: child.childId, recommendationId: selectedId }, false)
                }
                onManual={goManual}
              />
            )}
          </>
        )}
      </div>
    </InstructorLayout>
  )
}

export { RecommendationsPage }
