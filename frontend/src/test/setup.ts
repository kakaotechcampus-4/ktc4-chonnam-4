import "@testing-library/jest-dom/vitest"
import { cleanup, configure } from "@testing-library/react"
import { afterAll, afterEach, beforeAll } from "vitest"
import { useChildSessionStore } from "@/features/child/store/childSessionStore"
import { resetMswData } from "./msw/handlers"
import { server } from "./msw/server"

// findBy·waitFor 가 화면을 기다리는 시간. 기본 1초는 커버리지 계측으로 느려진 CI 에서 파일의 첫 렌더(모듈 변환·MSW 시작)에 모자랄 때가 있다.
// 기다리는 동안 화면이 나오면 바로 넘어가므로 통과하는 테스트가 느려지지는 않는다.
configure({ asyncUtilTimeout: 5_000 })

// 핸들러가 없는 요청은 테스트를 실패시킨다. 조용히 실제 네트워크로 나가면
// 백엔드가 없는 CI 에서만 깨지는 테스트가 된다.
// MSW 의 "error" 모드는 오류를 찍고 fetch 를 실패시킬 뿐이라, 화면이 그 실패를 오류 문구로 보여 주면 테스트가 통과해 버린다.
// 그래서 요청을 모아 두었다가 테스트가 끝날 때 하나라도 있으면 실패시킨다.
const unhandledRequests: string[] = []

beforeAll(() =>
  server.listen({
    onUnhandledRequest(request, print) {
      unhandledRequests.push(`${request.method} ${request.url}`)
      print.error()
    },
  }),
)

// 정리는 한 곳에서 순서대로 한다. 실패를 던지기 전에 화면·강사 토큰·아동 세션·가짜 서버 상태를 먼저 비워야 다음 테스트로 새지 않는다.
// globals: false 라서 Testing Library 의 자동 cleanup 이 등록되지 않는다. 직접 부른다.
afterEach(() => {
  cleanup()
  sessionStorage.clear()
  useChildSessionStore.getState().endSession()
  server.resetHandlers()
  resetMswData()
  const leaked = unhandledRequests.splice(0)
  if (leaked.length > 0) {
    throw new Error(`MSW 핸들러가 없는 요청: ${leaked.join(", ")}`)
  }
})
afterAll(() => server.close())
