import { useId, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  decideRecommendation,
  type Recommendation,
  type RecommendationDecisionInput,
  type RejectionReason,
} from '../../api'
import { describeError } from '../../errorMessage'
import { RECOMMENDATION_TYPE_LABELS, REJECTION_REASON_LABELS } from '../../lib/reportLabels'
import { badgeClass, inputClass, outlineButtonClass, primaryButtonClass, selectClass, textButtonClass } from '../styles'
import { FieldError } from '../Feedback'
import { ButtonLabel } from '../ButtonLabel'
import { cn } from '@/lib/utils'

const GOAL_TITLE_MAX = 200

const DECISION_BADGES = {
  ACCEPT: { label: '채택함', color: 'bg-[var(--instructor-success-bg)] text-[var(--instructor-success-fg)]' },
  MODIFIED_ACCEPT: { label: '고쳐서 채택함', color: 'bg-[var(--instructor-success-bg)] text-[var(--instructor-success-fg)]' },
  DEFER: { label: '보류함', color: 'bg-[var(--instructor-neutral-bg)] text-[var(--instructor-neutral-fg)]' },
  REJECT: { label: '거절함', color: 'bg-[var(--instructor-neutral-bg)] text-[var(--instructor-neutral-fg)]' },
} as const

/**
 * 다음 수업 제안 카드. 강사가 고르기 전에는 아무것도 배정하지 않는다(자동 배정 금지).
 * "이 제안으로 활동 만들기"는 채택을 남긴 뒤, 목표가 채워진 활동 만들기(역할극)로 이동한다. 배정은 그 화면에서 강사가 한다.
 * 확장은 넓힐 목표를 강사가 써야 해서 제목 입력부터 연다.
 */
function RecommendationCard({ recommendation, classId }: { recommendation: Recommendation; classId: string | null }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const titleId = useId()
  const suggested = recommendation.suggestedGoal
  const decidedGoal = recommendation.decidedGoal ?? suggested
  const [mode, setMode] = useState<'idle' | 'edit' | 'reject'>('idle')
  const [title, setTitle] = useState(suggested.title ?? '')
  const [reason, setReason] = useState<RejectionReason>('NOT_NOW')
  const attemptRef = useRef<{ submission: string; requestKey: string } | null>(null)

  const decided = recommendation.decision
  const isFinal = decided === 'ACCEPT' || decided === 'MODIFIED_ACCEPT' || decided === 'REJECT'

  const mutation = useMutation({
    mutationFn: (input: RecommendationDecisionInput) => {
      // 같은 결정을 다시 보낼 때만 같은 키를 쓴다. 응답을 못 받아 다시 눌러도 결정은 하나만 남는다.
      const submission = JSON.stringify(input)
      if (attemptRef.current?.submission !== submission) {
        attemptRef.current = { submission, requestKey: crypto.randomUUID() }
      }
      return decideRecommendation(recommendation.recommendationId, input, attemptRef.current.requestKey)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['learning-records', recommendation.recordId, 'recommendations'] })
    },
  })

  function goCreate(goalTitle: string, category: string | null) {
    if (!classId) return
    const params = new URLSearchParams({
      step: 'goal',
      classId,
      childId: recommendation.childId,
      type: 'ROLEPLAY',
      goalTitle,
    })
    if (category) params.set('goalCategory', category)
    if (suggested.parentGoalId) params.set('parentGoalId', suggested.parentGoalId)
    navigate(`/activities/new?${params.toString()}`)
  }

  function accept() {
    const trimmed = title.trim()
    if (mode === 'edit' || !suggested.title) {
      if (!trimmed) return
      const goal = { title: trimmed, category: suggested.category, parentGoalId: suggested.parentGoalId }
      if (trimmed === suggested.title) {
        mutation.mutate({ decision: 'ACCEPT' }, { onSuccess: () => goCreate(trimmed, suggested.category) })
      } else {
        mutation.mutate({ decision: 'MODIFIED_ACCEPT', goal }, { onSuccess: () => goCreate(trimmed, suggested.category) })
      }
      return
    }
    mutation.mutate({ decision: 'ACCEPT' }, { onSuccess: () => goCreate(suggested.title!, suggested.category) })
  }

  const titleMissing = (mode === 'edit' || !suggested.title) && !title.trim()

  return (
    <article
      aria-labelledby={titleId}
      className="flex flex-col gap-3 rounded-[var(--instructor-radius-control)] border border-[var(--instructor-border)] px-4 py-3.5"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-xs font-semibold text-[var(--instructor-link)]">
            {RECOMMENDATION_TYPE_LABELS[recommendation.type]}
            {recommendation.evidenceSufficiency !== 'SUFFICIENT' && (
              <span className="ml-2 font-normal text-[var(--instructor-text-muted)]">근거가 적어요</span>
            )}
          </p>
          <h4 id={titleId} className="mt-0.5 text-[15px] font-semibold break-keep">
            {decidedGoal.title ?? '넓힐 목표를 골라 주세요'}
          </h4>
          <p className="mt-1 text-xs leading-5 text-[var(--instructor-text-muted)] break-keep">{recommendation.reason}</p>
        </div>
        {decided && (
          <span className={cn(badgeClass, 'shrink-0', DECISION_BADGES[decided].color)}>{DECISION_BADGES[decided].label}</span>
        )}
      </div>

      {!isFinal && mode === 'idle' && suggested.title && (
        <div className="flex flex-wrap gap-2">
          <button type="button" onClick={accept} disabled={mutation.isPending} className={primaryButtonClass}>
            <ButtonLabel pending={mutation.isPending} label="이 제안으로 활동 만들기" pendingLabel="남기는 중..." />
          </button>
          <button type="button" onClick={() => setMode('edit')} className={outlineButtonClass}>
            목표 고치기
          </button>
          {decided !== 'DEFER' && (
            <button
              type="button"
              onClick={() => mutation.mutate({ decision: 'DEFER' })}
              disabled={mutation.isPending}
              className={textButtonClass}
            >
              보류
            </button>
          )}
          <button type="button" onClick={() => setMode('reject')} className={textButtonClass}>
            거절
          </button>
        </div>
      )}

      {!isFinal && (mode === 'edit' || (mode === 'idle' && !suggested.title)) && (
        <div className="flex flex-col gap-2">
          <label className="flex flex-col gap-1.5 text-sm">
            다음 활동 목표
            <input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              maxLength={GOAL_TITLE_MAX}
              placeholder="예: 친구가 속상할 때 위로하는 말을 한다"
              className={inputClass}
            />
          </label>
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={accept}
              disabled={mutation.isPending || titleMissing}
              className={primaryButtonClass}
            >
              <ButtonLabel pending={mutation.isPending} label="이 목표로 활동 만들기" pendingLabel="남기는 중..." />
            </button>
            {suggested.title ? (
              <button type="button" onClick={() => setMode('idle')} className={textButtonClass}>
                취소
              </button>
            ) : (
              <button type="button" onClick={() => setMode('reject')} className={textButtonClass}>
                거절
              </button>
            )}
          </div>
        </div>
      )}

      {!isFinal && mode === 'reject' && (
        <div className="flex flex-wrap items-end gap-2">
          <label className="flex flex-col gap-1.5 text-sm">
            거절 이유
            <select value={reason} onChange={(e) => setReason(e.target.value as RejectionReason)} className={selectClass}>
              {(Object.keys(REJECTION_REASON_LABELS) as RejectionReason[]).map((key) => (
                <option key={key} value={key}>
                  {REJECTION_REASON_LABELS[key]}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            onClick={() =>
              mutation.mutate({ decision: 'REJECT', rejectionReason: reason }, { onSuccess: () => setMode('idle') })
            }
            disabled={mutation.isPending}
            className={outlineButtonClass}
          >
            <ButtonLabel pending={mutation.isPending} label="거절하기" pendingLabel="남기는 중..." />
          </button>
          <button type="button" onClick={() => setMode('idle')} className={textButtonClass}>
            취소
          </button>
        </div>
      )}

      {(decided === 'ACCEPT' || decided === 'MODIFIED_ACCEPT') && decidedGoal.title && (
        <div>
          <button
            type="button"
            onClick={() => goCreate(decidedGoal.title!, decidedGoal.category)}
            className={outlineButtonClass}
          >
            활동 만들기로 가기
          </button>
        </div>
      )}

      {mutation.isError && <FieldError>{describeError(mutation.error, '결정을 남기지 못했습니다.')}</FieldError>}
    </article>
  )
}

export { RecommendationCard }
