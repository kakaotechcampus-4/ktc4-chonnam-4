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
    ["/child/activities", "활동을 준비하고 있어요"],
    ["/child/quiz/q-1", "활동 q-1의 퀴즈 화면은 스프린트 1에서 구현돼요."],
    ["/child/roleplay/r-1", "활동 r-1의 역할극 화면은 스프린트 1에서 구현돼요."],
  ])("입장하면 %s 는 약속한 화면을 연다", async (path, text) => {
    enterAsChild()
    renderRoutes(router.routes, path)

    expect(await screen.findByText(text)).toBeInTheDocument()
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
    renderRoutes(router.routes, "/child/quiz/q-1")
    expect(await screen.findByText("1/3 문항")).toBeInTheDocument()

    renderRoutes(router.routes, "/child/roleplay/r-1")
    expect(await screen.findByText("1턴")).toBeInTheDocument()
  })
})
