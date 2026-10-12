import { screen } from "@testing-library/react"
import { http, HttpResponse } from "msw"
import { afterEach, beforeEach, describe, expect, it } from "vitest"
import { hasAccessToken } from "../api"
import { instructors } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { renderRoutes, signIn } from "@/test/render"
import { AccountPage } from "./AccountPage"
import { LoginPage } from "./LoginPage"

// 계정 화면과 즉시 탈퇴. 탈퇴는 확인 창 없이 버튼 한 번으로 실행된다.

function renderAccountPage() {
  return renderRoutes(
    [
      { path: "/account", element: <AccountPage /> },
      { path: "/login", element: <LoginPage /> },
    ],
    "/account",
  )
}

describe("계정 화면", () => {
  beforeEach(() => signIn())
  afterEach(() => server.events.removeAllListeners())

  it("내 이름과 이메일을 보여 준다", async () => {
    renderAccountPage()

    expect(await screen.findByText(instructors.a.email)).toBeInTheDocument()
    expect(screen.getAllByText(instructors.a.name).length).toBeGreaterThan(0)
  })

  it("회원 탈퇴를 누르면 확인 없이 탈퇴하고, 토큰을 지운 뒤 로그인 화면에서 완료를 알린다", async () => {
    const deletes = { count: 0 }
    server.events.on("request:start", ({ request }) => {
      if (request.method === "DELETE" && new URL(request.url).pathname === "/api/v1/users/me") deletes.count += 1
    })
    const { user, router } = renderAccountPage()

    await user.click(await screen.findByRole("button", { name: "회원 탈퇴" }))

    expect(await screen.findByText("탈퇴가 완료됐습니다.")).toBeInTheDocument()
    expect(router.state.location.pathname).toBe("/login")
    expect(deletes.count).toBe(1)
    expect(hasAccessToken()).toBe(false)
  })

  it("탈퇴가 실패하면 오류를 보이고 로그인 상태를 그대로 둔다", async () => {
    server.use(
      http.delete("*/api/v1/users/me", () =>
        HttpResponse.json(
          {
            error: {
              status: 500,
              code: "INTERNAL_ERROR",
              message: "요청을 처리하지 못했습니다.",
              path: "/api/v1/users/me",
              traceId: "t",
            },
          },
          { status: 500 },
        ),
      ),
    )
    const { user, router } = renderAccountPage()

    await user.click(await screen.findByRole("button", { name: "회원 탈퇴" }))

    expect(await screen.findByRole("alert")).toBeInTheDocument()
    expect(router.state.location.pathname).toBe("/account")
    expect(hasAccessToken()).toBe(true)
  })
})
