import { ArrowRight } from 'lucide-react'
import { useState } from 'react'
import type { AgentRunOverview, Workflow } from '../api'
import { compact, count, duration, percent } from '../lib/format'
import {
  PATH_KEY,
  PATH_LABEL,
  PATHS,
  stageLabel,
  STATUS_LABEL,
  stepLabel,
  stepOrder,
  WORKFLOW_LABEL,
  WORKFLOWS,
} from '../lib/labels'
import { focusRing, pathColor, statusColor } from '../lib/tones'
import { RankedBars, SegmentBar } from './charts'
import { TraceId } from './flows'
import { Code, Empty, Panel, PathBadge, StatusIcon } from './primitives'

type Overview = AgentRunOverview

const thClass =
  'border-b border-[var(--ops-grid)] px-2 py-1.5 text-left text-xs font-semibold whitespace-nowrap text-[var(--ops-text-2)]'
const tdClass = 'px-2 py-2 align-middle text-[13px] text-[var(--ops-text-2)]'
const bigNumberClass = 'text-[34px] leading-none font-semibold tracking-tight'

/** AI 호출 수·토큰·걸린 시간. */
export function CallStat({ totals }: { totals: Overview['totals'] }) {
  const empty = totals.runs === 0
  return (
    <Panel title="AI 호출 · 시간" hint="AI·음성 호출 수와 입력·출력 토큰, 실행 한 번에 걸린 시간">
      <div className="grid grid-cols-2 gap-x-4 gap-y-3">
        <div>
          <p className="text-xs text-[var(--ops-muted)]">AI 호출</p>
          <p className={`${bigNumberClass} mt-1 text-[var(--ops-text)]`}>{compact(totals.aiCalls)}</p>
        </div>
        <div>
          <p className="text-xs text-[var(--ops-muted)]">걸린 시간 중앙값</p>
          <p className={`${bigNumberClass} mt-1 text-[var(--ops-text)]`}>{empty ? '—' : duration(totals.p50Ms)}</p>
        </div>
        <p className="ops-num text-xs text-[var(--ops-text-2)]">
          토큰 {compact(totals.inputTokens)} / {compact(totals.outputTokens)}
        </p>
        <p className="ops-num text-xs text-[var(--ops-text-2)]">p95 {empty ? '—' : duration(totals.p95Ms)}</p>
      </div>
    </Panel>
  )
}

function pathSegments(counts: Record<'normal' | 'retried' | 'guided' | 'failed', number>) {
  return PATHS.map((path) => ({
    key: path,
    label: PATH_LABEL[path],
    value: counts[PATH_KEY[path]],
    color: pathColor(path),
  }))
}

export function WorkflowPanel({ workflows }: { workflows: Overview['workflows'] }) {
  return (
    <Panel title="워크플로우별" hint="실행 수와 흐름 종류 비율">
      {workflows.length === 0 ? (
        <Empty>이 기간에 기록된 실행이 없습니다.</Empty>
      ) : (
        <ul className="flex flex-col gap-4">
          {workflows.map((row) => (
            <li key={row.workflow}>
              <p className="flex items-baseline justify-between gap-3">
                <span className="text-[13px] font-medium text-[var(--ops-text)]">{WORKFLOW_LABEL[row.workflow]}</span>
                <span className="ops-num text-xs text-[var(--ops-text-2)]">
                  {count(row.runs)}건 · 중앙값 {duration(row.p50Ms)} · p95 {duration(row.p95Ms)}
                </span>
              </p>
              <SegmentBar segments={pathSegments(row)} className="mt-2" />
              <p className="ops-num mt-1.5 flex flex-wrap gap-x-3 text-xs text-[var(--ops-muted)]">
                {PATHS.filter((path) => row[PATH_KEY[path]] > 0).map((path) => (
                  <span key={path}>
                    {PATH_LABEL[path]} {count(row[PATH_KEY[path]])}
                  </span>
                ))}
              </p>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  )
}

/** 여러 워크플로우를 함께 볼 때만 어느 워크플로우의 줄인지 붙인다. */
function WorkflowTag({ workflow, show }: { workflow: Workflow; show: boolean }) {
  if (!show) return null
  return <span className="text-xs text-[var(--ops-muted)]">· {WORKFLOW_LABEL[workflow]}</span>
}

type TransitionRow = Overview['transitions'][number]

/** 같은 워크플로우·단계의 판정끼리 묶고, 묶음은 흐름 순서로, 묶음 안은 많이 일어난 순서로 줄 세운다. */
function groupTransitions(rows: TransitionRow[]) {
  const groups = new Map<string, TransitionRow[]>()
  for (const row of rows) {
    const key = `${row.workflow}|${row.from}`
    groups.set(key, [...(groups.get(key) ?? []), row])
  }
  return [...groups.values()]
    .map((group) => ({
      workflow: group[0].workflow,
      from: group[0].from,
      total: group.reduce((sum, row) => sum + row.count, 0),
      rows: [...group].sort((a, b) => b.count - a.count),
    }))
    .sort(
      (a, b) =>
        WORKFLOWS.indexOf(a.workflow) - WORKFLOWS.indexOf(b.workflow) || stepOrder(a.from) - stepOrder(b.from),
    )
}

/**
 * 이 화면의 중심. 단계마다 어떤 판정이 몇 번 나왔고, 그 판정 때문에 실제로 어느 단계로 이어졌는지를 흐름 순서대로 보인다.
 * 예: 응답 검수가 REGENERATE 로 거절하면 응답 생성이 다시 돈다. 비율은 그 단계의 판정 가운데 차지하는 몫이다.
 */
export function TransitionPanel({ transitions, mixed }: { transitions: Overview['transitions']; mixed: boolean }) {
  const [blockedOnly, setBlockedOnly] = useState(false)
  const groups = groupTransitions(transitions)
    .map((group) => ({ ...group, rows: group.rows.filter((row) => !blockedOnly || row.status !== 'PASSED') }))
    .filter((group) => group.rows.length > 0)
  return (
    <Panel
      title="판정이 이어진 곳"
      hint="단계가 낸 판정과 그 때문에 실제로 이어진 다음 단계. 비율은 그 단계의 판정 가운데 차지하는 몫입니다."
      scroll
      action={
        <div role="group" aria-label="판정 범위" className="flex shrink-0 rounded-[4px] border border-[var(--ops-border)] p-0.5">
          {[
            { label: '전체', value: false },
            { label: '거절·실패만', value: true },
          ].map((option) => (
            <button
              key={option.label}
              type="button"
              aria-pressed={blockedOnly === option.value}
              onClick={() => setBlockedOnly(option.value)}
              className={`rounded px-2 py-1 text-xs ${focusRing} ${
                blockedOnly === option.value
                  ? 'bg-[var(--ops-panel-raised)] text-[var(--ops-text)]'
                  : 'text-[var(--ops-muted)] hover:text-[var(--ops-text-2)]'
              }`}
            >
              {option.label}
            </button>
          ))}
        </div>
      }
    >
      {groups.length === 0 ? (
        <Empty>{blockedOnly ? '거절되거나 실패한 단계가 없습니다.' : '이 기간에 기록된 실행이 없습니다.'}</Empty>
      ) : (
        <table className="w-full border-collapse">
          <thead className="sticky top-0 z-[1] bg-[var(--ops-panel)]">
            <tr>
              <th scope="col" className={thClass}>
                단계
              </th>
              <th scope="col" className={thClass}>
                판정
              </th>
              <th scope="col" className={thClass}>
                다음 단계
              </th>
              <th scope="col" className={`${thClass} text-right`}>
                건수
              </th>
              <th scope="col" className={`${thClass} w-[22%] text-right`}>
                비율
              </th>
            </tr>
          </thead>
          {groups.map((group) => (
            <tbody key={`${group.workflow}-${group.from}`} className="border-b border-[var(--ops-grid)] last:border-b-0">
              {group.rows.map((row, index) => (
                <tr key={`${row.status}-${row.verdict}-${row.to}`}>
                  {index === 0 && (
                    <th
                      scope="rowgroup"
                      rowSpan={group.rows.length}
                      className={`${tdClass} text-left align-top font-medium text-[var(--ops-text)]`}
                    >
                      {stepLabel(group.from)}
                      {mixed && (
                        <span className="block text-xs font-normal text-[var(--ops-muted)]">
                          {WORKFLOW_LABEL[group.workflow]}
                        </span>
                      )}
                    </th>
                  )}
                  <td className={`${tdClass} py-1.5`}>
                    <span className="inline-flex flex-wrap items-center gap-1.5">
                      <StatusIcon status={row.status} size={13} />
                      <span className="sr-only">{STATUS_LABEL[row.status]}</span>
                      {row.verdict ? <Code>{row.verdict}</Code> : STATUS_LABEL[row.status]}
                    </span>
                  </td>
                  <td className={`${tdClass} py-1.5`}>
                    <span className="inline-flex items-center gap-1.5">
                      <ArrowRight aria-hidden size={13} className="text-[var(--ops-muted)]" />
                      <span className={row.status === 'PASSED' ? '' : 'text-[var(--ops-text)]'}>{stepLabel(row.to)}</span>
                    </span>
                  </td>
                  <td className={`${tdClass} ops-num py-1.5 text-right text-[var(--ops-text)]`}>{count(row.count)}</td>
                  <td className={`${tdClass} py-1.5`}>
                    <span className="flex items-center justify-end gap-2">
                      <span aria-hidden className="h-1.5 w-full max-w-[90px] rounded-[2px] bg-[var(--ops-track)]">
                        <span
                          className="block h-full rounded-[2px]"
                          style={{
                            width: `${Math.max(2, (row.count / group.total) * 100)}%`,
                            background: statusColor(row.status),
                          }}
                        />
                      </span>
                      <span className="ops-num w-9 text-right text-xs">{percent(row.count, group.total)}</span>
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          ))}
        </table>
      )}
    </Panel>
  )
}

export function StepPanel({ steps, mixed }: { steps: Overview['steps']; mixed: boolean }) {
  return (
    <Panel title="단계별 호출 결과" hint="AI·음성 호출 단계의 통과·거절·실패와 시간·토큰">
      {steps.length === 0 ? (
        <Empty>이 기간에 기록된 호출이 없습니다.</Empty>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse">
            <thead className="sticky top-0 z-[1] bg-[var(--ops-panel)]">
              <tr>
                <th scope="col" className={thClass}>
                  단계
                </th>
                <th scope="col" className={`${thClass} text-right`}>
                  호출
                </th>
                <th scope="col" className={thClass}>
                  통과 · 거절 · 실패
                </th>
                <th scope="col" className={`${thClass} text-right`}>
                  평균
                </th>
                <th scope="col" className={`${thClass} text-right`}>
                  p95
                </th>
                <th scope="col" className={`${thClass} text-right`}>
                  토큰
                </th>
              </tr>
            </thead>
            <tbody>
              {steps.map((row) => (
                <tr key={`${row.workflow}-${row.step}`} className="border-b border-[var(--ops-grid)] last:border-b-0">
                  <th scope="row" className={`${tdClass} text-left font-medium text-[var(--ops-text)]`}>
                    {stepLabel(row.step)} <WorkflowTag workflow={row.workflow} show={mixed} />
                  </th>
                  <td className={`${tdClass} ops-num text-right`}>{count(row.calls)}</td>
                  <td className={`${tdClass} min-w-[150px]`}>
                    <SegmentBar
                      segments={(['PASSED', 'REJECTED', 'FAILED'] as const).map((status) => ({
                        key: status,
                        label: STATUS_LABEL[status],
                        value: { PASSED: row.passed, REJECTED: row.rejected, FAILED: row.failed }[status],
                        color: statusColor(status),
                      }))}
                    />
                    <span className="ops-num mt-1 block text-xs text-[var(--ops-muted)]">
                      {count(row.passed)} · {count(row.rejected)} · {count(row.failed)}
                    </span>
                  </td>
                  <td className={`${tdClass} ops-num text-right`}>{duration(row.avgMs)}</td>
                  <td className={`${tdClass} ops-num text-right`}>{duration(row.p95Ms)}</td>
                  <td className={`${tdClass} ops-num text-right`}>{compact(row.inputTokens + row.outputTokens)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  )
}

export function CodePanel({ codes }: { codes: Overview['codes'] }) {
  return (
    <Panel title="거절·실패 사유" hint="통과하지 못한 단계에 붙은 사유 코드">
      {codes.length === 0 ? (
        <Empty>사유 코드가 붙은 거절·실패가 없습니다.</Empty>
      ) : (
        <RankedBars
          rows={codes.slice(0, 8).map((row) => ({
            key: `${row.workflow}-${row.step}-${row.code}`,
            value: row.count,
            valueText: `${count(row.count)}건`,
            label: (
              <span className="flex flex-wrap items-center gap-x-1.5 gap-y-1">
                <Code>{row.code}</Code>
                <span className="text-xs text-[var(--ops-muted)]">{stepLabel(row.step)}</span>
              </span>
            ),
          }))}
        />
      )}
    </Panel>
  )
}

/** 실패 흐름의 원인. 어느 단계의 어떤 판정·사유 때문에 정상 결과 없이 끝났는지 많이 일어난 순서로 보인다. */
export function FailureCausePanel({
  endings,
  onOpen,
  className = '',
}: {
  endings: Overview['endings']
  onOpen: (runId: string) => void
  className?: string
}) {
  const rows = endings.filter((row) => row.path === 'GUIDED' || row.path === 'FAILED')
  return (
    <Panel
      title="실패 원인"
      hint="어느 단계의 어떤 판정·사유 때문에 정상 결과 없이 끝났는지"
      className={className}
      scroll
    >
      {rows.length === 0 ? (
        <Empty>정상 결과 없이 끝난 실행이 없습니다.</Empty>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse">
            <thead className="sticky top-0 z-[1] bg-[var(--ops-panel)]">
              <tr>
                <th scope="col" className={thClass}>
                  흐름
                </th>
                <th scope="col" className={thClass}>
                  원인 단계 · 사유
                </th>
                <th scope="col" className={thClass}>
                  끝난 결과
                </th>
                <th scope="col" className={`${thClass} text-right`}>
                  건수
                </th>
                <th scope="col" className={thClass}>
                  예시 Trace ID
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr
                  key={`${row.workflow}-${row.path}-${row.result}-${row.endedBy}-${row.reason}`}
                  className="border-b border-[var(--ops-grid)] last:border-b-0"
                >
                  <td className={tdClass}>
                    <PathBadge path={row.path} />
                  </td>
                  <td className={tdClass}>
                    <span className="flex flex-wrap items-center gap-1.5">
                      {row.endedBy ? stepLabel(row.endedBy) : '—'}
                      {row.reason && <Code>{row.reason}</Code>}
                    </span>
                  </td>
                  <td className={tdClass}>{stepLabel(row.result)}</td>
                  <td className={`${tdClass} ops-num text-right text-[var(--ops-text)]`}>{count(row.count)}</td>
                  <td className={`${tdClass} whitespace-nowrap`}>
                    {row.recentRunIds[0] ? <TraceId runId={row.recentRunIds[0]} onOpen={onOpen} /> : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  )
}

export function StagePanel({ stages }: { stages: Overview['stages'] }) {
  const rows = stages.filter((row) => row.avgMs > 0 || row.p95Ms > 0).slice(0, 8)
  return (
    <Panel title="역할극 턴의 구간별 시간" hint="안쪽 구간을 뺀 자체 시간의 평균">
      {rows.length === 0 ? (
        <Empty>이 기간에 기록된 역할극 턴이 없습니다.</Empty>
      ) : (
        <RankedBars
          rows={rows.map((row) => ({
            key: row.stage,
            value: row.avgMs,
            valueText: `${duration(row.avgMs)} · p95 ${duration(row.p95Ms)}`,
            label: stageLabel(row.stage),
          }))}
        />
      )}
    </Panel>
  )
}

