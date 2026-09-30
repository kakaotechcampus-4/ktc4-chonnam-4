import { act, screen } from "@testing-library/react"
import { describe, expect, it } from "vitest"
import { router } from "@/router"
import { enterAsChild, renderRoutes } from "@/test/render"
import { useChildSessionStore } from "./store/childSessionStore"

// VS-003 인수 조건 검사(아동 화면 흐름). 실제 라우팅 표로 입장 → 활동 → 퀴즈·역할극 → 사용 종료를 따라간다.
// - "사용 종료를 누르면 현재 브라우저의 아동 상태를 지우고 코드 입력 화면으로 돌아간다": 모든 아동 화면에서 같다.
// - 입장하지 않았거나 사용 종료한 뒤에는 활동 화면을 주소로 열어도, 뒤로 가기로 돌아와도 코드 입력 화면으로 간다(#22 의 5번, RequireChildSession).
// - "아동 화면에 강사용 평가 정보와 다른 아동 정보가 노출되지 않는다": 아동 화면에서 강사 화면으로 가는 길이 없다.
// 실제 4자리 코드 검증은 S1-JIN-01·S1-YOON-01 몫이라 여기서는 임시 입장 버튼을 쓴다. 코드 입력이 들어오면 이 흐름을 그쪽으로 바꾼다.
// 의도적으로 흐름을 바꾸면 이 테스트도 같이 고치세요.

const ACTIVITY_PAGES = ["/child/activities", "/child/quiz/q-1", "/child/roleplay/r-1"]
const CHILD_PAGES = [...ACTIVITY_PAGES, "/child/_dev/states"]

describe("아동 화면 흐름", () => {
  it("임시 입장을 누르면 아동 세션이 생기고 내 활동 화면으로 간다", async () => {
    const { user, router: memoryRouter } = renderRoutes(router.routes, "/child")

    await user.click(screen.getByRole("button", { name: "임시로 들어가보기" }))

    expect(await screen.findByText("내 활동")).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe("/child/activities")
    expect(useChildSessionStore.getState().childName).not.toBeNull()
  })

  it.each([
    ["퀴즈 화면 보기", "/child/quiz/demo", "표정 퀴즈"],
    ["역할극 화면 보기", "/child/roleplay/demo", "역할극"],
  ])("내 활동에서 '%s' 를 누르면 %s 로 간다", async (link, path, title) => {
    enterAsChild()
    const { user, router: memoryRouter } = renderRoutes(router.routes, "/child/activities")

    await user.click(await screen.findByRole("link", { name: link }))

    expect(await screen.findByText(title)).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe(path)
  })

  it.each(ACTIVITY_PAGES)("입장하지 않고 %s 를 열면 코드 입력 화면으로 replace 이동한다", async (path) => {
    const { router: memoryRouter } = renderRoutes(router.routes, path)

    expect(await screen.findByText("입장 코드를 입력해줘!")).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe("/child")
    expect(memoryRouter.state.historyAction).toBe("REPLACE")
  })

  it.each(CHILD_PAGES)("%s 에서 사용 종료하면 세션을 비우고 코드 입력 화면으로 replace 이동한다", async (path) => {
    enterAsChild()
    const { user, router: memoryRouter } = renderRoutes(router.routes, path)

    await user.click(await screen.findByRole("button", { name: "사용 종료" }))

    expect(await screen.findByText("입장 코드를 입력해줘!")).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe("/child")
    expect(memoryRouter.state.historyAction).toBe("REPLACE")
    expect(useChildSessionStore.getState()).toMatchObject({ childName: null, accessCode: null })
  })

  it("사용 종료한 뒤 뒤로 가기를 눌러도 활동 화면이 다시 열리지 않는다", async () => {
    const { user, router: memoryRouter } = renderRoutes(router.routes, "/child")
    await user.click(screen.getByRole("button", { name: "임시로 들어가보기" }))
    await user.click(await screen.findByRole("link", { name: "퀴즈 화면 보기" }))
    await user.click(await screen.findByRole("button", { name: "사용 종료" }))
    await screen.findByText("입장 코드를 입력해줘!")

    // 뒤로 가면 가드가 코드 입력 화면으로 다시 보낸다. act 로 그 이동까지 다 그린 뒤에 본다.
    await act(() => memoryRouter.navigate(-1))

    expect(await screen.findByText("입장 코드를 입력해줘!")).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe("/child")
    expect(memoryRouter.state.historyAction).toBe("REPLACE")
    expect(screen.queryByText("내 활동")).not.toBeInTheDocument()
  })

  it.each([
    ["/child", "입장 코드를 입력해줘!"],
    ...CHILD_PAGES.map((path) => [path, "사용 종료"]),
  ])("%s 에는 강사 화면으로 가는 링크나 강사용 문구가 없다", async (path, anchor) => {
    enterAsChild()
    renderRoutes(router.routes, path)
    expect(await screen.findByText(anchor)).toBeInTheDocument()

    const links = screen.queryAllByRole("link").map((link) => link.getAttribute("href") ?? "")

    expect(links.filter((href) => href.startsWith("/classrooms"))).toEqual([])
    expect(screen.queryByText(/강사/)).not.toBeInTheDocument()
  })
})
