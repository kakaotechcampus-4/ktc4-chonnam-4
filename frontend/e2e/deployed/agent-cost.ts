import type { APIRequestContext } from "@playwright/test";

// 에이전트 실행 기록(/api/v1/ops/agent-runs)으로 경로별 시간·비용을 낸다. agent-cost.spec.ts 가 쓴다.
// 대시보드는 호출마다 모델과 입력·출력 토큰을 남기지만 비용은 계산하지 않는다. 여기서 단가를 곱한다.

/**
 * 엘리스 ML API 모델 라이브러리의 Serverless 단가(1M 토큰당 원, 2026-11-11 확인). 모델 이름에 이 글자가 들어 있으면 이 단가를 쓴다.
 * 캐시된 입력의 할인 단가는 넣지 않는다. 대시보드가 캐시 토큰 수를 남기지 않아서, 비용은 실제 청구보다 조금 높은 상한이다.
 */
export const PRICES: Record<string, { input: number; output: number }> = {
  "gemini-3.8-flash": { input: 1063, output: 5315 },
  "gemini-4-argon": { input: 2835, output: 14175 },
};

export type Step = {
  kind: string;
  step: string;
  attempt: number;
  status: string;
  verdict: string | null;
  codes: string[];
  model: string | null;
  finishReason: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  durationMs: number;
};

export type Run = {
  runId: string;
  workflow: string;
  path: string;
  result: string;
  endedBy: string | null;
  reason: string | null;
  inputType: string | null;
  replayed: boolean;
  startedAt: string;
  durationMs: number;
  aiCalls: number;
  inputTokens: number;
  outputTokens: number;
  steps: Step[];
};

export type Priced = Run & {
  krw: number;
  /** 단가표에 없는 모델과 그 토큰. 비용에 넣지 않았다. */
  unpriced: string[];
  /** 토큰이 남지 않는 음성 호출(TTS·STT) 수와 그중 실패 수. */
  speech: { calls: number; failed: number };
};

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function priceOf(model: string) {
  const key = Object.keys(PRICES).find((name) => model.includes(name));
  return key ? PRICES[key] : undefined;
}

/** 한 실행의 비용. 응답을 받기 전에 끊긴 호출(시간 초과·연결 실패)은 토큰이 없어 0 원으로 센다. */
export function price(run: Run): Priced {
  let krw = 0;
  const unpriced: string[] = [];
  const speech = { calls: 0, failed: 0 };
  for (const step of run.steps) {
    if (step.kind !== "CALL") continue;
    // 음성 합성(SPEECH_SYNTHESIS)·음성 인식(SPEECH_TRANSCRIPTION)은 엘리스가 아닌 음성 API 라 단가표에 없다.
    if (step.step.startsWith("SPEECH_")) {
      speech.calls += 1;
      if (step.status === "FAILED") speech.failed += 1;
      continue;
    }
    if (!step.model || step.inputTokens == null) continue;
    const unit = priceOf(step.model);
    if (!unit) {
      unpriced.push(
        `${step.model} ${step.inputTokens}/${step.outputTokens ?? 0}`,
      );
      continue;
    }
    krw +=
      (step.inputTokens * unit.input + (step.outputTokens ?? 0) * unit.output) /
      1_000_000;
  }
  return { ...run, krw, unpriced, speech };
}

/** 실행 기록 하나. 기록은 실행이 끝난 뒤 따로 저장되므로 바로 404 면 잠시 뒤 다시 읽는다. */
export async function fetchRun(
  ctx: APIRequestContext,
  runId: string,
): Promise<Priced | undefined> {
  for (let attempt = 0; attempt < 6; attempt += 1) {
    const res = await ctx.get(`/api/v1/ops/agent-runs/${runId}`);
    if (res.ok()) {
      const { data } = (await res.json()) as { data: Run };
      return price(data);
    }
    if (res.status() !== 404) return undefined;
    await sleep(3_000);
  }
  return undefined;
}

/** 여러 기록을 8개씩 나눠 읽는다. 못 읽은 번호는 빠진다. */
export async function fetchRuns(ctx: APIRequestContext, runIds: string[]) {
  const runs: Priced[] = [];
  for (let i = 0; i < runIds.length; i += 8) {
    const batch = await Promise.all(
      runIds.slice(i, i + 8).map((id) => fetchRun(ctx, id)),
    );
    runs.push(...batch.filter((run): run is Priced => run !== undefined));
  }
  return runs;
}

/** 목록 API(최신순, 한 번에 200건까지). 상세 단계는 들어 있지 않다. */
export async function listRuns(
  ctx: APIRequestContext,
  query: { hours: number; workflow: string; path?: string; limit?: number },
) {
  const params = new URLSearchParams({
    hours: String(query.hours),
    workflow: query.workflow,
    limit: String(query.limit ?? 200),
  });
  if (query.path) params.set("path", query.path);
  const res = await ctx.get(`/api/v1/ops/agent-runs?${params}`);
  if (!res.ok()) throw new Error(`실행 기록 목록 → HTTP ${res.status()}`);
  const { data } = (await res.json()) as { data: Run[] };
  return data;
}

const sec = (ms: number) => `${(ms / 1000).toFixed(1)}s`;
const won = (krw: number) => `₩${krw.toFixed(1)}`;
const mean = (values: number[]) =>
  values.reduce((sum, value) => sum + value, 0) / Math.max(values.length, 1);

function percentile(values: number[], q: number) {
  const sorted = [...values].sort((a, b) => a - b);
  return (
    sorted[Math.min(sorted.length - 1, Math.ceil(q * sorted.length) - 1)] ?? 0
  );
}

export type Row = {
  key: string;
  count: number;
  share: number;
  ms: { min: number; avg: number; p95: number; max: number };
  calls: number;
  krw: { min: number; avg: number; max: number; total: number };
};

/** key 로 묶어 건수·시간(최소·평균·p95·최대)·AI 호출 평균·비용(최소·평균·최대·합계)을 낸다. 건수 많은 순. */
export function summarize(runs: Priced[], key: (run: Priced) => string): Row[] {
  const groups = new Map<string, Priced[]>();
  for (const run of runs) {
    const name = key(run);
    groups.set(name, [...(groups.get(name) ?? []), run]);
  }
  return [...groups.entries()]
    .map(([name, group]) => {
      const ms = group.map((run) => run.durationMs);
      const krw = group.map((run) => run.krw);
      return {
        key: name,
        count: group.length,
        share: group.length / runs.length,
        ms: {
          min: Math.min(...ms),
          avg: mean(ms),
          p95: percentile(ms, 0.95),
          max: Math.max(...ms),
        },
        calls: mean(group.map((run) => run.aiCalls)),
        krw: {
          min: Math.min(...krw),
          avg: mean(krw),
          max: Math.max(...krw),
          total: krw.reduce((sum, value) => sum + value, 0),
        },
      };
    })
    .sort((a, b) => b.count - a.count);
}

/** summarize 결과를 마크다운 표로 쓴다. */
export function table(title: string, rows: Row[]) {
  const lines = [
    `| ${title} | 건수 | 비율 | 시간 최소 · 평균 · p95 · 최대 | AI 호출 평균 | 비용 최소 · 평균 · 최대 | 비용 합계 |`,
    "|---|---:|---:|---|---:|---|---:|",
    ...rows.map(
      (row) =>
        `| ${row.key} | ${row.count} | ${(row.share * 100).toFixed(0)}% | ` +
        `${sec(row.ms.min)} · ${sec(row.ms.avg)} · ${sec(row.ms.p95)} · ${sec(row.ms.max)} | ` +
        `${row.calls.toFixed(1)} | ${won(row.krw.min)} · ${won(row.krw.avg)} · ${won(row.krw.max)} | ${won(row.krw.total)} |`,
    ),
  ];
  return lines.join("\n");
}

/** 경로 이름. 역할극은 끝난 방식(결과·끝낸 단계·이유)까지 붙여야 실패 종류가 갈린다. */
export function pathKey(run: Priced) {
  return `${run.workflow} · ${run.path}`;
}

export function endingKey(run: Priced) {
  const by = [run.endedBy, run.reason].filter(Boolean).join("/");
  return `${run.path} · ${run.result}${by ? ` (${by})` : ""}`;
}

export function speechLine(runs: Priced[]) {
  const calls = runs.reduce((sum, run) => sum + run.speech.calls, 0);
  const failed = runs.reduce((sum, run) => sum + run.speech.failed, 0);
  const unpriced = runs.flatMap((run) => run.unpriced);
  return (
    `음성 호출(TTS·STT) ${calls}번, 그중 실패 ${failed}번(토큰이 남지 않아 비용에서 뺐다)` +
    (unpriced.length ? ` · 단가 없는 호출 ${unpriced.length}번` : "")
  );
}

export { mean, percentile, sec, won };
