import { screen, waitFor, within } from "@testing-library/react"
import type { UserEvent } from "@testing-library/user-event"
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest"
import { router } from "@/router"
import { enterAsChild, renderRoutes } from "@/test/render"
import * as api from "./api"

// S2-YOON-01 아동 화면 오류·로딩·재입력 점검 (VS-003·006·010).
// 아동 API는 아직 화면 쪽 mock(api.ts)이라 항상 성공한다. 그래서 mock 함수를 감싸 일부러 실패시키거나 응답을 붙잡아 두고,
// 화면이 오류 모달·로딩 표시·재입력을 제대로 보여 주는지 본다.
// - 기술 오류는 오답이 아니므로 같은 요청을 그대로 다시 보낸다(퀴즈 VS-006, 역할극 턴 VS-011, 완료·스탬프 VS-013).
// - 응답을 기다리는 동안에는 버튼을 막아 같은 요청이 두 번 가지 않게 한다.
// 실제 API가 붙으면 mock 대신 MSW 핸들러로 같은 상황을 만든다. 의도적으로 동작을 바꾸면 이 테스트도 같이 고치세요.

vi.mock("./api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api")>()
  return {
    ...actual,
    verifyAccessCode: vi.fn(actual.verifyAccessCode),
    getMyActivities: vi.fn(actual.getMyActivities),
    getQuizQuestions: vi.fn(actual.getQuizQuestions),
    submitQuizAnswer: vi.fn(actual.submitQuizAnswer),
    getRoleplayScenario: vi.fn(actual.getRoleplayScenario),
    sendRoleplayTurn: vi.fn(actual.sendRoleplayTurn),
    // 실제 mock은 활동을 완료로 바꿔 다음 테스트에 남으므로, 상태를 바꾸지 않는 응답으로 대신한다.
    completeActivity: vi.fn(async ({ activityId }: { activityId: string }) => ({ activityId, stampGranted: true })),
  }
})

const QUIZ_ACTIVITY = "mock-activity-1"
const networkError = () => new TypeError("Failed to fetch")

/** 응답을 붙잡아 두었다가 원할 때 풀어 주는 약속. 로딩 중 화면을 보려고 쓴다. */
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

function openChildPage(path: string) {
  enterAsChild()
  return renderRoutes(router.routes, path)
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

/** 역할극 두 턴을 보내 마무리 화면까지 간다(mock 대본은 두 번째 턴에서 끝난다). */
async function finishRoleplay(user: UserEvent) {
  const input = await openTextComposer(user)
  await user.type(input, "아플 것 같아{Enter}")
  await screen.findByText(/친구 눈썹이 아래로/)
  await user.type(input, "속상할 것 같아{Enter}")
  await user.click(await screen.findByRole("button", { name: "이야기 마무리하기" }))
}

beforeAll(() => {
  // jsdom 에는 scrollIntoView 가 없다. 역할극은 새 말풍선이 생길 때마다 맨 아래로 내리므로 빈 함수로 채운다.
  Element.prototype.scrollIntoView = vi.fn()
})

beforeEach(() => {
  vi.clearAllMocks()
})

describe("오류가 나면 모달로 알리고 같은 요청을 다시 보낸다", () => {
  it("퀴즈 답 제출이 실패하면 같은 답을 다시 보내고 판정을 이어 간다", async () => {
    vi.mocked(api.submitQuizAnswer).mockRejectedValueOnce(networkError())
    const { user } = openChildPage(`/child/quiz/${QUIZ_ACTIVITY}`)

    await user.click(await screen.findByRole("radio", { name: /슬퍼요/ }))
    await user.click(screen.getByRole("button", { name: "이 표정으로 확인할래요" }))

    const dialog = await screen.findByRole("dialog")
    await user.click(within(dialog).getByRole("button", { name: "다시 하기" }))

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
    expect(api.submitQuizAnswer).toHaveBeenCalledTimes(2)
    expect(vi.mocked(api.submitQuizAnswer).mock.calls[1][0]).toEqual(vi.mocked(api.submitQuizAnswer).mock.calls[0][0])
    // 기술 오류는 오답으로 치지 않으므로 힌트 없이 첫 시도로 판정된다.
    expect(vi.mocked(api.submitQuizAnswer).mock.calls[1][0].attempt).toBe(1)
  })

  it("역할극 대화가 실패하면 같은 요청 ID로 다시 보내고, 아이 답은 한 번만 보인다", async () => {
    vi.mocked(api.sendRoleplayTurn).mockRejectedValueOnce(networkError())
    const { user } = openChildPage(`/child/roleplay/${QUIZ_ACTIVITY}`)

    const input = await openTextComposer(user)
    await user.type(input, "아플 것 같아{Enter}")

    const dialog = await screen.findByRole("dialog")
    await user.click(within(dialog).getByRole("button", { name: "다시 하기" }))

    expect(await screen.findByText(/친구 눈썹이 아래로/)).toBeInTheDocument()
    const [first, retry] = vi.mocked(api.sendRoleplayTurn).mock.calls.map(([input]) => input)
    expect(retry.requestId).toBe(first.requestId)
    expect(retry.text).toBe("아플 것 같아")
    expect(screen.getAllByText("“아플 것 같아”")).toHaveLength(1)
  })

  it("활동 완료가 실패하면 같은 요청 ID로 다시 보내 스탬프가 한 번만 지급되게 한다", async () => {
    vi.mocked(api.completeActivity).mockRejectedValueOnce(networkError())
    const { user, router: memoryRouter } = openChildPage(`/child/roleplay/${QUIZ_ACTIVITY}`)

    await finishRoleplay(user)
    await user.click(await screen.findByRole("button", { name: "다음으로" }))

    const dialog = await screen.findByRole("dialog")
    await user.click(within(dialog).getByRole("button", { name: "다시 하기" }))

    expect(await screen.findByText("스탬프를 받았어요!")).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe(`/child/done/${QUIZ_ACTIVITY}`)
    const [first, retry] = vi.mocked(api.completeActivity).mock.calls.map(([input]) => input)
    expect(retry.requestId).toBe(first.requestId)
  })

  it.each([
    ["내 활동", "/child/activities", api.getMyActivities, "활동을 불러오지 못했어요", "친구 마음 알아보기"],
    ["표정 퀴즈", `/child/quiz/${QUIZ_ACTIVITY}`, api.getQuizQuestions, "퀴즈를 불러오지 못했어요", "1/3 문항"],
    ["역할극", `/child/roleplay/${QUIZ_ACTIVITY}`, api.getRoleplayScenario, "역할극을 불러오지 못했어요", "놀이터에서"],
  ] as const)("%s 을 불러오지 못하면 모달이 뜨고, 다시 하면 화면이 정상으로 돌아온다", async (_name, path, load, message, ready) => {
    vi.mocked(load).mockRejectedValueOnce(networkError())
    const { user } = openChildPage(path)

    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByText(message)).toBeInTheDocument()
    await user.click(within(dialog).getByRole("button", { name: "다시 하기" }))

    expect(await screen.findByText(ready)).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
  })
})

describe("응답을 기다리는 동안 로딩을 보여 주고 버튼을 막는다", () => {
  it("코드를 확인하는 동안 입장 버튼과 숫자판을 막는다", async () => {
    const pending = deferred<api.ChildAccess>()
    vi.mocked(api.verifyAccessCode).mockReturnValueOnce(pending.promise)
    const { user } = renderRoutes(router.routes, "/child")

    await pressCode(user, "1234")
    await user.click(screen.getByRole("button", { name: "입장하기" }))

    expect(screen.getByRole("button", { name: "확인하고 있어요…" })).toBeDisabled()
    expect(screen.getByRole("button", { name: "1" })).toBeDisabled()
    pending.resolve({ childId: "mock-child-1", childName: "서연" })
    expect(await screen.findByText("안녕, 서연아!")).toBeInTheDocument()
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
    const pending = deferred<api.QuizAnswerResult>()
    vi.mocked(api.submitQuizAnswer).mockReturnValueOnce(pending.promise)
    const { user } = openChildPage(`/child/quiz/${QUIZ_ACTIVITY}`)

    await user.click(await screen.findByRole("radio", { name: /슬퍼요/ }))
    await user.click(screen.getByRole("button", { name: "이 표정으로 확인할래요" }))

    expect(screen.getByRole("button", { name: "확인하고 있어요…" })).toBeDisabled()
    for (const choice of screen.getAllByRole("radio")) expect(choice).toBeDisabled()
    pending.resolve({ result: "CORRECT" })
    expect(await screen.findByText("맞아! 잘 찾았어")).toBeInTheDocument()
  })

  it("역할극 응답을 기다리는 동안 '생각하고 있어요'를 보여 주고 보내기를 막는다", async () => {
    const pending = deferred<api.RoleplayTurnResult>()
    vi.mocked(api.sendRoleplayTurn).mockReturnValueOnce(pending.promise)
    const { user } = openChildPage(`/child/roleplay/${QUIZ_ACTIVITY}`)

    const input = await openTextComposer(user)
    await user.type(input, "아플 것 같아{Enter}")

    expect(await screen.findByText("생각하고 있어요…")).toBeInTheDocument()
    expect(input).toBeDisabled()
    pending.resolve({ status: "DELIVERED", reply: { speaker: "turtle", text: "그랬구나" }, isFinished: false })
    expect(await screen.findByText("그랬구나")).toBeInTheDocument()
    expect(screen.queryByText("생각하고 있어요…")).not.toBeInTheDocument()
  })

  it("활동 완료를 처리하는 동안 '다음으로' 버튼을 막는다", async () => {
    const pending = deferred<api.ActivityCompletion>()
    vi.mocked(api.completeActivity).mockReturnValueOnce(pending.promise)
    const { user } = openChildPage(`/child/roleplay/${QUIZ_ACTIVITY}`)

    await finishRoleplay(user)
    await user.click(await screen.findByRole("button", { name: "다음으로" }))

    expect(screen.getByLabelText("처리 중").closest("button")).toBeDisabled()
    pending.resolve({ activityId: QUIZ_ACTIVITY, stampGranted: true })
    expect(await screen.findByText("스탬프를 받았어요!")).toBeInTheDocument()
  })
})

describe("잘못 입력하면 다시 입력할 수 있다", () => {
  it("틀린 코드를 넣으면 칸을 비우고 바로 다시 입력해 입장할 수 있다", async () => {
    const { user } = renderRoutes(router.routes, "/child")

    await pressCode(user, "5678")
    await user.click(screen.getByRole("button", { name: "입장하기" }))

    expect(await screen.findByText("코드가 맞지 않아요. 다시 눌러볼까?")).toBeInTheDocument()
    expect(screen.getByRole("group", { name: "입력한 숫자 0개" })).toBeInTheDocument()

    await pressCode(user, "1234")
    expect(screen.queryByText("코드가 맞지 않아요. 다시 눌러볼까?")).not.toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "입장하기" }))
    expect(await screen.findByText("안녕, 서연아!")).toBeInTheDocument()
  })

  it("역할극에서 비어 있거나 공백만 있는 답은 보내지 않는다", async () => {
    const { user } = openChildPage(`/child/roleplay/${QUIZ_ACTIVITY}`)

    const input = await openTextComposer(user)
    expect(screen.getByRole("button", { name: "보낼래요" })).toBeDisabled()

    await user.type(input, "   {Enter}")
    expect(screen.getByRole("button", { name: "보낼래요" })).toBeDisabled()
    expect(api.sendRoleplayTurn).not.toHaveBeenCalled()
  })

  it("버튼을 연달아 눌러도 요청은 한 번만 간다", async () => {
    const { user } = renderRoutes(router.routes, "/child")
    await pressCode(user, "1234")
    const enter = screen.getByRole("button", { name: "입장하기" })
    await user.dblClick(enter)
    await screen.findByText("안녕, 서연아!")
    expect(api.verifyAccessCode).toHaveBeenCalledTimes(1)
  })

  it("역할극 '보낼래요'를 연달아 눌러도 답은 한 번만 간다", async () => {
    const { user } = openChildPage(`/child/roleplay/${QUIZ_ACTIVITY}`)

    const input = await openTextComposer(user)
    await user.type(input, "아플 것 같아")
    await user.dblClick(screen.getByRole("button", { name: "보낼래요" }))

    await screen.findByText(/친구 눈썹이 아래로/)
    expect(api.sendRoleplayTurn).toHaveBeenCalledTimes(1)
  })
})
