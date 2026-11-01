import { useState } from 'react'
import type { LearningGoal, Scenario } from '../api'
import { ButtonLabel } from './ButtonLabel'
import { Notice } from './Feedback'
import { cardClass, outlineButtonClass, primaryButtonClass, badgeClass } from './styles'
import { cn } from '@/lib/utils'

/**
 * 승인 확인(시안 T-SCN-04). 승인한 시나리오는 활동에서 뗄 수 없으므로, 아동에게 가는 내용을 검토했다는 확인을 받은 뒤에만 승인한다.
 * 후보 화면과 초안 수정 화면이 같은 패널을 쓴다.
 */
function ApprovalConfirmPanel({
  scenario,
  goal,
  change,
  backLabel,
  isPending,
  error,
  onBack,
  onApprove,
}: {
  scenario: Scenario
  goal: LearningGoal | undefined
  change: string
  backLabel: string
  isPending: boolean
  error: string | null
  onBack: () => void
  onApprove: () => void
}) {
  const [checked, setChecked] = useState(false)
  const microGoals = scenario.microGoals.map((g) => g.description)
  const forbidden = goal?.forbiddenExpressions ?? []
  const rows: [string, string][] = [
    ['목표', goal?.title ?? '불러오는 중...'],
    ['작은 목표', microGoals.length > 0 ? `${microGoals.join(' · ')} (${microGoals.length}개)` : '없음'],
    ['금지 표현', forbidden.length > 0 ? forbidden.join(', ') : '없음'],
    ['변경 사항', change],
  ]

  return (
    <section aria-label="시나리오 승인 확인" className="mx-auto flex w-full max-w-[760px] flex-col gap-4">
      <div className={cn(cardClass, 'flex flex-col gap-4 p-8')}>
        <div className="flex items-start justify-between gap-3">
          <div>
            <h2 className="text-xl font-semibold">{scenario.title}</h2>
            <p className="mt-1 text-xs text-[var(--instructor-text-subtle)]">
              v{scenario.version} · {scenario.scenarioLevel}
            </p>
          </div>
          <span className={cn(badgeClass, 'bg-[var(--instructor-info-bg)] text-[var(--instructor-info-fg)]')}>승인 대기</span>
        </div>
        <dl className="overflow-hidden rounded-[var(--instructor-radius-control)] border border-[var(--instructor-border)]">
          {rows.map(([label, value]) => (
            <div key={label} className="grid grid-cols-[120px_minmax(0,1fr)] border-b border-[var(--instructor-border-subtle)] px-4 py-3 text-sm last:border-b-0">
              <dt className="text-[var(--instructor-text-subtle)]">{label}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
      </div>
      <Notice tone="warning">
        승인 후 내용을 수정하면 새 판으로 다시 승인해야 합니다. 이미 배정된 활동에는 승인 시점의 판이 유지됩니다.
      </Notice>
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={checked} onChange={(event) => setChecked(event.target.checked)} className="size-4" />
        아동에게 노출되는 내용을 검토했습니다.
      </label>
      {error && (
        <Notice tone="error" role="alert">
          {error}
        </Notice>
      )}
      <div className="flex gap-3">
        <button type="button" onClick={onBack} disabled={isPending} className={outlineButtonClass}>
          {backLabel}
        </button>
        <button type="button" onClick={onApprove} disabled={!checked || isPending} className={cn(primaryButtonClass, 'flex-1')}>
          <ButtonLabel pending={isPending} label="승인" pendingLabel="승인하는 중..." />
        </button>
      </div>
    </section>
  )
}

export { ApprovalConfirmPanel }
