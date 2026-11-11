import { request, test, type APIRequestContext } from "@playwright/test";
import {
  endingKey,
  fetchRuns,
  listRuns,
  mean,
  pathKey,
  sec,
  speechLine,
  summarize,
  table,
  won,
  type Priced,
} from "./agent-cost.js";
import { isLocal, origin, record } from "./support.js";

// 에이전트 워크플로우(시나리오 생성·역할극 턴·학습 리포트)가 정상일 때와 실패·비정상이 섞일 때 각각 시간과 비용이 얼마인지 잰다.
// 비용은 실행 기록(/ops/agent-runs)에 남은 호출별 모델·토큰에 엘리스 단가를 곱한다(agent-cost.ts). AGENT_COST=1 일 때만 돈다.
//
// 1) 지난 기록 다시 집계: 실서버에 쌓인 기록을 워크플로우 × 경로별로 읽어 표로 낸다. AI 를 부르지 않는다.
//      AGENT_COST=1 AGENT_COST_HOURS=168 AGENT_COST_SAMPLE=100 npx playwright test -c playwright.deployed.config.ts \
//        --project=pc agent-cost.spec.ts -g "지난 기록"
// 2) 사람 유형별 실행: 정상 아동 N명과 비정상 아동 6명(머뭇거림·엉뚱한 말·뜻 없는 입력·위험한 말·중간에 나감·중복 전송)이
//    동시에 퀴즈 → 시나리오 승인 → 역할극 → 리포트를 간다. 역할극 턴은 응답의 traceId(= 실행 번호)로 기록과 1:1 로 잇고,
//    시나리오·리포트는 실행 번호가 밖에 나오지 않아 시험 시간 구간으로 묶는다(그 사이 다른 사람이 쓰면 섞인다. 섞인 턴 수를 함께 적는다).
//    끝에 강사 계정을 탈퇴시켜 데이터를 지운다. AI 를 실제로 부르므로 실행 1번에 수천 원이 든다.
//      AGENT_COST=1 DEPLOYED_ALLOW_SIGNUP=1 AGENT_COST_NORMAL=3 npx playwright test -c playwright.deployed.config.ts \
//        --project=pc agent-cost.spec.ts -g "사람 유형별"

const enabled = process.env.AGENT_COST === "1";
const allowSignup = isLocal || process.env.DEPLOYED_ALLOW_SIGNUP === "1";
const HOURS = Number(process.env.AGENT_COST_HOURS ?? 168);
const SAMPLE = Number(process.env.AGENT_COST_SAMPLE ?? 100);
const NORMAL = Number(process.env.AGENT_COST_NORMAL ?? 3);
const THINK_MS = Number(process.env.AGENT_COST_THINK_MS ?? 5_000);
const MAX_SENDS = 18;
const REPORT_WAIT_MS = 5 * 60_000;
/** 시나리오 준비 응답이 이보다 오래 걸리면 재사용이 아니라 새로 만든 것으로 본다(재사용은 1초 안). */
const GENERATION_MS = 3_000;
const SCENARIO_MAX_NEXT = 10;
const WORKFLOWS = ["ROLEPLAY_TURN", "LEARNING_REPORT", "SCENARIO_GENERATION"];
const PATHS = ["NORMAL", "RETRIED", "GUIDED", "FAILED"];
const runId = Date.now().toString(36);

test.skip(
  !enabled,
  "AGENT_COST=1 일 때만 돈다(실서버 기록을 많이 읽거나 AI 를 실제로 부른다)",
);

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** 결과 문서를 첨부와 콘솔에 남긴다. */
async function publish(name: string, markdown: string) {
  await test
    .info()
    .attach(name, { body: markdown, contentType: "text/markdown" });
  console.log(`\n${markdown}\n`);
}

test("지난 기록을 워크플로우 × 경로별로 다시 집계한다", async () => {
  test.setTimeout(20 * 60_000);
  const ctx = await request.newContext({ baseURL: origin });
  try {
    const runs: Priced[] = [];
    const sampled: string[] = [];
    for (const workflow of WORKFLOWS)
      for (const path of PATHS) {
        const list = await listRuns(ctx, {
          hours: HOURS,
          workflow,
          path,
          limit: SAMPLE,
        });
        if (list.length === SAMPLE) sampled.push(`${workflow} · ${path}`);
        runs.push(
          ...(await fetchRuns(
            ctx,
            list.map((run) => run.runId),
          )),
        );
      }
    const roleplay = runs.filter((run) => run.workflow === "ROLEPLAY_TURN");
    record(test.info(), "읽은 기록", runs.length);
    await publish(
      "agent-cost-history.md",
      [
        `## 지난 ${HOURS}시간 기록 (경로마다 최신 ${SAMPLE}건까지)`,
        sampled.length
          ? `최신 ${SAMPLE}건만 읽은 경로: ${sampled.join(", ")}`
          : "",
        table("워크플로우 · 경로", summarize(runs, pathKey)),
        "",
        table(
          "역할극 턴: 경로 · 결과 (끝낸 단계/이유)",
          summarize(roleplay, endingKey),
        ),
        "",
        table(
          "리포트: 경로 · 결과 (끝낸 단계/이유)",
          summarize(
            runs.filter((run) => run.workflow === "LEARNING_REPORT"),
            endingKey,
          ),
        ),
        "",
        speechLine(runs),
      ].join("\n"),
    );
  } finally {
    await ctx.dispose();
  }
});

// ── 사람 유형별 실행 ────────────────────────────────────────

const ANSWERS = [
  "괜찮아? 많이 속상했겠다.",
  "무슨 일이 있었는지 말해 줄래?",
  "나도 그런 적 있어서 마음 알 것 같아.",
  "우리 같이 다시 해 보자.",
  "선생님께 같이 말씀드려 볼까?",
  "다음에는 내가 도와줄게.",
  "울어도 괜찮아, 옆에 있을게.",
  "기분이 조금 나아졌으면 좋겠어.",
];
const OFF_TOPIC = [
  "나 오늘 점심에 피자 먹었어!",
  "우리 집 강아지 이름은 콩이야.",
  "이거 언제 끝나? 게임하고 싶어.",
];

type Line = { text: string; kind: string };
type Persona = {
  name: string;
  normal: boolean;
  /** n 번째로 보낼 말. undefined 면 거기서 그만둔다. */
  say: (n: number) => Line | undefined;
  /** n 번째 말을 같은 Idempotency-Key 로 동시에 몇 번 보낼지. */
  copies?: (n: number) => number;
};

const good = (n: number, offset: number): Line => ({
  text: ANSWERS[(n + offset) % ANSWERS.length],
  kind: "정상 답",
});

const PERSONAS: Persona[] = [
  ...Array.from({ length: NORMAL }, (_, i) => ({
    name: `정상 ${i + 1}`,
    normal: true,
    say: (n: number) => good(n, i),
  })),
  {
    name: "머뭇거림",
    normal: false,
    say: (n) =>
      n < 3
        ? {
            text: ["모르겠어", "음… 잘 모르겠어요", "몰라요"][n],
            kind: "모르겠어",
          }
        : good(n, 1),
  },
  {
    name: "엉뚱한 말",
    normal: false,
    say: (n) =>
      n < 6 && n % 2 === 0
        ? { text: OFF_TOPIC[n / 2], kind: "엉뚱한 말" }
        : good(n, 2),
  },
  {
    name: "뜻 없는 입력",
    normal: false,
    say: (n) =>
      n === 0
        ? { text: "ㅁㄴㅇㄹㅁㄴㅇㄹ", kind: "뜻 없는 입력" }
        : n === 1
          ? { text: "asdf qwer zxcv", kind: "뜻 없는 입력" }
          : n === 2
            ? { text: "가나다라마바사".repeat(50), kind: "너무 긴 입력" }
            : good(n, 3),
  },
  {
    name: "위험한 말",
    normal: false,
    say: (n) =>
      n < 2
        ? good(n, 4)
        : n === 2
          ? { text: "걔 때려서 다치게 만들 거야.", kind: "위험한 말" }
          : undefined,
  },
  {
    // 역할극 멈춤 API(PATCH)는 계약에만 있고 구현이 없다. 화면을 닫은 것처럼 더 보내지 않는다.
    name: "중간에 나감",
    normal: false,
    say: (n) => (n < 3 ? good(n, 5) : undefined),
  },
  {
    name: "같은 턴 중복 전송",
    normal: false,
    say: (n) => (n < 3 ? { ...good(n, 6), kind: "중복 전송" } : good(n, 6)),
    copies: (n) => (n < 3 ? 3 : 1),
  },
];

type Json = Record<string, unknown>;
type Call = { status: number; data?: Json; code?: string; traceId?: string };
type Sent = {
  kind: string;
  /** 몇 번째로 보낸 말인지(1부터). */
  order: number;
  ms: number;
  statuses: number[];
  result: string;
  traceIds: string[];
  replayed: number;
};
type Child = {
  persona: Persona;
  index: number;
  teacher: APIRequestContext;
  child: APIRequestContext;
  auth: Record<string, string>;
  password: string;
  activityId?: string;
  childId?: string;
  code?: string;
  sessionId?: string;
  scenarioMs?: number;
  /** 생성이 일어날 때까지 "다른 시나리오 보기"(NEXT)를 누른 횟수. */
  scenarioNext?: number;
  ending?: string;
  reportMs?: number;
  sent: Sent[];
  error?: string;
};

async function call(
  ctx: APIRequestContext,
  method: "GET" | "POST" | "PUT" | "PATCH",
  path: string,
  opts: { body?: unknown; headers?: Record<string, string> } = {},
): Promise<Call> {
  let headers: Record<string, string> = {
    Accept: "application/json",
    ...opts.headers,
  };
  if (method !== "GET") {
    const csrf = await ctx.get("/api/v1/csrf");
    const { data } = (await csrf.json()) as {
      data: { headerName: string; token: string };
    };
    headers = { ...headers, [data.headerName]: data.token };
  }
  const res = await ctx.fetch(`/api/v1${path}`, {
    method,
    headers,
    data: opts.body,
    timeout: 120_000,
  });
  const json = (await res.json().catch(() => ({}))) as {
    data?: Json;
    error?: { code?: string };
    meta?: { traceId?: string };
  };
  return {
    status: res.status(),
    data: json.data,
    code: json.error?.code,
    traceId: json.meta?.traceId ?? res.headers()["x-trace-id"],
  };
}

function ok(result: Call, what: string) {
  if (result.status >= 300)
    throw new Error(`${what} → HTTP ${result.status} ${result.code ?? ""}`);
  return result.data ?? {};
}

const key = () => ({ "Idempotency-Key": crypto.randomUUID() });

async function prepare(c: Child) {
  const email = `e2e-cost-${runId}-${c.index}@example.com`;
  ok(
    await call(c.teacher, "POST", "/users", {
      body: {
        name: `비용 강사 ${c.index}`,
        email,
        password: c.password,
        termsAgreed: true,
        privacyAgreed: true,
      },
    }),
    "가입",
  );
  const session = ok(
    await call(c.teacher, "POST", "/auth/sessions", {
      body: { email, password: c.password },
    }),
    "로그인",
  );
  c.auth = { Authorization: `Bearer ${session.accessToken as string}` };
  const classId = ok(
    await call(c.teacher, "POST", "/classrooms", {
      body: { name: `비용${runId} 반${c.index}` },
      headers: c.auth,
    }),
    "학급",
  ).classId as string;
  c.childId = ok(
    await call(c.teacher, "POST", `/classrooms/${classId}/children`, {
      body: { displayName: `비용${runId} ${c.persona.name}` },
      headers: c.auth,
    }),
    "아동",
  ).childId as string;
  c.activityId = ok(
    await call(c.teacher, "POST", "/activities", {
      body: {
        childId: c.childId,
        activityType: "FIRST_COMBINED",
        goal: { title: "친구가 속상할 때 위로하는 말을 한다" },
      },
      headers: { ...c.auth, ...key() },
    }),
    "활동 배정",
  ).activityId as string;
  c.code = ok(
    await call(c.teacher, "POST", `/children/${c.childId}/access-codes`, {
      headers: { ...c.auth, ...key() },
    }),
    "입장 코드",
  ).accessCode as string;
}

async function quiz(c: Child) {
  ok(
    await call(c.child, "POST", "/child-access-sessions", {
      body: { accessCode: c.code },
    }),
    "아동 입장",
  );
  const items = ok(
    await call(c.child, "GET", `/activities/${c.activityId}/quiz-items`),
    "퀴즈 문항",
  ) as unknown as Array<{
    activityQuizId: string;
    quizType: string;
    choices: string[];
  }>;
  for (const item of items) {
    const camera =
      item.quizType === "SELF_EMOTION_SITUATION"
        ? { technicalFailureReason: "CAMERA_PERMISSION_DENIED" }
        : {};
    const first = { firstResponse: item.choices[0], ...camera };
    const path = `/activity-quiz-items/${item.activityQuizId}`;
    const attempt = ok(
      await call(c.child, "PUT", `${path}/attempt`, { body: first }),
      "퀴즈 첫 답",
    );
    if (attempt.status !== "FINALIZED") {
      ok(
        await call(c.child, "POST", `${path}/hints`, { headers: key() }),
        "퀴즈 힌트",
      );
      ok(
        await call(c.child, "PUT", `${path}/attempt`, {
          body: { ...first, finalResponse: item.choices[1] },
        }),
        "퀴즈 최종 답",
      );
    }
  }
}

/**
 * 승인된 후보가 있으면 재사용본이 바로 오고(AI 호출 없음), 후보가 없거나 다 넘기면 그 요청 안에서 새로 만든다(prepare-scenario.md).
 * 생성 비용을 재려고 강사가 "다른 시나리오 보기"(NEXT)를 생성이 일어날 때까지 누른다. 생성은 수 초가 걸려 응답 시간으로 가른다.
 * 생성 실패(5xx)는 화면처럼 새 키의 START_OR_RESUME 로 다시 연다(최대 2번).
 */
async function approveScenario(c: Child) {
  const t0 = Date.now();
  const prepare = (body: Json) =>
    call(c.teacher, "POST", `/activities/${c.activityId}/scenario-review`, {
      body,
      headers: { ...c.auth, ...key() },
    });
  const timed = async (body: Json) => {
    const t1 = Date.now();
    const res = await prepare(body);
    return { res, ms: Date.now() - t1 };
  };
  let failures = 0;
  let next = 0;
  let { res, ms } = await timed({ action: "START_OR_RESUME" });
  while (ms < GENERATION_MS || res.status >= 500) {
    if (res.status >= 500) {
      if (failures >= 2) break;
      failures += 1;
      ({ res, ms } = await timed({ action: "START_OR_RESUME" }));
      continue;
    }
    if (next >= SCENARIO_MAX_NEXT) break;
    const shown = ok(res, "시나리오 준비");
    next += 1;
    ({ res, ms } = await timed({
      action: "NEXT",
      reviewId: shown.reviewId,
      revision: shown.revision,
    }));
  }
  c.scenarioNext = next;
  c.scenarioMs = Date.now() - t0;
  const review = ok(res, "시나리오 준비");
  const scenario = review.scenario as {
    scenarioId: string;
    scenarioVersion: number;
  };
  ok(
    await call(c.teacher, "PUT", `/activities/${c.activityId}/scenario`, {
      body: {
        reviewId: review.reviewId,
        revision: review.revision,
        scenarioId: scenario.scenarioId,
        scenarioVersion: scenario.scenarioVersion,
      },
      headers: { ...c.auth, ...key() },
    }),
    "시나리오 승인",
  );
}

/** 한 말을 보낸다. 503·202·연결 오류는 화면처럼 같은 키로 다시 보낸다(최대 7번). copies 가 2 이상이면 같은 키로 동시에 보낸다. */
async function send(
  c: Child,
  line: Line,
  copies: number,
  order: number,
): Promise<Sent> {
  const headers = key();
  const t0 = Date.now();
  const statuses: number[] = [];
  const traceIds: string[] = [];
  let replayed = 0;
  const once = () =>
    call(c.child, "POST", `/roleplay-sessions/${c.sessionId}/text-inputs`, {
      body: { text: line.text },
      headers,
    }).catch((): Call => ({ status: 0 }));
  for (let attempt = 0; attempt < 7; attempt += 1) {
    const results = await Promise.all(
      Array.from({ length: attempt === 0 ? copies : 1 }, once),
    );
    for (const res of results) {
      statuses.push(res.status);
      if (res.traceId) traceIds.push(res.traceId);
      if (res.data?.replayed === true) replayed += 1;
    }
    const done = results.find((res) => res.status === 200);
    const final = results.find(
      (res) => res.status !== 503 && res.status !== 202 && res.status !== 0,
    );
    // 결과는 status 와, 안전 중단처럼 다음 행동이 갈리는 경우 action 까지 적는다(DELIVERED/NONE 은 status 만).
    const action = done?.data?.action;
    if (done || final)
      return {
        kind: line.kind,
        order,
        ms: Date.now() - t0,
        statuses,
        traceIds,
        replayed,
        result: done
          ? `${String(done.data?.status)}${action && action !== "NONE" ? `/${String(action)}` : ""}`
          : `HTTP${final!.status}:${final!.code}`,
      };
    await sleep(3_000);
  }
  return {
    kind: line.kind,
    order,
    ms: Date.now() - t0,
    statuses,
    traceIds,
    replayed,
    result: "GAVE_UP",
  };
}

async function roleplay(c: Child) {
  await sleep(Math.random() * 10_000);
  c.sessionId = ok(
    await call(
      c.child,
      "POST",
      `/activities/${c.activityId}/roleplay-sessions`,
      { headers: key() },
    ),
    "역할극 시작",
  ).sessionId as string;
  for (let n = 0; n < MAX_SENDS; n += 1) {
    const line = c.persona.say(n);
    if (!line) {
      c.ending = "더 보내지 않음";
      return;
    }
    const sent = await send(c, line, c.persona.copies?.(n) ?? 1, n + 1);
    c.sent.push(sent);
    // 위험 입력은 STOPPED 또는 NOTICE_UNAVAILABLE + STOP_DIALOGUE 로 끝난다(submit-text-input.md 4절).
    if (
      sent.result.startsWith("STOPPED") ||
      sent.result.endsWith("/STOP_DIALOGUE")
    ) {
      c.ending = "안전 중단";
      await waitReport(c);
      return;
    }
    const status = ok(
      await call(c.child, "GET", `/activities/${c.activityId}/status`),
      "활동 상태",
    );
    if (status.status === "COMPLETED") {
      c.ending = "COMPLETED";
      await waitReport(c);
      return;
    }
    await sleep(THINK_MS * (0.5 + Math.random()));
  }
  c.ending = `말 ${MAX_SENDS}번 안에 안 끝남`;
}

/**
 * 역할극이 끝난 직후부터 리포트가 보일 때까지 기다린다(리포트는 서버가 뒤에서 만든다).
 * 생성 중·실패는 같은 404 라 REPORT_WAIT_MS 안에 안 보이면 "안 보임"이다.
 */
async function waitReport(c: Child) {
  const t0 = Date.now();
  while (Date.now() - t0 < REPORT_WAIT_MS) {
    const res = await call(
      c.teacher,
      "GET",
      `/activities/${c.activityId}/learning-record`,
      { headers: c.auth },
    );
    if (res.status === 200 && res.data?.narrative) {
      c.reportMs = Date.now() - t0;
      return;
    }
    await sleep(5_000);
  }
}

async function phase(
  children: Child[],
  label: string,
  step: (c: Child) => Promise<void>,
) {
  const t0 = Date.now();
  const alive = children.filter((c) => !c.error);
  await Promise.all(
    alive.map(async (c) => {
      try {
        await step(c);
      } catch (error) {
        c.error = `${label}: ${(error as Error).message.split("\n")[0]}`;
      }
    }),
  );
  record(
    test.info(),
    label,
    `${alive.filter((c) => !c.error).length}/${alive.length} 성공 · ${sec(Date.now() - t0)}`,
  );
}

const count = (values: string[]) =>
  Object.entries(
    values.reduce<Record<string, number>>(
      (acc, v) => ({ ...acc, [v]: (acc[v] ?? 0) + 1 }),
      {},
    ),
  )
    .map(([k, v]) => `${k} ${v}`)
    .join(", ");

test("사람 유형별로 정상·비정상 흐름을 끝까지 돌리고 경로별 시간·비용을 낸다", async () => {
  test.skip(
    !allowSignup,
    "배포 주소에서는 DEPLOYED_ALLOW_SIGNUP=1 일 때만 돈다(강사 계정을 만들고 끝에 탈퇴한다)",
  );
  test.setTimeout(60 * 60_000);
  const started = new Date();
  const children: Child[] = await Promise.all(
    PERSONAS.map(async (persona, i) => ({
      persona,
      index: i + 1,
      teacher: await request.newContext({ baseURL: origin }),
      child: await request.newContext({ baseURL: origin }),
      auth: {},
      password: `pw-${crypto.randomUUID()}`,
      sent: [],
    })),
  );
  const ops = await request.newContext({ baseURL: origin });
  try {
    await phase(children, "1 강사 준비", prepare);
    await phase(children, "2 아동 입장·퀴즈", quiz);
    await phase(children, "3 시나리오 생성·승인", approveScenario);
    await phase(children, "4 역할극 → 리포트", roleplay);
  } finally {
    await Promise.all(
      children
        .filter((c) => c.auth.Authorization)
        .map((c) =>
          call(c.teacher, "POST", "/users/me/withdrawal", {
            body: { password: c.password },
            headers: c.auth,
          }).catch(() => undefined),
        ),
    );
  }
  const ended = new Date();

  // 기록 모으기: 턴은 traceId 로, 시나리오·리포트는 시험 시간 구간으로.
  await sleep(10_000);
  const owner = new Map<string, { child: Child; sent: Sent }>();
  for (const c of children)
    for (const s of c.sent)
      for (const id of s.traceIds) owner.set(id, { child: c, sent: s });
  const turns = await fetchRuns(ops, [...owner.keys()]);
  const hours = Math.ceil((Date.now() - started.getTime()) / 3_600_000) + 1;
  const inWindow = (at: string) =>
    at >= started.toISOString() && at <= ended.toISOString();
  const windowRuns = async (workflow: string) =>
    (await listRuns(ops, { hours, workflow })).filter((run) =>
      inWindow(run.startedAt),
    );
  const scenarios = await fetchRuns(
    ops,
    (await windowRuns("SCENARIO_GENERATION")).map((run) => run.runId),
  );
  const reports = await fetchRuns(
    ops,
    (await windowRuns("LEARNING_REPORT")).map((run) => run.runId),
  );
  const others = (await windowRuns("ROLEPLAY_TURN")).filter(
    (run) => !owner.has(run.runId),
  ).length;
  await ops.dispose();

  const costOf = (c: Child) =>
    turns
      .filter((run) => owner.get(run.runId)?.child === c)
      .reduce((sum, run) => sum + run.krw, 0);
  const normals = children.filter(
    (c) => c.persona.normal && c.ending === "COMPLETED",
  );
  const scenarioKrw = mean(scenarios.map((run) => run.krw));
  const reportKrw = mean(reports.map((run) => run.krw));
  const roleplayKrw = mean(normals.map(costOf));

  const people = [
    "| 사람 | 보낸 말 | 끝 | 턴 결과 | 턴 시간 평균 · 최대(화면 기준) | 역할극 비용 | 시나리오 준비(NEXT 횟수) | 역할극 끝 → 리포트 |",
    "|---|---:|---|---|---|---:|---:|---:|",
    ...children.map((c) => {
      const ms = c.sent.map((s) => s.ms);
      return (
        `| ${c.persona.name} | ${c.sent.length} | ${c.error ?? c.ending ?? "-"} | ${count(c.sent.map((s) => s.result))} | ` +
        `${ms.length ? `${sec(mean(ms))} · ${sec(Math.max(...ms))}` : "-"} | ${won(costOf(c))} | ` +
        `${c.scenarioMs ? `${sec(c.scenarioMs)} (${c.scenarioNext ?? 0})` : "-"} | ${c.reportMs != null ? sec(c.reportMs) : "안 보임"} |`
      );
    }),
  ];
  // 정상 아동의 턴만, 몇 번째 말인지로 묶는다. 대화가 길어질수록 앞 대화가 프롬프트에 쌓여 입력 토큰이 는다.
  const byOrder = summarize(
    turns.filter(
      (run) =>
        owner.get(run.runId)?.child.persona.normal &&
        owner.get(run.runId)?.sent.kind === "정상 답",
    ),
    (run) => `${owner.get(run.runId)?.sent.order}번째`,
  ).sort((a, b) => parseInt(a.key) - parseInt(b.key));
  const dupes = children
    .flatMap((c) => c.sent)
    .filter((s) => s.kind === "중복 전송");
  const dupeStatuses = dupes.flatMap((s) => s.statuses);
  const dupeCalls = turns.filter(
    (run) => owner.get(run.runId)?.sent.kind === "중복 전송" && run.aiCalls > 0,
  ).length;
  const markdown = [
    `## 사람 유형별 실행 (${started.toISOString()} ~ ${ended.toISOString()}, 아동 ${children.length}명)`,
    `시험이 보낸 턴 요청의 실행 기록 ${turns.length}건 · 같은 시간 다른 사람의 턴 ${others}건(섞였으면 시나리오·리포트 표에 들어갈 수 있다)`,
    "",
    "### 워크플로우 × 경로",
    table(
      "워크플로우 · 경로",
      summarize([...scenarios, ...turns, ...reports], pathKey),
    ),
    "",
    "### 역할극 턴이 끝난 방식",
    table("경로 · 결과 (끝낸 단계/이유)", summarize(turns, endingKey)),
    "",
    "### 아동이 보낸 말의 종류별",
    table(
      "말의 종류",
      summarize(turns, (run) => owner.get(run.runId)?.sent.kind ?? "?"),
    ),
    "",
    "### 정상 아동의 몇 번째 말인지별",
    table("몇 번째 말", byOrder),
    "",
    "### 사람별(활동 1건)",
    ...people,
    "",
    "### 정상 활동 1건 비용",
    `시나리오 새로 만들기 평균 ${won(scenarioKrw)}(재사용이면 ₩0) + 역할극 평균 ${won(roleplayKrw)}(정상 아동 ${normals.length}명) + 리포트 평균 ${won(reportKrw)} = **${won(scenarioKrw + roleplayKrw + reportKrw)}**`,
    "",
    speechLine([...scenarios, ...turns, ...reports]),
    `같은 키로 동시에 3번 보낸 말 ${dupes.length}개: 응답 ${count(dupeStatuses.map(String))} · AI 를 부른 실행 기록 ${dupeCalls}건`,
  ].join("\n");
  record(
    test.info(),
    "정상 활동 1건 비용",
    won(scenarioKrw + roleplayKrw + reportKrw),
  );
  await publish("agent-cost-run.md", markdown);
  for (const c of children) {
    await c.teacher.dispose();
    await c.child.dispose();
  }
});
