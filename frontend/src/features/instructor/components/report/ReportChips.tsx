import type { ReactNode } from 'react'
import type {
  GoalStatus,
  Recommendation,
  RecommendationDecision,
  RecommendationType,
  SupportLevel,
} from '../../api'
import { GOAL_STATUS_LABELS, RECOMMENDATION_TYPE_LABELS, SUPPORT_LABELS } from '../../lib/reportLabels'
import { badgeClass } from '../styles'
import { cn } from '@/lib/utils'

/**
 * 리포트·추천 화면의 작은 칩. 시안(T-RPT-02~08, T-REC-01·02)은 회색 와이어프레임이라 색은 디자인 시스템 상태색에 맞춘다.
 * 색만으로 뜻을 알리지 않도록 칩마다 글자를 둔다.
 */

const NEUTRAL = 'bg-[var(--instructor-neutral-bg)] text-[var(--instructor-neutral-fg)]'
const SUCCESS = 'bg-[var(--instructor-success-bg)] text-[var(--instructor-success-fg)]'
const WARNING = 'bg-[var(--instructor-warning-bg)] text-[var(--instructor-warning-fg)]'
const DANGER = 'bg-[var(--instructor-danger-bg)] text-[var(--instructor-danger-text)]'
const INFO = 'bg-[var(--instructor-info-bg)] text-[var(--instructor-info-fg)]'

// 시안 T-RPT-02~08 의 "달성"(초록)·"부분 달성"(주황). 시안 예시대로 처음 받은 지원으로 도달하면 달성, 지원을 더 받고서야
// 도달하면(답을 듣고 따라 말한 것 포함) 부분 달성이다. 자세한 상태는 title 로 남긴다.
const GOAL_STATUS_CHIPS: Record<GoalStatus, { label: string; color: string }> = {
  NO_SUPPORT_NEEDED: { label: '달성', color: SUCCESS },
  REACHED_AT_START: { label: '달성', color: SUCCESS },
  EXTRA_SUPPORT: { label: '부분 달성', color: WARNING },
  ANSWER_GIVEN: { label: '부분 달성', color: WARNING },
  NOT_REACHED: { label: '미달성', color: DANGER },
  EXCLUDED: { label: '제외', color: NEUTRAL },
}

// 시안 T-REC-01 의 유형 칩: 보완 주황, 반복 파랑, 확장 초록, 적용 회색.
const TYPE_COLORS: Record<RecommendationType, string> = {
  REINFORCE: WARNING,
  REPEAT: INFO,
  EXTEND: SUCCESS,
  APPLY: NEUTRAL,
}

/** 지원 단계 칩. 시안처럼 기호(S0~S3)를 쓰고, 받은 도움은 title 과 화면 읽기용 글자로 함께 알린다. */
function SupportChip({ level, prefix }: { level: SupportLevel; prefix?: string }) {
  return (
    <span className={cn(badgeClass, NEUTRAL, 'font-semibold')} title={SUPPORT_LABELS[level]}>
      {prefix && `${prefix} `}
      {level}
      <span className="sr-only"> {SUPPORT_LABELS[level]}</span>
    </span>
  )
}

function GoalStatusChip({ status }: { status: GoalStatus }) {
  const chip = GOAL_STATUS_CHIPS[status]
  return (
    <span className={cn(badgeClass, 'shrink-0', chip.color)} title={GOAL_STATUS_LABELS[status]}>
      {chip.label}
    </span>
  )
}

function RecommendationTypeChip({ type }: { type: RecommendationType }) {
  return (
    <span className={cn(badgeClass, 'font-semibold', TYPE_COLORS[type])}>{RECOMMENDATION_TYPE_LABELS[type]}</span>
  )
}

const DECISION_CHIPS: Record<RecommendationDecision, { label: string; color: string }> = {
  ACCEPT: { label: '채택함', color: SUCCESS },
  MODIFIED_ACCEPT: { label: '고쳐서 채택함', color: SUCCESS },
  DEFER: { label: '보류함', color: NEUTRAL },
  REJECT: { label: '거절함', color: NEUTRAL },
}

/** 강사가 남긴 결정. 결정 전이면 아무것도 보이지 않는다. */
function DecisionChip({ decision }: { decision: RecommendationDecision | null }) {
  if (!decision) return null
  return <span className={cn(badgeClass, 'shrink-0', DECISION_CHIPS[decision].color)}>{DECISION_CHIPS[decision].label}</span>
}

/** 회색 칩(예상 난이도 L2, 지원 S1 처럼 조건을 보이는 칩). */
function ConditionChip({ children }: { children: ReactNode }) {
  return <span className={cn(badgeClass, NEUTRAL)}>{children}</span>
}

// 서버는 근거가 얼마나 되는지만 세 단계로 준다. 시안의 "확신 수준 82%" 같은 수치는 없어서 세 단계 막대로 보인다.
const EVIDENCE_LEVELS: Record<Recommendation['evidenceSufficiency'], { label: string; filled: number }> = {
  SUFFICIENT: { label: '근거 충분', filled: 3 },
  LIMITED: { label: '근거 적음', filled: 2 },
  INSUFFICIENT: { label: '근거 부족', filled: 1 },
}

/** 시안의 "확신 수준" 막대 자리. 세 칸 중 채운 칸 수와 글자로 근거 수준을 보인다. */
function EvidenceMeter({ sufficiency }: { sufficiency: Recommendation['evidenceSufficiency'] }) {
  const level = EVIDENCE_LEVELS[sufficiency]
  return (
    <div className="flex flex-col gap-1.5">
      <p className="text-xs text-[var(--instructor-text-subtle)]">근거 수준</p>
      <div aria-hidden className="grid grid-cols-3 gap-1">
        {[1, 2, 3].map((step) => (
          <span
            key={step}
            className={cn(
              'h-1.5 rounded-full',
              step <= level.filled ? 'bg-[var(--instructor-text-muted)]' : 'bg-[var(--instructor-disabled-bg)]',
            )}
          />
        ))}
      </div>
      <p className="text-xs text-[var(--instructor-text-subtle)]">{level.label}</p>
    </div>
  )
}

export { ConditionChip, DecisionChip, EvidenceMeter, GoalStatusChip, RecommendationTypeChip, SupportChip }
