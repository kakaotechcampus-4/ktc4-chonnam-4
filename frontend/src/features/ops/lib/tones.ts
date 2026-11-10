import type { RunPath, StepStatus } from '../api'
import { PATH_KEY } from './labels'
import type { StepTone } from './steps'

/**
 * 흐름 종류·단계 상태의 색(index.css 의 --ops-path-*). 차트의 채움과 아이콘에만 쓰고 글자는 본문 색을 쓴다.
 * 단계 상태는 흐름 종류와 같은 말로 읽히도록 같은 색을 쓴다: 통과 = 한 번에 통과, 거절 = 재시도, 실패 = 오류.
 */
export function pathColor(path: RunPath): string {
  return `var(--ops-path-${PATH_KEY[path]})`
}

const STATUS_COLOR: Record<StepStatus, string> = {
  PASSED: 'var(--ops-path-normal)',
  REJECTED: 'var(--ops-path-retried)',
  FAILED: 'var(--ops-path-failed)',
}

export function statusColor(status: StepStatus): string {
  return STATUS_COLOR[status]
}

/** 단계가 실행에서 한 역할의 색. 재시도는 노랑, 안내로 멈춤은 파랑, 실패로 끝남은 빨강으로 흐름 종류 색과 같다. */
const TONE_COLOR: Record<StepTone, string> = {
  passed: 'var(--ops-path-normal)',
  retry: 'var(--ops-path-retried)',
  guided: 'var(--ops-path-guided)',
  failed: 'var(--ops-path-failed)',
}

export function toneColor(tone: StepTone): string {
  return TONE_COLOR[tone]
}

/** 칩·끝 상자·구역 머리에 까는 옅은 바탕. 어두운 배경에서 테두리만으로는 잘 안 보여서 같은 색을 섞는다. */
export function tint(color: string, amount = 16, base = 'var(--ops-panel-raised)'): string {
  return `color-mix(in srgb, ${color} ${amount}%, ${base})`
}

export const focusRing =
  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--ops-focus)]'
