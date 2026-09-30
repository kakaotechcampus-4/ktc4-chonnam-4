import { screen, within } from "@testing-library/react"
import { http, HttpResponse } from "msw"
import { beforeEach, describe, expect, it } from "vitest"
import { apiError, envelope, fixtures, instructors } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { renderRoutes, signIn } from "@/test/render"
import { ClassroomListPage } from "./ClassroomListPage"

// VS-002 "강사가 학급을 … 조회할 수 있다", VS-001 "다른 강사의 학급·아동은 목록과 상세에서 모두 조회되지 않는다"(화면 쪽).
// 학급 만들기는 ClassroomCreatePage.test.tsx 가 본다. 화면 안쪽 구현(상태 이름·훅)이 아니라 강사가 보고 누르는 것만 본다.
// 의도적으로 흐름을 바꾸면 이 테스트도 같이 고치세요.

function renderListPage() {
  return renderRoutes(
    [
      { path: "/classrooms", element: <ClassroomListPage /> },
      { path: "/classrooms/new", element: <p>학급 생성 화면</p> },
      { path: "/classrooms/:classId", element: <p>학급 상세 화면</p> },
    ],
    "/classrooms",
  )
}

function classroomRows() {
  const [, body] = screen.getAllByRole("rowgroup")
  return within(body).queryAllByRole("row")
}

describe("학급 목록 화면", () => {
  beforeEach(() => signIn())

  it("불러오는 동안 안내를 보이고, 로그인한 강사의 학급만 이름·상태와 함께 보여 준다", async () => {
    renderListPage()

    expect(screen.getByText("불러오는 중...")).toBeInTheDocument()
    expect(await screen.findByRole("cell", { name: "햇살반" })).toBeInTheDocument()
    expect(screen.getByRole("cell", { name: "바람반" })).toBeInTheDocument()
    expect(classroomRows()).toHaveLength(2)
    expect(within(classroomRows()[0]).getByText("운영중")).toBeInTheDocument()
    expect(screen.queryByText(fixtures.classroomC1.name)).not.toBeInTheDocument()
    expect(screen.queryByText("불러오는 중...")).not.toBeInTheDocument()
  })

  it("사이드바에 로그인한 강사 이름을 보인다", async () => {
    renderListPage()

    expect(await screen.findByText(instructors.a.name)).toBeInTheDocument()
  })

  it("학급의 '상세' 를 누르면 그 학급의 상세 주소로 이동한다", async () => {
    const { user, router } = renderListPage()

    await user.click(await screen.findByRole("link", { name: "햇살반 상세" }))

    expect(await screen.findByText("학급 상세 화면")).toBeInTheDocument()
    expect(router.state.location.pathname).toBe(`/classrooms/${fixtures.classroomA1.classId}`)
  })

  it("'학급 생성' 을 누르면 학급 생성 화면으로 간다", async () => {
    const { user, router } = renderListPage()

    await user.click(screen.getByRole("link", { name: "학급 생성" }))

    expect(await screen.findByText("학급 생성 화면")).toBeInTheDocument()
    expect(router.state.location.pathname).toBe("/classrooms/new")
  })

  it("목록을 불러오지 못하면 서버의 오류 문구를 보이고 표는 그리지 않는다", async () => {
    server.use(
      http.get("*/api/v1/classrooms", () =>
        apiError(500, "INTERNAL_ERROR", "서버 내부 오류가 발생했습니다.", "/api/v1/classrooms"),
      ),
    )
    renderListPage()

    expect(await screen.findByText("서버 내부 오류가 발생했습니다.")).toBeInTheDocument()
    expect(screen.queryByRole("table")).not.toBeInTheDocument()
  })

  it("학급이 하나도 없으면 안내를 보이고, 학급 생성으로 갈 수 있다", async () => {
    server.use(http.get("*/api/v1/classrooms", () => HttpResponse.json(envelope([]))))
    renderListPage()

    expect(await screen.findByText(/아직 학급이 없습니다/)).toBeInTheDocument()
    expect(screen.queryByRole("table")).not.toBeInTheDocument()
    expect(screen.getByRole("link", { name: "학급 생성" })).toHaveAttribute("href", "/classrooms/new")
  })
})
