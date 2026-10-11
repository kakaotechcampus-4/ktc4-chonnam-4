import { cn } from '@/lib/utils'

type WizardStep<K extends string> = {
  key: K
  label: string
}

/**
 * 마법사 단계 표시 (시안 T-ACT-01 의 "대상 › 유형 › 목표 …" 띠).
 * 단계 목록은 화면이 배열로 넘긴다. 단계를 늘리려면 그 배열에 항목을 더하면 된다(ADR 2026-10-03 D2).
 */
function WizardStepper<K extends string>({ steps, current }: { steps: WizardStep<K>[]; current: K }) {
  const currentIndex = steps.findIndex((step) => step.key === current)
  return (
    <ol aria-label="진행 단계" className="flex items-center gap-2 py-3">
      {steps.map((step, index) => (
        <li key={step.key} className="flex items-center gap-2">
          {index > 0 && (
            <span aria-hidden className="text-xs text-[var(--instructor-text-disabled)]">
              ›
            </span>
          )}
          <span
            aria-current={index === currentIndex ? 'step' : undefined}
            className={cn(
              'rounded-full px-3.5 py-1.5 text-xs',
              index === currentIndex
                ? 'bg-[var(--instructor-primary)] font-semibold text-[var(--instructor-primary-foreground)]'
                : index < currentIndex
                  ? 'bg-[var(--instructor-nav-active)] text-[var(--instructor-text)]'
                  : 'bg-[var(--instructor-surface-muted)] text-[var(--instructor-text-disabled)]'
            )}
          >
            {step.label}
          </span>
        </li>
      ))}
    </ol>
  )
}

export { WizardStepper }
export type { WizardStep }
