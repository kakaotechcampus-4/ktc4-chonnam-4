import { describe, expect, it } from 'vitest'
import type { AgentRunSpan } from '../api'
import { mergeSpans } from './spans'
import { stepTones } from './steps'

describe('단계가 실행에서 한 역할', () => {
  it('정상 결과로 끝난 실행의 거절은 다시 시도로 본다', () => {
    const steps = [
      { step: 'RESPONSE_GENERATION', status: 'PASSED' as const },
      { step: 'RESPONSE_EVALUATION', status: 'REJECTED' as const, ledTo: 'RESPONSE_GENERATION' },
      { step: 'RESPONSE_GENERATION', status: 'PASSED' as const },
      { step: 'RESPONSE_EVALUATION', status: 'PASSED' as const },
    ]
    expect(stepTones(steps, 'RETRIED')).toEqual(['passed', 'retry', 'passed', 'passed'])
  })

  it('안내로 끝난 실행에서 거기서 멈춘 거절은 다시 시도가 아니라 멈춤이다', () => {
    const steps = [
      { step: 'INPUT_VALIDATION', status: 'REJECTED' as const, ledTo: 'TURN_RESOLUTION' },
      { step: 'TURN_RESOLUTION', status: 'REJECTED' as const, ledTo: 'REINPUT_REQUIRED' },
      { step: 'RESULT', status: 'REJECTED' as const },
    ]
    expect(stepTones(steps, 'GUIDED')).toEqual(['guided', 'guided', 'guided'])
  })

  it('오류로 끝난 실행에서도 앞에서 다시 돈 거절은 다시 시도로, 마지막 실패는 실패로 본다', () => {
    const steps = [
      { step: 'RESPONSE_EVALUATION', status: 'REJECTED' as const, ledTo: 'RESPONSE_GENERATION' },
      { step: 'RESPONSE_GENERATION', status: 'PASSED' as const },
      { step: 'RESPONSE_EVALUATION', status: 'FAILED' as const, ledTo: 'TURN_RESOLUTION' },
      { step: 'RESULT', status: 'FAILED' as const },
    ]
    expect(stepTones(steps, 'FAILED')).toEqual(['retry', 'passed', 'failed', 'failed'])
  })
})

describe('시간 구간 합치기', () => {
  const span = (id: number, parent: number, stage: string, startMs: number, durationMs: number, operation: string | null = null): AgentRunSpan => ({
    id,
    parent,
    stage,
    operation,
    attempt: 1,
    startMs,
    durationMs,
    selfMs: durationMs,
    outcome: 'COMPLETED',
  })

  it('같은 부모 아래 이름 없는 같은 구간이 잇달아 돌면 한 줄로 합치고 횟수를 붙인다', () => {
    const rows = mergeSpans([
      span(1, 0, 'HTTP_REQUEST', 0, 200),
      span(2, 1, 'CHECKPOINT_LOOKUP', 10, 12),
      span(3, 1, 'CHECKPOINT_LOOKUP', 25, 48),
      span(4, 1, 'LLM_CALL', 80, 100, 'INPUT_VALIDATION'),
      span(5, 1, 'LLM_CALL', 180, 10, 'CAUSE_ANALYSIS'),
    ])
    expect(rows.map((row) => [row.span.stage, row.span.operation, row.repeats, row.durationMs, row.startMs, row.endMs])).toEqual([
      ['HTTP_REQUEST', null, 1, 200, 0, 200],
      ['CHECKPOINT_LOOKUP', null, 2, 60, 10, 73],
      ['LLM_CALL', 'INPUT_VALIDATION', 1, 100, 80, 180],
      ['LLM_CALL', 'CAUSE_ANALYSIS', 1, 10, 180, 190],
    ])
  })

  it('안쪽 구간이 있는 구간은 합치지 않는다', () => {
    const rows = mergeSpans([
      span(1, 0, 'HTTP_REQUEST', 0, 100),
      span(2, 1, 'CONTEXT_PREPARATION', 0, 10),
      span(3, 1, 'CONTEXT_PREPARATION', 10, 10),
      span(4, 3, 'LLM_CALL', 12, 5, 'CAUSE_ANALYSIS'),
    ])
    expect(rows.map((row) => row.repeats)).toEqual([1, 1, 1, 1])
  })
})
