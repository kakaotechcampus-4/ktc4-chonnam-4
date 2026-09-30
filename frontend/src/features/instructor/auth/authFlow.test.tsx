import { act, screen } from "@testing-library/react"
import { describe, expect, it } from "vitest"
import { router } from "@/router"
import { fixtures, instructors } from "@/test/msw/handlers"
import { renderRoutes, signIn } from "@/test/render"
import { hasAccessToken } from "../api"

// VS-001 인수 조건 검사(강사 화면). 실제 라우팅 표로 가입·로그인·로그아웃과 보호 경로를 따라간다.
// - "활성 강사가 로그인하고 본인 정보를 조회할 수 있다": 로그인하면 학급 화면으로 가고 사이드바에 이름이 보인다.
// - "인증이 없거나 만료되면 보호된 기능을 사용할 수 없다": 토큰 없이 강사 화면을 열면 로그인 화면으로 가고, 로그인하면 보려던 화면으로 돌아간다.
//   토큰이 만료·폐기된 경우(401 → 로그인 화면)는 전체 페이지 이동이라 E2E(instructor-auth.spec.ts)가 본다.
// - "비밀번호는 8자 이상", "이용약관과 개인정보 처리방침에 각각 동의해야 가입할 수 있다": 조건이 안 맞으면 가입 버튼이 꺼져 있다.
// 가입 이메일은 예약 도메인(example.com)이고 비밀번호는 MSW 픽스처가 실행마다 만든다.
// 의도적으로 흐름을 바꾸면 이 테스트도 같이 고치세요.

const NEW_PASSWORD = "password-8"

async function fillSignup(user: ReturnType<typeof renderRoutes>["user"], email: string, password = NEW_PASSWORD) {
  await user.type(screen.getByLabelText("이름"), "새 강사")
  await user.type(screen.getByLabelText("이메일"), email)
  await user.type(screen.getByLabelText("비밀번호"), password)
  await user.type(screen.getByLabelText("비밀번호 확인"), password)
}

describe("강사 가입·로그인", () => {
  it("로그인하면 학급 화면으로 가고, 본인 이름과 담당 학급을 보여 준다", async () => {
    const { user, router: memoryRouter } = renderRoutes(router.routes, "/login")

    await user.type(screen.getByLabelText("이메일"), instructors.a.email)
    await user.type(screen.getByLabelText("비밀번호"), instructors.a.password)
    await user.click(screen.getByRole("button", { name: "로그인" }))

    expect(await screen.findByText(instructors.a.name)).toBeInTheDocument()
    expect(await screen.findByRole("cell", { name: fixtures.classroomA1.name })).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe("/classrooms")
    expect(hasAccessToken()).toBe(true)
  })

  it("비밀번호가 틀리면 서버 문구를 보이고 로그인 화면에 머문다", async () => {
    const { user, router: memoryRouter } = renderRoutes(router.routes, "/login")

    await user.type(screen.getByLabelText("이메일"), instructors.a.email)
    await user.type(screen.getByLabelText("비밀번호"), "wrong-password")
    await user.click(screen.getByRole("button", { name: "로그인" }))

    expect(await screen.findByRole("alert")).toHaveTextContent("이메일 또는 비밀번호가 올바르지 않습니다.")
    expect(memoryRouter.state.location.pathname).toBe("/login")
    expect(hasAccessToken()).toBe(false)
  })

  it.each(["/classrooms", "/classrooms/new", `/classrooms/${fixtures.classroomA1.classId}`])(
    "로그인하지 않고 %s 를 열면 로그인 화면으로 가고, 로그인하면 그 화면으로 돌아간다",
    async (path) => {
      const { user, router: memoryRouter } = renderRoutes(router.routes, path)

      expect(await screen.findByRole("heading", { name: "로그인" })).toBeInTheDocument()
      expect(memoryRouter.state.location.pathname).toBe("/login")

      await user.type(screen.getByLabelText("이메일"), instructors.a.email)
      await user.type(screen.getByLabelText("비밀번호"), instructors.a.password)
      await user.click(screen.getByRole("button", { name: "로그인" }))

      expect(await screen.findByText(instructors.a.name)).toBeInTheDocument()
      expect(memoryRouter.state.location.pathname).toBe(path)
    },
  )

  it("가입하면 바로 로그인되어 학급 화면으로 간다", async () => {
    const { user, router: memoryRouter } = renderRoutes(router.routes, "/signup")

    await fillSignup(user, "new-instructor@example.com")
    await user.click(screen.getByLabelText("전체 동의"))
    await user.click(screen.getByRole("button", { name: "가입하기" }))

    expect(await screen.findByText("새 강사")).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe("/classrooms")
    expect(await screen.findByText(/아직 학급이 없습니다/)).toBeInTheDocument()
  })

  it("약관과 개인정보 처리방침에 각각 동의해야 가입 버튼이 켜진다", async () => {
    const { user } = renderRoutes(router.routes, "/signup")
    const submit = screen.getByRole("button", { name: "가입하기" })
    await fillSignup(user, "new-instructor@example.com")

    expect(submit).toBeDisabled()
    await user.click(screen.getByLabelText("[필수] 서비스 이용약관 동의"))
    expect(submit).toBeDisabled()
    await user.click(screen.getByLabelText("[필수] 개인정보 처리방침 동의"))
    expect(submit).toBeEnabled()
  })

  it("비밀번호가 8자보다 짧거나 확인과 다르면 가입 버튼이 꺼져 있다", async () => {
    const { user } = renderRoutes(router.routes, "/signup")
    const submit = screen.getByRole("button", { name: "가입하기" })

    await fillSignup(user, "new-instructor@example.com", "1234567")
    await user.click(screen.getByLabelText("전체 동의"))
    expect(submit).toBeDisabled()

    await user.type(screen.getByLabelText("비밀번호"), "8")
    expect(submit).toBeDisabled()
    await user.type(screen.getByLabelText("비밀번호 확인"), "8")
    expect(submit).toBeEnabled()
  })

  it("이미 가입된 이메일이면 서버 문구를 보이고 가입 화면에 머문다", async () => {
    const { user, router: memoryRouter } = renderRoutes(router.routes, "/signup")

    await fillSignup(user, instructors.a.email.toUpperCase())
    await user.click(screen.getByLabelText("전체 동의"))
    await user.click(screen.getByRole("button", { name: "가입하기" }))

    expect(await screen.findByRole("alert")).toHaveTextContent("이미 가입된 이메일입니다.")
    expect(memoryRouter.state.location.pathname).toBe("/signup")
    expect(hasAccessToken()).toBe(false)
  })

  it("로그아웃하면 로그인 화면으로 가고, 강사 화면을 다시 열어도 로그인 화면이다", async () => {
    signIn()
    const { user, router: memoryRouter } = renderRoutes(router.routes, "/classrooms")

    await user.click(await screen.findByRole("button", { name: "로그아웃" }))

    expect(await screen.findByRole("heading", { name: "로그인" })).toBeInTheDocument()
    expect(hasAccessToken()).toBe(false)
    await act(() => memoryRouter.navigate("/classrooms"))
    expect(await screen.findByRole("heading", { name: "로그인" })).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe("/login")
  })
})
