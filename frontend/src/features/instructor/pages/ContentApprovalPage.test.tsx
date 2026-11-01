import { screen, within } from "@testing-library/react"
import { http, HttpResponse } from "msw"
import { beforeEach, describe, expect, it } from "vitest"
import { envelope } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { renderRoutes, signIn } from "@/test/render"
import type { ScenarioApprovalItem } from "../api"
import { ContentApprovalPage } from "./ContentApprovalPage"

// 콘텐츠·승인 → 승인 대기함. 시나리오를 기다리는 활동을 상태와 함께 보여 주고, 상태에 맞는 화면으로 보낸다.

function item(overrides: Partial<ScenarioApprovalItem>): ScenarioApprovalItem {
  return {
    activityId: crypto.randomUUID(),
    childId: crypto.randomUUID(),
    childName: "가명아동",
    classId: "20000000-0000-4000-8000-000000000001",
    className: "햇살반",
    goalId: crypto.randomUUID(),
    goalTitle: "학습 목표",
    scenarioLevel: "L2",
    waitingSince: "2026-11-01T01:00:00Z",
    state: "CANDIDATE_AVAILABLE",
    draftScenarioId: null,
    draftVersion: null,
    ...overrides,
  }
}

function serve(items: ScenarioApprovalItem[]) {
  server.use(http.get("*/api/v1/scenario-approvals", () => HttpResponse.json(envelope(items))))
}

function renderInbox() {
  return renderRoutes([{ path: "/content", element: <ContentApprovalPage /> }], "/content")
}

describe("승인 대기함", () => {
  beforeEach(() => signIn())

  it("후보 없는 활동을 아래로 내리고, 상태마다 검토·수정 화면으로 보낸다", async () => {
    const none = item({ childName: "가나", goalTitle: "후보 없는 목표", state: "NO_CANDIDATE" })
    const candidate = item({ childName: "다라", goalTitle: "후보 있는 목표" })
    const draft = item({
      childName: "마바",
      goalTitle: "고치는 목표",
      state: "DRAFT_IN_PROGRESS",
      draftScenarioId: "80000000-0000-4000-8000-000000000009",
      draftVersion: 2,
    })
    serve([none, candidate, draft])
    renderInbox()

    const rows = (await screen.findAllByRole("row")).slice(1)
    expect(rows.map((row) => within(row).getAllByRole("cell")[0].textContent)).toEqual([
      "후보 있는 목표다라",
      "고치는 목표마바",
      "후보 없는 목표가나",
    ])
    expect(within(rows[0]).getByRole("link", { name: "다라 후보 있는 목표 검토" })).toHaveAttribute(
      "href",
      `/activities/${candidate.activityId}/scenario`,
    )
    expect(within(rows[1]).getByRole("link", { name: "마바 고치는 목표 수정" })).toHaveAttribute(
      "href",
      "/scenarios/80000000-0000-4000-8000-000000000009/edit",
    )
    expect(within(rows[2]).queryByRole("link")).not.toBeInTheDocument()
    expect(within(rows[2]).getByText("후보 없음")).toBeInTheDocument()
  })

  it("상태로 거르고 아동·목표로 찾는다", async () => {
    serve([
      item({ childName: "가나", goalTitle: "위로하기", state: "NO_CANDIDATE" }),
      item({ childName: "다라", goalTitle: "도움 요청하기" }),
    ])
    const { user } = renderInbox()

    await user.click(await screen.findByRole("button", { name: "후보 없음 1" }))
    expect(screen.getAllByRole("row")).toHaveLength(2)
    expect(screen.getByText("위로하기")).toBeInTheDocument()

    await user.click(screen.getByRole("button", { name: "전체 2" }))
    await user.type(screen.getByRole("searchbox", { name: "아동·목표·학급 검색" }), "도움")
    expect(screen.getAllByRole("row")).toHaveLength(2)
    expect(screen.getByText("도움 요청하기")).toBeInTheDocument()
  })

  it("기다리는 활동이 없으면 안내한다", async () => {
    serve([])
    renderInbox()
    expect(await screen.findByText(/승인을 기다리는 활동이 없습니다/)).toBeInTheDocument()
    expect(screen.getByRole("tab", { name: "승인 대기함" })).toHaveAttribute("aria-selected", "true")
  })
})
