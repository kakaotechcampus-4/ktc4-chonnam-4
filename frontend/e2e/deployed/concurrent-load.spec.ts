import {
  expect,
  request,
  test,
  type APIRequestContext,
} from "@playwright/test";
import { isLocal, origin, record } from "./support.js";

// 20~30명이 한꺼번에 쓰는 상황을 가상 사용자로 만든다. 브라우저 대신 화면이 부르는 API 를 같은 순서·같은 헤더(CSRF·
// Idempotency-Key)로 부른다. 브라우저 수십 개는 이 PC 가 먼저 느려져 서버를 잴 수 없어서다(실제 화면은 concurrent-users.spec.ts).
//   강사(API): 가입 → 학급 → 아동 → 최초 결합 활동 → 입장 코드
//   아동(API): 입장 → 퀴즈 3문항(카메라 거부) → 기다림
//   강사(API): 시나리오 준비·승인
//   아동(API): 역할극 시작 → 글자 턴 LOAD_TURNS 번(턴 사이 사람처럼 쉬기) — 503 BUSY·202 는 화면처럼 같은 키로 다시 보낸다
// 끝에 강사 계정을 탈퇴시켜 데이터를 모두 지운다. AI 를 실제로 부르므로 사람 수 × 턴 수만큼 비용이 든다.
//   DEPLOYED_BASE_URL=https://<주소> DEPLOYED_ALLOW_SIGNUP=1 LOAD_USERS=20 LOAD_TURNS=6 \
//     npx playwright test -c playwright.deployed.config.ts --project=pc concurrent-load.spec.ts

const USERS = Number(process.env.LOAD_USERS ?? 20);
const TURNS = Number(process.env.LOAD_TURNS ?? 6);
const THINK_MS = Number(process.env.LOAD_THINK_MS ?? 8_000);
const allowSignup = isLocal || process.env.DEPLOYED_ALLOW_SIGNUP === "1";
const runId = Date.now().toString(36);
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

test.skip(
  !allowSignup,
  "배포 주소에서는 DEPLOYED_ALLOW_SIGNUP=1 일 때만 돈다(강사 계정이 dev DB 에 남는다)",
);

type Json = Record<string, unknown>;
type Call = {
  status: number;
  data: Json | undefined;
  code?: string;
  ms: number;
};
type Turn = { ms: number; statuses: number[]; result: string };

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
const s = (ms: number) => `${(ms / 1000).toFixed(1)}s`;

async function call(
  ctx: APIRequestContext,
  method: "GET" | "POST" | "PUT" | "DELETE",
  path: string,
  opts: { body?: unknown; headers?: Record<string, string> } = {},
): Promise<Call> {
  const t0 = Date.now();
  let headers = { Accept: "application/json", ...opts.headers };
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
    code?: string;
  };
  return {
    status: res.status(),
    data: json.data,
    code: json.error?.code ?? json.code,
    ms: Date.now() - t0,
  };
}

function ok(result: Call, what: string) {
  if (result.status >= 300)
    throw new Error(`${what} → HTTP ${result.status} ${result.code ?? ""}`);
  return result.data!;
}

type VirtualUser = {
  index: number;
  teacher: APIRequestContext;
  child: APIRequestContext;
  auth: Record<string, string>;
  password: string;
  classId?: string;
  childId?: string;
  activityId?: string;
  code?: string;
  turns: Turn[];
  log: string[];
  failedAt?: string;
};

async function prepareTeacher(u: VirtualUser) {
  const email = `e2e-load-${runId}-${u.index}@example.com`;
  const password = u.password;
  ok(
    await call(u.teacher, "POST", "/users", {
      body: {
        name: `부하 강사 ${u.index}`,
        email,
        password,
        termsAgreed: true,
        privacyAgreed: true,
      },
    }),
    "가입",
  );
  const session = ok(
    await call(u.teacher, "POST", "/auth/sessions", {
      body: { email, password },
    }),
    "로그인",
  );
  u.auth = { Authorization: `Bearer ${session.accessToken as string}` };
  u.classId = ok(
    await call(u.teacher, "POST", "/classrooms", {
      body: { name: `부하${runId} 반${u.index}` },
      headers: u.auth,
    }),
    "학급",
  ).classId as string;
  u.childId = ok(
    await call(u.teacher, "POST", `/classrooms/${u.classId}/children`, {
      body: { displayName: `부하${runId} 아동${u.index}` },
      headers: u.auth,
    }),
    "아동",
  ).childId as string;
  u.activityId = ok(
    await call(u.teacher, "POST", "/activities", {
      body: {
        childId: u.childId,
        activityType: "FIRST_COMBINED",
        goal: { title: "친구가 속상할 때 위로하는 말을 한다" },
      },
      headers: { ...u.auth, "Idempotency-Key": crypto.randomUUID() },
    }),
    "활동 배정",
  ).activityId as string;
  u.code = ok(
    await call(u.teacher, "POST", `/children/${u.childId}/access-codes`, {
      headers: { ...u.auth, "Idempotency-Key": crypto.randomUUID() },
    }),
    "입장 코드",
  ).accessCode as string;
}

async function childQuiz(u: VirtualUser) {
  ok(
    await call(u.child, "POST", "/child-access-sessions", {
      body: { accessCode: u.code },
    }),
    "아동 입장",
  );
  const items = ok(
    await call(u.child, "GET", `/activities/${u.activityId}/quiz-items`),
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
    const attempt = ok(
      await call(
        u.child,
        "PUT",
        `/activity-quiz-items/${item.activityQuizId}/attempt`,
        { body: first },
      ),
      "퀴즈 첫 답",
    );
    if (attempt.status !== "FINALIZED") {
      ok(
        await call(
          u.child,
          "POST",
          `/activity-quiz-items/${item.activityQuizId}/hints`,
          { headers: { "Idempotency-Key": crypto.randomUUID() } },
        ),
        "퀴즈 힌트",
      );
      ok(
        await call(
          u.child,
          "PUT",
          `/activity-quiz-items/${item.activityQuizId}/attempt`,
          { body: { ...first, finalResponse: item.choices[1] } },
        ),
        "퀴즈 최종 답",
      );
    }
  }
}

async function approveScenario(u: VirtualUser) {
  const review = ok(
    await call(
      u.teacher,
      "POST",
      `/activities/${u.activityId}/scenario-review`,
      {
        body: { action: "START_OR_RESUME" },
        headers: { ...u.auth, "Idempotency-Key": crypto.randomUUID() },
      },
    ),
    "시나리오 준비",
  );
  const scenario = review.scenario as {
    scenarioId: string;
    scenarioVersion: number;
  };
  ok(
    await call(u.teacher, "PUT", `/activities/${u.activityId}/scenario`, {
      body: {
        reviewId: review.reviewId,
        revision: review.revision,
        scenarioId: scenario.scenarioId,
        scenarioVersion: scenario.scenarioVersion,
      },
      headers: { ...u.auth, "Idempotency-Key": crypto.randomUUID() },
    }),
    "시나리오 승인",
  );
}

/** 한 턴을 보낸다. 503(BUSY)·202(PROCESSING)·네트워크 오류는 화면의 "다시 하기"처럼 같은 키로 다시 보낸다(최대 6번). */
async function sendTurn(
  u: VirtualUser,
  sessionId: string,
  text: string,
): Promise<Turn> {
  const key = crypto.randomUUID();
  const t0 = Date.now();
  const statuses: number[] = [];
  for (let attempt = 0; attempt < 7; attempt += 1) {
    const res = await call(
      u.child,
      "POST",
      `/roleplay-sessions/${sessionId}/text-inputs`,
      {
        body: { text },
        headers: { "Idempotency-Key": key },
      },
    ).catch(() => ({ status: 0, data: undefined, ms: 0 }) as Call);
    statuses.push(res.status);
    if (res.status === 200)
      return {
        ms: Date.now() - t0,
        statuses,
        result: String(res.data?.status),
      };
    if (res.status !== 503 && res.status !== 202 && res.status !== 0)
      return {
        ms: Date.now() - t0,
        statuses,
        result: `HTTP${res.status}:${res.code}`,
      };
    await sleep(3_000);
  }
  return { ms: Date.now() - t0, statuses, result: "GAVE_UP" };
}

async function childRoleplay(u: VirtualUser) {
  // 아이들이 정확히 같은 순간에 시작하지는 않는다. 0~10초 사이에 흩어 시작한다.
  await sleep(Math.random() * 10_000);
  const started = ok(
    await call(
      u.child,
      "POST",
      `/activities/${u.activityId}/roleplay-sessions`,
      { headers: { "Idempotency-Key": crypto.randomUUID() } },
    ),
    "역할극 시작",
  );
  const sessionId = started.sessionId as string;
  for (let i = 0; i < TURNS; i += 1) {
    const turn = await sendTurn(
      u,
      sessionId,
      ANSWERS[(i + u.index) % ANSWERS.length],
    );
    u.turns.push(turn);
    u.log.push(
      `턴${i + 1} ${s(turn.ms)} ${turn.statuses.join(",")} ${turn.result}`,
    );
    const status = ok(
      await call(u.child, "GET", `/activities/${u.activityId}/status`),
      "활동 상태",
    );
    if (status.status === "COMPLETED" || turn.result === "STOPPED") break;
    // 대답을 읽고 다음 말을 적는 시간.
    await sleep(THINK_MS * (0.5 + Math.random()));
  }
}

async function phase(
  users: VirtualUser[],
  label: string,
  step: (u: VirtualUser) => Promise<void>,
) {
  const t0 = Date.now();
  const alive = users.filter((u) => !u.failedAt);
  await Promise.all(
    alive.map(async (u) => {
      try {
        await step(u);
      } catch (error) {
        u.failedAt = `${label}: ${(error as Error).message.split("\n")[0]}`;
      }
    }),
  );
  const failed = alive.filter((u) => u.failedAt).length;
  record(
    test.info(),
    label,
    `${alive.length - failed}/${alive.length} 성공 · ${s(Date.now() - t0)}`,
  );
}

test(`가상 사용자 ${USERS}명이 동시에 퀴즈·시나리오 승인·역할극 ${TURNS}턴을 한다`, async () => {
  test.setTimeout(40 * 60_000);
  const users: VirtualUser[] = await Promise.all(
    Array.from({ length: USERS }, async (_, index) => ({
      index: index + 1,
      teacher: await request.newContext({ baseURL: origin }),
      child: await request.newContext({ baseURL: origin }),
      auth: {},
      password: `pw-${crypto.randomUUID()}`,
      turns: [],
      log: [],
    })),
  );
  try {
    await phase(users, "1 강사 준비", prepareTeacher);
    await phase(users, "2 아동 입장·퀴즈", childQuiz);
    await phase(users, "3 시나리오 준비·승인", approveScenario);
    await phase(users, "4 역할극", childRoleplay);
  } finally {
    // 강사 탈퇴(API)는 계정과 학급·아동·활동·기록을 모두 지운다. 실행마다 dev DB 에 남는 것이 없다.
    await Promise.all(
      users
        .filter((u) => u.auth.Authorization)
        .map((u) =>
          call(u.teacher, "POST", "/users/me/withdrawal", {
            body: { password: u.password },
            headers: u.auth,
          }).catch(() => undefined),
        ),
    );
    const turns = users.flatMap((u) => u.turns);
    const sorted = turns.map((t) => t.ms).sort((a, b) => a - b);
    const pick = (q: number) =>
      sorted[Math.min(sorted.length - 1, Math.floor(q * sorted.length))] ?? 0;
    const results = turns.reduce<Record<string, number>>(
      (acc, t) => ({ ...acc, [t.result]: (acc[t.result] ?? 0) + 1 }),
      {},
    );
    record(test.info(), "턴 수", turns.length);
    record(
      test.info(),
      "턴 시간 p50·p90·p95·최대",
      `${s(pick(0.5))} · ${s(pick(0.9))} · ${s(pick(0.95))} · ${s(sorted.at(-1) ?? 0)}`,
    );
    record(test.info(), "턴 결과", JSON.stringify(results));
    record(
      test.info(),
      "503 BUSY 받은 턴",
      turns.filter((t) => t.statuses.includes(503)).length,
    );
    record(
      test.info(),
      "202 받은 턴",
      turns.filter((t) => t.statuses.includes(202)).length,
    );
    record(
      test.info(),
      "60초 넘은 턴",
      turns.filter((t) => t.ms > 60_000).length,
    );
    for (const u of users)
      console.log(
        `사용자 ${u.index}${u.failedAt ? ` ❌ ${u.failedAt}` : ""}\n  ${u.log.join("\n  ")}`,
      );
    for (const u of users) {
      await u.teacher.dispose();
      await u.child.dispose();
    }
  }
  const failed = users.filter((u) => u.failedAt);
  expect(
    failed.map((u) => `${u.index}: ${u.failedAt}`),
    "중간에 막힌 사용자",
  ).toEqual([]);
});
