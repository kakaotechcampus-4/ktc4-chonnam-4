import { useState } from 'react'
import type { ReviewConditions, ReviewScenario } from '../api'
import { ButtonLabel } from './ButtonLabel'
import { Notice } from './Feedback'
import { cardClass, outlineButtonClass, primaryButtonClass, badgeClass } from './styles'
import { cn } from '@/lib/utils'

/**
 * 승인 확인(시안 T-SCN-04). 승인하면 바로 이 활동에 붙고 뗄 수 없으므로, 아동에게 가는 내용을 검토했다는 확인을 받은 뒤에만 승인한다.
 * 보여 주는 시나리오는 고친 내용을 덮은 모습이라, 보이는 내용과 승인되는 내용이 같다.
 */
function ApprovalConfirmPanel({
  scenario,
  conditions,
  edited,
  change,
  isPending,
  error,
  onBack,
  onApprove,
}: {
  scenario: ReviewScenario
  conditions: ReviewConditions
  edited: boolean
  change: string
  isPending: boolean
  error: string | null
  onBack: () => void
  onApprove: () => void
}) {
  const [checked, setChecked] = useState(false)
  const microGoals = scenario.microGoals.map((g) => g.description)
  const forbidden = conditions.forbiddenExpressions
  const rows: [string, string][] = [
    ['목표', conditions.title],
    ['작은 목표', microGoals.length > 0 ? `${microGoals.join(' · ')} (${microGoals.length}개)` : '없음'],
    ['금지 표현', forbidden.length > 0 ? forbidden.join(', ') : '없음'],
    ['변경 사항', change],
  ]
  const version = edited ? `v${scenario.scenarioVersion} → v${scenario.scenarioVersion + 1}` : `v${scenario.scenarioVersion}`

  return (
    <section aria-label="시나리오 승인 확인" className="mx-auto flex w-full max-w-[760px] flex-col gap-4">
      <div className={cn(cardClass, 'flex flex-col gap-4 p-8')}>
        <div className="flex items-start justify-between gap-3">
          <div>
            <h2 className="text-xl font-semibold">{scenario.title}</h2>
            <p className="mt-1 text-xs text-[var(--instructor-text-subtle)]">
              {version} · {scenario.scenarioLevel}
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
        승인하면 이 활동에 바로 배정되고 되돌릴 수 없습니다. 고친 내용은 새 버전으로 저장되고, 원본은 그대로 남아 다른 활동에서 다시 쓸 수 있습니다.
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
          {edited ? '수정으로 돌아가기' : '검토로 돌아가기'}
        </button>
        <button type="button" onClick={onApprove} disabled={!checked || isPending} className={cn(primaryButtonClass, 'flex-1')}>
          <ButtonLabel pending={isPending} label="승인" pendingLabel="승인하는 중..." />
        </button>
      </div>
    </section>
  )
}

export { ApprovalConfirmPanel }
