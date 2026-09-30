import { act, fireEvent, screen } from "@testing-library/react"
import { http, HttpResponse } from "msw"
import { afterEach, beforeEach, describe, expect, it } from "vitest"
import { envelope, fixtures, instructors } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { renderRoutes, signIn } from "@/test/render"
import { ClassroomCreatePage } from "./ClassroomCreatePage"
import { ClassroomListPage } from "./ClassroomListPage"

// VS-002 인수 조건 검사(강사 화면). "강사가 학급을 생성·조회한다"를 정상·실패 경로로 본다(S1-JEONG-01 "정상·실패 핵심 경로").
// 화면 안쪽 구현(상태 이름·훅)이 아니라 강사가 보고 누르는 것만 본다. 문구는 화면에 그대로 보이는 것만 단정한다.
// 의도적으로 흐름을 바꾸면 이 테스트도 같이 고치세요.

function renderCreatePage() {
  return renderRoutes(
    [
      { path: "/classrooms", element: <ClassroomListPage /> },
      { path: "/classrooms/new", element: <ClassroomCreatePage /> },
      { path: "/classrooms/:classId", element: <p>학급 상세 화면</p> },
    ],
    "/classrooms/new",
  )
}

function countPosts(path: string) {
  const posts = { count: 0 }
  server.events.on("request:start", ({ request }) => {
    if (request.method === "POST" && new URL(request.url).pathname === path) posts.count += 1
  })
  return posts
}

describe("학급 생성 화면", () => {
  beforeEach(() => signIn())
  afterEach(() => server.events.removeAllListeners())

  it("학급을 만들면 그 학급의 상세 화면으로 가고, 목록에도 로그인한 강사의 학급으로 나온다", async () => {
    let created: { classId: string; instructorId: string } | null = null
    server.events.on("response:mocked", async ({ request, response }) => {
      if (request.method === "POST" && new URL(request.url).pathname === "/api/v1/classrooms") {
        created = ((await response.clone().json()) as { data: { classId: string; instructorId: string } }).data
      }
    })
    const { user, router } = renderCreatePage()

    await user.type(screen.getByLabelText(/학급명/), "구름반")
    await user.click(screen.getByRole("button", { name: "저장" }))

    expect(await screen.findByText("학급 상세 화면")).toBeInTheDocument()
    expect(created).not.toBeNull()
    expect(router.state.location.pathname).toBe(`/classrooms/${created!.classId}`)
    expect(created!.instructorId).toBe(instructors.a.userId)

    await act(() => router.navigate("/classrooms"))
    expect(await screen.findByRole("cell", { name: "구름반" })).toBeInTheDocument()
  })

  it("Enter 로도 만들 수 있고, 앞뒤 공백을 뺀 이름으로 보낸다", async () => {
    let sentName: unknown = null
    server.events.on("request:start", async ({ request }) => {
      if (request.method === "POST") sentName = ((await request.clone().json()) as { name: string }).name
    })
    const { user } = renderCreatePage()

    await user.type(screen.getByLabelText(/학급명/), "  구름반  {Enter}")

    expect(await screen.findByText("학급 상세 화면")).toBeInTheDocument()
    expect(sentName).toBe("구름반")
  })

  it.each([
    ["빈 입력", ""],
    ["공백만", "   "],
  ])("%s이면 저장 버튼이 비활성이고 요청을 보내지 않는다", async (_label, name) => {
    const posts = countPosts("/api/v1/classrooms")
    const { user } = renderCreatePage()
    const input = screen.getByLabelText(/학급명/)

    if (name) await user.type(input, name)
    fireEvent.submit(input.closest("form")!)

    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled()
    expect(posts.count).toBe(0)
  })

  it("요청 중에는 버튼이 '저장 중...' 으로 바뀌고 눌리지 않는다", async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    server.use(
      http.post("*/api/v1/classrooms", async () => {
        await gate
        return HttpResponse.json(envelope({ ...fixtures.classroomA1, classId: crypto.randomUUID(), name: "구름반" }))
      }),
    )
    const { user } = renderCreatePage()

    await user.type(screen.getByLabelText(/학급명/), "구름반")
    await user.click(screen.getByRole("button", { name: "저장" }))

    expect(await screen.findByRole("button", { name: "저장 중..." })).toBeDisabled()
    release()
    expect(await screen.findByText("학급 상세 화면")).toBeInTheDocument()
  })

  it("같은 순간 두 번 제출해도 생성 요청은 한 번만 보낸다(#17 ref 잠금)", async () => {
    const posts = countPosts("/api/v1/classrooms")
    const { user } = renderCreatePage()
    const input = screen.getByLabelText(/학급명/)
    await user.type(input, "구름반")

    // 두 번째 제출은 isPending 이 화면에 반영되기 전에 들어온다. 버튼 비활성만으로는 못 막는 경우다.
    const form = input.closest("form")!
    fireEvent.submit(form)
    fireEvent.submit(form)

    expect(await screen.findByText("학급 상세 화면")).toBeInTheDocument()
    expect(posts.count).toBe(1)
  })

  it("검증 오류(422)면 서버가 알려 준 이유를 보이고, 입력값을 지우지 않고, 저장하지 않는다", async () => {
    const { user, router } = renderCreatePage()
    const input = screen.getByLabelText(/학급명/)
    const tooLong = "가".repeat(101)

    await user.click(input)
    await user.paste(tooLong)
    await user.click(screen.getByRole("button", { name: "저장" }))

    expect(await screen.findByRole("alert")).toHaveTextContent("크기가 0에서 100 사이여야 합니다")
    expect(input).toHaveValue(tooLong)
    expect(router.state.location.pathname).toBe("/classrooms/new")

    await act(() => router.navigate("/classrooms"))
    expect(await screen.findByRole("cell", { name: "햇살반" })).toBeInTheDocument()
    expect(screen.queryByRole("cell", { name: tooLong })).not.toBeInTheDocument()
  })

  it("CSRF 토큰을 받지 못하면 오류를 보이고 생성 요청을 보내지 않는다", async () => {
    server.use(http.get("*/api/v1/csrf", () => new HttpResponse(null, { status: 503 })))
    const posts = countPosts("/api/v1/classrooms")
    const { user } = renderCreatePage()

    await user.type(screen.getByLabelText(/학급명/), "구름반")
    await user.click(screen.getByRole("button", { name: "저장" }))

    expect(await screen.findByText("요청을 처리하지 못했습니다. (HTTP 503)")).toBeInTheDocument()
    expect(posts.count).toBe(0)
  })

  it("네트워크가 끊기면 오류를 보이고 입력값을 지우지 않으며, 다시 저장할 수 있다", async () => {
    server.use(http.post("*/api/v1/classrooms", () => HttpResponse.error(), { once: true }))
    const { user } = renderCreatePage()
    const input = screen.getByLabelText(/학급명/)
    await user.type(input, "구름반")

    await user.click(screen.getByRole("button", { name: "저장" }))
    expect(await screen.findByRole("alert")).toBeInTheDocument()
    expect(input).toHaveValue("구름반")

    await user.click(screen.getByRole("button", { name: "저장" }))
    expect(await screen.findByText("학급 상세 화면")).toBeInTheDocument()
  })

  it("'취소' 를 누르면 학급을 만들지 않고 목록으로 돌아간다", async () => {
    const posts = countPosts("/api/v1/classrooms")
    const { user, router } = renderCreatePage()

    await user.type(screen.getByLabelText(/학급명/), "구름반")
    await user.click(screen.getByRole("link", { name: "취소" }))

    expect(await screen.findByRole("cell", { name: "햇살반" })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe("/classrooms")
    expect(posts.count).toBe(0)
  })
})
