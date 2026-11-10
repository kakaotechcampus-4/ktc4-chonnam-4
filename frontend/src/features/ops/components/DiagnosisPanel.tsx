import { CircleCheck, Info, OctagonAlert, TriangleAlert } from 'lucide-react'
import type { AgentRunDiagnosis, DiagnosisSeverity } from '../api'
import { count, dayClock } from '../lib/format'
import { findingHint, findingTitle, SEVERITY_LABEL } from '../lib/diagnosis'
import { TraceId } from './flows'
import { Panel } from './primitives'

const SEVERITY_STYLE: Record<DiagnosisSeverity, { Icon: typeof Info; color: string }> = {
  CRITICAL: { Icon: OctagonAlert, color: 'var(--ops-path-failed)' },
  WARNING: { Icon: TriangleAlert, color: 'var(--ops-path-retried)' },
  INFO: { Icon: Info, color: 'var(--ops-path-guided)' },
}

const MIN_RUNS = 20

function rate(value: number | null): string {
  return value === null ? '—' : `${Math.round(value * 100)}%`
}

/**
 * 자동 진단. 서버가 이번 기간과 바로 앞 같은 길이 기간을 비교해 규칙으로 뽑은 "먼저 볼 곳"을 심각한 순서로 보인다.
 * 줄마다 볼 곳과 예시 Trace ID 를 붙인다. 표본이 적으면 판단하지 않았다고 알린다.
 */
export function DiagnosisPanel({
  diagnosis,
  runs,
  onOpen,
}: {
  diagnosis: AgentRunDiagnosis
  runs: number
  onOpen: (runId: string) => void
}) {
  const findings = diagnosis.findings
  return (
    <Panel
      title="자동 진단"
      hint={`이번 기간을 바로 앞 같은 길이 기간(${dayClock(diagnosis.previousFrom)}~${dayClock(diagnosis.previousTo)})과 비교해 규칙으로 뽑았습니다. 실행 ${MIN_RUNS}건 미만이면 판단하지 않고, 앞 기간이 ${MIN_RUNS}건 미만이면 비교하는 규칙은 건너뜁니다. AI 를 부르지 않습니다.`}
      className="md:col-span-2 xl:col-span-4"
      action={
        <p className="ops-num shrink-0 text-xs text-[var(--ops-muted)]">
          실패 비율 <span className="text-[var(--ops-text)]">{rate(diagnosis.failureRate)}</span> · 앞 기간{' '}
          {rate(diagnosis.previousFailureRate)}({count(diagnosis.previousRuns)}건)
        </p>
      }
    >
      {findings.length === 0 ? (
        <p className="flex items-center gap-2 py-2 text-[13px] text-[var(--ops-text-2)]">
          <CircleCheck aria-hidden size={16} style={{ color: 'var(--ops-path-normal)' }} />
          {runs < MIN_RUNS
            ? `실행이 ${MIN_RUNS}건 미만이라 판단하지 않았습니다(이번 기간 ${count(runs)}건).`
            : '특이 사항이 없습니다. 실패 비율·단계별 거절·원인 급증·응답 시간이 모두 기준 안입니다.'}
        </p>
      ) : (
        <ul className="flex flex-col divide-y divide-[var(--ops-grid)]">
          {findings.map((finding, index) => {
            const { Icon, color } = SEVERITY_STYLE[finding.severity]
            return (
              <li key={index} className="grid gap-x-3 gap-y-1 py-2 first:pt-0 last:pb-0 md:grid-cols-[72px_minmax(0,1fr)_auto]">
                <span className="inline-flex items-center gap-1.5 self-start text-xs font-semibold" style={{ color }}>
                  <Icon aria-hidden size={14} strokeWidth={2.2} />
                  <span className="text-[var(--ops-text)]">{SEVERITY_LABEL[finding.severity]}</span>
                </span>
                <div className="min-w-0">
                  <p className="text-[13px] font-medium text-[var(--ops-text)]">{findingTitle(finding)}</p>
                  <p className="mt-0.5 text-xs text-[var(--ops-text-2)]">{findingHint(finding)}</p>
                </div>
                {finding.sampleRunIds.length > 0 && (
                  <span className="flex flex-wrap items-center gap-x-2 self-start text-xs text-[var(--ops-muted)]">
                    예시
                    {finding.sampleRunIds.map((runId) => (
                      <TraceId key={runId} runId={runId} onOpen={onOpen} />
                    ))}
                  </span>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </Panel>
  )
}
