import { screen, waitFor, within } from "@testing-library/react"
import type { UserEvent } from "@testing-library/user-event"
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest"
import { http, HttpResponse } from "msw"
import { router } from "@/router"
import {
  apiError,
  childAccessCodes,
  envelope,
  ROLEPLAY_OPENING,
  ROLEPLAY_SCRIPT,
  storedRoleplaySession,
} from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { CHILD_ACTIVITY_ID, enterAsChild, renderRoutes, seedChildActivity } from "@/test/render"
import * as api from "./api"

// S2-YOON-01 아동 화면 오류·로딩·재입력 점검, S3-YOON-01 상태 규칙 통일 확인 (VS-003·006·010).
// 아동 화면은 모두 실제 API(contracts/api/v1)를 부르고, 테스트는 MSW 가짜 서버(test/msw/handlers.ts)로 대신한다.
// 일부러 실패시키거나 응답을 붙잡아 두려면 server.use 로 그 요청 하나만 바꾼다.
// - 기술 오류는 오답이 아니므로 같은 요청(같은 본문·같은 Idempotency-Key)을 그대로 다시 보낸다(퀴즈 VS-006, 역할극 턴 VS-011).
// - 응답을 기다리는 동안에는 버튼을 막아 같은 요청이 두 번 가지 않게 한다.
// 의도적으로 동작을 바꾸면 이 테스트도 같이 고치세요.

vi.mock("./api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api")>()
  return {
    ...actual,
    verifyAccessCode: vi.fn(actual.verifyAccessCode),
    getMyActivities: vi.fn(actual.getMyActivities),
  }
})

const QUIZ_ACTIVITY = CHILD_ACTIVITY_ID
const INDEPENDENT_QUIZ = "40000000-0000-4000-8000-000000000007"
const ENTRY_URL = "*/api/v1/child-access-sessions"
const QUIZ_ITEMS_URL = "*/api/v1/activities/:activityId/quiz-items"
const ATTEMPT_URL = "*/api/v1/activity-quiz-items/:activityQuizId/attempt"
const STATUS_URL = "*/api/v1/activities/:activityId/status"
const START_URL = "*/api/v1/activities/:activityId/roleplay-sessions"
const TEXT_INPUT_URL = "*/api/v1/roleplay-sessions/:sessionId/text-inputs"
const VALID_CODE = childAccessCodes.valid
const GREETING = "안녕, 김하늘!"
const speech = { available: false, url: null }

/** 응답을 붙잡아 두었다가 원할 때 풀어 주는 약속. 로딩 중 화면을 보려고 쓴다. */
function deferred<T = void>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

type SentRequest = { method: string; path: string; key: string | null; body: string }

/** 가짜 서버에 간 요청을 모은다(CSRF 조회 제외). 본문·Idempotency-Key 로 "같은 요청을 다시 보냈는지" 본다. */
let sent: SentRequest[] = []
const sentTo = (pattern: RegExp) => sent.filter((request) => pattern.test(request.path))

function openChildPage(path: string, seed: Parameters<typeof seedChildActivity>[0] = {}) {
  enterAsChild()
  seedChildActivity(seed)
  return renderRoutes(router.routes, path)
}

/** 역할극은 서버처럼 퀴즈를 마쳐야 시작된다. */
function openRoleplay() {
  return openChildPage(`/child/roleplay/${QUIZ_ACTIVITY}`, { quizFinished: true })
}

/** 마지막 1문항만 남은 독립 퀴즈(친구 감정 10문항). 마지막 문항은 이미지 채움 문항이고 정답은 "기쁨"이다. */
function openLastIndependentQuestion() {
  enterAsChild()
  seedChildActivity({ activityId: INDEPENDENT_QUIZ, activityType: "OTHER_EMOTION_QUIZ", quizFinished: 9 })
  return renderRoutes(router.routes, `/child/quiz/${INDEPENDENT_QUIZ}`)
}

async function finishLastIndependentQuestion(user: UserEvent) {
  await user.click(await screen.findByRole("radio", { name: /기쁨/ }))
  await user.click(screen.getByRole("button", { name: "이 표정으로 확인할래요" }))
  await user.click(await screen.findByRole("button", { name: "다 풀었어요" }))
  await user.click(await screen.findByRole("button", { name: "스탬프 받으러 가기" }))
}

async function pressCode(user: UserEvent, code: string) {
  for (const digit of code) {
    await user.click(screen.getByRole("button", { name: digit }))
  }
}

async function openTextComposer(user: UserEvent) {
  await user.click(await screen.findByRole("button", { name: "글자로 답할래요" }))
  return screen.getByRole("textbox", { name: "답 적기" })
}

/** 가짜 서버 대본은 두 번째 답에서 목표를 달성해 활동을 완료한다. 마무리 화면까지 간다. */
async function finishRoleplay(user: UserEvent) {
  const input = await openTextComposer(user)
  await user.type(input, "아플 것 같아{Enter}")
  await screen.findByText(ROLEPLAY_SCRIPT[0])
  await user.type(input, "속상할 것 같아{Enter}")
  await user.click(await screen.findByRole("button", { name: "이야기 마무리하기" }))
}

beforeAll(() => {
  // jsdom 에는 scrollIntoView 가 없다. 역할극은 새 말풍선이 생길 때마다 맨 아래로 내리므로 빈 함수로 채운다.
  Element.prototype.scrollIntoView = vi.fn()
})

beforeEach(() => {
  vi.clearAllMocks()
  sent = []
  server.events.on("request:start", async ({ request }) => {
    const path = new URL(request.url).pathname
    if (path.endsWith("/csrf")) return
    sent.push({
      method: request.method,
      path,
      key: request.headers.get("Idempotency-Key"),
      body: await request.clone().text(),
    })
  })
})

afterEach(() => {
  server.events.removeAllListeners("request:start")
})

describe("오류가 나면 모달로 알리고 같은 요청을 다시 보낸다", () => {
  it("퀴즈 답 제출이 실패하면 같은 답을 다시 보내고 판정을 이어 간다", async () => {
    server.use(http.put(ATTEMPT_URL, () => HttpResponse.error(), { once: true }))
    const { user } = openChildPage(`/child/quiz/${QUIZ_ACTIVITY}`)

    await user.click(await screen.findByRole("radio", { name: /기쁨/ }))
    await user.click(screen.getByRole("button", { name: "이 표정으로 확인할래요" }))

    const dialog = await screen.findByRole("dialog")
    await user.click(within(dialog).getByRole("button", { name: "다시 하기" }))

    expect(await screen.findByText("네 마음을 알려줘서 고마워!")).toBeInTheDocument()
    const puts = sent.filter((request) => request.method === "PUT")
    expect(puts).toHaveLength(2)
    expect(puts[1].body).toBe(puts[0].body)
  })

  it("역할극 대화가 실패하면 같은 요청 키로 다시 보내고, 아이 답은 한 번만 보인다", async () => {
    server.use(http.post(TEXT_INPUT_URL, () => HttpResponse.error(), { once: true }))
    const { user } = openRoleplay()

    const input = await openTextComposer(user)
    await user.type(input, "아플 것 같아{Enter}")

    const dialog = await screen.findByRole("dialog")
    await user.click(within(dialog).getByRole("button", { name: "다시 하기" }))

    expect(await screen.findByText(ROLEPLAY_SCRIPT[0])).toBeInTheDocument()
    const [first, retry] = sentTo(/\/text-inputs$/)
    expect(retry.key).toBe(first.key)
    expect(JSON.parse(retry.body)).toEqual({ text: "아플 것 같아" })
    expect(screen.getAllByText("“아플 것 같아”")).toHaveLength(1)
  })

  it("완료 확인이 실패하면 같은 키로 다시 보내 턴은 한 번만 저장되고 마무리로 이어 간다", async () => {
    const { user } = openRoleplay()
    const input = await openTextComposer(user)
    await user.type(input, "아플 것 같아{Enter}")
    await screen.findByText(ROLEPLAY_SCRIPT[0])

    server.use(http.get(STATUS_URL, () => HttpResponse.error(), { once: true }))
    await user.type(input, "속상할 것 같아{Enter}")
    const dialog = await screen.findByRole("dialog")
    await user.click(within(dialog).getByRole("button", { name: "다시 하기" }))

    expect(await screen.findByRole("button", { name: "이야기 마무리하기" })).toBeInTheDocument()
    const answers = sentTo(/\/text-inputs$/)
    expect(answers[2].key).toBe(answers[1].key)
    expect(storedRoleplaySession(QUIZ_ACTIVITY)?.turns).toHaveLength(2)
  })

  it("역할극을 마치면 서버가 완료한 활동의 스탬프 화면으로 간다", async () => {
    const { user, router: memoryRouter } = openRoleplay()

    await finishRoleplay(user)
    await user.click(await screen.findByRole("button", { name: "다음으로" }))

    expect(await screen.findByText("스탬프를 받았어요!")).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe(`/child/done/${QUIZ_ACTIVITY}`)
    // 완료는 서버가 정했다. 화면은 완료를 요청하지 않고 상태만 읽었다.
    expect(sent.filter((request) => request.method !== "GET").map((request) => request.path)).not.toContainEqual(
      expect.stringMatching(/complet/),
    )
  })

  it("독립 퀴즈 완료 확인이 실패하면 다시 확인해 스탬프 화면으로 간다", async () => {
    server.use(http.get(STATUS_URL, () => HttpResponse.error(), { once: true }))
    const { user, router: memoryRouter } = openLastIndependentQuestion()

    await finishLastIndependentQuestion(user)
    const dialog = await screen.findByRole("dialog")
    await user.click(within(dialog).getByRole("button", { name: "다시 하기" }))

    expect(await screen.findByText("스탬프를 받았어요!")).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe(`/child/done/${INDEPENDENT_QUIZ}`)
  })

  it.each([
    ["인사", "/child/hello", "활동을 불러오지 못했어요", "시작할래요!"],
    ["활동 소개", `/child/activities/${QUIZ_ACTIVITY}`, "활동을 불러오지 못했어요", "친구 마음 알아보기"],
    ["내 활동", "/child/activities", "활동을 불러오지 못했어요", "친구 마음 알아보기"],
  ] as const)("%s 을 불러오지 못하면 모달이 뜨고, 다시 하면 화면이 정상으로 돌아온다", async (_name, path, message, ready) => {
    vi.mocked(api.getMyActivities).mockRejectedValueOnce(new api.ChildApiError(0, api.NETWORK_ERROR, "끊김"))
    const { user } = openChildPage(path)

    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByText(/불러오지 못했어요|연결이 잠깐 끊겼어요/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole("button", { name: /다시/ }))

    expect(await screen.findByText(ready)).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
    void message
  })

  it.each([
    ["표정 퀴즈", `/child/quiz/${QUIZ_ACTIVITY}`, () => server.use(http.get(QUIZ_ITEMS_URL, () => apiError(500, "INTERNAL_ERROR", "오류", "/"), { once: true })), "퀴즈를 불러오지 못했어요", "1/3 문항", false],
    ["역할극", `/child/roleplay/${QUIZ_ACTIVITY}`, () => server.use(http.post(START_URL, () => apiError(500, "INTERNAL_ERROR", "오류", "/"), { once: true })), "역할극을 불러오지 못했어요", ROLEPLAY_OPENING, true],
  ] as const)("%s 을 불러오지 못하면 모달이 뜨고, 다시 하면 화면이 정상으로 돌아온다", async (_name, path, fail, message, ready, quizFinished) => {
    fail()
    const { user } = openChildPage(path, { quizFinished })

    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByText(message)).toBeInTheDocument()
    await user.click(within(dialog).getByRole("button", { name: "다시 하기" }))

    expect(await screen.findByText(ready)).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
  })

  it("역할극을 다시 불러와도 같은 시작 키를 써서 세션은 하나만 생긴다", async () => {
    server.use(http.post(START_URL, () => HttpResponse.error(), { once: true }))
    const { user } = openRoleplay()

    const dialog = await screen.findByRole("dialog")
    await user.click(within(dialog).getByRole("button", { name: "다시 시도하기" }))

    expect(await screen.findByText(ROLEPLAY_OPENING)).toBeInTheDocument()
    const [first, retry] = sentTo(/\/roleplay-sessions$/)
    expect(retry.key).toBe(first.key)
  })
})

describe("코드 입력 화면은 만료·연결 끊김·서버 오류를 틀린 코드와 따로 알린다", () => {
  it("코드를 숫자로 바꾸지 않고 앞자리 0까지 그대로 보낸다", async () => {
    const sent: unknown[] = []
    server.use(
      http.post(ENTRY_URL, async ({ request }) => {
        sent.push(await request.clone().json())
      }),
    )
    const { user } = renderRoutes(router.routes, "/child")

    await pressCode(user, VALID_CODE)
    await user.click(screen.getByRole("button", { name: "입장하기" }))

    expect(await screen.findByText(GREETING)).toBeInTheDocument()
    expect(sent).toEqual([{ accessCode: VALID_CODE }])
  })

  it("만료된 코드면 만료 모달이 뜨고, 다시 입력하기를 누르면 칸을 비운다", async () => {
    const { user } = renderRoutes(router.routes, "/child")

    await pressCode(user, childAccessCodes.expired)
    await user.click(screen.getByRole("button", { name: "입장하기" }))

    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByText("코드 사용 기간이 끝났어요")).toBeInTheDocument()
    await user.click(within(dialog).getByRole("button", { name: "다시 입력하기" }))

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
    expect(screen.getByRole("group", { name: "입력한 숫자 0개" })).toBeInTheDocument()
  })

  it("연결이 끊기면 연결 모달이 뜨고, 다시 시도하면 같은 코드로 입장한다", async () => {
    server.use(http.post(ENTRY_URL, () => HttpResponse.error(), { once: true }))
    const { user } = renderRoutes(router.routes, "/child")

    await pressCode(user, VALID_CODE)
    await user.click(screen.getByRole("button", { name: "입장하기" }))

    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByText("연결이 잠깐 끊겼어요")).toBeInTheDocument()
    await user.click(within(dialog).getByRole("button", { name: "다시 시도하기" }))

    expect(await screen.findByText(GREETING)).toBeInTheDocument()
    expect(vi.mocked(api.verifyAccessCode).mock.calls.map(([code]) => code)).toEqual([VALID_CODE, VALID_CODE])
  })

  it.each([
    ["CSRF 거부(403)", () => new HttpResponse(null, { status: 403 })],
    ["서버 오류(500)", () => apiError(500, "INTERNAL_ERROR", "서버 오류", "/api/v1/child-access-sessions")],
  ])("%s 는 틀린 코드가 아니라 오류 모달로 알리고, 다시 하면 같은 코드로 입장한다", async (_name, failure) => {
    server.use(http.post(ENTRY_URL, failure, { once: true }))
    const { user } = renderRoutes(router.routes, "/child")

    await pressCode(user, VALID_CODE)
    await user.click(screen.getByRole("button", { name: "입장하기" }))

    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByText("잠깐 문제가 있었어요")).toBeInTheDocument()
    expect(screen.queryByText("코드가 맞지 않아요. 다시 눌러볼까?")).not.toBeInTheDocument()
    // 틀린 코드처럼 칸을 비우지 않는다. 모달이 뒤 화면을 가려(aria-hidden) 이름으로 찾을 수 없어 속성으로 본다.
    expect(screen.getByRole("group", { hidden: true })).toHaveAttribute("aria-label", "입력한 숫자 4개")
    await user.click(within(dialog).getByRole("button", { name: "다시 하기" }))

    expect(await screen.findByText(GREETING)).toBeInTheDocument()
  })
})

describe("응답을 기다리는 동안 로딩을 보여 주고 버튼을 막는다", () => {
  it("코드를 확인하는 동안 입장 버튼과 숫자판을 막는다", async () => {
    const pending = deferred<api.ChildAccess>()
    vi.mocked(api.verifyAccessCode).mockReturnValueOnce(pending.promise)
    const { user } = renderRoutes(router.routes, "/child")

    await pressCode(user, VALID_CODE)
    await user.click(screen.getByRole("button", { name: "입장하기" }))

    expect(screen.getByRole("button", { name: "확인하고 있어요…" })).toBeDisabled()
    expect(screen.getByRole("button", { name: "1" })).toBeDisabled()
    pending.resolve({ childId: "child-1", childName: "김하늘" })
    expect(await screen.findByText(GREETING)).toBeInTheDocument()
  })

  it("내 활동을 불러오는 동안 로딩 문구를 보여 준다", async () => {
    const pending = deferred<api.ChildActivity[]>()
    vi.mocked(api.getMyActivities).mockReturnValueOnce(pending.promise)
    openChildPage("/child/activities")

    expect(await screen.findByRole("status")).toHaveTextContent("활동을 불러오고 있어요")
    pending.resolve([])
    await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument())
  })

  it("퀴즈 판정을 기다리는 동안 보기와 확인 버튼을 막는다", async () => {
    const pending = deferred()
    server.use(
      http.put(
        ATTEMPT_URL,
        async () => {
          await pending.promise
          return HttpResponse.json(
            envelope({
              attemptId: crypto.randomUUID(),
              activityQuizId: crypto.randomUUID(),
              firstResponse: "기쁨",
              firstResponseCorrect: false,
              expressionMatchResult: "NOT_ANALYZABLE",
              cameraModelVersion: api.CAMERA_NOT_CONNECTED,
              usedHintTypes: [],
              finalResponse: "기쁨",
              resolvedAfterHint: false,
              itemCorrect: false,
              excludedFromScoring: true,
              status: "FINALIZED",
              respondedAt: new Date().toISOString(),
            }),
            { status: 201 },
          )
        },
        { once: true },
      ),
    )
    const { user } = openChildPage(`/child/quiz/${QUIZ_ACTIVITY}`)

    await user.click(await screen.findByRole("radio", { name: /기쁨/ }))
    await user.click(screen.getByRole("button", { name: "이 표정으로 확인할래요" }))

    expect(await screen.findByRole("button", { name: "확인하고 있어요…" })).toBeDisabled()
    for (const choice of screen.getAllByRole("radio")) expect(choice).toBeDisabled()
    pending.resolve()
    expect(await screen.findByText("네 마음을 알려줘서 고마워!")).toBeInTheDocument()
  })

  it("역할극 응답을 기다리는 동안 '생각하고 있어요'를 보여 주고 보내기를 막는다", async () => {
    const pending = deferred()
    server.use(
      http.post(
        TEXT_INPUT_URL,
        async () => {
          await pending.promise
          return HttpResponse.json(
            envelope({
              status: "DELIVERED",
              action: "NONE",
              messages: [{ speakerRole: "DIALOGUE", text: "그랬구나", speech }],
              acceptedInput: "아플 것 같아",
              checkpointVersion: 1,
              replayed: false,
            }),
          )
        },
        { once: true },
      ),
    )
    const { user } = openRoleplay()

    const input = await openTextComposer(user)
    await user.type(input, "아플 것 같아{Enter}")

    expect(await screen.findByText("생각하고 있어요…")).toBeInTheDocument()
    expect(input).toBeDisabled()
    pending.resolve()
    expect(await screen.findByText("그랬구나")).toBeInTheDocument()
    expect(screen.queryByText("생각하고 있어요…")).not.toBeInTheDocument()
  })

  it("독립 퀴즈 완료를 확인하는 동안 '스탬프를 준비하고 있어요'를 보여 주고 버튼을 막는다", async () => {
    const pending = deferred()
    server.use(
      http.get(
        STATUS_URL,
        async () => {
          await pending.promise
          return HttpResponse.json(envelope({ status: "COMPLETED" }))
        },
        { once: true },
      ),
    )
    const { user } = openLastIndependentQuestion()

    await finishLastIndependentQuestion(user)

    expect(await screen.findByRole("button", { name: "스탬프를 준비하고 있어요…" })).toBeDisabled()
    pending.resolve()
    expect(await screen.findByText("스탬프를 받았어요!")).toBeInTheDocument()
  })

  it("완료 확인을 기다리는 사이 사용 종료하면, 늦게 온 응답으로 완료 화면에 가지 않는다 (#56 FE 전달사항 4)", async () => {
    const pending = deferred()
    server.use(
      http.get(
        STATUS_URL,
        async () => {
          await pending.promise
          return HttpResponse.json(envelope({ status: "COMPLETED" }))
        },
        { once: true },
      ),
    )
    const { user, router: memoryRouter } = openLastIndependentQuestion()

    await finishLastIndependentQuestion(user)
    await user.click(screen.getByRole("button", { name: "사용 종료" }))
    await screen.findByText("입장 코드를 입력해줘!")
    // 완료 화면으로 갔다가 가드가 바로 돌려보내도 마지막 주소는 /child 라서, 거쳐 간 주소를 모두 모은다.
    const visited: string[] = []
    memoryRouter.subscribe((state) => visited.push(state.location.pathname))

    pending.resolve()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(visited.filter((path) => path.startsWith("/child/done"))).toEqual([])
    expect(memoryRouter.state.location.pathname).toBe("/child")
  })
})

describe("잘못 입력하면 다시 입력할 수 있다", () => {
  it("틀린 코드를 넣으면 칸을 비우고 바로 다시 입력해 입장할 수 있다", async () => {
    const { user } = renderRoutes(router.routes, "/child")

    await pressCode(user, "5678")
    await user.click(screen.getByRole("button", { name: "입장하기" }))

    expect(await screen.findByText("코드가 맞지 않아요. 다시 눌러볼까?")).toBeInTheDocument()
    expect(screen.getByRole("group", { name: "입력한 숫자 0개" })).toBeInTheDocument()

    await pressCode(user, VALID_CODE)
    expect(screen.queryByText("코드가 맞지 않아요. 다시 눌러볼까?")).not.toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "입장하기" }))
    expect(await screen.findByText(GREETING)).toBeInTheDocument()
  })

  it("역할극에서 비어 있거나 공백만 있는 답은 보내지 않는다", async () => {
    const { user } = openRoleplay()

    const input = await openTextComposer(user)
    expect(screen.getByRole("button", { name: "보낼래요" })).toBeDisabled()

    await user.type(input, "   {Enter}")
    expect(screen.getByRole("button", { name: "보낼래요" })).toBeDisabled()
    expect(sentTo(/\/text-inputs$/)).toEqual([])
  })

  it("버튼을 연달아 눌러도 요청은 한 번만 간다", async () => {
    const { user } = renderRoutes(router.routes, "/child")
    await pressCode(user, VALID_CODE)
    const enter = screen.getByRole("button", { name: "입장하기" })
    await user.dblClick(enter)
    await screen.findByText(GREETING)
    expect(api.verifyAccessCode).toHaveBeenCalledTimes(1)
  })

  it("역할극 '보낼래요'를 연달아 눌러도 답은 한 번만 간다", async () => {
    const { user } = openRoleplay()

    const input = await openTextComposer(user)
    await user.type(input, "아플 것 같아")
    await user.dblClick(screen.getByRole("button", { name: "보낼래요" }))

    await screen.findByText(ROLEPLAY_SCRIPT[0])
    expect(sentTo(/\/text-inputs$/)).toHaveLength(1)
  })
})
