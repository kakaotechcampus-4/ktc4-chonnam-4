import { screen, within } from "@testing-library/react"
import { http, HttpResponse } from "msw"
import { afterEach, beforeEach, describe, expect, it } from "vitest"
import { hasAccessToken } from "../api"
import { instructors } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { renderRoutes, signIn } from "@/test/render"
import { AccountPage } from "./AccountPage"
import { LoginPage } from "./LoginPage"

// 계정 화면과 탈퇴. 탈퇴는 확인 창에서 비밀번호를 넣고 한 번 더 눌러야 실행된다.

function renderAccountPage() {
  return renderRoutes(
    [
      { path: "/account", element: <AccountPage /> },
      { path: "/login", element: <LoginPage /> },
    ],
    "/account",
  )
}

function countWithdrawRequests() {
  const deletes = { count: 0 }
  server.events.on("request:start", ({ request }) => {
    if (request.method === "POST" && new URL(request.url).pathname === "/api/v1/users/me/withdrawal") deletes.count += 1
  })
  return deletes
}

describe("계정 화면", () => {
  beforeEach(() => signIn())
  afterEach(() => server.events.removeAllListeners())

  it("내 이름과 이메일을 보여 준다", async () => {
    renderAccountPage()

    expect(await screen.findByText(instructors.a.email)).toBeInTheDocument()
    expect(screen.getAllByText(instructors.a.name).length).toBeGreaterThan(0)
  })

  it("확인 창에서 취소하면 탈퇴 요청을 보내지 않는다", async () => {
    const deletes = countWithdrawRequests()
    const { user, router } = renderAccountPage()

    await user.click(await screen.findByRole("button", { name: "회원 탈퇴" }))
    await user.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "취소" }))

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument()
    expect(router.state.location.pathname).toBe("/account")
    expect(deletes.count).toBe(0)
    expect(hasAccessToken()).toBe(true)
  })

  it("비밀번호를 넣기 전에는 확인 창의 회원 탈퇴 버튼이 꺼져 있다", async () => {
    const { user } = renderAccountPage()

    await user.click(await screen.findByRole("button", { name: "회원 탈퇴" }))
    const dialog = await screen.findByRole("alertdialog")

    expect(within(dialog).getByRole("button", { name: "회원 탈퇴" })).toBeDisabled()
    await user.type(within(dialog).getByLabelText("확인을 위해 비밀번호를 입력해 주세요."), "x")
    expect(within(dialog).getByRole("button", { name: "회원 탈퇴" })).toBeEnabled()
  })

  it("확인 창에서 비밀번호를 넣고 회원 탈퇴를 누르면 탈퇴하고, 토큰을 지운 뒤 로그인 화면에서 완료를 알린다", async () => {
    const deletes = countWithdrawRequests()
    const { user, router } = renderAccountPage()

    await user.click(await screen.findByRole("button", { name: "회원 탈퇴" }))
    const dialog = await screen.findByRole("alertdialog")
    await user.type(within(dialog).getByLabelText("확인을 위해 비밀번호를 입력해 주세요."), instructors.a.password)
    await user.click(within(dialog).getByRole("button", { name: "회원 탈퇴" }))

    expect(await screen.findByText("탈퇴가 완료됐습니다.")).toBeInTheDocument()
    expect(router.state.location.pathname).toBe("/login")
    expect(deletes.count).toBe(1)
    expect(hasAccessToken()).toBe(false)
  })

  it("비밀번호가 틀리면 확인 창 안에 알리고 로그아웃하지 않는다", async () => {
    const { user, router } = renderAccountPage()

    await user.click(await screen.findByRole("button", { name: "회원 탈퇴" }))
    const dialog = await screen.findByRole("alertdialog")
    await user.type(within(dialog).getByLabelText("확인을 위해 비밀번호를 입력해 주세요."), "wrong-password")
    await user.click(within(dialog).getByRole("button", { name: "회원 탈퇴" }))

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("비밀번호가 올바르지 않습니다.")
    expect(router.state.location.pathname).toBe("/account")
    expect(hasAccessToken()).toBe(true)
  })

  it("탈퇴가 실패하면 확인 창 안에 오류를 보이고 로그인 상태를 그대로 둔다", async () => {
    server.use(
      http.post("*/api/v1/users/me/withdrawal", () =>
        HttpResponse.json(
          {
            error: {
              status: 500,
              code: "INTERNAL_ERROR",
              message: "요청을 처리하지 못했습니다.",
              path: "/api/v1/users/me/withdrawal",
              traceId: "t",
            },
          },
          { status: 500 },
        ),
      ),
    )
    const { user, router } = renderAccountPage()

    await user.click(await screen.findByRole("button", { name: "회원 탈퇴" }))
    const dialog = await screen.findByRole("alertdialog")
    await user.type(within(dialog).getByLabelText("확인을 위해 비밀번호를 입력해 주세요."), instructors.a.password)
    await user.click(within(dialog).getByRole("button", { name: "회원 탈퇴" }))

    expect(await within(dialog).findByRole("alert")).toBeInTheDocument()
    expect(router.state.location.pathname).toBe("/account")
    expect(hasAccessToken()).toBe(true)
  })
})
