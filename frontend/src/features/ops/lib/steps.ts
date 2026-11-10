import type { RunPath, StepStatus } from '../api'

/**
 * 단계가 낸 판정이 실행에서 어떤 역할을 했는지. 같은 "거절"이라도 다시 돌아 정상 결과로 이어졌으면 재시도이고,
 * 거기서 실행이 끝났으면 멈춤(안내로 종료)이나 실패(오류)다. 실패 흐름의 거절을 재시도 색으로 칠하면 "다시 할 것"처럼 읽힌다.
 */
export type StepTone = 'passed' | 'retry' | 'guided' | 'failed'

type ToneStep = { step: string; status: StepStatus; ledTo?: string | null }

export function stepTones(steps: ToneStep[], path: RunPath): StepTone[] {
  const recovered = path === 'NORMAL' || path === 'RETRIED'
  return steps.map((step, index) => {
    if (step.status === 'PASSED') return 'passed'
    if (recovered) return 'retry'
    const repeatedLater = steps.slice(index + 1).some((later) => later.step === step.step)
    const loopsBack = !!step.ledTo && steps.slice(0, index).some((earlier) => earlier.step === step.ledTo)
    if (repeatedLater || loopsBack) return 'retry'
    return path === 'GUIDED' ? 'guided' : 'failed'
  })
}

export const TONE_LABEL: Record<StepTone, string> = {
  passed: '통과',
  retry: '거절 → 다시 시도',
  guided: '멈춤 → 안내로 종료',
  failed: '실패 → 오류로 종료',
}
