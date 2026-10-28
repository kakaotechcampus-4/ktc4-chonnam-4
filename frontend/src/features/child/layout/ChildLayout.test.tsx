import { screen, waitFor, within } from "@testing-library/react"
import { http, HttpResponse } from "msw"
import { describe, expect, it } from "vitest"
import { apiError } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { enterAsChild, renderRoutes } from "@/test/render"
import { useChildSessionStore } from "../store/childSessionStore"
import { ChildLayout } from "./ChildLayout"

// 개인정보(PRV)·VS-003 검사. "사용 종료"는 서버 세션을 끝낸 뒤(API-IAM-09) 브라우저에 남은 아동 정보·캐시를 지우고
// 코드 입력 화면으로 돌아간다. replace 로 이동해야 공용 기기에서 뒤로 가기로 활동 화면에 다시 들어올 수 없다.
// 서버 종료가 실패하면 끝난 것처럼 보이지 않게 화면에 남는다.
// 의도적으로 흐름을 바꾸면 이 테스트도 같이 고치세요.

const EXIT_URL = "*/api/v1/child-access-sessions/current"

function renderLayout() {
  enterAsChild()
  const view = renderRoutes(
    [
      { path: "/child", element: <p>코드 입력 화면</p> },
      {
        path: "/child/activities",
        element: (
          <ChildLayout activityTitle="내 활동">
            <p>활동 목록</p>
          </ChildLayout>
        ),
      },
    ],
    "/child/activities",
  )
  // 사용 종료 뒤 지워져야 할 아동 화면 캐시
  view.queryClient.setQueryData(["child", "child-1", "activities"], [{ activityId: "a-1" }])
  return view
}

describe("ChildLayout 사용 종료", () => {
  it("서버 종료가 끝나면 세션·캐시를 비우고 코드 입력 화면으로 replace 이동한다", async () => {
    const { router, user, queryClient } = renderLayout()

    await user.click(screen.getByRole("button", { name: "사용 종료" }))

    expect(await screen.findByText("코드 입력 화면")).toBeInTheDocument()
    expect(useChildSessionStore.getState()).toMatchObject({ childId: null, childName: null })
    expect(queryClient.getQueryCache().findAll({ queryKey: ["child"] })).toEqual([])
    expect(router.state.location.pathname).toBe("/child")
    expect(router.state.historyAction).toBe("REPLACE")
  })

  it("종료를 처리하는 동안 '끝내고 있어요'를 보여 주고 버튼을 막는다", async () => {
    let release!: () => void
    server.use(
      http.delete(EXIT_URL, async () => {
        await new Promise<void>((resolve) => (release = resolve))
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const { user } = renderLayout()

    await user.click(screen.getByRole("button", { name: "사용 종료" }))

    expect(await screen.findByRole("button", { name: "끝내고 있어요…" })).toBeDisabled()
    expect(useChildSessionStore.getState().childId).not.toBeNull()
    release()
    expect(await screen.findByText("코드 입력 화면")).toBeInTheDocument()
  })

  it.each([
    ["서버 오류", () => apiError(500, "INTERNAL_ERROR", "서버 오류", "/api/v1/child-access-sessions/current")],
    ["연결 끊김", () => HttpResponse.error()],
  ])("%s 로 종료가 실패하면 화면에 남아 모달을 띄우고, 다시 하면 끝낸다", async (_name, failure) => {
    server.use(http.delete(EXIT_URL, failure, { once: true }))
    const { router, user, queryClient } = renderLayout()

    await user.click(screen.getByRole("button", { name: "사용 종료" }))

    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByText("사용 종료를 하지 못했어요")).toBeInTheDocument()
    expect(router.state.location.pathname).toBe("/child/activities")
    expect(useChildSessionStore.getState().childId).not.toBeNull()
    expect(queryClient.getQueryData(["child", "child-1", "activities"])).toBeDefined()

    await user.click(within(dialog).getByRole("button", { name: "다시 하기" }))

    expect(await screen.findByText("코드 입력 화면")).toBeInTheDocument()
    expect(useChildSessionStore.getState()).toMatchObject({ childId: null, childName: null })
  })

  it("서버에 아동 세션이 이미 없으면(401) 끝난 것으로 보고 코드 입력 화면으로 간다", async () => {
    server.use(
      http.delete(EXIT_URL, () =>
        apiError(401, "AUTHENTICATION_REQUIRED", "아동 입장이 필요합니다.", "/api/v1/child-access-sessions/current"),
      ),
    )
    const { router, user } = renderLayout()

    await user.click(screen.getByRole("button", { name: "사용 종료" }))

    expect(await screen.findByText("코드 입력 화면")).toBeInTheDocument()
    expect(useChildSessionStore.getState()).toMatchObject({ childId: null, childName: null })
    expect(router.state.historyAction).toBe("REPLACE")
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
  })
})
