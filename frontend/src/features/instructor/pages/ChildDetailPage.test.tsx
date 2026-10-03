import { screen, within } from "@testing-library/react"
import { describe, expect, it, beforeEach } from "vitest"
import { fixtures, seedActivity } from "@/test/msw/handlers"
import { renderRoutes, signIn } from "@/test/render"
import { ChildDetailPage } from "./ChildDetailPage"

// 아동 상세의 활동 이력 탭(ADR 2026-10-03 D5). 배정한 활동이 목표 제목·상태와 함께 보이고, 누르면 활동 리포트로 간다.
// 다른 강사의 아동은 없는 아동처럼 보인다(VS-001 "권한 밖 … 존재 여부가 드러나지 않도록").

const A1 = fixtures.classroomA1.classId
const CHILD = fixtures.childA1_1

function renderChildDetail(classId = A1, childId = CHILD.childId) {
  return renderRoutes(
    [
      { path: "/classrooms/:classId/children/:childId", element: <ChildDetailPage /> },
      { path: "/activities/:activityId/report", element: <p>활동 리포트 화면</p> },
      { path: "/activities/new", element: <p>활동 만들기 화면</p> },
    ],
    `/classrooms/${classId}/children/${childId}`,
  )
}

describe("아동 상세 화면", () => {
  beforeEach(() => signIn())

  it("배정한 활동을 목표 제목·상태와 함께 최신순으로 보여 준다", async () => {
    seedActivity({ childId: CHILD.childId, goalTitle: "표정에서 기쁨·슬픔·화남을 구분한다", status: "COMPLETED" })
    seedActivity({ childId: CHILD.childId, goalTitle: "친구가 속상할 때 위로하는 말을 한다" })

    renderChildDetail()

    expect(await screen.findByRole("heading", { name: "아동 상세 · 김하늘 (햇살반)" })).toBeInTheDocument()
    const table = await screen.findByRole("table")
    const [, body] = within(table).getAllByRole("rowgroup")
    const rows = within(body).getAllByRole("row")
    expect(rows.map((row) => within(row).getAllByRole("cell")[0].textContent)).toEqual([
      "친구가 속상할 때 위로하는 말을 한다",
      "표정에서 기쁨·슬픔·화남을 구분한다",
    ])
    expect(within(rows[0]).getByText("시작 전")).toBeInTheDocument()
    expect(within(rows[1]).getByText("완료")).toBeInTheDocument()
  })

  it("활동 이력 탭만 열리고 나머지 탭은 준비 중으로 보인다", async () => {
    renderChildDetail()

    const tabs = await screen.findByRole("tablist", { name: "아동 상세" })
    expect(within(tabs).getByRole("tab", { name: "활동 이력" })).toHaveAttribute("aria-selected", "true")
    expect(within(tabs).getByRole("tab", { name: "개요 · 학습 상태" })).toHaveAttribute("aria-disabled", "true")
    expect(within(tabs).getByRole("tab", { name: "접근 코드 · QR" })).toHaveAttribute("aria-disabled", "true")
  })

  it("활동을 누르면 그 활동의 리포트로 간다", async () => {
    const activity = seedActivity({ childId: CHILD.childId, goalTitle: "도움이 필요할 때 말로 요청한다" })
    const { user, router } = renderChildDetail()

    await user.click(await screen.findByRole("link", { name: "도움이 필요할 때 말로 요청한다" }))

    expect(router.state.location.pathname).toBe(`/activities/${activity.activityId}/report`)
  })

  it("활동이 없으면 안내하고, '활동 만들기' 는 이 아동을 골라 둔 채 연다", async () => {
    const { user, router } = renderChildDetail()

    expect(await screen.findByText("아직 배정된 활동이 없습니다.")).toBeInTheDocument()
    // 사이드바에도 같은 이름의 메뉴가 있다. 헤더의 버튼을 누른다.
    await user.click(within(screen.getByRole("banner")).getByRole("link", { name: "활동 만들기" }))
    expect(router.state.location.pathname).toBe("/activities/new")
    expect(router.state.location.search).toBe(`?classId=${A1}&childId=${CHILD.childId}`)
  })

  it("다른 강사의 아동은 없는 아동처럼 보이고 이름이 드러나지 않는다", async () => {
    const other = fixtures.childC1_1
    renderChildDetail(fixtures.classroomC1.classId, other.childId)

    expect(await screen.findByText("아동을 찾을 수 없습니다.")).toBeInTheDocument()
    expect(screen.queryByText(other.displayName, { exact: false })).not.toBeInTheDocument()
    expect(within(screen.getByRole("banner")).queryByRole("link", { name: "활동 만들기" })).not.toBeInTheDocument()
  })

  it("주소의 학급과 아동의 학급이 다르면 잘못된 주소로 본다", async () => {
    renderChildDetail(fixtures.classroomB1.classId, CHILD.childId)

    expect(await screen.findByText("아동을 찾을 수 없습니다.")).toBeInTheDocument()
    expect(screen.queryByRole("table")).not.toBeInTheDocument()
  })
})
