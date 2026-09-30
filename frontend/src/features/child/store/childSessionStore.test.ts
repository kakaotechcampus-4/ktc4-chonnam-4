import { afterEach, describe, expect, it } from "vitest"
import { useChildSessionStore } from "./childSessionStore"

// 개인정보(PRV) 검사. store 주석의 약속 — "아동 개인정보는 새로고침 시 함께 사라져야 하므로
// persist 미들웨어를 쓰지 않는다" — 을 고정한다. 누가 persist 를 붙이면 이 테스트가 먼저 깨진다.
// 의도적으로 저장 방식을 바꾸면 이 테스트도 같이 고치세요.

describe("아동 세션 저장소", () => {
  afterEach(() => {
    useChildSessionStore.getState().endSession()
    localStorage.clear()
    sessionStorage.clear()
  })

  it("아동 이름과 입장 코드를 브라우저 저장소에 남기지 않는다", () => {
    useChildSessionStore.getState().startSession({ childName: "김하늘", accessCode: "1234" })

    expect(useChildSessionStore.getState().childName).toBe("김하늘")
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it("사용 종료하면 이름과 입장 코드를 비운다", () => {
    useChildSessionStore.getState().startSession({ childName: "김하늘", accessCode: "1234" })

    useChildSessionStore.getState().endSession()

    expect(useChildSessionStore.getState()).toMatchObject({ childName: null, accessCode: null })
  })
})
