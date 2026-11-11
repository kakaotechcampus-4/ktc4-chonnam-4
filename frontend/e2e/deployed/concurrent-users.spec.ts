import {
  expect,
  request,
  test,
  type APIRequestContext,
  type Browser,
  type Page,
} from "@playwright/test";
import { isLocal, origin, record } from "./support.js";

// 최종 시연처럼 여러 사람이 같은 시각에 테스트 주소를 쓸 때 서비스가 끝까지 도는지 본다.
// 사람마다 브라우저(컨텍스트)를 따로 열어 실제 화면을 누른다. 강사 N명과 아동 N명이 단계마다 동시에 움직인다.
//   강사: 가입 → 학급 → 아동 → 최초 결합 활동 배정 → 입장 코드
//   아동: 코드 입장 → 퀴즈 3문항 → 선생님 기다림
//   강사: 시나리오 준비(AI)·승인
//   아동: 역할극(AI, 턴마다) → 마무리
//   강사: 리포트(AI, 서버가 뒤에서 만든다)
// 사람 수는 CONCURRENT_USERS(기본 4). 배포 주소에서는 DEPLOYED_ALLOW_SIGNUP=1 일 때만 돈다(실행마다 강사 계정이 dev DB 에 남는다).
// 학급은 끝에 지운다(아동·활동·코드가 함께 지워진다). AI 를 실제로 부르므로 실행마다 비용이 든다.
//   DEPLOYED_BASE_URL=https://<주소> DEPLOYED_ALLOW_SIGNUP=1 CONCURRENT_USERS=4 \
//     npx playwright test -c playwright.deployed.config.ts --project=pc concurrent-users.spec.ts

const USERS = Number(process.env.CONCURRENT_USERS ?? 4);
const allowSignup = isLocal || process.env.DEPLOYED_ALLOW_SIGNUP === "1";
const runId = Date.now().toString(36);
const marker = `동시${runId}`;
const CHILD_ANSWERS = [
  "괜찮아? 많이 속상했겠다.",
  "무슨 일이 있었는지 말해 줄래?",
  "나도 그런 적 있어서 마음 알 것 같아.",
  "우리 같이 다시 해 보자.",
  "선생님께 같이 말씀드려 볼까?",
  "다음에는 내가 도와줄게.",
  "울어도 괜찮아, 옆에 있을게.",
  "기분이 조금 나아졌으면 좋겠어.",
];
const MAX_TURNS = 14;

test.skip(
  !allowSignup,
  "배포 주소에서는 DEPLOYED_ALLOW_SIGNUP=1 일 때만 돈다(강사 계정이 dev DB 에 남는다)",
);
test.describe.configure({ mode: "serial" });

type Account = { name: string; email: string; password: string };
type Person = {
  index: number;
  account: Account;
  instructor: Page;
  child: Page;
  classroomName: string;
  childName: string;
  classId?: string;
  childId?: string;
  activityId?: string;
  accessCode?: string;
  log: string[];
};

const seconds = (ms: number) => `${(ms / 1000).toFixed(1)}s`;

function newAccount(tag: string): Account {
  return {
    name: `동시 강사 ${tag}`,
    email:
      `e2e-conc-${runId}-${tag}-${crypto.randomUUID().slice(0, 6)}@example.com`.toLowerCase(),
    password: `pw-${crypto.randomUUID()}`,
  };
}

/** 모두 같은 단계를 동시에 시작하고, 각자 걸린 시간과 실패를 모은다. 한 사람이 실패해도 다른 사람은 끝까지 간다. */
async function together<T>(
  people: Person[],
  label: string,
  step: (person: Person) => Promise<T>,
) {
  const started = Date.now();
  const results = await Promise.allSettled(
    people.map(async (person) => {
      const t0 = Date.now();
      try {
        const value = await step(person);
        person.log.push(`${label} ✅ ${seconds(Date.now() - t0)}`);
        return value;
      } catch (error) {
        person.log.push(
          `${label} ❌ ${seconds(Date.now() - t0)} ${(error as Error).message.split("\n")[0]}`,
        );
        await person.instructor
          .screenshot({
            path: test
              .info()
              .outputPath(`${label}-${person.index}-instructor.png`),
          })
          .catch(() => {});
        await person.child
          .screenshot({
            path: test.info().outputPath(`${label}-${person.index}-child.png`),
          })
          .catch(() => {});
        throw error;
      }
    }),
  );
  const failed = results.filter(
    (result) => result.status === "rejected",
  ).length;
  record(
    test.info(),
    label,
    `${people.length - failed}/${people.length} 성공 · 전체 ${seconds(Date.now() - started)}`,
  );
  return results;
}

// ── 강사 화면 ──────────────────────────────────────────────

async function signUp(page: Page, account: Account) {
  await page.goto("/signup");
  await page.getByLabel("이름").fill(account.name);
  await page.getByLabel("이메일").fill(account.email);
  await page.getByLabel("비밀번호", { exact: true }).fill(account.password);
  await page.getByLabel("비밀번호 확인").fill(account.password);
  await page.getByLabel("전체 동의").check();
  await page.getByRole("button", { name: "가입하기" }).click();
  await expect(page).toHaveURL(/\/classrooms$/, { timeout: 30_000 });
}

async function logIn(page: Page, account: Account) {
  await page.goto("/login");
  await page.getByLabel("이메일").fill(account.email);
  await page.getByLabel("비밀번호", { exact: true }).fill(account.password);
  await page.getByRole("button", { name: "로그인", exact: true }).click();
  await expect(page).toHaveURL(/\/classrooms$/, { timeout: 30_000 });
}

async function createClassroom(page: Page, name: string) {
  await page.goto("/classrooms/new");
  await page.getByPlaceholder("예: 햇살반").fill(name);
  await page.getByRole("button", { name: "저장" }).click();
  await expect(
    page.getByRole("heading", { name: `학급 상세 · ${name}` }),
  ).toBeVisible({ timeout: 30_000 });
  return new URL(page.url()).pathname.split("/").pop()!;
}

async function registerChild(page: Page, childName: string) {
  await page.getByRole("tab", { name: /아동 목록/ }).click();
  const panel = page.getByRole("tabpanel", { name: /아동 목록/ });
  await panel.getByPlaceholder("아동 이름").fill(childName);
  await panel.getByRole("button", { name: "아동 등록" }).click();
  const link = page.getByRole("link", { name: childName, exact: true });
  await expect(link).toHaveCount(1, { timeout: 30_000 });
  return (await link.getAttribute("href"))!.split("/").pop()!;
}

/** 최초 결합 활동을 배정하고 활동 ID 를 돌려준다(배정 응답에서 읽는다). */
async function assignFirstActivity(
  page: Page,
  classId: string,
  childId: string,
) {
  await page.goto(`/activities/new?classId=${classId}&childId=${childId}`);
  const toType = page.getByRole("button", { name: "다음 · 활동 유형 선택" });
  if (await toType.isVisible().catch(() => false)) await toType.click();
  await page.getByRole("radio", { name: "최초 결합 활동" }).click();
  await page.getByRole("button", { name: "다음 · 학습 목표 설정" }).click();
  await page.getByRole("button", { name: "공감 표현" }).click();
  await page.getByRole("radio").first().check();
  await page.getByRole("button", { name: "다음 · 추가 조건" }).click();
  await page.getByRole("button", { name: "다음 · 확인" }).click();
  const assigned = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      /\/api\/v1\/activities$/.test(new URL(response.url()).pathname),
  );
  await page.getByRole("button", { name: "배정하기" }).click();
  const response = await assigned;
  expect(response.status(), "활동 배정 응답").toBeLessThan(300);
  const { data } = (await response.json()) as { data: { activityId: string } };
  await expect(page).toHaveURL(new RegExp(`/children/${childId}$`), {
    timeout: 30_000,
  });
  return data.activityId;
}

async function issueAccessCode(page: Page, classId: string, childId: string) {
  await page.goto(`/classrooms/${classId}/children/${childId}?tab=access-code`);
  await page.getByRole("button", { name: /^코드 (다시 )?받기$/ }).click();
  const code = page
    .getByRole("tabpanel", { name: "접근 코드" })
    .locator("p.font-mono");
  await expect(code).toHaveText(/^\d{4}$/, { timeout: 30_000 });
  return (await code.textContent())!.trim();
}

/** 시나리오를 준비(맞는 후보가 없으면 AI 가 새로 만든다)하고 승인한다. 준비에 걸린 시간을 돌려준다. */
async function approveScenario(page: Page, activityId: string) {
  const t0 = Date.now();
  await page.goto(`/activities/${activityId}/scenario`);
  const toApproval = page.getByRole("button", { name: "승인으로 이동" });
  await expect(toApproval).toBeVisible({ timeout: 4 * 60_000 });
  const prepared = Date.now() - t0;
  await toApproval.click();
  await page.getByLabel("아동에게 노출되는 내용을 검토했습니다.").check();
  await page.getByRole("button", { name: "승인", exact: true }).click();
  await expect(page.getByText("승인했습니다.", { exact: false })).toBeVisible({
    timeout: 60_000,
  });
  return prepared;
}

/** 리포트 화면을 열어 AI 요약이 보일 때까지 다시 연다(화면은 스스로 새로 읽지 않는다). 기다린 시간을 돌려준다. */
async function waitForReport(page: Page, activityId: string, since: number) {
  const deadline = Date.now() + 8 * 60_000;
  while (Date.now() < deadline) {
    await page.goto(`/activities/${activityId}/report`);
    const summary = page.getByRole("heading", { name: "AI 요약" });
    // isVisible 은 기다리지 않는다. 화면이 리포트를 받아 그릴 때까지 기다린 뒤에만 다시 연다.
    const shown = await summary
      .waitFor({ state: "visible", timeout: 10_000 })
      .then(() => true)
      .catch(() => false);
    if (shown) return Date.now() - since;
    await page.waitForTimeout(5_000);
  }
  throw new Error("8분 안에 AI 요약이 보이지 않았다");
}

async function deleteClassroom(page: Page, classId: string, name: string) {
  await page.goto(`/classrooms/${classId}`);
  await page.getByRole("button", { name: "학급 삭제" }).click();
  const dialog = page.getByRole("alertdialog");
  await dialog.getByRole("textbox").fill(name);
  await dialog.getByRole("button", { name: "영구 삭제" }).click();
  await expect(page).toHaveURL(/\/classrooms$/, { timeout: 30_000 });
}

// ── 아동 화면 ──────────────────────────────────────────────

async function enterChild(page: Page, code: string, name: string) {
  await page.goto("/child");
  await expect(page.getByText("입장 코드를 입력해줘!")).toBeVisible();
  for (const digit of code)
    await page.getByRole("button", { name: digit, exact: true }).click();
  await page.getByRole("button", { name: "입장하기" }).click();
  await expect(page).toHaveURL(/\/child\/hello$/, { timeout: 30_000 });
  await expect(page.getByText(`안녕, ${name}!`)).toBeVisible();
  await page.getByRole("button", { name: "시작할래요!" }).click();
  await expect(page).toHaveURL(/\/child\/activities$/);
}

/** 퀴즈 3문항을 푼다. 정답은 서버만 알아서 처음 보기를 고르고, 틀리면 다른 보기로 한 번 더 고른다. 카메라는 허락하지 않는다. */
async function solveQuiz(page: Page) {
  await page
    .getByRole("button", { name: /안 했어요|진행 중/ })
    .first()
    .click();
  await page.getByRole("button", { name: /^(시작하기|이어서 하기)$/ }).click();
  await expect(page).toHaveURL(/\/child\/quiz\//, { timeout: 30_000 });

  const deadline = Date.now() + 4 * 60_000;
  while (Date.now() < deadline) {
    if (
      await page
        .getByText(/다 풀었어!/)
        .isVisible()
        .catch(() => false)
    )
      return;
    const next = page.getByRole("button", {
      name: /^(다음 문제|다 풀었어요)$/,
    });
    if (await next.isVisible().catch(() => false)) {
      await next.click();
      await page.waitForTimeout(500);
      continue;
    }
    const choices = page
      .getByRole("radiogroup", { name: "보기" })
      .getByRole("radio");
    const retry = page.getByRole("button", { name: "다시 골라볼래요" });
    const confirm = page.getByRole("button", {
      name: "이 표정으로 확인할래요",
    });
    if (await retry.isVisible().catch(() => false)) {
      if (await retry.isEnabled().catch(() => false)) {
        await choices.nth(1).click();
        await retry.click();
      } else {
        await choices
          .nth(1)
          .click()
          .catch(() => {});
      }
    } else if (await confirm.isVisible().catch(() => false)) {
      if (
        (await choices.count()) > 0 &&
        !(await page.getByRole("radio", { checked: true }).count())
      )
        await choices
          .first()
          .click()
          .catch(() => {});
      if (await confirm.isEnabled().catch(() => false)) await confirm.click();
    }
    await page.waitForTimeout(700);
  }
  throw new Error("4분 안에 퀴즈를 다 풀지 못했다");
}

async function goWaitForTeacher(page: Page) {
  await page.getByRole("button", { name: "역할극 하러 가기" }).click();
  await expect(page.getByText("선생님이 역할극을 준비하고 있어")).toBeVisible({
    timeout: 30_000,
  });
}

type Turn = { ms: number; http: number[]; status: string };

/**
 * 역할극을 글자로 한다. 턴마다 보낸 뒤 대답이 올 때까지 걸린 시간·HTTP 상태(200·202·503 등)·서버 판정을 남긴다.
 * 보내기 오류 창이 뜨면 화면의 "다시 하기"(같은 키로 다시 보냄)를 누른다. 서버가 끝내면(COMPLETED·STOPPED) 멈춘다.
 */
async function doRoleplay(page: Page, person: Person) {
  // 아동 세션은 메모리에만 있어서 page.goto 로 새로 열면 코드 화면으로 간다. 부르는 쪽이 앱 안에서 목록까지 와 있다.
  await expect(page).toHaveURL(/\/child\/activities$/);
  await page
    .getByRole("button", { name: /진행 중|안 했어요/ })
    .first()
    .click();
  await page.getByRole("button", { name: /^(시작하기|이어서 하기)$/ }).click();
  await expect(page).toHaveURL(/\/child\/roleplay\//, { timeout: 30_000 });
  await page
    .getByRole("button", { name: "글자로 답할래요" })
    .click({ timeout: 90_000 });

  const turns: Turn[] = [];
  let current: Turn | null = null;
  page.on("response", async (response) => {
    if (!current || !/\/text-inputs$/.test(new URL(response.url()).pathname))
      return;
    current.http.push(response.status());
    const body = await response.json().catch(() => null);
    current.status =
      body?.data?.status ?? body?.code ?? String(response.status());
  });

  for (let turn = 0; turn < MAX_TURNS; turn += 1) {
    const finish = page.getByRole("button", { name: "이야기 마무리하기" });
    const stopped = page.getByText("오늘 이야기는 여기까지 할게");
    if (await finish.isVisible().catch(() => false)) break;
    if (await stopped.isVisible().catch(() => false)) break;

    const box = page.getByRole("textbox", { name: "답 적기" });
    await expect(box).toBeEditable({ timeout: 120_000 });
    current = { ms: 0, http: [], status: "" };
    const t0 = Date.now();
    await box.fill(CHILD_ANSWERS[(turn + person.index) % CHILD_ANSWERS.length]);
    await page.getByRole("button", { name: "보낼래요" }).click();

    // 대답이 오거나(입력창이 다시 열림), 끝나거나, 오류 창이 뜰 때까지 기다린다. 오류 창은 같은 키로 다시 보낸다(최대 5번).
    let retries = 0;
    for (;;) {
      await page.waitForTimeout(500);
      if (Date.now() - t0 > 3 * 60_000)
        throw new Error(`턴 ${turn + 1} 이 3분 안에 끝나지 않았다`);
      const again = page.getByRole("button", { name: "다시 하기" });
      if (await again.isVisible().catch(() => false)) {
        if (++retries > 5)
          throw new Error(
            `턴 ${turn + 1} 다시 하기 5번 실패 (${current.http.join(",")})`,
          );
        await page.waitForTimeout(2_000);
        await again.click().catch(() => {});
        continue;
      }
      if (await finish.isVisible().catch(() => false)) break;
      if (await stopped.isVisible().catch(() => false)) break;
      const thinking = await page
        .getByText("생각하고 있어요…")
        .isVisible()
        .catch(() => false);
      if (!thinking && (await box.isEditable().catch(() => false))) break;
    }
    current.ms = Date.now() - t0;
    turns.push(current);
    person.log.push(
      `  턴${turn + 1} ${seconds(current.ms)} http=${current.http.join(",")} ${current.status}`,
    );
    current = null;
  }

  const finish = page.getByRole("button", { name: "이야기 마무리하기" });
  if (await finish.isVisible().catch(() => false)) {
    await finish.click();
    await page.getByRole("button", { name: "다음으로" }).click();
    await expect(page.getByText("스탬프를 받았어요!")).toBeVisible({
      timeout: 30_000,
    });
    return { turns, ended: "COMPLETED" as const };
  }
  if (
    await page
      .getByText("오늘 이야기는 여기까지 할게")
      .isVisible()
      .catch(() => false)
  )
    return { turns, ended: "STOPPED" as const };
  return { turns, ended: "TURN_LIMIT_IN_TEST" as const };
}

// ── API(같은 요청 겹치기) ───────────────────────────────────

async function api(): Promise<APIRequestContext> {
  return request.newContext({ baseURL: origin });
}

async function csrf(ctx: APIRequestContext) {
  const res = await ctx.get("/api/v1/csrf");
  const { data } = (await res.json()) as {
    data: { headerName: string; token: string };
  };
  return { [data.headerName]: data.token };
}

async function apiSignUp(ctx: APIRequestContext, account: Account) {
  const created = await ctx.post("/api/v1/users", {
    data: { ...account, termsAgreed: true, privacyAgreed: true },
    headers: await csrf(ctx),
  });
  expect(created.status(), "가입").toBeLessThan(300);
  const session = await ctx.post("/api/v1/auth/sessions", {
    data: { email: account.email, password: account.password },
    headers: await csrf(ctx),
  });
  expect(session.status(), "로그인").toBeLessThan(300);
  const { data } = (await session.json()) as { data: { accessToken: string } };
  return { Authorization: `Bearer ${data.accessToken}` };
}

async function apiPost<T>(
  ctx: APIRequestContext,
  path: string,
  body: unknown,
  headers: Record<string, string>,
) {
  const res = await ctx.post(path, {
    data: body,
    headers: { ...(await csrf(ctx)), ...headers },
  });
  return {
    status: res.status(),
    data: ((await res.json().catch(() => ({}))) as { data?: T }).data,
  };
}

// ── 테스트 ────────────────────────────────────────────────

async function openPeople(browser: Browser, count: number): Promise<Person[]> {
  return Promise.all(
    Array.from({ length: count }, async (_, index) => {
      const instructorContext = await browser.newContext({
        locale: "ko-KR",
        timezoneId: "Asia/Seoul",
      });
      const childContext = await browser.newContext({
        locale: "ko-KR",
        timezoneId: "Asia/Seoul",
      });
      // 버튼을 못 찾으면 테스트 전체 시간(45분)까지 기다리지 않고 1분 안에 실패한다.
      instructorContext.setDefaultTimeout(60_000);
      childContext.setDefaultTimeout(60_000);
      return {
        index,
        account: newAccount(String(index + 1)),
        instructor: await instructorContext.newPage(),
        child: await childContext.newPage(),
        classroomName: `${marker} 반${index + 1}`,
        childName: `${marker} 아동${index + 1}`,
        log: [],
      };
    }),
  );
}

test(`${USERS}명이 동시에 강사 준비 → 아동 퀴즈 → 시나리오 승인 → 역할극 → 리포트까지 끝낸다`, async ({
  browser,
}, testInfo) => {
  test.setTimeout(45 * 60_000);
  const people = await openPeople(browser, USERS);
  let alive = people;
  const keep = (results: PromiseSettledResult<unknown>[]) => {
    alive = alive.filter((_, i) => results[i].status === "fulfilled");
  };
  const roleplayResults = new Map<
    number,
    Awaited<ReturnType<typeof doRoleplay>>
  >();
  const reportWaits = new Map<number, number>();
  const roleplayEndedAt = new Map<number, number>();

  try {
    keep(
      await together(alive, "1 강사 가입·학급·아동·활동·코드", async (p) => {
        await signUp(p.instructor, p.account);
        p.classId = await createClassroom(p.instructor, p.classroomName);
        p.childId = await registerChild(p.instructor, p.childName);
        p.activityId = await assignFirstActivity(
          p.instructor,
          p.classId,
          p.childId,
        );
        p.accessCode = await issueAccessCode(
          p.instructor,
          p.classId,
          p.childId,
        );
      }),
    );
    keep(
      await together(alive, "2 아동 입장·퀴즈", async (p) => {
        await enterChild(p.child, p.accessCode!, p.childName);
        await solveQuiz(p.child);
        await goWaitForTeacher(p.child);
      }),
    );
    keep(
      await together(alive, "3 시나리오 준비·승인(AI)", async (p) => {
        const ms = await approveScenario(p.instructor, p.activityId!);
        p.log.push(`  시나리오 준비 ${seconds(ms)}`);
      }),
    );
    // 아동은 기다림 화면에서 "내 활동으로 돌아가기"로 앱 안에서 목록으로 간다(새로 열면 아동 세션이 사라진다).
    keep(
      await together(alive, "4 아동 역할극(AI)", async (p) => {
        await p.child
          .getByRole("button", { name: "내 활동으로 돌아가기" })
          .click();
        await expect(p.child).toHaveURL(/\/child\/activities$/);
        const result = await doRoleplay(p.child, p);
        roleplayResults.set(p.index, result);
        roleplayEndedAt.set(p.index, Date.now());
        p.log.push(`  역할극 끝: ${result.ended} · ${result.turns.length}턴`);
        if (result.ended === "TURN_LIMIT_IN_TEST")
          throw new Error(`${MAX_TURNS}턴 안에 서버가 역할극을 끝내지 않았다`);
      }),
    );
    keep(
      await together(
        alive.filter(
          (p) => roleplayResults.get(p.index)?.ended === "COMPLETED",
        ),
        "5 리포트(AI)",
        async (p) => {
          const ms = await waitForReport(
            p.instructor,
            p.activityId!,
            roleplayEndedAt.get(p.index)!,
          );
          reportWaits.set(p.index, ms);
          p.log.push(`  리포트 AI 요약까지 ${seconds(ms)}(역할극 끝난 뒤)`);
        },
      ),
    );
  } finally {
    await together(
      people.filter((p) => p.classId),
      "6 정리(학급 삭제)",
      (p) => deleteClassroom(p.instructor, p.classId!, p.classroomName),
    );
    // 사람별 기록과 숫자 요약을 보고서에 남긴다.
    for (const p of people) {
      const lines = [`사람 ${p.index + 1}`, ...p.log].join("\n");
      console.log(lines);
      testInfo.annotations.push({
        type: `사람 ${p.index + 1}`,
        description: lines,
      });
    }
    const turns = [...roleplayResults.values()].flatMap((r) => r.turns);
    if (turns.length) {
      const sorted = turns.map((t) => t.ms).sort((a, b) => a - b);
      const pick = (q: number) =>
        sorted[Math.min(sorted.length - 1, Math.floor(q * sorted.length))];
      record(testInfo, "역할극 턴 수", turns.length);
      record(
        testInfo,
        "턴 시간 p50·p95·최대",
        `${seconds(pick(0.5))} · ${seconds(pick(0.95))} · ${seconds(sorted.at(-1)!)}`,
      );
      record(
        testInfo,
        "503 BUSY 받은 턴",
        turns.filter((t) => t.http.includes(503)).length,
      );
      record(
        testInfo,
        "202 PROCESSING 받은 턴",
        turns.filter((t) => t.http.includes(202)).length,
      );
      record(
        testInfo,
        "DELIVERED 아닌 턴",
        turns
          .filter((t) => t.status !== "DELIVERED")
          .map((t) => t.status)
          .join(",") || "없음",
      );
    }
    // 실패 시 trace 를 남기는 설정에서 컨텍스트 8개를 닫으면 Windows 에서 trace 파일 복사가 겹쳐 ENOENT 가 날 때가 있다.
    // 결과와 상관없는 정리 단계라 실패해도 넘긴다.
    for (const p of people) {
      await p.instructor
        .context()
        .close()
        .catch(() => {});
      await p.child
        .context()
        .close()
        .catch(() => {});
    }
  }
  expect(alive.length, "모든 사람이 끝까지 갔다").toBe(USERS);
});

test("같은 강사 계정으로 3곳에서 동시에 로그인해 함께 써도 서로 끊기지 않는다", async ({
  browser,
}) => {
  test.setTimeout(5 * 60_000);
  const account = newAccount("shared");
  const setup = await api();
  await apiSignUp(setup, account);
  await setup.dispose();

  const pages = await Promise.all(
    [0, 1, 2].map(async () =>
      (await browser.newContext({ locale: "ko-KR" })).newPage(),
    ),
  );
  const names = pages.map((_, i) => `${marker} 같이반${i + 1}`);
  const ids: string[] = [];
  try {
    await Promise.all(pages.map((page) => logIn(page, account)));
    ids.push(
      ...(await Promise.all(
        pages.map((page, i) => createClassroom(page, names[i])),
      )),
    );
    // 모두가 서로 만든 학급 3개를 본다(먼저 로그인한 곳이 끊기지 않았다).
    await Promise.all(
      pages.map(async (page) => {
        await page.goto("/classrooms");
        await expect(page).toHaveURL(/\/classrooms$/);
        for (const name of names)
          await expect(
            page.getByText(name, { exact: true }).first(),
          ).toBeVisible({ timeout: 30_000 });
      }),
    );
  } finally {
    for (const [i, id] of ids.entries())
      await deleteClassroom(pages[0], id, names[i]).catch(() => {});
    for (const page of pages) await page.context().close();
  }
});

test("같은 요청을 동시에 여러 번 보내도 한 번만 처리된다(멱등성)", async () => {
  const ctx = await api();
  const auth = await apiSignUp(ctx, newAccount("idem"));
  const classroom = await apiPost<{ classId: string }>(
    ctx,
    "/api/v1/classrooms",
    { name: `${marker} 멱등반` },
    auth,
  );
  expect(classroom.status).toBeLessThan(300);
  const classId = classroom.data!.classId;
  try {
    const child = await apiPost<{ childId: string }>(
      ctx,
      `/api/v1/classrooms/${classId}/children`,
      { displayName: `${marker} 멱등` },
      auth,
    );
    const childId = child.data!.childId;

    // 배정하기를 여러 번 누르거나 응답을 못 받아 다시 보내도(같은 Idempotency-Key) 활동은 하나다.
    const key = crypto.randomUUID();
    const body = {
      childId,
      activityType: "FIRST_COMBINED",
      goal: { title: "친구의 마음에 공감하기" },
    };
    const assigned = await Promise.all(
      Array.from({ length: 5 }, () =>
        apiPost<{ activityId: string }>(ctx, "/api/v1/activities", body, {
          ...auth,
          "Idempotency-Key": key,
        }),
      ),
    );
    const okIds = new Set(
      assigned.filter((r) => r.status < 300).map((r) => r.data!.activityId),
    );
    record(
      test.info(),
      "같은 키 활동 배정 5번 동시",
      assigned.map((r) => r.status).join(","),
    );
    expect(okIds.size, "같은 키로 만든 활동 ID 는 하나다").toBe(1);
    const listed = await ctx.get(`/api/v1/children/${childId}/activities`, {
      headers: auth,
    });
    const items = (
      (await listed.json()) as { data: unknown[] | { content?: unknown[] } }
    ).data;
    const count = Array.isArray(items)
      ? items.length
      : (items.content?.length ?? -1);
    expect(count, "아동의 활동 목록에 하나만 있다").toBe(1);

    // 코드 받기를 같은 키로 여러 번 동시에 → 코드는 하나.
    const codeKey = crypto.randomUUID();
    const codes = await Promise.all(
      Array.from({ length: 3 }, () =>
        apiPost<{ accessCode: string }>(
          ctx,
          `/api/v1/children/${childId}/access-codes`,
          undefined,
          { ...auth, "Idempotency-Key": codeKey },
        ),
      ),
    );
    record(
      test.info(),
      "같은 키 코드 받기 3번 동시",
      codes.map((r) => r.status).join(","),
    );
    const codeSet = new Set(
      codes.filter((r) => r.status < 300).map((r) => r.data!.accessCode),
    );
    expect(codeSet.size, "같은 키로 받은 코드는 하나다").toBe(1);

    // 같은 코드로 기기 3대가 동시에 입장 → 결과만 기록한다(정책 확인용).
    const code = [...codeSet][0];
    const entries = await Promise.all(
      [0, 1, 2].map(async () => {
        const device = await api();
        const res = await device.post("/api/v1/child-access-sessions", {
          data: { accessCode: code },
          headers: await csrf(device),
        });
        const status = res.status();
        await device.dispose();
        return status;
      }),
    );
    record(test.info(), "같은 코드로 3대 동시 입장", entries.join(","));
    expect(
      entries.every((s) => s < 500),
      "동시 입장이 서버 오류(5xx)를 내지 않는다",
    ).toBe(true);
  } finally {
    await ctx
      .delete(`/api/v1/classrooms/${classId}`, {
        headers: { ...(await csrf(ctx)), ...auth },
      })
      .catch(() => {});
    await ctx.dispose();
  }
});
