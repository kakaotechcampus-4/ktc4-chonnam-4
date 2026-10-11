import { screen } from "@testing-library/react"
import { describe, expect, it } from "vitest"
import { fixtures } from "@/test/msw/handlers"
import { enterAsChild, renderRoutes, signIn } from "@/test/render"
import { router } from "./router"

// 라우팅 표 검사. 실제 router.tsx 의 경로 목록을 그대로 써서, 주소마다 약속한 화면이 뜨는지 본다.
// 강사 화면은 로그인해야(RequireInstructorAuth), 아동 활동 화면은 입장해야(RequireChildSession) 열린다. 막히는 쪽은
// instructor/auth/authFlow.test.tsx(로그인 화면으로)와 child/childFlow.test.tsx(코드 입력 화면으로)가 본다.
// 경로 이름을 바꾸거나 화면을 잘못 연결하면 E2E 까지 가지 않고 여기서 먼저 깨진다.
// 의도적으로 경로를 바꾸면 이 테스트도 같이 고치세요.

const A1 = fixtures.classroomA1.classId
// 아동 활동은 아직 화면 쪽 mock(features/child/api.ts)이다. 1 = 진행 중, 3 = 완료.
const ACTIVITY = "mock-activity-1"
const COMPLETED_ACTIVITY = "mock-activity-3"

describe("라우팅 표", () => {
  it.each([
    ["/", "느링고"],
    ["/login", "강사 · 기관 계정으로 로그인하세요"],
    ["/signup", "기본 정보를 입력해 계정을 만드세요"],
    ["/child", "입장 코드를 입력해줘!"],
    ["/child/_dev/states", "상태 컴포넌트 미리보기 (개발용)"],
  ])("%s 는 로그인·입장 없이 약속한 화면을 연다", async (path, text) => {
    renderRoutes(router.routes, path)

    expect(await screen.findByText(text)).toBeInTheDocument()
  })

  it.each([
    ["/classrooms", "햇살반"],
    ["/classrooms/new", "기본 정보"],
    [`/classrooms/${A1}`, "학급 상세 · 햇살반"],
  ])("로그인하면 %s 는 약속한 화면을 연다", async (path, text) => {
    signIn()
    const { router: memoryRouter } = renderRoutes(router.routes, path)

    expect(await screen.findByText(text)).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe(path)
  })

  it.each([
    ["/child/hello", "오늘도 만나서 반가워"],
    ["/child/activities", "친구 마음 알아보기"],
    [`/child/activities/${ACTIVITY}`, "이어서 하기"],
    [`/child/quiz/${ACTIVITY}`, "지금 네 기분은 어때?"],
    [`/child/roleplay/${ACTIVITY}`, "친구가 넘어져서 울고 있어. 몸은 어떤 느낌일까?"],
  ])("입장하면 %s 는 약속한 화면을 연다", async (path, text) => {
    enterAsChild()
    const { router: memoryRouter } = renderRoutes(router.routes, path)

    expect(await screen.findByText(text)).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe(path)
  })

  // 활동 ID 는 주소에 그대로 보이므로, 배정 목록에 없는 ID·이미 끝낸 활동·완료 기록 없는 완료 화면은 내 활동으로 돌려보낸다.
  // 다른 아동의 활동을 최종으로 막는 것은 서버(403)지만, 화면도 그 활동을 그리지 않는다(VS-003).
  it.each([
    "/child/activities/other-child-activity",
    "/child/quiz/other-child-activity",
    "/child/roleplay/other-child-activity",
    `/child/quiz/${COMPLETED_ACTIVITY}`,
    `/child/roleplay/${COMPLETED_ACTIVITY}`,
    `/child/done/${ACTIVITY}`,
  ])("입장해도 %s 는 내 활동으로 replace 이동한다", async (path) => {
    enterAsChild()
    const { router: memoryRouter } = renderRoutes(router.routes, path)

    expect(await screen.findByText("친구 마음 알아보기")).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe("/child/activities")
    expect(memoryRouter.state.historyAction).toBe("REPLACE")
  })

  it("첫 화면에서 강사 화면과 아동 화면으로 들어갈 수 있다", async () => {
    signIn()
    const { user } = renderRoutes(router.routes, "/")

    await user.click(screen.getByRole("link", { name: "아동 화면 진입" }))
    expect(await screen.findByText("입장 코드를 입력해줘!")).toBeInTheDocument()

    renderRoutes(router.routes, "/")
    await user.click(screen.getAllByRole("link", { name: "강사 화면 진입" })[0])
    expect(await screen.findByRole("cell", { name: "햇살반" })).toBeInTheDocument()
  })

  it("퀴즈·역할극 화면은 단계 표시를 보여 준다", async () => {
    enterAsChild()
    renderRoutes(router.routes, `/child/quiz/${ACTIVITY}`)
    expect(await screen.findByText("1/3 문항")).toBeInTheDocument()

    renderRoutes(router.routes, `/child/roleplay/${ACTIVITY}`)
    expect(await screen.findByText("놀이터에서")).toBeInTheDocument()
  })
})
