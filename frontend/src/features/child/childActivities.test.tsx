import { act, screen, waitFor, within } from "@testing-library/react"
import { describe, expect, it } from "vitest"
import { http, HttpResponse } from "msw"
import { router } from "@/router"
import { apiError, fixtures, seedActivity } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { enterAsChild, renderRoutes } from "@/test/render"
import { ChildApiError, getMyActivities } from "./api"
import { childGetRequest } from "./apiClient"
import { useChildSessionStore } from "./store/childSessionStore"

// S4-YOON-01 아동 활동 목록 실제 연결 (API-ACT-02, VS-003·009).
// 완료 기준 "다른 아동 활동 ID가 화면과 직접 요청에서 차단됨"을 화면(목록에 없는 ID는 돌려보냄)과 요청(서버 404) 양쪽에서 본다.
// 서버 응답에는 제목이 없어 활동 유형으로 정한 문구(api.ts ACTIVITY_CONTENT)가 보인다. 의도적으로 바꾸면 이 테스트도 같이 고치세요.

const ME = fixtures.childA1_1.childId
const OTHER_CHILD = fixtures.childA1_2.childId
const LIST_URL = "*/api/v1/children/:childId/activities"

describe("내 활동 목록은 서버에 배정된 활동을 보여 준다", () => {
  it("활동 유형별 이름과 상태를 열기구마다 보여 준다", async () => {
    seedActivity({ childId: ME, activityType: "FIRST_COMBINED", status: "COMPLETED" })
    seedActivity({ childId: ME, activityType: "SELF_EMOTION_QUIZ", status: "PAUSED" })
    seedActivity({ childId: ME, activityType: "OTHER_EMOTION_QUIZ" })
    seedActivity({ childId: ME, activityType: "ROLEPLAY", status: "RECOVERY_NEEDED" })
    enterAsChild()
    const { user } = renderRoutes(router.routes, "/child/activities")

    const balloon = async (title: string) => (await screen.findByText(title)).closest("button")!

    // 최신 배정 순이라 1쪽에 나중에 배정한 3개, 2쪽에 처음 배정한 1개가 보인다.
    // 일시 중단·복구 필요는 아이에게 "진행 중"으로 보인다.
    expect(within(await balloon("내 마음 알아보기")).getByText("진행 중")).toBeInTheDocument()
    expect(within(await balloon("상대방 마음 맞히기")).getByText("안 했어요")).toBeInTheDocument()
    expect(within(await balloon("친구랑 이야기하기")).getByText("진행 중")).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "다음 쪽" }))
    expect(within(await balloon("친구 마음 알아보기")).getByText("완료!")).toBeInTheDocument()
  })

  it("다른 아동에게 배정된 활동은 보이지 않는다", async () => {
    seedActivity({ childId: OTHER_CHILD, activityType: "SELF_EMOTION_QUIZ" })
    enterAsChild()
    renderRoutes(router.routes, "/child/activities")

    expect(await screen.findByText("오늘은 할 활동이 없어요. 선생님께 물어봐")).toBeInTheDocument()
    expect(screen.queryByText("내 마음 알아보기")).not.toBeInTheDocument()
  })

  it("인사 화면은 남은 활동 수와 첫 활동의 구성을 보여 준다", async () => {
    seedActivity({ childId: ME, activityType: "OTHER_EMOTION_QUIZ" })
    seedActivity({ childId: ME, activityType: "FIRST_COMBINED", status: "COMPLETED" })
    enterAsChild()
    renderRoutes(router.routes, "/child/hello")

    expect(await screen.findByText("오늘 할 활동이 1개 있어")).toBeInTheDocument()
    expect(screen.getByText("상대방 마음 맞히기")).toBeInTheDocument()
    // 퀴즈만 있는 활동은 "역할극 0개"를 붙이지 않는다.
    expect(screen.getByText("표정 퀴즈 10개")).toBeInTheDocument()
  })

  it("퀴즈만 있는 활동 소개는 역할극 단계를 빼고, 시작하면 퀴즈로 간다", async () => {
    const activity = seedActivity({ childId: ME, activityType: "SELF_EMOTION_QUIZ" })
    enterAsChild()
    const { user, router: memoryRouter } = renderRoutes(router.routes, `/child/activities/${activity.activityId}`)

    expect(await screen.findByText("10문제")).toBeInTheDocument()
    expect(screen.queryByText("역할극")).not.toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "시작하기" }))

    expect(memoryRouter.state.location.pathname).toBe(`/child/quiz/${activity.activityId}`)
  })

  it("역할극만 있는 활동은 퀴즈 단계 없이 역할극으로 간다", async () => {
    const activity = seedActivity({ childId: ME, activityType: "ROLEPLAY", status: "PAUSED", scenarioConfirmed: true })
    enterAsChild()
    const { user, router: memoryRouter } = renderRoutes(router.routes, `/child/activities/${activity.activityId}`)

    await user.click(await screen.findByRole("button", { name: "이어서 하기" }))

    expect(screen.queryByText("표정 퀴즈")).not.toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe(`/child/roleplay/${activity.activityId}`)
  })
})

describe("활동이 3개보다 많으면 쪽을 나눠 3개씩 보여 준다", () => {
  /** 안 한 활동 n개를 배정한다. 열기구 이름표의 "안 했어요"로 지금 쪽의 열기구 수를 센다. */
  function seedNotStarted(count: number) {
    for (let index = 0; index < count; index++) seedActivity({ childId: ME, activityType: "OTHER_EMOTION_QUIZ" })
  }
  const visibleBalloons = () => screen.queryAllByRole("button", { name: /안 했어요/ })
  const currentPage = () =>
    within(screen.getByRole("navigation", { name: "활동 쪽 넘기기" })).getByRole("button", { current: "page" })

  it("3개 이하면 쪽 넘김 버튼을 보여 주지 않는다", async () => {
    seedNotStarted(3)
    enterAsChild()
    renderRoutes(router.routes, "/child/activities")

    await waitFor(() => expect(visibleBalloons()).toHaveLength(3))
    expect(screen.queryByRole("navigation", { name: "활동 쪽 넘기기" })).not.toBeInTheDocument()
  })

  it("7개면 3쪽으로 나누고, 화살표와 쪽 번호로 넘기며, 처음·마지막 쪽에서는 화살표를 막는다", async () => {
    seedNotStarted(7)
    enterAsChild()
    const { user, router: memoryRouter } = renderRoutes(router.routes, "/child/activities")

    await waitFor(() => expect(visibleBalloons()).toHaveLength(3))
    expect(currentPage()).toHaveAccessibleName("1쪽")
    expect(screen.getByRole("button", { name: "이전 쪽" })).toBeDisabled()

    await user.click(screen.getByRole("button", { name: "다음 쪽" }))
    expect(currentPage()).toHaveAccessibleName("2쪽")
    expect(visibleBalloons()).toHaveLength(3)
    expect(memoryRouter.state.location.search).toBe("?page=2")

    await user.click(screen.getByRole("button", { name: "3쪽" }))
    expect(currentPage()).toHaveAccessibleName("3쪽")
    expect(visibleBalloons()).toHaveLength(1)
    expect(screen.getByRole("button", { name: "다음 쪽" })).toBeDisabled()
  })

  it("쪽 번호는 3개씩 묶어 보여 주고, 화살표로 묶음을 넘어가면 번호가 다음 묶음으로 바뀐다", async () => {
    seedNotStarted(10)
    enterAsChild()
    const { user } = renderRoutes(router.routes, "/child/activities?page=3")
    const pageNumbers = () =>
      within(screen.getByRole("navigation", { name: "활동 쪽 넘기기" }))
        .queryAllByRole("button", { name: /쪽$/ })
        .filter((button) => /^\d+쪽$/.test(button.getAttribute("aria-label") ?? ""))
        .map((button) => button.textContent)

    await waitFor(() => expect(visibleBalloons()).toHaveLength(3))
    expect(pageNumbers()).toEqual(["1", "2", "3"])

    // 3쪽에서 한 쪽 넘기면 4쪽 하나만 남는다(4쪽에는 10번째 활동 1개).
    await user.click(screen.getByRole("button", { name: "다음 쪽" }))
    expect(currentPage()).toHaveAccessibleName("4쪽")
    expect(pageNumbers()).toEqual(["4"])
    expect(visibleBalloons()).toHaveLength(1)
    expect(screen.getByRole("button", { name: "다음 쪽" })).toBeDisabled()

    await user.click(screen.getByRole("button", { name: "이전 쪽" }))
    expect(currentPage()).toHaveAccessibleName("3쪽")
    expect(pageNumbers()).toEqual(["1", "2", "3"])
  })

  it("마지막 쪽에 1개만 남아도 1쪽 첫 열기구와 같은 자리에 뜬다", async () => {
    seedNotStarted(4)
    enterAsChild()
    const { user } = renderRoutes(router.routes, "/child/activities")

    await waitFor(() => expect(visibleBalloons()).toHaveLength(3))
    const firstSlot = visibleBalloons()[0].closest("li")!.className
    await user.click(screen.getByRole("button", { name: "다음 쪽" }))

    expect(visibleBalloons()).toHaveLength(1)
    expect(visibleBalloons()[0].closest("li")!.className).toBe(firstSlot)
  })

  it("2쪽에서 활동에 들어갔다가 뒤로 오면 2쪽을 그대로 보여 준다", async () => {
    seedNotStarted(5)
    enterAsChild()
    const { user, router: memoryRouter } = renderRoutes(router.routes, "/child/activities?page=2")

    await waitFor(() => expect(visibleBalloons()).toHaveLength(2))
    await user.click(visibleBalloons()[0])
    await waitFor(() => expect(memoryRouter.state.location.pathname).toMatch(/^\/child\/activities\/.+/))

    await act(() => memoryRouter.navigate(-1))
    await waitFor(() => expect(visibleBalloons()).toHaveLength(2))
    expect(currentPage()).toHaveAccessibleName("2쪽")
  })

  it("없는 쪽 번호로 오면 마지막 쪽을 보여 준다", async () => {
    seedNotStarted(4)
    enterAsChild()
    renderRoutes(router.routes, "/child/activities?page=9")

    await waitFor(() => expect(visibleBalloons()).toHaveLength(1))
    expect(currentPage()).toHaveAccessibleName("2쪽")
  })
})

describe("다음 동작은 서버가 계산한 nextAction 으로 정한다 (API-ACT-02)", () => {
  it("퀴즈를 마쳤지만 선생님이 시나리오를 확정하기 전이면 시작 버튼 대신 기다림을 안내한다", async () => {
    const activity = seedActivity({ childId: ME, status: "IN_PROGRESS", quizFinished: true, scenarioConfirmed: false })
    enterAsChild()
    renderRoutes(router.routes, `/child/activities/${activity.activityId}`)

    expect(await screen.findByText("선생님이 역할극을 준비하고 있어")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /이어서 하기|시작하기/ })).not.toBeInTheDocument()
  })

  it("역할극 주소로 바로 와도 확정 전이면 시작 요청을 보내지 않고 기다림을 안내한다", async () => {
    const activity = seedActivity({ childId: ME, status: "IN_PROGRESS", quizFinished: true, scenarioConfirmed: false })
    const starts: string[] = []
    server.events.on("request:start", ({ request }) => {
      if (request.url.includes("/roleplay-sessions")) starts.push(request.url)
    })
    enterAsChild()
    renderRoutes(router.routes, `/child/roleplay/${activity.activityId}`)

    expect(await screen.findByText("선생님이 역할극을 준비하고 있어")).toBeInTheDocument()
    server.events.removeAllListeners("request:start")
    expect(starts).toEqual([])
  })
})

describe("다른 아동의 활동은 화면과 직접 요청에서 막힌다 (S4-YOON-01 완료 기준)", () => {
  it.each(["/child/activities", "/child/quiz", "/child/roleplay"])(
    "다른 아동의 활동 ID 로 %s/:id 를 열면 내 활동 목록으로 돌려보낸다",
    async (prefix) => {
      const others = seedActivity({ childId: OTHER_CHILD, activityType: "FIRST_COMBINED", status: "IN_PROGRESS" })
      enterAsChild()
      const { router: memoryRouter } = renderRoutes(router.routes, `${prefix}/${others.activityId}`)

      expect(await screen.findByText("김하늘의 활동")).toBeInTheDocument()
      expect(memoryRouter.state.location.pathname).toBe("/child/activities")
      expect(memoryRouter.state.historyAction).toBe("REPLACE")
    },
  )

  it("다른 아동 ID 로 목록을 직접 요청하면 서버가 404 CHILD_NOT_FOUND 로 막는다", async () => {
    seedActivity({ childId: OTHER_CHILD })
    enterAsChild()

    await expect(getMyActivities(OTHER_CHILD)).rejects.toMatchObject({ status: 404, code: "CHILD_NOT_FOUND" })
    await expect(getMyActivities(OTHER_CHILD)).rejects.toBeInstanceOf(ChildApiError)
  })
})

describe("아동 세션의 활동 목록에는 평가 정보가 오지 않는다 (#116)", () => {
  it("서버처럼 아동에게는 5개 칸만 주고, 아이 화면은 그것만으로 활동을 보여 준다", async () => {
    seedActivity({ childId: ME, activityType: "FIRST_COMBINED", status: "IN_PROGRESS" })
    enterAsChild()

    const items = await childGetRequest<Record<string, unknown>[]>(`/children/${ME}/activities`)
    expect(Object.keys(items[0]).sort()).toEqual(["activityId", "activityType", "completedAt", "nextAction", "status"])

    renderRoutes(router.routes, "/child/activities")
    expect(await screen.findByText("친구 마음 알아보기")).toBeInTheDocument()
  })
})

describe("목록을 불러오지 못하면 이유에 맞게 안내한다", () => {
  it("서버에 아동 세션이 없으면(401) 코드를 다시 넣게 하고, 누르면 세션을 비우고 코드 입력 화면으로 간다", async () => {
    server.use(
      http.get(LIST_URL, ({ request }) =>
        apiError(401, "AUTHENTICATION_REQUIRED", "인증이 필요합니다.", new URL(request.url).pathname),
      ),
    )
    enterAsChild()
    const { user, router: memoryRouter } = renderRoutes(router.routes, "/child/activities")

    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByText("다시 들어와 줘")).toBeInTheDocument()
    await user.click(within(dialog).getByRole("button", { name: "코드 입력하기" }))

    expect(await screen.findByText("입장 코드를 입력해줘!")).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe("/child")
    expect(useChildSessionStore.getState()).toMatchObject({ childId: null, childName: null })
  })

  it("서버에 닿지 못하면 연결 끊김을 알리고, 다시 하면 목록이 보인다", async () => {
    seedActivity({ childId: ME })
    server.use(http.get(LIST_URL, () => HttpResponse.error(), { once: true }))
    enterAsChild()
    const { user } = renderRoutes(router.routes, "/child/activities")

    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByText("연결이 잠깐 끊겼어요")).toBeInTheDocument()
    await user.click(within(dialog).getByRole("button", { name: "다시 시도하기" }))

    expect(await screen.findByText("친구 마음 알아보기")).toBeInTheDocument()
  })
})
