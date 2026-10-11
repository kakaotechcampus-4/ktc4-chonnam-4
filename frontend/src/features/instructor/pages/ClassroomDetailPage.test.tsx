import { fireEvent, screen, within } from "@testing-library/react"
import { http, HttpResponse } from "msw"
import { afterEach, beforeEach, describe, expect, it } from "vitest"
import { apiError, fixtures } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { renderRoutes, signIn } from "@/test/render"
import { listClassrooms } from "../api"
import { ClassroomDetailPage } from "./ClassroomDetailPage"

// VS-002 인수 조건 검사(강사 화면). "담당 학급에 아동을 등록한다", "같은 학급에 실명이 같은 아동을 등록할 수 있고 내부 childId 로
// 구분된다"를 정상·실패 경로로 본다. 다른 학급의 아동이 섞여 보이지 않는지, 다른 강사의 학급이 없는 학급과 똑같이 보이는지도 본다
// (VS-001 "다른 강사의 학급·아동은 … 상세에서 조회되지 않는다", "권한 밖 … 존재 여부가 드러나지 않도록").
// 의도적으로 흐름을 바꾸면 이 테스트도 같이 고치세요.

const A1 = fixtures.classroomA1.classId
const UNKNOWN_CLASS_ID = "00000000-0000-4000-8000-00000000ffff"

function renderDetailPage(classId = A1, tab = "") {
  return renderRoutes(
    [
      { path: "/classrooms", element: <p>학급 목록 화면</p> },
      { path: "/classrooms/:classId", element: <ClassroomDetailPage /> },
    ],
    `/classrooms/${classId}${tab ? `?tab=${tab}` : ""}`,
  )
}

/** 아동 목록 탭의 표에서 이름 칸만 읽는다. */
function childNames() {
  const table = screen.queryByRole("table")
  if (!table) return []
  const [, body] = within(table).getAllByRole("rowgroup")
  return within(body)
    .queryAllByRole("row")
    .map((row) => within(row).getAllByRole("cell")[0].textContent)
}

async function openChildrenTab() {
  const view = renderDetailPage(A1, "children")
  await screen.findByRole("table")
  return view
}

describe("학급 상세 화면", () => {
  beforeEach(() => signIn())
  afterEach(() => server.events.removeAllListeners())

  it("학급 이름을 제목에, 상태를 개요 탭에 보여 준다", async () => {
    renderDetailPage()

    expect(await screen.findByRole("heading", { name: "학급 상세 · 햇살반" })).toBeInTheDocument()
    expect(within(screen.getByRole("tabpanel", { name: "개요" })).getByText("운영중")).toBeInTheDocument()
  })

  it("개요의 '아동 등록' 을 누르면 아동 목록 탭으로 넘어가 그 학급 아동을 보여 준다", async () => {
    const { user, router } = renderDetailPage()

    await user.click(await screen.findByRole("button", { name: "아동 등록" }))

    expect(await screen.findByRole("tabpanel", { name: "아동 목록" })).toBeInTheDocument()
    expect(router.state.location.search).toBe("?tab=children")
    expect(await screen.findAllByRole("cell", { name: "김하늘" })).toHaveLength(2)
  })

  it("다른 학급의 아동은 보이지 않는다", async () => {
    await openChildrenTab()

    expect(childNames()).toEqual(["김하늘", "김하늘"])
    expect(screen.queryByText(/이바다/)).not.toBeInTheDocument()
  })

  it("동명이인을 한 명 더 등록하면 같은 이름이 세 명 보이고 입력칸이 빈다", async () => {
    const { user } = await openChildrenTab()
    const input = screen.getByLabelText("아동 이름")

    await user.type(input, "김하늘")
    await user.click(screen.getByRole("button", { name: "아동 등록" }))

    expect(await screen.findAllByRole("cell", { name: "김하늘" })).toHaveLength(3)
    expect(input).toHaveValue("")
  })

  it("등록 요청은 이 학급 주소로, 앞뒤 공백을 뺀 이름으로 보낸다", async () => {
    let sent: { path: string; displayName: string } | null = null
    server.events.on("request:start", async ({ request }) => {
      if (request.method !== "POST" || !new URL(request.url).pathname.endsWith("/children")) return
      const { displayName } = (await request.clone().json()) as { displayName: string }
      sent = { path: new URL(request.url).pathname, displayName }
    })
    const { user } = await openChildrenTab()

    await user.type(screen.getByLabelText("아동 이름"), "  이하늘  {Enter}")

    expect(await screen.findByRole("cell", { name: "이하늘" })).toBeInTheDocument()
    expect(sent).toEqual({ path: `/api/v1/classrooms/${A1}/children`, displayName: "이하늘" })
  })

  it("같은 순간 두 번 제출해도 등록 요청은 한 번만 보낸다(#17 ref 잠금)", async () => {
    let posts = 0
    server.events.on("request:start", ({ request }) => {
      if (request.method === "POST" && new URL(request.url).pathname.endsWith("/children")) posts += 1
    })
    const { user } = await openChildrenTab()
    const input = screen.getByLabelText("아동 이름")
    await user.type(input, "이하늘")

    const form = input.closest("form")!
    fireEvent.submit(form)
    fireEvent.submit(form)

    expect(await screen.findByRole("cell", { name: "이하늘" })).toBeInTheDocument()
    expect(childNames().filter((name) => name === "이하늘")).toHaveLength(1)
    expect(posts).toBe(1)
  })

  it("요청 중에는 버튼이 '등록 중...' 으로 바뀌고 눌리지 않는다", async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    server.use(
      http.post("*/api/v1/classrooms/:classId/children", async () => {
        await gate
        return HttpResponse.json({ data: fixtures.childA1_1, meta: { traceId: crypto.randomUUID() } })
      }),
    )
    const { user } = await openChildrenTab()

    await user.type(screen.getByLabelText("아동 이름"), "이하늘")
    await user.click(screen.getByRole("button", { name: "아동 등록" }))

    expect(await screen.findByRole("button", { name: "등록 중..." })).toBeDisabled()
    release()
    expect(await screen.findByRole("button", { name: "아동 등록" })).toBeInTheDocument()
  })

  it("검증 오류(422)면 서버가 알려 준 이유를 보이고, 목록은 그대로다", async () => {
    const { user } = await openChildrenTab()
    const input = screen.getByLabelText("아동 이름")

    await user.click(input)
    await user.paste("가".repeat(101))
    await user.click(screen.getByRole("button", { name: "아동 등록" }))

    expect(await screen.findByRole("alert")).toHaveTextContent("크기가 0에서 100 사이여야 합니다")
    expect(childNames()).toEqual(["김하늘", "김하늘"])
  })

  it.each([
    ["없는 학급", UNKNOWN_CLASS_ID],
    ["다른 강사의 학급", fixtures.classroomC1.classId],
  ])("%s이면 '학급을 찾을 수 없습니다' 를 한 번만 보이고 아동 등록 폼은 없다", async (_label, classId) => {
    renderDetailPage(classId, "children")

    expect(await screen.findAllByText(`학급을 찾을 수 없습니다: ${classId}`)).toHaveLength(1)
    expect(screen.queryByLabelText("아동 이름")).not.toBeInTheDocument()
    expect(screen.queryByRole("table")).not.toBeInTheDocument()
    expect(screen.queryByText(fixtures.classroomC1.name)).not.toBeInTheDocument()
  })

  it("아동 목록만 실패하면 학급 제목은 보이고 목록 자리에 오류를 보인다", async () => {
    server.use(
      http.get("*/api/v1/classrooms/:classId/children", ({ request }) =>
        apiError(500, "INTERNAL_ERROR", "서버 내부 오류가 발생했습니다.", new URL(request.url).pathname),
      ),
    )
    renderDetailPage(A1, "children")

    expect(await screen.findByRole("heading", { name: "학급 상세 · 햇살반" })).toBeInTheDocument()
    expect(await screen.findByText("서버 내부 오류가 발생했습니다.")).toBeInTheDocument()
    expect(screen.queryByRole("table")).not.toBeInTheDocument()
  })

  it("'← 학급 목록' 으로 목록 화면에 돌아간다", async () => {
    const { user, router } = renderDetailPage()

    await user.click(screen.getByRole("link", { name: "← 학급 목록" }))

    expect(await screen.findByText("학급 목록 화면")).toBeInTheDocument()
    expect(router.state.location.pathname).toBe("/classrooms")
  })

  // 학급 삭제(ADR 2026-10-04 D4). 아동 여러 명의 기록이 함께 사라져 학급 이름을 입력해야 실행된다.
  it("학급 이름을 똑같이 입력해야 삭제할 수 있고, 삭제하면 학급 목록으로 간다", async () => {
    const { user, router } = renderDetailPage()

    await user.click(await screen.findByRole("button", { name: "학급 삭제" }))
    const dialog = await screen.findByRole("alertdialog", { name: "햇살반 학급을 삭제할까요?" })
    expect(within(dialog).getByText(/아동 2명과/)).toBeInTheDocument()
    const confirm = within(dialog).getByRole("button", { name: "영구 삭제" })
    expect(confirm).toBeDisabled()

    await user.type(within(dialog).getByRole("textbox"), "햇살")
    expect(confirm).toBeDisabled()
    await user.type(within(dialog).getByRole("textbox"), "반")
    await user.click(confirm)

    await screen.findByText("학급 목록 화면")
    expect(router.state.location.pathname).toBe("/classrooms")
    expect((await listClassrooms()).map((classroom) => classroom.classId)).not.toContain(A1)
  })

  it("삭제가 실패하면 확인 창에 오류를 보이고 화면에 머문다", async () => {
    server.use(
      http.delete("*/api/v1/classrooms/:classId", ({ request }) =>
        apiError(500, "INTERNAL_ERROR", "잠시 후 다시 시도해 주세요.", new URL(request.url).pathname),
      ),
    )
    const { user, router } = renderDetailPage()

    await user.click(await screen.findByRole("button", { name: "학급 삭제" }))
    const dialog = await screen.findByRole("alertdialog")
    await user.type(within(dialog).getByRole("textbox"), "햇살반")
    await user.click(within(dialog).getByRole("button", { name: "영구 삭제" }))

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("잠시 후 다시 시도해 주세요.")
    expect(router.state.location.pathname).toBe(`/classrooms/${A1}`)
  })
})
