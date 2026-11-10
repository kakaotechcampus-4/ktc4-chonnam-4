import { ArrowRight, Check, CircleCheck, Copy, TriangleAlert } from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'
import type { AgentRun, AgentRunFlow, AgentRunOverview, Outcome, RunPath } from '../api'
import { compact, count, dayClock, duration, percent } from '../lib/format'
import {
  OUTCOME_HINT,
  OUTCOME_LABEL,
  OUTCOME_PATHS,
  PATH_KEY,
  PATH_LABEL,
  STATUS_LABEL,
  stepLabel,
  WORKFLOW_LABEL,
} from '../lib/labels'
import { focusRing, pathColor, statusColor } from '../lib/tones'
import { Sparkline } from './charts'
import { Code, Empty, Panel, PathBadge, PathIcon, StatusIcon } from './primitives'

type Overview = AgentRunOverview

const OUTCOME_COLOR: Record<Outcome, string> = {
  SUCCESS: 'var(--ops-path-normal)',
  FAILURE: 'var(--ops-path-failed)',
}

const OUTCOME_ICON = { SUCCESS: CircleCheck, FAILURE: TriangleAlert } as const

function outcomeCount(counts: Record<'normal' | 'retried' | 'guided' | 'failed', number>, outcome: Outcome) {
  return OUTCOME_PATHS[outcome].reduce((sum, path) => sum + counts[PATH_KEY[path]], 0)
}

/** 성공·실패 실행 수(이미지의 큰 숫자 패널). 아래에는 두 가지 세부 흐름 건수와 구간별 추세선을 둔다. */
export function OutcomeStat({
  outcome,
  totals,
  series,
}: {
  outcome: Outcome
  totals: Overview['totals']
  series: Overview['series']
}) {
  const Icon = OUTCOME_ICON[outcome]
  const value = outcomeCount(totals, outcome)
  return (
    <Panel title={outcome === 'SUCCESS' ? '성공 실행' : '실패 실행'} hint={OUTCOME_HINT[outcome]}>
      <div className="flex h-full flex-col justify-between gap-2">
        <div className="flex items-baseline justify-between gap-3">
          <p className="flex items-baseline gap-2">
            <Icon aria-hidden size={20} strokeWidth={2.2} style={{ color: OUTCOME_COLOR[outcome] }} className="self-center" />
            <span className="text-[34px] leading-none font-semibold tracking-tight" style={{ color: OUTCOME_COLOR[outcome] }}>
              {compact(value)}
            </span>
          </p>
          <p className="ops-num text-sm text-[var(--ops-text-2)]">
            {percent(value, totals.runs)} <span className="text-xs text-[var(--ops-muted)]">/ 전체 {compact(totals.runs)}</span>
          </p>
        </div>
        <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-[var(--ops-text-2)]">
          {OUTCOME_PATHS[outcome].map((path) => (
            <li key={path} className="inline-flex items-center gap-1.5">
              <PathIcon path={path} size={12} />
              {PATH_LABEL[path]} <span className="ops-num text-[var(--ops-text)]">{count(totals[PATH_KEY[path]])}</span>
            </li>
          ))}
        </ul>
        <Sparkline values={series.map((bucket) => outcomeCount(bucket, outcome))} color={OUTCOME_COLOR[outcome]} />
      </div>
    </Panel>
  )
}

/** 성공·실패를 나누는 구역(Grafana 의 행 머리). 왼쪽 색 띠·아이콘·건수로 어느 구역인지 바로 보이게 한다. */
export function OutcomeSection({
  outcome,
  value,
  total,
  children,
}: {
  outcome: Outcome
  value: number
  total: number
  children: ReactNode
}) {
  const Icon = OUTCOME_ICON[outcome]
  return (
    <section aria-label={OUTCOME_LABEL[outcome]} className="flex flex-col gap-2">
      <header
        className="flex flex-wrap items-baseline gap-x-3 gap-y-1 rounded-[4px] border border-l-4 border-[var(--ops-border)] bg-[var(--ops-panel)] px-3 py-2"
        style={{ borderLeftColor: OUTCOME_COLOR[outcome] }}
      >
        <h2 className="inline-flex items-center gap-2 text-sm font-semibold text-[var(--ops-text)]">
          <Icon aria-hidden size={16} strokeWidth={2.2} style={{ color: OUTCOME_COLOR[outcome] }} />
          {OUTCOME_LABEL[outcome]}
        </h2>
        <span className="ops-num text-sm text-[var(--ops-text-2)]">
          {count(value)}건 · {percent(value, total)}
        </span>
        <span className="text-xs text-[var(--ops-muted)]">{OUTCOME_HINT[outcome]}</span>
      </header>
      <div className="grid grid-cols-1 gap-2 md:grid-cols-2 xl:grid-cols-4">{children}</div>
    </section>
  )
}

function shortId(runId: string): string {
  return `${runId.slice(0, 8)}…${runId.slice(-4)}`
}

/**
 * 실행 번호(역할극 턴은 X-Trace-Id). 누르면 상세를 열고, 옆 단추로 전체 번호를 복사한다. 서버 로그를 grep 할 때 그대로 붙여 넣는다.
 */
export function TraceId({ runId, onOpen, full = false }: { runId: string; onOpen: (runId: string) => void; full?: boolean }) {
  const [copied, setCopied] = useState(false)
  useEffect(() => {
    if (!copied) return
    const timer = window.setTimeout(() => setCopied(false), 1500)
    return () => window.clearTimeout(timer)
  }, [copied])

  async function copy() {
    try {
      await navigator.clipboard.writeText(runId)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  return (
    <span className="inline-flex items-center gap-1">
      <button
        type="button"
        onClick={() => onOpen(runId)}
        title={runId}
        aria-label={`Trace ID ${runId} 상세 열기`}
        className={`ops-mono rounded px-1 text-[var(--ops-accent)] hover:underline hover:underline-offset-4 ${focusRing}`}
      >
        {full ? runId : shortId(runId)}
      </button>
      <button
        type="button"
        onClick={() => void copy()}
        aria-label={copied ? 'Trace ID 복사함' : `Trace ID ${runId} 복사`}
        title={copied ? '복사함' : 'Trace ID 복사'}
        className={`rounded p-0.5 text-[var(--ops-muted)] hover:text-[var(--ops-text)] ${focusRing}`}
      >
        {copied ? <Check aria-hidden size={13} /> : <Copy aria-hidden size={13} />}
      </button>
    </span>
  )
}

/** 흐름 하나를 단계 칩 사슬로 그린다. 같은 단계가 다시 돌면 몇 번째인지 붙이고, 통과하지 못한 단계에는 판정 코드를 붙인다. */
function FlowChain({ flow }: { flow: AgentRunFlow }) {
  const seen = new Map<string, number>()
  return (
    <ol className="flex flex-wrap items-center gap-x-1 gap-y-1.5" aria-label="단계 흐름">
      {flow.steps.map((step, index) => {
        const nth = (seen.get(step.step) ?? 0) + 1
        seen.set(step.step, nth)
        const blocked = step.status !== 'PASSED'
        return (
          <li key={index} className="inline-flex items-center gap-1">
            <span
              className="inline-flex items-center gap-1 rounded-[3px] border bg-[var(--ops-panel-raised)] px-1.5 py-0.5 text-xs text-[var(--ops-text-2)]"
              style={{ borderColor: blocked ? statusColor(step.status) : 'var(--ops-border)' }}
            >
              <StatusIcon status={step.status} size={12} />
              <span className="sr-only">{STATUS_LABEL[step.status]}</span>
              <span className={blocked ? 'text-[var(--ops-text)]' : ''}>
                {stepLabel(step.step)}
                {nth > 1 && <span className="text-[var(--ops-muted)]"> {nth}</span>}
              </span>
              {blocked && step.verdict && <span className="ops-mono text-[var(--ops-text)]">{step.verdict}</span>}
            </span>
            <ArrowRight aria-hidden size={12} className="text-[var(--ops-muted)]" />
          </li>
        )
      })}
      <li>
        <span
          className="inline-flex items-center gap-1 rounded-[3px] border-l-2 bg-[var(--ops-panel-raised)] px-1.5 py-0.5 text-xs font-medium text-[var(--ops-text)]"
          style={{ borderLeftColor: pathColor(flow.path) }}
        >
          {stepLabel(flow.result)}
        </span>
      </li>
    </ol>
  )
}

/**
 * 같은 단계 순서·판정으로 흘러간 실행끼리 묶은 흐름 패턴. 성공·실패마다 흐름이 여러 개일 수 있어 많이 일어난 순서로 줄 세운다.
 * 줄마다 그 흐름으로 끝난 최근 실행의 Trace ID 를 붙인다.
 */
export function FlowPatternsPanel({
  outcome,
  flows,
  mixed,
  onOpen,
}: {
  outcome: Outcome
  flows: AgentRunFlow[]
  mixed: boolean
  onOpen: (runId: string) => void
}) {
  const rows = flows.filter((flow) => flow.outcome === outcome)
  const total = rows.reduce((sum, flow) => sum + flow.count, 0)
  return (
    <Panel
      title="흐름 패턴"
      hint="같은 단계 순서와 판정으로 흘러간 실행끼리 묶었습니다. 비율은 이 구역 안에서 차지하는 몫이고, Trace ID 를 누르면 그 실행의 상세가 열립니다."
      className="xl:col-span-2"
      scroll
    >
      {rows.length === 0 ? (
        <Empty>{outcome === 'SUCCESS' ? '정상 결과로 끝난 실행이 없습니다.' : '정상 결과 없이 끝난 실행이 없습니다.'}</Empty>
      ) : (
        <ul className="flex flex-col divide-y divide-[var(--ops-grid)]">
          {rows.map((flow, index) => (
            <li key={index} className="flex flex-col gap-2 py-2.5 first:pt-0 last:pb-0">
              <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
                <span className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[13px]">
                  <PathBadge path={flow.path} />
                  {mixed && <span className="text-xs text-[var(--ops-muted)]">· {WORKFLOW_LABEL[flow.workflow]}</span>}
                  <span className="ops-num text-[var(--ops-text)]">{count(flow.count)}건</span>
                  <span className="ops-num text-xs text-[var(--ops-muted)]">
                    {percent(flow.count, total)} · 중앙값 {duration(flow.p50Ms)}
                  </span>
                </span>
                <span className="flex flex-wrap items-center gap-x-2 text-xs text-[var(--ops-muted)]">
                  최근
                  {flow.recentRunIds.map((runId) => (
                    <TraceId key={runId} runId={runId} onOpen={onOpen} />
                  ))}
                </span>
              </div>
              <FlowChain flow={flow} />
            </li>
          ))}
        </ul>
      )}
    </Panel>
  )
}

const thClass =
  'border-b border-[var(--ops-grid)] px-2 py-1.5 text-left text-xs font-semibold whitespace-nowrap text-[var(--ops-text-2)]'
const tdClass = 'px-2 py-2 align-middle text-[13px] text-[var(--ops-text-2)]'

/** 구역 하나의 최근 실행. 맨 앞 열이 Trace ID 다. 세부 흐름(예: 안내로 종료·오류)으로 더 좁힐 수 있다. */
export function RunListPanel({
  outcome,
  runs,
  path,
  onPath,
  selected,
  onOpen,
  className = '',
}: {
  outcome: Outcome
  runs: AgentRun[] | undefined
  path: RunPath | undefined
  onPath: (path: RunPath | undefined) => void
  selected: string | undefined
  onOpen: (runId: string) => void
  className?: string
}) {
  const options: { label: string; value: RunPath | undefined }[] = [
    { label: '전체', value: undefined },
    ...OUTCOME_PATHS[outcome].map((value) => ({ label: PATH_LABEL[value], value })),
  ]
  const wide = className.includes('col-span-4')
  return (
    <Panel
      title="실행 목록"
      hint="최근 순입니다. Trace ID 를 누르면 단계 흐름 상세가 열리고, 옆 단추로 복사해 서버 로그(grep)에 바로 쓸 수 있습니다."
      className={className}
      scroll
      action={
        <div role="group" aria-label="세부 흐름" className="flex shrink-0 rounded-[4px] border border-[var(--ops-border)] p-0.5">
          {options.map((option) => (
            <button
              key={option.label}
              type="button"
              aria-pressed={path === option.value}
              onClick={() => onPath(option.value)}
              className={`rounded px-2 py-1 text-xs ${focusRing} ${
                path === option.value
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
      {runs === undefined ? (
        <Empty>불러오는 중입니다.</Empty>
      ) : runs.length === 0 ? (
        <Empty>조건에 맞는 실행이 없습니다.</Empty>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse">
            <thead className="sticky top-0 z-[1] bg-[var(--ops-panel)]">
              <tr>
                <th scope="col" className={thClass}>
                  Trace ID
                </th>
                <th scope="col" className={thClass}>
                  시작
                </th>
                <th scope="col" className={thClass}>
                  워크플로우
                </th>
                <th scope="col" className={thClass}>
                  흐름
                </th>
                <th scope="col" className={thClass}>
                  {outcome === 'SUCCESS' ? '거쳐 간 거절' : '원인 단계 · 사유'}
                </th>
                {wide && (
                  <th scope="col" className={thClass}>
                    결과
                  </th>
                )}
                <th scope="col" className={`${thClass} text-right`}>
                  걸린 시간
                </th>
                <th scope="col" className={`${thClass} text-right`}>
                  AI 호출
                </th>
              </tr>
            </thead>
            <tbody>
              {runs.map((run) => (
                <tr
                  key={run.runId}
                  className={`border-b border-[var(--ops-grid)] last:border-b-0 ${
                    selected === run.runId ? 'bg-white/[0.06]' : 'hover:bg-white/[0.03]'
                  }`}
                >
                  <td className={`${tdClass} whitespace-nowrap`}>
                    <TraceId runId={run.runId} onOpen={onOpen} full={wide} />
                  </td>
                  <td className={`${tdClass} ops-num whitespace-nowrap`}>{dayClock(run.startedAt)}</td>
                  <td className={`${tdClass} whitespace-nowrap`}>{WORKFLOW_LABEL[run.workflow]}</td>
                  <td className={tdClass}>
                    <PathBadge path={run.path} />
                  </td>
                  <td className={tdClass}>
                    <span className="flex flex-wrap items-center gap-1.5">
                      {run.endedBy ? stepLabel(run.endedBy) : '—'}
                      {run.reason && <Code>{run.reason}</Code>}
                    </span>
                  </td>
                  {wide && <td className={`${tdClass} whitespace-nowrap`}>{stepLabel(run.result)}</td>}
                  <td className={`${tdClass} ops-num text-right`}>{duration(run.durationMs)}</td>
                  <td className={`${tdClass} ops-num text-right whitespace-nowrap`}>
                    {count(run.aiCalls)}
                    {run.repeatedCalls > 0 && <span className="text-[var(--ops-muted)]"> (반복 {count(run.repeatedCalls)})</span>}
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
