import type { RunPath, StepStatus } from '../api'
import { PATH_KEY } from './labels'

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

export const focusRing =
  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--ops-focus)]'
