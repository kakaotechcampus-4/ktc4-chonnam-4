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
import { REJECTION_REASON_LABELS, SUPPORT_LABELS } from '../../lib/reportLabels'
import {
  cardClass,
  fieldLabelClass,
  inputClass,
  outlineButtonClass,
  primaryButtonClass,
  selectClass,
  textButtonClass,
} from '../styles'
import { FieldError } from '../Feedback'
import { ButtonLabel } from '../ButtonLabel'
import { ConditionChip, DecisionChip, EvidenceMeter, RecommendationTypeChip } from './ReportChips'
import { recommendationTitle, type NextLevel } from './reportHelpers'
import { cn } from '@/lib/utils'

const GOAL_TITLE_MAX = 200

const sectionLabelClass = 'text-xs text-[var(--instructor-text-subtle)]'
const insetClass =
  'rounded-[var(--instructor-radius-control)] bg-[var(--instructor-surface-muted)] px-3 py-2.5 text-sm leading-6 break-keep'

/**
 * 추천 상세(시안 T-REC-02). 왼쪽은 추천 이유와 관찰 근거, 오른쪽은 예상 조건·다른 추천·결정 버튼이다.
 * 강사가 고르기 전에는 아무것도 배정하지 않는다(자동 배정 금지). "이 추천으로 다음 목표 설정"은 채택을 남긴 뒤
 * 목표가 채워진 활동 만들기(역할극)로 이동하고, 배정은 그 화면에서 강사가 한다.
 * 시안의 다음 목표 확정(T-NXT-01) 자리는 팀 활동 만들기 마법사(T-ACT-03·04)가 맡는다. 목표 문장 고치기만 여기서 한다.
 * 확장은 넓힐 목표를 강사가 써야 해서 제목 입력부터 연다.
 */
function RecommendationDetail({
  recommendation,
  others,
  level,
  classId,
  onBack,
  onOpenOther,
}: {
  recommendation: Recommendation
  others: Recommendation[]
  level: NextLevel | null
  classId: string
  onBack: () => void
  onOpenOther: (recommendationId: string) => void
}) {
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
  const accepted = decided === 'ACCEPT' || decided === 'MODIFIED_ACCEPT'
  const isFinal = accepted || decided === 'REJECT'
  const needsTitle = !suggested.title
  const editing = !isFinal && (mode === 'edit' || needsTitle)

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
    if (editing) {
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

  return (
    <div className="grid grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)] items-start gap-4">
      <article aria-labelledby={titleId} className={cn(cardClass, 'flex flex-col gap-5 p-6')}>
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-1.5">
            <RecommendationTypeChip type={recommendation.type} />
            {level && (
              <>
                <ConditionChip>예상 난이도 {level.scenarioLevel}</ConditionChip>
                <ConditionChip>지원 {level.supportLevel}</ConditionChip>
              </>
            )}
            <DecisionChip decision={decided} />
          </div>
          <h2 id={titleId} className="text-xl leading-8 font-semibold break-keep">
            {accepted ? (decidedGoal.title ?? recommendationTitle(recommendation)) : recommendationTitle(recommendation)}
          </h2>
          {editing && (
            <label className={fieldLabelClass}>
              {needsTitle ? '넓힐 목표' : '목표 문장 수정'}
              <input
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                maxLength={GOAL_TITLE_MAX}
                placeholder="예: 친구가 속상할 때 위로하는 말을 한다"
                className={inputClass}
              />
            </label>
          )}
        </div>

        <section className="flex flex-col gap-1.5">
          <h3 className={sectionLabelClass}>추천 이유</h3>
          <p className="text-sm leading-6 break-keep">{recommendation.reason}</p>
        </section>

        <section className="flex flex-col gap-1.5">
          <h3 className={sectionLabelClass}>관찰 근거</h3>
          {recommendation.evidence.length > 0 ? (
            <ul className="flex flex-col gap-2">
              {recommendation.evidence.map((line) => (
                <li key={line} className={insetClass}>
                  {line}
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-[var(--instructor-text-muted)]">근거로 쓴 관찰이 따로 없어요.</p>
          )}
          {recommendation.basisGoalTitle && (
            <p className="text-xs text-[var(--instructor-text-subtle)]">근거 목표 · {recommendation.basisGoalTitle}</p>
          )}
        </section>

        {!isFinal && mode === 'reject' && (
          <div className="flex flex-wrap items-end gap-2">
            <label className={fieldLabelClass}>
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

        {mutation.isError && <FieldError>{describeError(mutation.error, '결정을 남기지 못했습니다.')}</FieldError>}
      </article>

      <div className="flex flex-col gap-4">
        <section aria-labelledby={`${titleId}-conditions`} className={cn(cardClass, 'flex flex-col gap-3 p-5')}>
          <h3 id={`${titleId}-conditions`} className="text-sm font-semibold">
            예상 조건
          </h3>
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
            <dt className="text-[var(--instructor-text-muted)]">난이도</dt>
            <dd className="text-right font-semibold">{level?.scenarioLevel ?? '-'}</dd>
            <dt className="text-[var(--instructor-text-muted)]">초기 지원</dt>
            <dd className="text-right font-semibold">
              {level ? `${level.supportLevel} (${SUPPORT_LABELS[level.supportLevel]})` : '-'}
            </dd>
            <dt className="text-[var(--instructor-text-muted)]">활동 유형</dt>
            <dd className="text-right font-semibold">역할극</dd>
          </dl>
          <EvidenceMeter sufficiency={recommendation.evidenceSufficiency} />
        </section>

        {others.length > 0 && (
          <section aria-labelledby={`${titleId}-others`} className={cn(cardClass, 'flex flex-col gap-1 p-5')}>
            <h3 id={`${titleId}-others`} className="mb-1 text-sm font-semibold">
              다른 추천
            </h3>
            <ul className="flex flex-col">
              {others.map((other) => (
                <li key={other.recommendationId} className="border-t border-[var(--instructor-border-subtle)]">
                  <button
                    type="button"
                    onClick={() => onOpenOther(other.recommendationId)}
                    className="flex w-full items-center justify-between gap-3 py-3 text-left text-sm hover:text-[var(--instructor-link)]"
                  >
                    <span className="break-keep">{recommendationTitle(other)}</span>
                    <RecommendationTypeChip type={other.type} />
                  </button>
                </li>
              ))}
            </ul>
          </section>
        )}

        <div className="flex gap-2">
          <button type="button" onClick={onBack} className={outlineButtonClass}>
            목록으로
          </button>
          {accepted && decidedGoal.title ? (
            <button
              type="button"
              onClick={() => goCreate(decidedGoal.title!, decidedGoal.category)}
              className={cn(primaryButtonClass, 'flex-1')}
            >
              이 목표로 활동 만들기
            </button>
          ) : !isFinal ? (
            <button
              type="button"
              onClick={accept}
              disabled={mutation.isPending || (editing && !title.trim())}
              className={cn(primaryButtonClass, 'flex-1')}
            >
              <ButtonLabel pending={mutation.isPending} label="이 추천으로 다음 목표 설정" pendingLabel="남기는 중..." />
            </button>
          ) : null}
        </div>

        {!isFinal && (
          <div className="flex flex-wrap justify-end gap-1">
            {!needsTitle &&
              (mode === 'edit' ? (
                <button
                  type="button"
                  onClick={() => {
                    setMode('idle')
                    setTitle(suggested.title ?? '')
                  }}
                  className={textButtonClass}
                >
                  고치기 취소
                </button>
              ) : (
                <button type="button" onClick={() => setMode('edit')} className={textButtonClass}>
                  목표 문장 고치기
                </button>
              ))}
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
      </div>
    </div>
  )
}

export { RecommendationDetail }
