import { screen } from "@testing-library/react"
import { http, HttpResponse } from "msw"
import { afterEach, beforeEach, describe, expect, it } from "vitest"
import { envelope, fixtures, instructors, toUser } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { renderRoutes, signIn } from "@/test/render"
import { router } from "./router"

// 화면 출력 보안(XSS) 검사. 학급·아동·강사 이름은 입력한 그대로 저장된다(백엔드 NameInputBoundaryIntegrationTest).
// 그 값이 화면에서 HTML 로 해석되면 다른 강사·아동 화면에서 스크립트가 돈다. 이름은 언제나 글자로만 보여야 한다.
// 강사 토큰이 sessionStorage 에 있어 스크립트가 돌면 토큰까지 읽힌다(tokenStorage.ts 의 규칙). dangerouslySetInnerHTML 같은 것을 쓰면
// 이 테스트가 먼저 깨진다.

const HOSTILE_NAMES = [
  `<img src=x onerror="window.__xss = true">`,
  `<script>window.__xss = true</script>`,
  `<a href="javascript:window.__xss = true">눌러 보세요</a>`,
]

declare global {
  interface Window {
    __xss?: boolean
  }
}

describe("이름 출력 보안(XSS)", () => {
  beforeEach(() => signIn())
  afterEach(() => {
    delete window.__xss
  })

  it.each(HOSTILE_NAMES)("학급 이름 %s 는 목록·제목에서 글자로만 보인다", async (name) => {
    const classroom = { ...fixtures.classroomA1, name }
    server.use(
      http.get("*/api/v1/classrooms", () => HttpResponse.json(envelope([classroom]))),
      http.get("*/api/v1/classrooms/:classId", () => HttpResponse.json(envelope(classroom))),
    )

    const list = renderRoutes(router.routes, "/classrooms")
    expect(await screen.findByRole("cell", { name })).toBeInTheDocument()
    expect(screen.getByRole("link", { name: `${name} 상세` })).toBeInTheDocument()
    expectNoInjectedElements(list.container)
    list.unmount()

    const detail = renderRoutes(router.routes, `/classrooms/${classroom.classId}`)
    expect(await screen.findByRole("heading", { name: `학급 상세 · ${name}` })).toBeInTheDocument()
    expectNoInjectedElements(detail.container)
  })

  it.each(HOSTILE_NAMES)("아동 이름 %s 는 아동 목록에서 글자로만 보인다", async (name) => {
    server.use(
      http.get("*/api/v1/classrooms/:classId/children", () =>
        HttpResponse.json(envelope([{ ...fixtures.childA1_1, displayName: name }])),
      ),
    )

    const { container } = renderRoutes(router.routes, `/classrooms/${fixtures.classroomA1.classId}?tab=children`)

    expect(await screen.findByRole("cell", { name })).toBeInTheDocument()
    expectNoInjectedElements(container)
  })

  it.each(HOSTILE_NAMES)("강사 이름 %s 는 사이드바에서 글자로만 보인다", async (name) => {
    server.use(
      http.get("*/api/v1/auth/session", () => HttpResponse.json(envelope({ user: { ...toUser(instructors.a), name } }))),
    )

    const { container } = renderRoutes(router.routes, "/classrooms")

    expect(await screen.findByText(name)).toBeInTheDocument()
    expectNoInjectedElements(container)
  })
})

function expectNoInjectedElements(container: HTMLElement) {
  expect(container.querySelector("img, script, iframe")).toBeNull()
  expect(container.querySelector('a[href^="javascript:"]')).toBeNull()
  expect(window.__xss).toBeUndefined()
}
