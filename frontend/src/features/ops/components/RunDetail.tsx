import { useQuery } from '@tanstack/react-query'
import { ArrowDown, X } from 'lucide-react'
import { useEffect, type ReactNode } from 'react'
import { ApiError } from '@/features/instructor/api'
import { getAgentRun, type AgentRun, type AgentRunSpan, type AgentRunStep } from '../api'
import { count, dayClockSeconds, duration } from '../lib/format'
import { KIND_LABEL, stageLabel, stepLabel, stepTitle, WORKFLOW_LABEL } from '../lib/labels'
import { focusRing } from '../lib/tones'
import { Code, Empty, PathBadge, StatusBadge, StatusIcon } from './primitives'

/**
 * 실행 한 건. 위에서 아래로 단계가 낸 판정과 그 때문에 실제로 이어진 다음 단계를 읽고, 아래에서 시간이 어느 구간에 쓰였는지 본다.
 * 화면 오른쪽에 겹쳐 뜨고 Esc 나 닫기로 닫는다.
 */
export function RunDetail({ runId, onClose }: { runId: string; onClose: () => void }) {
  const run = useQuery({ queryKey: ['ops', 'agent-runs', 'detail', runId], queryFn: () => getAgentRun(runId) })

  useEffect(() => {
    const close = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', close)
    return () => window.removeEventListener('keydown', close)
  }, [onClose])

  return (
    <aside
      role="dialog"
      aria-label="실행 상세"
      className="fixed inset-y-0 right-0 z-30 flex w-full max-w-[620px] flex-col border-l border-[var(--ops-border)] bg-[var(--ops-panel)] shadow-[-16px_0_40px_rgba(0,0,0,0.45)]"
    >
      <header className="flex items-center justify-between gap-3 border-b border-[var(--ops-border)] px-5 py-3">
        <div className="min-w-0">
          <h2 className="text-sm font-semibold text-[var(--ops-text)]">실행 상세</h2>
          <p className="ops-mono mt-0.5 truncate text-[var(--ops-muted)]">{runId}</p>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="실행 상세 닫기"
          className={`rounded-md p-1.5 text-[var(--ops-text-2)] hover:bg-white/10 ${focusRing}`}
        >
          <X aria-hidden size={18} />
        </button>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">
        {run.isPending && <Empty>불러오는 중입니다.</Empty>}
        {run.isError && (
          <Empty>
            {run.error instanceof ApiError && run.error.status === 404
              ? '이 번호의 실행 기록이 없습니다. 보관 기간이 지났거나 기록을 켜기 전의 요청입니다.'
              : '실행 기록을 불러오지 못했습니다.'}
          </Empty>
        )}
        {run.data && <Body run={run.data} />}
      </div>
    </aside>
  )
}

function Body({ run }: { run: AgentRun }) {
  return (
    <div className="flex flex-col gap-6">
      <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-[13px]">
        <Fact label="워크플로우">{WORKFLOW_LABEL[run.workflow] ?? run.workflow}</Fact>
        <Fact label="흐름">
          <PathBadge path={run.path} />
        </Fact>
        <Fact label="최종 결과">
          {stepLabel(run.result)} <Code>{run.result}</Code>
        </Fact>
        <Fact label="원인 단계 · 사유">
          {run.endedBy ? (
            <>
              {stepLabel(run.endedBy)} {run.reason && <Code>{run.reason}</Code>}
            </>
          ) : (
            '없음'
          )}
        </Fact>
        <Fact label="시작">{dayClockSeconds(run.startedAt)}</Fact>
        <Fact label="걸린 시간">{duration(run.durationMs)}</Fact>
        <Fact label="AI 호출">
          {count(run.aiCalls)}회{run.repeatedCalls > 0 && ` (반복 ${count(run.repeatedCalls)}회)`}
        </Fact>
        <Fact label="토큰 (입력 / 출력)">
          {count(run.inputTokens)} / {count(run.outputTokens)}
        </Fact>
        {run.inputType && <Fact label="입력">{run.inputType === 'VOICE' ? '음성' : '글자'}</Fact>}
        {run.replayed && <Fact label="재전달">이미 저장된 결과를 다시 보냄</Fact>}
      </dl>
      {run.truncated && (
        <p className="rounded-md bg-[var(--ops-panel-raised)] px-3 py-2 text-xs text-[var(--ops-text-2)]">
          사건이 한도를 넘어 뒷부분 일부가 기록되지 않았습니다.
        </p>
      )}

      <section aria-label="단계 흐름">
        <h3 className="text-[13px] font-semibold text-[var(--ops-text)]">단계 흐름</h3>
        <p className="mt-0.5 text-xs text-[var(--ops-muted)]">단계가 낸 판정과 그 때문에 실제로 이어진 다음 단계</p>
        <ol className="mt-3 flex flex-col">
          {run.steps.map((step, index) => (
            <StepRow key={step.seq} step={step} last={index === run.steps.length - 1} />
          ))}
        </ol>
      </section>

      {run.spans.length > 0 && <Waterfall spans={run.spans} total={run.durationMs} />}
    </div>
  )
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-xs text-[var(--ops-muted)]">{label}</dt>
      <dd className="mt-0.5 flex flex-wrap items-center gap-1.5 text-[var(--ops-text)]">{children}</dd>
    </div>
  )
}

function StepRow({ step, last }: { step: AgentRunStep; last: boolean }) {
  const tokens = step.inputTokens !== null || step.outputTokens !== null
  return (
    <li className="grid grid-cols-[20px_minmax(0,1fr)] gap-x-3">
      <div className="flex flex-col items-center">
        <span className="mt-0.5">
          <StatusIcon status={step.status} size={16} />
        </span>
        {!last && <span aria-hidden className="my-1 w-px flex-1 bg-[var(--ops-axis)]" />}
      </div>

      <div className={last ? '' : 'pb-4'}>
        <div className="flex flex-wrap items-baseline justify-between gap-x-3">
          <p className="text-[13px] font-semibold text-[var(--ops-text)]">
            {stepTitle(step.step, step.attempt)}
            <span className="ml-2 text-xs font-normal text-[var(--ops-muted)]">{KIND_LABEL[step.kind]}</span>
          </p>
          {step.kind === 'CALL' && (
            <span className="ops-num text-xs text-[var(--ops-text-2)]">{duration(step.durationMs)}</span>
          )}
        </div>

        <p className="mt-1.5 flex flex-wrap items-center gap-1.5 text-xs">
          <StatusBadge status={step.status} />
          {step.verdict && <Code>{step.verdict}</Code>}
          {step.codes.map((code) => (
            <Code key={code}>{code}</Code>
          ))}
        </p>

        {step.kind === 'CALL' && (step.model || tokens) && (
          <p className="ops-num mt-1.5 text-xs text-[var(--ops-muted)]">
            {[
              step.model,
              step.promptVersion,
              tokens && `토큰 ${count(step.inputTokens ?? 0)} / ${count(step.outputTokens ?? 0)}`,
              step.finishReason && `종료 ${step.finishReason}`,
            ]
              .filter(Boolean)
              .join(' · ')}
          </p>
        )}

        {step.ledTo && (
          <p className="mt-2 flex flex-wrap items-center gap-1.5 text-xs text-[var(--ops-text-2)]">
            <ArrowDown aria-hidden size={13} className="text-[var(--ops-muted)]" />
            다음: <span className="font-medium text-[var(--ops-text)]">{stepLabel(step.ledTo)}</span>
            {step.declaredNext && step.declaredNext !== step.ledTo && (
              <span className="text-[var(--ops-muted)]">
                (판정이 지목한 곳 <Code>{step.declaredNext}</Code>)
              </span>
            )}
          </p>
        )}
      </div>
    </li>
  )
}

/** 부모 구간을 따라 올라간 깊이. 구간이 잘려 부모가 없으면 0 으로 둔다. */
function depthOf(span: AgentRunSpan, byId: Map<number, AgentRunSpan>): number {
  let depth = 0
  for (let parent = byId.get(span.parent); parent && depth < 8; parent = byId.get(parent.parent)) depth += 1
  return depth
}

function Waterfall({ spans, total }: { spans: AgentRunSpan[]; total: number }) {
  const byId = new Map(spans.map((span) => [span.id, span]))
  const end = Math.max(total, ...spans.map((span) => span.startMs + span.durationMs), 1)
  return (
    <section aria-label="시간 구간">
      <h3 className="text-[13px] font-semibold text-[var(--ops-text)]">시간 구간</h3>
      <p className="mt-0.5 text-xs text-[var(--ops-muted)]">
        안쪽 구간의 시간은 바깥 구간에 포함됩니다. 전체 {duration(end)}
      </p>
      <ul className="mt-3 flex flex-col gap-1.5">
        {spans.map((span) => {
          const failed = span.outcome !== null && span.outcome !== 'COMPLETED'
          return (
            <li key={span.id} className="grid grid-cols-[150px_minmax(0,1fr)_56px] items-center gap-x-3 text-xs">
              <span
                className="truncate text-[var(--ops-text-2)]"
                style={{ paddingLeft: depthOf(span, byId) * 10 }}
                title={failed ? `${stageLabel(span.stage)} — ${span.outcome}` : stageLabel(span.stage)}
              >
                {span.operation ? stepLabel(span.operation) : stageLabel(span.stage)}
              </span>
              <span aria-hidden className="relative h-2 rounded-full bg-[var(--ops-track)]">
                <span
                  className="absolute inset-y-0 rounded-full"
                  style={{
                    left: `${(span.startMs / end) * 100}%`,
                    width: `max(3px, ${(span.durationMs / end) * 100}%)`,
                    background: failed ? 'var(--ops-path-failed)' : 'var(--ops-accent)',
                  }}
                />
              </span>
              <span className="ops-num text-right text-[var(--ops-text-2)]">{duration(span.durationMs)}</span>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
