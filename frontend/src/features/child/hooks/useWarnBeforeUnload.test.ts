import { act, renderHook } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"
import { useChildSessionStore } from "../store/childSessionStore"
import { useWarnBeforeUnload } from "./useWarnBeforeUnload"

// 아동 세션 수명주기 검사(#18). 아동 정보는 브라우저 저장소에 남기지 않으므로(PRV, childSessionStore) 새로고침하면 세션이 사라진다.
// 그래서 세션이 있을 때만 새로고침·닫기 전에 확인을 받는다. 사용 종료했거나 화면을 떠난 뒤에는 묻지 않는다.
// 의도적으로 동작을 바꾸면 이 테스트도 같이 고치세요.

// jsdom 은 new BeforeUnloadEvent() 를 막는다. 취소할 수 있는 같은 이름의 이벤트로 흉내 낸다.
function tryToLeave() {
  const event = new Event("beforeunload", { cancelable: true })
  window.dispatchEvent(event)
  return event.defaultPrevented
}

function startSession() {
  act(() => useChildSessionStore.getState().startSession({ childName: "김하늘", accessCode: "1234" }))
}

describe("새로고침·닫기 전 확인", () => {
  afterEach(() => act(() => useChildSessionStore.getState().endSession()))

  it("아동 세션이 있으면 떠나기 전에 확인을 요청한다", () => {
    startSession()
    renderHook(() => useWarnBeforeUnload())

    expect(tryToLeave()).toBe(true)
  })

  it("아동 세션이 없으면 확인을 요청하지 않는다", () => {
    renderHook(() => useWarnBeforeUnload())

    expect(tryToLeave()).toBe(false)
  })

  it("사용 종료로 세션을 비우면 더 이상 묻지 않는다", () => {
    startSession()
    renderHook(() => useWarnBeforeUnload())

    act(() => useChildSessionStore.getState().endSession())

    expect(tryToLeave()).toBe(false)
  })

  it("화면을 떠나면(언마운트) 더 이상 묻지 않는다", () => {
    startSession()
    const { unmount } = renderHook(() => useWarnBeforeUnload())

    unmount()

    expect(tryToLeave()).toBe(false)
  })

  it("화면이 떠 있는 동안 세션이 시작되면 그때부터 묻는다", () => {
    renderHook(() => useWarnBeforeUnload())
    expect(tryToLeave()).toBe(false)

    startSession()

    expect(tryToLeave()).toBe(true)
  })
})
