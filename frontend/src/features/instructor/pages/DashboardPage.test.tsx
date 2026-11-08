import { screen, within } from "@testing-library/react"
import { http, HttpResponse } from "msw"
import { beforeEach, describe, expect, it } from "vitest"
import { apiError, envelope, fixtures, seedActivity } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { renderRoutes, signIn } from "@/test/render"
import type { ScenarioApprovalItem } from "../api"
import { DashboardPage } from "./DashboardPage"

// 강사 대시보드(시안 T-HOME-01 축소판). 학급마다 학급 활동 목록을 불러 합치고, 승인 대기·타임라인·챙길 활동·학급 요약을 보인다.

const waiting: ScenarioApprovalItem = {
  activityId: "40000000-0000-4000-8000-0000000000aa",
  childId: fixtures.childA1_1.childId,
  childName: "김하늘",
  classId: fixtures.classroomA1.classId,
  className: "햇살반",
  goalId: "30000000-0000-4000-8000-0000000000aa",
  goalTitle: "친구 위로하기",
  scenarioLevel: "L2",
  waitingSince: new Date().toISOString(),
  state: "WAITING",
}

function renderDashboard() {
  return renderRoutes([{ path: "/dashboard", element: <DashboardPage /> }], "/dashboard")
}

describe("강사 대시보드", () => {
  beforeEach(() => {
    signIn()
    server.use(http.get("*/api/v1/scenario-approvals", () => HttpResponse.json(envelope([waiting]))))
  })

  it("학급·아동 수, 활동 합계, 승인 대기, 챙길 활동, 학급 요약을 보인다", async () => {
    seedActivity({ childId: fixtures.childA1_1.childId, goalTitle: "위로하는 말 하기", status: "COMPLETED" })
    seedActivity({ childId: fixtures.childB1_1.childId, goalTitle: "차례 기다리기", status: "RECOVERY_NEEDED" })
    renderDashboard()

    expect(await screen.findByText("1건")).toBeInTheDocument()
    expect(screen.getByRole("link", { name: "햇살반 · 김하늘 · 친구 위로하기" })).toBeInTheDocument()
    expect(await screen.findByText("진행 중 0 · 완료 1")).toBeInTheDocument()

    const attention = screen.getByRole("region", { name: "챙길 활동" })
    expect(within(attention).getByRole("link", { name: "이바다 · 차례 기다리기" })).toBeInTheDocument()
    expect(within(attention).getByText("복구 필요")).toBeInTheDocument()

    const timeline = screen.getByRole("region", { name: "최근 학습 타임라인" })
    expect(within(timeline).getAllByRole("listitem")).toHaveLength(2)

    const classes = screen.getByRole("region", { name: "담당 학급 요약" })
    expect(within(classes).getByRole("link", { name: "햇살반" })).toBeInTheDocument()
    expect(await within(classes).findByText("아동 2 · 활동 완료 1/1")).toBeInTheDocument()
  })

  it("학급 하나의 아동·활동 조회가 실패해도 나머지 학급으로 그리고, 실패한 학급은 불러오지 못했다고 알린다", async () => {
    const B1 = fixtures.classroomB1.classId
    seedActivity({ childId: fixtures.childA1_1.childId, goalTitle: "위로하는 말 하기", status: "COMPLETED" })
    server.use(
      http.get(`*/api/v1/classrooms/${B1}/children`, () => apiError(500, "INTERNAL_ERROR", "오류", "/x")),
      http.get(`*/api/v1/classrooms/${B1}/activities`, () => apiError(500, "INTERNAL_ERROR", "오류", "/x")),
    )
    renderDashboard()

    expect(await screen.findByText("진행 중 0 · 완료 1")).toBeInTheDocument()
    expect(await screen.findByText("학급 1곳은 불러오지 못했어요")).toBeInTheDocument()
    expect(screen.getByText("2명")).toBeInTheDocument()
    const timeline = screen.getByRole("region", { name: "최근 학습 타임라인" })
    expect(within(timeline).getByRole("link", { name: /위로하는 말 하기/ })).toBeInTheDocument()
    const classes = screen.getByRole("region", { name: "담당 학급 요약" })
    expect(within(classes).getByText("아동 불러오지 못함 · 활동 완료 불러오지 못함")).toBeInTheDocument()
  })
})
