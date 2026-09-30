import { screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"
import { renderRoutes } from "@/test/render"
import { useChildSessionStore } from "../store/childSessionStore"
import { ChildLayout } from "./ChildLayout"

// 개인정보(PRV)·VS-003 검사. "사용 종료" 는 브라우저에 남은 아동 정보를 지우고 코드 입력 화면으로
// 돌아간다(store 주석). replace 로 이동해야 공용 기기에서 뒤로 가기로 활동 화면에 다시 들어올 수 없다.
// 의도적으로 흐름을 바꾸면 이 테스트도 같이 고치세요.

describe("ChildLayout 사용 종료", () => {
  afterEach(() => useChildSessionStore.getState().endSession())

  it("세션을 비우고 코드 입력 화면으로 replace 이동한다", async () => {
    useChildSessionStore.getState().startSession({ childName: "김하늘", accessCode: "1234" })
    const { router, user } = renderRoutes(
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

    await user.click(screen.getByRole("button", { name: "사용 종료" }))

    expect(await screen.findByText("코드 입력 화면")).toBeInTheDocument()
    expect(useChildSessionStore.getState()).toMatchObject({ childName: null, accessCode: null })
    expect(router.state.location.pathname).toBe("/child")
    expect(router.state.historyAction).toBe("REPLACE")
  })
})
