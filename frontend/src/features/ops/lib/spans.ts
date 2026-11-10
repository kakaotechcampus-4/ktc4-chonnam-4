import type { AgentRunSpan } from '../api'

export type SpanRow = { span: AgentRunSpan; repeats: number; startMs: number; endMs: number; durationMs: number }

/**
 * 같은 부모 아래에서 이름 없는 같은 구간이 잇달아 돈 것은 한 줄로 합친다(예: 이전 결과 조회를 두 번 함).
 * 서버가 구간을 단계 이름 없이 남겨 줄마다 같은 이름이 반복되면 무엇이 다른지 읽을 수 없어서다. 막대는 첫 시작부터 마지막 끝까지 그린다.
 */
export function mergeSpans(spans: AgentRunSpan[]): SpanRow[] {
  const parents = new Set(spans.map((span) => span.parent))
  const rows: SpanRow[] = []
  for (const span of spans) {
    const last = rows[rows.length - 1]
    const leaf = !parents.has(span.id)
    if (
      last &&
      leaf &&
      !span.operation &&
      !last.span.operation &&
      !parents.has(last.span.id) &&
      last.span.parent === span.parent &&
      last.span.stage === span.stage &&
      (last.span.outcome ?? 'COMPLETED') === (span.outcome ?? 'COMPLETED')
    ) {
      last.repeats += 1
      last.durationMs += span.durationMs
      last.endMs = Math.max(last.endMs, span.startMs + span.durationMs)
      continue
    }
    rows.push({ span, repeats: 1, startMs: span.startMs, endMs: span.startMs + span.durationMs, durationMs: span.durationMs })
  }
  return rows
}
