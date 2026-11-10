import { readRequest } from '@/features/instructor/api'

/**
 * 에이전트 워크플로우 실행 기록 조회(백엔드 ops.controller.AgentRunController).
 * 값은 모두 고정 코드·횟수·시간·토큰 수다. 발화·프롬프트·모델 출력 글은 서버가 저장하지 않는다.
 */
export type Workflow = 'ROLEPLAY_TURN' | 'LEARNING_REPORT' | 'SCENARIO_GENERATION'

/** NORMAL 한 번에 통과, RETRIED 다시 돌아 정상 결과, GUIDED 설계된 안내로 끝남, FAILED 오류로 끝남. */
export type RunPath = 'NORMAL' | 'RETRIED' | 'GUIDED' | 'FAILED'

/** SUCCESS 는 정상 결과로 끝난 실행(NORMAL·RETRIED), FAILURE 는 정상 결과 없이 끝난 실행(GUIDED·FAILED). */
export type Outcome = 'SUCCESS' | 'FAILURE'

export type StepKind = 'CALL' | 'CHECK' | 'RESULT'

export type StepStatus = 'PASSED' | 'REJECTED' | 'FAILED'

export type AgentRunStep = {
  seq: number
  kind: StepKind
  step: string
  attempt: number
  status: StepStatus
  verdict: string | null
  codes: string[]
  /** 판정이 지목한 다음 단계 */
  declaredNext: string | null
  /** 실제로 이어진 다음 단계. 마지막 단계면 최종 결과 코드 */
  ledTo: string | null
  atMs: number
  durationMs: number
  model: string | null
  promptVersion: string | null
  finishReason: string | null
  inputTokens: number | null
  outputTokens: number | null
}

export type AgentRunSpan = {
  id: number
  parent: number
  stage: string
  operation: string | null
  attempt: number
  startMs: number
  durationMs: number
  selfMs: number
  outcome: string | null
}

export type AgentRun = {
  /** 역할극 턴은 그 요청의 X-Trace-Id 와 같다 */
  runId: string
  workflow: Workflow
  path: RunPath
  result: string
  endedBy: string | null
  reason: string | null
  inputType: string | null
  httpStatus: number | null
  replayed: boolean
  startedAt: string
  durationMs: number
  aiCalls: number
  repeatedCalls: number
  inputTokens: number
  outputTokens: number
  truncated: boolean
  stageMs: Record<string, number>
  spans: AgentRunSpan[]
  steps: AgentRunStep[]
}

export type PathCounts = { normal: number; retried: number; guided: number; failed: number }

export type AgentRunOverview = {
  window: { from: string; to: string; hours: number; bucketMinutes: number }
  totals: PathCounts & {
    runs: number
    p50Ms: number
    p95Ms: number
    aiCalls: number
    inputTokens: number
    outputTokens: number
  }
  series: (PathCounts & { at: string; p50Ms: number; p95Ms: number })[]
  workflows: (PathCounts & { workflow: Workflow; runs: number; p50Ms: number; p95Ms: number })[]
  steps: {
    workflow: Workflow
    step: string
    calls: number
    passed: number
    rejected: number
    failed: number
    avgMs: number
    p95Ms: number
    inputTokens: number
    outputTokens: number
  }[]
  transitions: {
    workflow: Workflow
    from: string
    status: StepStatus
    verdict: string
    to: string
    count: number
  }[]
  codes: { workflow: Workflow; step: string; code: string; count: number }[]
  endings: {
    workflow: Workflow
    path: RunPath
    result: string
    endedBy: string
    reason: string
    count: number
    recentRunIds: string[]
  }[]
  stages: { stage: string; avgMs: number; p95Ms: number }[]
  flows: AgentRunFlow[]
  diagnosis: AgentRunDiagnosis
}

export type DiagnosisKind = 'FAILURE_RATE' | 'NEW_FAILURE_FLOW' | 'STEP_BLOCKED' | 'REASON_SURGE' | 'LATENCY'

export type DiagnosisSeverity = 'CRITICAL' | 'WARNING' | 'INFO'

/** 진단 하나. current·previous·threshold 의 단위는 unit(RATE 는 0~1). previous 가 null 이면 비교할 앞 기간 표본이 없다. */
export type DiagnosisFinding = {
  kind: DiagnosisKind
  severity: DiagnosisSeverity
  workflow: Workflow | null
  step: string | null
  code: string | null
  current: number
  previous: number | null
  threshold: number
  unit: 'RATE' | 'COUNT' | 'MS'
  sampleRunIds: string[]
}

/** 바로 앞 같은 길이 기간(previousFrom~previousTo)과 비교한 자동 진단. */
export type AgentRunDiagnosis = {
  previousFrom: string
  previousTo: string
  previousRuns: number
  failureRate: number | null
  previousFailureRate: number | null
  findings: DiagnosisFinding[]
}

/** 같은 단계 순서·판정으로 흘러간 실행끼리 묶은 흐름 패턴. recentRunIds 는 최근 실행 번호(역할극 턴은 X-Trace-Id). */
export type AgentRunFlow = {
  workflow: Workflow
  outcome: Outcome
  path: RunPath
  result: string
  steps: { step: string; status: StepStatus; verdict: string | null }[]
  count: number
  p50Ms: number
  recentRunIds: string[]
}

export type AgentRunFilter = { hours: number; workflow?: Workflow; path?: RunPath; outcome?: Outcome }

function query(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) search.set(key, String(value))
  }
  return search.toString()
}

export function getAgentRunOverview({ hours, workflow }: AgentRunFilter): Promise<AgentRunOverview> {
  return readRequest(`/ops/agent-runs/overview?${query({ hours, workflow })}`)
}

export function listAgentRuns({ hours, workflow, path, outcome }: AgentRunFilter, limit = 50): Promise<AgentRun[]> {
  return readRequest(`/ops/agent-runs?${query({ hours, workflow, path, outcome, limit })}`)
}

export function getAgentRun(runId: string): Promise<AgentRun> {
  return readRequest(`/ops/agent-runs/${encodeURIComponent(runId)}`)
}
