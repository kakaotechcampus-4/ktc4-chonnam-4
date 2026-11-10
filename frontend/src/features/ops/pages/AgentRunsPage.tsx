import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { ChevronDown, Clock3, RefreshCw, Search } from 'lucide-react'
import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiError } from '@/features/instructor/api'
import { getAgentRunOverview, listAgentRuns, type AgentRunFilter, type RunPath, type Workflow } from '../api'
import { LatencyChart, PathSeriesChart } from '../components/charts'
import {
  CallStat,
  CodePanel,
  EndingPanel,
  PathStat,
  RunsPanel,
  RunsStat,
  StagePanel,
  StepPanel,
  TransitionPanel,
  WorkflowPanel,
} from '../components/panels'
import { Empty, Panel } from '../components/primitives'
import { PeriodContext } from '../lib/period'
import { RunDetail } from '../components/RunDetail'
import { dayClockSeconds } from '../lib/format'
import { PATHS, WORKFLOW_LABEL, WORKFLOWS } from '../lib/labels'
import { focusRing } from '../lib/tones'

const PERIODS = [
  { hours: 1, label: '최근 1시간' },
  { hours: 6, label: '최근 6시간' },
  { hours: 24, label: '최근 24시간' },
  { hours: 72, label: '최근 3일' },
  { hours: 168, label: '최근 7일' },
]
const DEFAULT_HOURS = 24
const REFRESH_MS = 30_000
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const controlClass = `h-8 rounded-[4px] border border-[var(--ops-border)] bg-[var(--ops-panel)] text-[13px] text-[var(--ops-text)] hover:border-[var(--ops-axis)] ${focusRing}`

/**
 * 에이전트 워크플로우 실행 기록 대시보드. 역할극 턴·학습 리포트·시나리오 생성이 한 번 돌 때마다 서버가 남긴
 * "단계 → 판정 → 실제로 이어진 다음 단계"를 정상 흐름과 오류 흐름 모두 한 화면에서 본다.
 *
 * 배치는 Grafana 패널 격자, 글꼴·강조색은 Figma 디자인 시스템 Teacher 값을 따른다.
 * 기간·워크플로우·목록 흐름·열어 둔 실행은 주소에 남긴다. 30초마다 다시 받고, 받는 동안은 앞 화면을 그대로 둔다.
 */
export function AgentRunsPage() {
  const [params, setParams] = useSearchParams()
  const period = PERIODS.find((option) => String(option.hours) === params.get('hours')) ??
    PERIODS.find((option) => option.hours === DEFAULT_HOURS)!
  const hours = period.hours
  const workflow = WORKFLOWS.find((value) => value === params.get('workflow'))
  const path = PATHS.find((value) => value === params.get('path'))
  const selected = params.get('run') ?? undefined
  const filter: AgentRunFilter = { hours, workflow }

  const overview = useQuery({
    queryKey: ['ops', 'agent-runs', 'overview', hours, workflow ?? null],
    queryFn: () => getAgentRunOverview(filter),
    refetchInterval: REFRESH_MS,
    placeholderData: keepPreviousData,
  })
  const runs = useQuery({
    queryKey: ['ops', 'agent-runs', 'list', hours, workflow ?? null, path ?? null],
    queryFn: () => listAgentRuns({ ...filter, path }),
    refetchInterval: REFRESH_MS,
    placeholderData: keepPreviousData,
  })

  function change(next: Record<string, string | undefined>) {
    setParams(
      (current) => {
        const updated = new URLSearchParams(current)
        for (const [key, value] of Object.entries(next)) {
          if (value === undefined) updated.delete(key)
          else updated.set(key, value)
        }
        return updated
      },
      { replace: true },
    )
  }

  const refreshing = overview.isFetching || runs.isFetching
  const data = overview.data

  return (
    <div className="ops-scope">
      <header className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3 border-b border-[var(--ops-border)] bg-[var(--ops-panel)] px-4 py-2.5">
        <div className="min-w-0">
          <p className="text-xs text-[var(--ops-muted)]">
            운영 <span aria-hidden>/</span>
          </p>
          <h1 className="text-[15px] font-semibold text-[var(--ops-text)]">에이전트 워크플로우 실행 기록</h1>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Picker label="워크플로우">
            <select
              aria-label="워크플로우"
              value={workflow ?? ''}
              onChange={(event) => change({ workflow: event.target.value || undefined })}
              className={`${controlClass} appearance-none pr-8 pl-2.5`}
            >
              <option value="">전체 워크플로우</option>
              {WORKFLOWS.map((option: Workflow) => (
                <option key={option} value={option}>
                  {WORKFLOW_LABEL[option]}
                </option>
              ))}
            </select>
          </Picker>

          <Picker label="기간" icon={<Clock3 aria-hidden size={14} className="text-[var(--ops-accent)]" />}>
            <select
              aria-label="기간"
              value={hours}
              onChange={(event) =>
                change({ hours: Number(event.target.value) === DEFAULT_HOURS ? undefined : event.target.value })
              }
              className={`${controlClass} appearance-none pr-8 pl-8`}
            >
              {PERIODS.map((option) => (
                <option key={option.hours} value={option.hours}>
                  {option.label}
                </option>
              ))}
            </select>
          </Picker>

          <button
            type="button"
            onClick={() => {
              void overview.refetch()
              void runs.refetch()
            }}
            aria-label="새로 고침"
            title={
              overview.dataUpdatedAt > 0
                ? `마지막 갱신 ${dayClockSeconds(new Date(overview.dataUpdatedAt).toISOString())} · 30초마다 자동 갱신`
                : '30초마다 자동 갱신'
            }
            className={`${controlClass} inline-flex items-center gap-1.5 px-2.5 text-[var(--ops-text-2)]`}
          >
            <RefreshCw aria-hidden size={14} className={refreshing ? 'animate-spin' : ''} />
            <span className="ops-num">
              {overview.dataUpdatedAt > 0 ? dayClockSeconds(new Date(overview.dataUpdatedAt).toISOString()).slice(-8) : '갱신'}
            </span>
          </button>

          <Link to="/dashboard" className={`rounded px-1 text-[13px] text-[var(--ops-text-2)] hover:text-[var(--ops-text)] ${focusRing}`}>
            강사 화면
          </Link>
        </div>
      </header>

      <main className="flex flex-col gap-2 p-2 sm:p-3">
        <TraceSearch onOpen={(runId) => change({ run: runId })} />

        <PeriodContext.Provider value={period.label}>
          {overview.isPending ? (
            <Panel title="실행 기록">
              <Empty>불러오는 중입니다.</Empty>
            </Panel>
          ) : overview.isError || !data ? (
            <Unavailable error={overview.error} />
          ) : (
            <div
              className={`grid grid-cols-1 gap-2 md:grid-cols-2 xl:grid-cols-4 ${overview.isPlaceholderData ? 'opacity-60' : ''}`}
            >
              <RunsStat totals={data.totals} series={data.series} />
              <PathStat totals={data.totals} />
              <CallStat totals={data.totals} />
              <WorkflowPanel workflows={data.workflows} />

              <Panel
                title="시간대별 실행"
                hint={`${data.window.bucketMinutes >= 60 ? `${data.window.bucketMinutes / 60}시간` : `${data.window.bucketMinutes}분`} 단위로 묶은 흐름 종류별 실행 수`}
                className="md:col-span-2"
              >
                {data.totals.runs === 0 ? (
                  <Empty>이 기간에 기록된 실행이 없습니다.</Empty>
                ) : (
                  <PathSeriesChart series={data.series} bucketMinutes={data.window.bucketMinutes} />
                )}
              </Panel>
              <Panel
                title="응답 시간"
                hint="실행 한 번에 걸린 시간의 구간별 중앙값과 p95. 역할극 턴은 요청을 받은 때부터 응답할 때까지입니다."
                className="md:col-span-2"
              >
                {data.totals.runs === 0 ? (
                  <Empty>이 기간에 기록된 실행이 없습니다.</Empty>
                ) : (
                  <LatencyChart series={data.series} bucketMinutes={data.window.bucketMinutes} />
                )}
              </Panel>

              <div className="grid md:col-span-2">
                <TransitionPanel transitions={data.transitions} mixed={!workflow} />
              </div>
              <div className="grid md:col-span-2">
                <StepPanel steps={data.steps} mixed={!workflow} />
              </div>

              <div className="grid md:col-span-2">
                <EndingPanel endings={data.endings} runs={data.totals.runs} />
              </div>
              <CodePanel codes={data.codes} />
              <StagePanel stages={data.stages} />

              <div className="grid md:col-span-2 xl:col-span-4">
                <RunsPanel
                  runs={runs.data}
                  path={path}
                  onPath={(next: RunPath | undefined) => change({ path: next })}
                  selected={selected}
                  onSelect={(runId) => change({ run: runId })}
                />
              </div>
            </div>
          )}
        </PeriodContext.Provider>
      </main>

      {selected && <RunDetail runId={selected} onClose={() => change({ run: undefined })} />}
    </div>
  )
}

/** Grafana 상단 막대의 고르는 칸. 왼쪽 아이콘과 오른쪽 화살표를 select 위에 얹는다. */
function Picker({ label, icon, children }: { label: string; icon?: ReactNode; children: ReactNode }) {
  return (
    <span className="relative inline-flex items-center" data-picker={label}>
      {icon && <span className="pointer-events-none absolute left-2.5 inline-flex">{icon}</span>}
      {children}
      <ChevronDown aria-hidden size={14} className="pointer-events-none absolute right-2.5 text-[var(--ops-muted)]" />
    </span>
  )
}

/** 브라우저 개발자 도구에서 본 X-Trace-Id 로 그 요청의 실행을 바로 연다(노션 "역할극 진행시 서버 로그 보기" 절차의 화면판). */
function TraceSearch({ onOpen }: { onOpen: (runId: string) => void }) {
  const [value, setValue] = useState('')
  const [invalid, setInvalid] = useState(false)

  function submit(event: FormEvent) {
    event.preventDefault()
    const runId = value.trim()
    if (!UUID.test(runId)) {
      setInvalid(true)
      return
    }
    setInvalid(false)
    setValue('')
    onOpen(runId.toLowerCase())
  }

  return (
    <form onSubmit={submit} className="flex flex-wrap items-center gap-2">
      <label className="relative flex min-w-0 flex-1 items-center sm:max-w-[460px]">
        <span className="sr-only">요청 번호</span>
        <Search aria-hidden size={14} className="pointer-events-none absolute left-2.5 text-[var(--ops-muted)]" />
        <input
          value={value}
          onChange={(event) => {
            setValue(event.target.value)
            setInvalid(false)
          }}
          placeholder="요청 번호(X-Trace-Id)로 실행 찾기"
          aria-invalid={invalid}
          aria-describedby={invalid ? 'trace-search-error' : undefined}
          className={`${controlClass} ops-mono w-full pr-2.5 pl-8 placeholder:font-[Pretendard_Variable] placeholder:text-[var(--ops-muted)]`}
        />
      </label>
      <button type="submit" className={`${controlClass} px-3`}>
        열기
      </button>
      {invalid && (
        <p id="trace-search-error" role="alert" className="text-xs text-[var(--ops-text-2)]">
          요청 번호는 8-4-4-4-12 자리 UUID 형식입니다.
        </p>
      )}
    </form>
  )
}

function Unavailable({ error }: { error: unknown }) {
  const hidden = error instanceof ApiError && error.status === 404
  return (
    <Panel title={hidden ? '이 계정으로는 볼 수 없습니다' : '실행 기록을 불러오지 못했습니다'}>
      <div className="flex flex-col gap-2 py-4 text-[13px] text-[var(--ops-text-2)]">
        {hidden ? (
          <>
            <p>실행 기록이 꺼져 있거나, 이 계정이 볼 수 있는 계정 목록에 없습니다.</p>
            <p className="text-[var(--ops-muted)]">
              서버 설정 <span className="ops-mono">NEURINGO_AGENT_RUNS_ENABLED</span> 와{' '}
              <span className="ops-mono">NEURINGO_AGENT_RUNS_OPERATORS</span> 를 확인하세요.
            </p>
          </>
        ) : (
          <p>{error instanceof ApiError ? error.message : '잠시 후 다시 시도해 주세요.'}</p>
        )}
      </div>
    </Panel>
  )
}
