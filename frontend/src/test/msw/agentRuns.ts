import { http, HttpResponse } from "msw"
import type {
  AgentRun,
  AgentRunOverview,
  AgentRunSpan,
  AgentRunStep,
  Outcome,
  RunPath,
  StepKind,
  StepStatus,
  Workflow,
} from "@/features/ops/api"
import { apiError, envelope } from "./handlers"

// 운영 대시보드(/api/v1/ops/agent-runs) 가짜 서버. 기본 핸들러에는 넣지 않고, 쓰는 테스트가 server.use(...agentRunHandlers()) 로 붙인다.
// 실행 기록은 정상·재시도·안내 종료·오류 흐름을 워크플로우마다 실제 서버가 남기는 모양대로 만들고, 요약은 그 목록에서 계산한다.
// 그래서 요약 숫자와 목록·상세가 서로 맞는다. 모양은 백엔드 AgentRunAssembler·AgentRunStore 를 따른다.

type StepSpec = {
  kind: StepKind
  step: string
  status: StepStatus
  verdict: string | null
  codes?: string[]
  next?: string | null
  ms?: number
  round?: number
}

type Template = {
  key: string
  workflow: Workflow
  path: RunPath
  result: string
  endedBy: string | null
  reason: string | null
  inputType?: "TEXT" | "VOICE"
  steps: StepSpec[]
}

const MODEL = "gpt-5.4-nano"

function call(step: string, ms: number, status: StepStatus = "PASSED", verdict = "OK", codes: string[] = [], next: string | null = null): StepSpec {
  return { kind: "CALL", step, status, verdict, codes, next, ms }
}

function check(step: string, verdict: string, status: StepStatus, codes: string[] = [], next: string | null = null, round = 0): StepSpec {
  return { kind: "CHECK", step, status, verdict, codes, next, round }
}

const approved = check("TURN_RESOLUTION", "APPROVED", "PASSED", [], "RESPONSE_DELIVERY")

/** 실행 모양 한 가지. 화면에서 구분해 볼 흐름을 빠짐없이 둔다. */
export const AGENT_RUN_TEMPLATES: Template[] = [
  {
    key: "roleplay-text-normal",
    workflow: "ROLEPLAY_TURN",
    path: "NORMAL",
    result: "DELIVERED",
    endedBy: null,
    reason: null,
    inputType: "TEXT",
    steps: [
      call("INPUT_VALIDATION", 1_400, "PASSED", "ANALYZABLE"),
      call("CAUSE_ANALYSIS", 6_800, "PASSED", "VALID"),
      call("RESPONSE_GENERATION", 4_900, "PASSED", "VALID"),
      call("RESPONSE_EVALUATION", 7_600, "PASSED", "PASS"),
      approved,
    ],
  },
  {
    key: "roleplay-voice-normal",
    workflow: "ROLEPLAY_TURN",
    path: "NORMAL",
    result: "DELIVERED",
    endedBy: null,
    reason: null,
    inputType: "VOICE",
    steps: [
      call("SPEECH_TRANSCRIPTION", 1_900),
      call("INPUT_VALIDATION", 1_300, "PASSED", "ANALYZABLE"),
      call("CAUSE_ANALYSIS", 7_100, "PASSED", "VALID"),
      call("RESPONSE_GENERATION", 5_200, "PASSED", "VALID"),
      call("RESPONSE_EVALUATION", 8_300, "PASSED", "PASS"),
      call("SPEECH_SYNTHESIS", 2_600),
      approved,
    ],
  },
  {
    key: "roleplay-regenerate",
    workflow: "ROLEPLAY_TURN",
    path: "RETRIED",
    result: "DELIVERED",
    endedBy: "RESPONSE_EVALUATION",
    reason: "REGENERATE",
    inputType: "TEXT",
    steps: [
      call("INPUT_VALIDATION", 1_500, "PASSED", "ANALYZABLE"),
      call("CAUSE_ANALYSIS", 6_400, "PASSED", "VALID"),
      call("RESPONSE_GENERATION", 5_100, "PASSED", "VALID"),
      call("RESPONSE_EVALUATION", 8_900, "REJECTED", "REGENERATE", ["ANSWER_TOO_DIRECT"], "RESPONSE_GENERATION"),
      call("RESPONSE_GENERATION", 4_700, "PASSED", "VALID"),
      call("RESPONSE_EVALUATION", 7_900, "PASSED", "PASS"),
      approved,
    ],
  },
  {
    key: "roleplay-invalid-analysis",
    workflow: "ROLEPLAY_TURN",
    path: "RETRIED",
    result: "DELIVERED",
    endedBy: "CAUSE_ANALYSIS",
    reason: "INVALID_OUTPUT_FORMAT",
    inputType: "VOICE",
    steps: [
      call("SPEECH_TRANSCRIPTION", 2_100),
      call("INPUT_VALIDATION", 1_200, "PASSED", "ANALYZABLE"),
      call("CAUSE_ANALYSIS", 7_400, "REJECTED", "INVALID_OUTPUT_FORMAT"),
      call("CAUSE_ANALYSIS", 6_900, "PASSED", "VALID"),
      call("RESPONSE_GENERATION", 5_000, "PASSED", "VALID"),
      call("RESPONSE_EVALUATION", 7_700, "PASSED", "PASS"),
      call("SPEECH_SYNTHESIS", 2_400),
      approved,
    ],
  },
  {
    key: "roleplay-low-confidence",
    workflow: "ROLEPLAY_TURN",
    path: "GUIDED",
    result: "REINPUT_REQUIRED",
    endedBy: "SPEECH_TRANSCRIPTION",
    reason: "LOW_CONFIDENCE",
    inputType: "VOICE",
    steps: [
      call("SPEECH_TRANSCRIPTION", 1_800),
      check("TURN_RESOLUTION", "INPUT_REJECTED", "REJECTED", ["LOW_CONFIDENCE"], "NOTICE_INPUT_QUALITY"),
    ],
  },
  {
    key: "roleplay-unsafe",
    workflow: "ROLEPLAY_TURN",
    path: "GUIDED",
    result: "STOPPED",
    endedBy: "INPUT_VALIDATION",
    reason: "INPUT_UNSAFE",
    inputType: "TEXT",
    steps: [
      call("INPUT_VALIDATION", 1_600, "REJECTED", "UNSAFE"),
      check("TURN_RESOLUTION", "INPUT_UNSAFE", "REJECTED", [], "SAFETY_STOP"),
    ],
  },
  {
    key: "roleplay-evaluation-timeout",
    workflow: "ROLEPLAY_TURN",
    path: "FAILED",
    result: "RETRY_REQUIRED",
    endedBy: "RESPONSE_EVALUATION",
    reason: "LIMIT_REACHED",
    inputType: "TEXT",
    steps: [
      call("INPUT_VALIDATION", 1_400, "PASSED", "ANALYZABLE"),
      call("CAUSE_ANALYSIS", 6_600, "PASSED", "VALID"),
      call("RESPONSE_GENERATION", 4_800, "PASSED", "VALID"),
      call("RESPONSE_EVALUATION", 10_000, "FAILED", "TIMEOUT"),
      call("RESPONSE_EVALUATION", 10_000, "FAILED", "TIMEOUT"),
      call("RESPONSE_EVALUATION", 10_000, "FAILED", "TIMEOUT"),
      { ...check("TURN_RESOLUTION", "LIMIT_REACHED", "FAILED", ["SAFE_FALLBACK"], "NOTICE_SAFETY_CHECK_ERROR") },
    ],
  },
  {
    key: "report-normal",
    workflow: "LEARNING_REPORT",
    path: "NORMAL",
    result: "COMPLETED",
    endedBy: null,
    reason: null,
    steps: [
      call("REPORT_GENERATION", 21_000, "PASSED", "VALID"),
      check("REPORT_RULE_CHECK", "RULES_PASSED", "PASSED", [], null, 1),
      call("REPORT_REVIEW", 17_000, "PASSED", "PASS"),
      check("REPORT_QUALITY_GATE", "PASSED", "PASSED", [], null, 1),
    ],
  },
  {
    key: "report-rules-rejected",
    workflow: "LEARNING_REPORT",
    path: "RETRIED",
    result: "COMPLETED",
    endedBy: "REPORT_RULE_CHECK",
    reason: "RULES_REJECTED",
    steps: [
      call("REPORT_GENERATION", 22_000, "PASSED", "VALID"),
      check("REPORT_RULE_CHECK", "RULES_REJECTED", "REJECTED", ["TOO_LONG"], null, 1),
      call("REPORT_GENERATION", 19_000, "PASSED", "VALID"),
      check("REPORT_RULE_CHECK", "RULES_PASSED", "PASSED", [], null, 2),
      call("REPORT_REVIEW", 16_000, "PASSED", "PASS"),
      check("REPORT_QUALITY_GATE", "PASSED", "PASSED", [], null, 2),
    ],
  },
  {
    key: "report-quality-limit",
    workflow: "LEARNING_REPORT",
    path: "FAILED",
    result: "FAILED",
    endedBy: "REPORT_QUALITY_GATE",
    reason: "QUALITY_LIMIT_REACHED",
    steps: [1, 2, 3].flatMap((round) => [
      call("REPORT_GENERATION", 20_000, "PASSED", "VALID"),
      check("REPORT_RULE_CHECK", "RULES_PASSED", "PASSED", [], null, round),
      call("REPORT_REVIEW", 18_000, "REJECTED", "REVISE", ["UNGROUNDED_CLAIM"]),
      check("REPORT_QUALITY_GATE", "REVIEW_REJECTED", "REJECTED", ["UNGROUNDED_CLAIM"], null, round),
    ]),
  },
  {
    key: "scenario-normal",
    workflow: "SCENARIO_GENERATION",
    path: "NORMAL",
    result: "GENERATED",
    endedBy: null,
    reason: null,
    steps: [call("SCENARIO_GENERATION", 24_000, "PASSED", "VALID")],
  },
  {
    key: "scenario-condition",
    workflow: "SCENARIO_GENERATION",
    path: "RETRIED",
    result: "GENERATED",
    endedBy: "SCENARIO_GENERATION",
    reason: "CONDITION_VIOLATED",
    steps: [
      call("SCENARIO_GENERATION", 23_000, "REJECTED", "CONDITION_VIOLATED", ["CHARACTER_ROLE_MISMATCH"]),
      call("SCENARIO_GENERATION", 25_000, "PASSED", "VALID"),
    ],
  },
]

const TOKENS: Record<string, [number, number]> = {
  INPUT_VALIDATION: [420, 40],
  CAUSE_ANALYSIS: [2_300, 380],
  RESPONSE_GENERATION: [2_600, 160],
  RESPONSE_EVALUATION: [3_100, 260],
  REPORT_GENERATION: [5_400, 1_200],
  REPORT_REVIEW: [6_100, 540],
  SCENARIO_GENERATION: [3_800, 1_600],
}

/** 일정한 값을 내는 의사 난수. 테스트마다 같은 데이터가 나와야 한다. */
function seeded(seed: number) {
  let state = seed
  return () => {
    state = (state * 1_103_515_245 + 12_345) % 2_147_483_648
    return state / 2_147_483_648
  }
}

function uuid(index: number): string {
  const hex = (index + 1).toString(16).padStart(12, "0")
  return `7c1f3a52-0d6b-4f0e-9a51-${hex}`
}

export function buildAgentRun(template: Template, runId: string, startedAt: Date, jitter = 1): AgentRun {
  const seen = new Map<string, number>()
  let at = 120
  const drafts = template.steps.map((spec) => {
    const attempt = spec.kind === "CALL" ? (seen.get(spec.step) ?? 0) + 1 : (spec.round ?? 0)
    if (spec.kind === "CALL") seen.set(spec.step, attempt)
    const durationMs = spec.kind === "CALL" ? Math.round((spec.ms ?? 0) * jitter) : 0
    const tokens = spec.kind === "CALL" ? TOKENS[spec.step] : undefined
    const step = { spec, attempt, atMs: at, durationMs, tokens }
    at += durationMs + 40
    return step
  })
  const steps: AgentRunStep[] = drafts.map((draft, index) => ({
    seq: index + 1,
    kind: draft.spec.kind,
    step: draft.spec.step,
    attempt: draft.attempt,
    status: draft.spec.status,
    verdict: draft.spec.verdict,
    codes: draft.spec.codes ?? [],
    declaredNext: draft.spec.next ?? null,
    ledTo: index + 1 < drafts.length ? drafts[index + 1].spec.step : template.result,
    atMs: draft.atMs,
    durationMs: draft.durationMs,
    model: draft.tokens ? MODEL : null,
    promptVersion: draft.tokens ? `${draft.spec.step.toLowerCase().replaceAll("_", "-")}/v3` : null,
    finishReason: draft.tokens ? (draft.spec.verdict === "TIMEOUT" ? null : "stop") : null,
    inputTokens: draft.tokens && draft.spec.status !== "FAILED" ? draft.tokens[0] : null,
    outputTokens: draft.tokens && draft.spec.status !== "FAILED" ? draft.tokens[1] : null,
  }))
  const durationMs = at + 260
  steps.push({
    seq: steps.length + 1,
    kind: "RESULT",
    step: "RESULT",
    attempt: 0,
    status: template.path === "FAILED" ? "FAILED" : template.path === "GUIDED" ? "REJECTED" : "PASSED",
    verdict: template.result,
    codes: [],
    declaredNext: null,
    ledTo: null,
    atMs: durationMs,
    durationMs: 0,
    model: null,
    promptVersion: null,
    finishReason: null,
    inputTokens: null,
    outputTokens: null,
  })

  const calls = steps.filter((step) => step.kind === "CALL")
  const roleplay = template.workflow === "ROLEPLAY_TURN"
  const spans: AgentRunSpan[] = [
    { id: 1, parent: 0, stage: "HTTP_REQUEST", operation: null, attempt: 0, startMs: 0, durationMs, selfMs: 70, outcome: "COMPLETED" },
  ]
  const stageMs: Record<string, number> = { HTTP_REQUEST: 70 }
  if (roleplay) {
    spans.push(
      { id: 2, parent: 1, stage: "WORKER_QUEUE", operation: null, attempt: 0, startMs: 20, durationMs: 30, selfMs: 30, outcome: "COMPLETED" },
      { id: 3, parent: 1, stage: "WORKER_EXECUTION", operation: null, attempt: 0, startMs: 50, durationMs: durationMs - 120, selfMs: 40, outcome: "COMPLETED" },
      { id: 4, parent: 3, stage: "CONTEXT_PREPARATION", operation: null, attempt: 0, startMs: 60, durationMs: 55, selfMs: 55, outcome: "COMPLETED" },
    )
    stageMs.WORKER_QUEUE = 30
    stageMs.WORKER_EXECUTION = 40
    stageMs.CONTEXT_PREPARATION = 55
  }
  for (const step of calls) {
    const stage = step.step === "SPEECH_TRANSCRIPTION" ? "STT" : step.step === "SPEECH_SYNTHESIS" ? "TTS" : "LLM_CALL"
    spans.push({
      id: spans.length + 1,
      parent: roleplay ? 3 : 1,
      stage,
      operation: step.step,
      attempt: step.attempt,
      startMs: step.atMs,
      durationMs: step.durationMs,
      selfMs: step.durationMs,
      outcome: step.status === "FAILED" ? `PROVIDER_FAILURE_${step.verdict}` : "COMPLETED",
    })
    stageMs[stage] = (stageMs[stage] ?? 0) + step.durationMs
  }
  if (roleplay && template.result === "DELIVERED") {
    spans.push({ id: spans.length + 1, parent: 3, stage: "RESULT_COMMIT", operation: null, attempt: 0, startMs: at - 30, durationMs: 90, selfMs: 90, outcome: "COMPLETED" })
    stageMs.RESULT_COMMIT = 90
  }

  return {
    runId,
    workflow: template.workflow,
    path: template.path,
    result: template.result,
    endedBy: template.endedBy,
    reason: template.reason,
    inputType: template.inputType ?? null,
    httpStatus: roleplay ? 200 : null,
    replayed: false,
    startedAt: startedAt.toISOString(),
    durationMs,
    aiCalls: calls.length,
    repeatedCalls: calls.length - new Set(calls.map((step) => step.step)).size,
    inputTokens: steps.reduce((sum, step) => sum + (step.inputTokens ?? 0), 0),
    outputTokens: steps.reduce((sum, step) => sum + (step.outputTokens ?? 0), 0),
    truncated: false,
    stageMs: roleplay ? stageMs : {},
    spans,
    steps,
  }
}

/** 템플릿마다 하루에 몇 번 꼴로 생기는지. 역할극 턴이 가장 많고 오류는 드물다. */
const WEIGHT: Record<string, number> = {
  "roleplay-text-normal": 26,
  "roleplay-voice-normal": 22,
  "roleplay-regenerate": 9,
  "roleplay-invalid-analysis": 4,
  "roleplay-low-confidence": 7,
  "roleplay-unsafe": 2,
  "roleplay-evaluation-timeout": 3,
  "report-normal": 6,
  "report-rules-rejected": 3,
  "report-quality-limit": 1,
  "scenario-normal": 4,
  "scenario-condition": 2,
}

/** 지난 7일 동안의 실행 기록. 오후 수업 시간대에 몰리게 만든다. */
export function sampleAgentRuns(now = new Date()): AgentRun[] {
  const random = seeded(20_261_110)
  const runs: AgentRun[] = []
  for (let hour = 0; hour < 24 * 7; hour += 1) {
    const at = new Date(now.getTime() - (hour + 1) * 3_600_000)
    const local = at.getHours()
    const busy = local >= 9 && local <= 17 ? 1 : local >= 19 && local <= 21 ? 0.5 : 0.08
    for (const template of AGENT_RUN_TEMPLATES) {
      const expected = (WEIGHT[template.key] / 12) * busy
      const occurrences = Math.floor(expected + random())
      for (let index = 0; index < occurrences; index += 1) {
        const startedAt = new Date(at.getTime() + Math.floor(random() * 3_600_000))
        runs.push(buildAgentRun(template, uuid(runs.length), startedAt, 0.75 + random() * 0.6))
      }
    }
  }
  return runs.sort((a, b) => b.startedAt.localeCompare(a.startedAt))
}

function percentile(values: number[], fraction: number): number {
  if (values.length === 0) return 0
  const sorted = [...values].sort((a, b) => a - b)
  const position = (sorted.length - 1) * fraction
  const low = Math.floor(position)
  const high = Math.ceil(position)
  return Math.round(sorted[low] + (sorted[high] - sorted[low]) * (position - low))
}

function pathCounts(runs: AgentRun[]) {
  return {
    normal: runs.filter((run) => run.path === "NORMAL").length,
    retried: runs.filter((run) => run.path === "RETRIED").length,
    guided: runs.filter((run) => run.path === "GUIDED").length,
    failed: runs.filter((run) => run.path === "FAILED").length,
  }
}

function groupBy<T>(items: T[], key: (item: T) => string): Map<string, T[]> {
  const groups = new Map<string, T[]>()
  for (const item of items) groups.set(key(item), [...(groups.get(key(item)) ?? []), item])
  return groups
}

function bucketMinutes(hours: number): number {
  if (hours <= 1) return 5
  if (hours <= 6) return 15
  if (hours <= 24) return 60
  if (hours <= 72) return 180
  return hours <= 168 ? 360 : 720
}

/** 백엔드 AgentRunQueryService.overview 와 같은 계산. */
export function overviewOf(all: AgentRun[], hours: number, workflow: Workflow | null, now = new Date()): AgentRunOverview {
  const minutes = bucketMinutes(hours)
  const to = new Date(Math.floor(now.getTime() / 60_000) * 60_000 + 60_000)
  const from = new Date(to.getTime() - hours * 3_600_000)
  const runs = all.filter(
    (run) => new Date(run.startedAt) >= from && new Date(run.startedAt) < to && (!workflow || run.workflow === workflow),
  )
  const steps = runs.flatMap((run) => run.steps.map((step) => ({ run, step })))

  const series = []
  for (let at = from.getTime(); at < to.getTime(); at += minutes * 60_000) {
    const inBucket = runs.filter((run) => {
      const time = new Date(run.startedAt).getTime()
      return time >= at && time < at + minutes * 60_000
    })
    series.push({
      at: new Date(at).toISOString(),
      ...pathCounts(inBucket),
      p50Ms: percentile(inBucket.map((run) => run.durationMs), 0.5),
      p95Ms: percentile(inBucket.map((run) => run.durationMs), 0.95),
    })
  }

  const workflows = [...groupBy(runs, (run) => run.workflow)].map(([name, group]) => ({
    workflow: name as Workflow,
    runs: group.length,
    ...pathCounts(group),
    p50Ms: percentile(group.map((run) => run.durationMs), 0.5),
    p95Ms: percentile(group.map((run) => run.durationMs), 0.95),
  }))

  const callSteps = steps.filter(({ step }) => step.kind === "CALL")
  const stepStats = [...groupBy(callSteps, ({ run, step }) => `${run.workflow}|${step.step}`)]
    .map(([key, group]) => ({
      order: group.reduce((sum, { step }) => sum + step.seq, 0) / group.length,
      workflow: key.split("|")[0] as Workflow,
      step: key.split("|")[1],
      calls: group.length,
      passed: group.filter(({ step }) => step.status === "PASSED").length,
      rejected: group.filter(({ step }) => step.status === "REJECTED").length,
      failed: group.filter(({ step }) => step.status === "FAILED").length,
      avgMs: Math.round(group.reduce((sum, { step }) => sum + step.durationMs, 0) / group.length),
      p95Ms: percentile(group.map(({ step }) => step.durationMs), 0.95),
      inputTokens: group.reduce((sum, { step }) => sum + (step.inputTokens ?? 0), 0),
      outputTokens: group.reduce((sum, { step }) => sum + (step.outputTokens ?? 0), 0),
    }))
    .sort((a, b) => a.workflow.localeCompare(b.workflow) || a.order - b.order)
    .map((stat) => ({
      workflow: stat.workflow,
      step: stat.step,
      calls: stat.calls,
      passed: stat.passed,
      rejected: stat.rejected,
      failed: stat.failed,
      avgMs: stat.avgMs,
      p95Ms: stat.p95Ms,
      inputTokens: stat.inputTokens,
      outputTokens: stat.outputTokens,
    }))

  const transitions = [
    ...groupBy(
      steps.filter(({ step }) => step.kind !== "RESULT" && step.ledTo),
      ({ run, step }) => [run.workflow, step.step, step.status, step.verdict ?? "", step.ledTo].join("|"),
    ),
  ]
    .map(([key, group]) => {
      const [name, from, status, verdict, ledTo] = key.split("|")
      return { workflow: name as Workflow, from, status: status as StepStatus, verdict, to: ledTo, count: group.length }
    })
    .sort((a, b) => b.count - a.count)
    .slice(0, 200)

  const codes = [
    ...groupBy(
      steps.filter(({ step }) => step.status !== "PASSED").flatMap(({ run, step }) => step.codes.map((code) => ({ run, step, code }))),
      ({ run, step, code }) => [run.workflow, step.step, code].join("|"),
    ),
  ]
    .map(([key, group]) => {
      const [name, step, code] = key.split("|")
      return { workflow: name as Workflow, step, code, count: group.length }
    })
    .sort((a, b) => b.count - a.count)
    .slice(0, 30)

  const endings = [
    ...groupBy(
      runs.filter((run) => run.path !== "NORMAL"),
      (run) => [run.workflow, run.path, run.result, run.endedBy ?? "", run.reason ?? ""].join("|"),
    ),
  ]
    .map(([key, group]) => {
      const [name, path, result, endedBy, reason] = key.split("|")
      return { workflow: name as Workflow, path: path as RunPath, result, endedBy, reason, count: group.length }
    })
    .sort((a, b) => b.count - a.count)
    .slice(0, 30)

  const roleplay = runs.filter((run) => run.workflow === "ROLEPLAY_TURN")
  const stageNames = [...new Set(roleplay.flatMap((run) => Object.keys(run.stageMs)))]
  const stages = stageNames
    .map((stage) => {
      const values = roleplay.filter((run) => stage in run.stageMs).map((run) => run.stageMs[stage])
      return {
        stage,
        avgMs: Math.round(values.reduce((sum, value) => sum + value, 0) / values.length),
        p95Ms: percentile(values, 0.95),
      }
    })
    .sort((a, b) => b.avgMs - a.avgMs)

  // 백엔드 AgentRunStore.flows 와 같은 묶음: 워크플로우·흐름·결과와 "단계|상태|판정" 순서가 같은 실행끼리.
  const flows = [
    ...groupBy(runs, (run) =>
      [
        run.workflow,
        run.path,
        run.result,
        run.steps
          .filter((step) => step.kind !== "RESULT")
          .map((step) => `${step.step}|${step.status}|${step.verdict ?? ""}`)
          .join(","),
      ].join("#"),
    ),
  ]
    .map(([, group]) => {
      const sample = group[0]
      const recent = [...group].sort((a, b) => b.startedAt.localeCompare(a.startedAt) || a.runId.localeCompare(b.runId))
      return {
        workflow: sample.workflow,
        outcome: (sample.path === "NORMAL" || sample.path === "RETRIED" ? "SUCCESS" : "FAILURE") as Outcome,
        path: sample.path,
        result: sample.result,
        steps: sample.steps
          .filter((step) => step.kind !== "RESULT")
          .map((step) => ({ step: step.step, status: step.status, verdict: step.verdict })),
        count: group.length,
        p50Ms: percentile(group.map((run) => run.durationMs), 0.5),
        recentRunIds: recent.slice(0, 3).map((run) => run.runId),
      }
    })
    .sort((a, b) => b.count - a.count)
    .slice(0, 60)

  const durations = runs.map((run) => run.durationMs)
  return {
    window: { from: from.toISOString(), to: to.toISOString(), hours, bucketMinutes: minutes },
    totals: {
      runs: runs.length,
      ...pathCounts(runs),
      p50Ms: percentile(durations, 0.5),
      p95Ms: percentile(durations, 0.95),
      aiCalls: runs.reduce((sum, run) => sum + run.aiCalls, 0),
      inputTokens: runs.reduce((sum, run) => sum + run.inputTokens, 0),
      outputTokens: runs.reduce((sum, run) => sum + run.outputTokens, 0),
    },
    series,
    workflows,
    steps: stepStats,
    transitions,
    codes,
    endings,
    stages,
    flows,
  }
}

const WORKFLOW_VALUES = ["ROLEPLAY_TURN", "LEARNING_REPORT", "SCENARIO_GENERATION"]
const PATH_VALUES = ["NORMAL", "RETRIED", "GUIDED", "FAILED"]
const OUTCOME_PATHS: Record<string, RunPath[]> = { SUCCESS: ["NORMAL", "RETRIED"], FAILURE: ["GUIDED", "FAILED"] }

/**
 * 조회 세 개의 가짜 응답. denied 면 허용 목록에 없는 계정처럼 404 RESOURCE_NOT_FOUND 로 답한다.
 * 기록은 runs 로 바꿀 수 있고, 주지 않으면 지난 7일치 예시 기록을 쓴다.
 */
export function agentRunHandlers({ denied = false, runs }: { denied?: boolean; runs?: AgentRun[] } = {}) {
  const records = runs ?? sampleAgentRuns()

  function guard(request: Request): Response | null {
    const path = new URL(request.url).pathname
    if (!request.headers.get("Authorization")) return apiError(401, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다.", path)
    if (denied) return apiError(404, "RESOURCE_NOT_FOUND", "요청한 자원을 찾을 수 없습니다.", path)
    return null
  }

  function filters(
    request: Request,
  ): { hours: number; workflow: Workflow | null; paths: RunPath[] } | Response {
    const url = new URL(request.url)
    const hours = Number(url.searchParams.get("hours") ?? 24)
    const workflow = url.searchParams.get("workflow")
    const path = url.searchParams.get("path")
    const outcome = url.searchParams.get("outcome")
    const group = outcome ? OUTCOME_PATHS[outcome] : undefined
    const invalid =
      !Number.isInteger(hours) ||
      hours < 1 ||
      hours > 336 ||
      (workflow && !WORKFLOW_VALUES.includes(workflow)) ||
      (path && !PATH_VALUES.includes(path)) ||
      (outcome && !group) ||
      (path && group && !group.includes(path as RunPath))
    if (invalid) return apiError(400, "INVALID_AGENT_RUN_FILTER", "조회 조건이 올바르지 않습니다.", url.pathname)
    return { hours, workflow: workflow as Workflow | null, paths: path ? [path as RunPath] : (group ?? []) }
  }

  return [
    http.get("*/api/v1/ops/agent-runs/overview", ({ request }) => {
      const blocked = guard(request)
      if (blocked) return blocked
      const filter = filters(request)
      if (filter instanceof Response) return filter
      return HttpResponse.json(envelope(overviewOf(records, filter.hours, filter.workflow)))
    }),
    http.get("*/api/v1/ops/agent-runs/:runId", ({ request, params }) => {
      const blocked = guard(request)
      if (blocked) return blocked
      const run = records.find((record) => record.runId === params.runId)
      if (!run) return apiError(404, "AGENT_RUN_NOT_FOUND", "실행 기록을 찾을 수 없습니다.", new URL(request.url).pathname)
      return HttpResponse.json(envelope(run))
    }),
    http.get("*/api/v1/ops/agent-runs", ({ request }) => {
      const blocked = guard(request)
      if (blocked) return blocked
      const filter = filters(request)
      if (filter instanceof Response) return filter
      const limit = Number(new URL(request.url).searchParams.get("limit") ?? 50)
      const since = Date.now() - filter.hours * 3_600_000
      const listed = records
        .filter(
          (run) =>
            new Date(run.startedAt).getTime() >= since &&
            (!filter.workflow || run.workflow === filter.workflow) &&
            (filter.paths.length === 0 || filter.paths.includes(run.path)),
        )
        .slice(0, limit)
        .map((run) => ({ ...run, stageMs: {}, spans: [], steps: [] }))
      return HttpResponse.json(envelope(listed))
    }),
  ]
}
