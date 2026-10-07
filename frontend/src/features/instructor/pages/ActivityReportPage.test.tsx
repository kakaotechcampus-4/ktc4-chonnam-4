import { screen, within } from "@testing-library/react"
import { beforeEach, describe, expect, it } from "vitest"
import { fixtures, seedActivity } from "@/test/msw/handlers"
import { renderRoutes, signIn } from "@/test/render"
import { ActivityReportPage } from "./ActivityReportPage"

// 활동 리포트의 표정 퀴즈 탭(ADR 2026-10-03 D5). S1 완료 기준 "결과가 강사에게 보임"을 본다.
// 기술 문제로 판정하지 못한 문항은 서버가 정답률 분모에서 빼므로, 화면이 그 수를 따로 보여 주는지도 본다.

const CHILD = fixtures.childA1_1

const RESULT = {
  validQuestionCount: 2,
  correctQuestionCount: 1,
  overallAccuracy: 0.5,
  totalHintCount: 2,
  resolvedAfterHintCount: 1,
  scenarioLevel: "L2",
  initialSupportLevel: "S1",
  difficultyFallbackApplied: false,
  initialDifficultyUsed: true,
  policyVersion: "INITIAL_QUIZ_V1",
}

function renderReport(activityId: string) {
  return renderRoutes(
    [
      { path: "/activities/:activityId/report", element: <ActivityReportPage /> },
      { path: "/classrooms/:classId/children/:childId", element: <p>아동 상세 화면</p> },
    ],
    `/activities/${activityId}/report`,
  )
}

describe("활동 리포트 화면", () => {
  beforeEach(() => signIn())

  it("퀴즈 결과와 기술 문제로 뺀 문항 수, 첫 난이도를 보여 준다", async () => {
    const activity = seedActivity({
      childId: CHILD.childId,
      goalTitle: "친구가 속상할 때 위로하는 말을 한다",
      status: "IN_PROGRESS",
      result: RESULT,
    })

    renderReport(activity.activityId)

    expect(await screen.findByRole("heading", { name: "활동 리포트 · 김하늘 (햇살반)" })).toBeInTheDocument()
    const panel = await screen.findByRole("tabpanel", { name: "표정 퀴즈" })
    expect(await within(panel).findByText("50%")).toBeInTheDocument()
    expect(within(panel).getByText("1 / 2")).toBeInTheDocument()
    expect(within(panel).getByText("2회")).toBeInTheDocument()
    expect(within(panel).getByText("힌트 뒤 정답 1문항")).toBeInTheDocument()
    // 붙은 문항 3개 중 판정 문항 2개 → 1문항 제외
    expect(within(panel).getByText("1문항")).toBeInTheDocument()
    expect(within(panel).getByText(/이 결과로 첫 역할극 난이도를 정했어요\. 난이도 L2 · 지원 수준 S1/)).toBeInTheDocument()
    expect(within(panel).getByRole("heading", { name: "친구가 속상할 때 위로하는 말을 한다" })).toBeInTheDocument()
  })

  it("시작 전 활동은 결과를 묻지 않고 시작 전이라고 안내한다", async () => {
    const activity = seedActivity({ childId: CHILD.childId, goalTitle: "순서를 기다리며 기분을 말로 표현한다" })

    renderReport(activity.activityId)

    expect(await screen.findByText(/아직 시작 전이에요/)).toBeInTheDocument()
    expect(screen.getByText("시작 전")).toBeInTheDocument()
  })

  it("진행 중이고 결과가 아직 없으면 오류가 아니라 진행 중으로 안내한다", async () => {
    const activity = seedActivity({
      childId: CHILD.childId,
      goalTitle: "도움이 필요할 때 말로 요청한다",
      status: "IN_PROGRESS",
    })

    renderReport(activity.activityId)

    expect(await screen.findByText(/퀴즈를 진행 중이에요/)).toBeInTheDocument()
    expect(screen.queryByText("퀴즈가 완료되지 않았습니다.")).not.toBeInTheDocument()
  })

  it("이전 퀴즈로 첫 난이도가 정해졌으면 그렇게 안내한다", async () => {
    const activity = seedActivity({
      childId: CHILD.childId,
      goalTitle: "표정에서 기쁨·슬픔·화남을 구분한다",
      status: "IN_PROGRESS",
      result: { ...RESULT, initialDifficultyUsed: false },
    })

    renderReport(activity.activityId)

    expect(await screen.findByText("첫 난이도는 이전에 마친 퀴즈 결과로 이미 정해져 있어요.")).toBeInTheDocument()
  })

  it("다른 강사의 아동 활동은 볼 수 없다", async () => {
    const activity = seedActivity({ childId: fixtures.childC1_1.childId, goalTitle: "다른 반 목표" })

    renderReport(activity.activityId)

    expect(await screen.findByText("아동을 찾을 수 없습니다.")).toBeInTheDocument()
    expect(screen.queryByText("다른 반 목표")).not.toBeInTheDocument()
  })
})
