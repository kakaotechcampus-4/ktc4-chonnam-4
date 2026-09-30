import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { render } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { createMemoryRouter, RouterProvider, type RouteObject } from "react-router-dom"
import { setAccessToken } from "@/features/instructor/auth/tokenStorage"
import { useChildSessionStore } from "@/features/child/store/childSessionStore"
import { instructors } from "./msw/handlers"

/**
 * 라우터·React Query 를 붙여 화면을 렌더링한다. 화면 테스트는 이 함수로 시작하면 된다.
 *
 * - QueryClient 는 테스트마다 새로 만든다. 기본값(실패 시 3회 재시도)이면 오류 화면 테스트가
 *   재시도를 기다리다 느려지고, 앞 테스트의 캐시가 다음 테스트로 샌다.
 * - 반환하는 router 로 이동 결과(router.state.location, historyAction)를 확인할 수 있다.
 */
export function renderRoutes(routes: RouteObject[], initialEntry = "/") {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const router = createMemoryRouter(routes, { initialEntries: [initialEntry] })
  const user = userEvent.setup()

  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )

  return { ...view, router, user, queryClient }
}

/**
 * 강사가 로그인한 상태로 만든다. 실제 앱처럼 토큰을 sessionStorage 에 넣고(tokenStorage.ts), 가짜 서버는 그 토큰을 강사 A 로 안다.
 * 강사 화면·강사 API 테스트는 렌더링 전에 부른다. setup 이 테스트마다 sessionStorage 를 비운다.
 */
export function signIn(instructor: { accessToken: string } = instructors.a) {
  setAccessToken(instructor.accessToken)
}

/** 아동이 입장한 상태로 만든다(RequireChildSession 가드 통과). 끝나면 각 테스트가 endSession() 으로 비운다. */
export function enterAsChild(childName = "김하늘") {
  useChildSessionStore.getState().startSession({ childName, accessCode: "1234" })
}
