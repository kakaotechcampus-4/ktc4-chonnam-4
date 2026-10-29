import { Fragment } from 'react'
import { MaterialIcon } from './MaterialIcon'
import { cn } from '@/lib/utils'

type WizardStep<K extends string> = {
  key: K
  label: string
}

/**
 * 마법사 단계 표시 (시안 T-ACT-01 의 "대상 › 유형 › 목표 …" 띠). 자리는 와이어프레임처럼 헤더 아래 띠에 두고,
 * 모양은 디자인 시스템의 Stepper 를 따른다: 지난 단계는 체크, 지금 단계는 채운 번호(28), 남은 단계는 테두리 번호(내용 28 + 테두리 = 30),
 * 사이는 길이 70·두께 2 선으로 잇는다.
 * 단계 목록은 화면이 배열로 넘긴다. 단계를 늘리려면 그 배열에 항목을 더하면 된다(ADR 2026-10-03 D2).
 */
function WizardStepper<K extends string>({ steps, current }: { steps: WizardStep<K>[]; current: K }) {
  const currentIndex = steps.findIndex((step) => step.key === current)
  return (
    <ol aria-label="진행 단계" className="flex items-center gap-2 py-4">
      {steps.map((step, index) => {
        const isDone = index < currentIndex
        const isCurrent = index === currentIndex
        return (
          <Fragment key={step.key}>
            {index > 0 && (
              <li
                aria-hidden
                className={cn(
                  'h-0.5 w-[70px]',
                  index <= currentIndex ? 'bg-[var(--instructor-primary)]' : 'bg-[var(--instructor-border)]'
                )}
              />
            )}
            <li aria-current={isCurrent ? 'step' : undefined} className="flex items-center gap-2">
              <span
                aria-hidden
                className={cn(
                  'box-content flex size-7 items-center justify-center rounded-full text-sm',
                  isDone || isCurrent
                    ? 'bg-[var(--instructor-primary)] text-[var(--instructor-primary-foreground)]'
                    : 'border border-[var(--instructor-border-strong)] text-[var(--instructor-text-subtle)]'
                )}
              >
                {isDone ? <MaterialIcon name="check" className="size-[18px]" /> : index + 1}
              </span>
              <span
                className={cn(
                  'text-sm',
                  isCurrent && 'font-semibold',
                  isDone || isCurrent ? 'text-[var(--instructor-text)]' : 'text-[var(--instructor-text-subtle)]'
                )}
              >
                {step.label}
              </span>
            </li>
          </Fragment>
        )
      })}
    </ol>
  )
}

export { WizardStepper }
export type { WizardStep }
