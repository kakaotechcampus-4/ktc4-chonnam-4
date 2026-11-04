import { screen, within } from "@testing-library/react"
import axe from "axe-core"
import { beforeEach, describe, expect, it, vi } from "vitest"
import { fixtures, seedActivity, seedRoleplayLearning } from "@/test/msw/handlers"
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

function renderReport(activityId: string, search = "") {
  return renderRoutes(
    [
      { path: "/activities/:activityId/report", element: <ActivityReportPage /> },
      { path: "/classrooms/:classId/children/:childId", element: <p>아동 상세 화면</p> },
      { path: "/recommendations", element: <p>추천 화면</p> },
    ],
    `/activities/${activityId}/report${search}`,
  )
}

describe("역할극 리포트 탭", () => {
  beforeEach(() => signIn())

  it("역할극을 마친 활동은 개요부터 열고 요약·목표별 도움·다음 난이도·제안을 보인다", async () => {
    const { activity } = seedRoleplayLearning({ childId: CHILD.childId })

    const { container } = renderReport(activity.activityId)

    const panel = await screen.findByRole("tabpanel", { name: "개요" })
    expect(await within(panel).findByText(/감정 이름은 처음 단서로 말했고/)).toBeInTheDocument()
    const table = within(panel).getByRole("table")
    expect(within(table).getAllByRole("row")).toHaveLength(4)
    expect(within(table).getByText("상황 단서에서 도달")).toBeInTheDocument()
    expect(within(table).getByText("답 알려 주기에서 도달")).toBeInTheDocument()
    expect(within(panel).getByText("L2 그대로")).toBeInTheDocument()
    expect(await within(panel).findAllByRole("article")).toHaveLength(3)
    // 계약은 강사 수정이 없다. 팀이 정할 때까지 탭만 닫아 둔다.
    expect(screen.getByRole("tab", { name: "AI 요약 수정" })).toHaveAttribute("aria-disabled", "true")
    // 시안 헤더의 "추천 보기"는 이 아동을 고른 추천 화면으로 간다. "내보내기"는 API 가 없어 비활성이다.
    expect(screen.getByRole("link", { name: "추천 보기" })).toHaveAttribute(
      "href",
      `/recommendations?classId=${CHILD.classId}&childId=${CHILD.childId}`,
    )
    expect(screen.getByRole("button", { name: "내보내기" })).toBeDisabled()

    const results = await axe.run(container, { rules: { "color-contrast": { enabled: false } } })
    expect(results.violations.map((v) => v.id)).toEqual([])
  })

  it("역할극·난이도 근거·기술 기록 탭으로 서버가 센 근거를 본다", async () => {
    const { activity } = seedRoleplayLearning({ childId: CHILD.childId })
    const { user } = renderReport(activity.activityId)
    // 리포트가 와야 역할극 탭이 열린다.
    await screen.findByText(/감정 이름은 처음 단서로 말했고/)

    await user.click(screen.getByRole("tab", { name: "역할극" }))
    const roleplay = await screen.findByRole("tabpanel", { name: "역할극" })
    // 시안 지표 세 칸: 판단한 목표, 유도 횟수(지원을 올린 단계 수 0+1+2), 지원 수준(시작 → 다음)
    expect(within(roleplay).getByText("3개")).toBeInTheDocument()
    expect(within(roleplay).getByText("3회")).toBeInTheDocument()
    expect(within(roleplay).getByText("다음 S1")).toBeInTheDocument()
    // 작은 목표별 증거: 시안처럼 상태 칩(달성·부분 달성)과 회색 줄 하나. 줄은 지원 경로 · 관찰 문장이다.
    const goal = within(roleplay).getByRole("region", { name: "감정의 이유 말하기" })
    expect(within(goal).getByRole("heading", { name: "감정의 이유 말하기" })).toBeInTheDocument()
    expect(within(goal).getByText("부분 달성")).toHaveAttribute("title", "지원을 더 받고 도달")
    expect(within(goal).getByText("S1 → S2 · 감정의 이유 말하기 관찰")).toBeInTheDocument()
    expect(within(roleplay).getByRole("region", { name: "감정 이름 말하기" })).toHaveTextContent("달성")
    // 유도 기록: 받은 지원 기호와 목표 · 받은 도움
    const log = within(roleplay).getByRole("region", { name: "유도 기록" })
    expect(within(log).getByText("위로하는 말 하기 · 답 알려 주기")).toBeInTheDocument()
    expect(within(log).getByText("기술 문제로 뺀 답이나 목표는 없어요.")).toBeInTheDocument()

    await user.click(screen.getByRole("tab", { name: "난이도 근거" }))
    const basis = await screen.findByRole("tabpanel", { name: "난이도 근거" })
    expect(within(basis).getByText("50%")).toBeInTheDocument()
    expect(within(basis).getByText("3개 중 2개")).toBeInTheDocument()
    expect(within(basis).getByText("FOLLOW_UP_ROLEPLAY_V1")).toBeInTheDocument()

    await user.click(screen.getByRole("tab", { name: "기술 기록" }))
    expect(await screen.findByText("기술 문제로 뺀 답이나 목표가 없어요.")).toBeInTheDocument()

    await user.click(screen.getByRole("tab", { name: "표정 퀴즈" }))
    expect(await screen.findByRole("tabpanel", { name: "표정 퀴즈" })).toBeInTheDocument()
  })

  it("AI 요약을 만들지 못했어도 서버가 정한 다음 난이도와 제안은 보인다", async () => {
    const { activity } = seedRoleplayLearning({ childId: CHILD.childId, withReport: false })

    renderReport(activity.activityId)

    const panel = await screen.findByRole("tabpanel", { name: "개요" })
    expect(await within(panel).findByText(/AI 요약을 만드는 중이거나 만들지 못했어요/)).toBeInTheDocument()
    expect(await within(panel).findAllByRole("article")).toHaveLength(3)
    expect(within(panel).getByText("L2 그대로")).toBeInTheDocument()
    expect(screen.getByRole("tab", { name: "역할극" })).toHaveAttribute("aria-disabled", "true")
  })

  it("제안 카드를 누르면 결정 없이 그 제안의 추천 상세로 간다", async () => {
    const { activity, recommendations } = seedRoleplayLearning({ childId: CHILD.childId })
    const { user, router } = renderReport(activity.activityId)

    const panel = await screen.findByRole("tabpanel", { name: "개요" })
    const [, second] = await within(panel).findAllByRole("article")
    await user.click(within(second).getByRole("link"))

    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/recommendations"))
    const params = new URLSearchParams(router.state.location.search)
    expect(params.get("classId")).toBe(CHILD.classId)
    expect(params.get("childId")).toBe(CHILD.childId)
    expect(params.get("recommendationId")).toBe(recommendations[1].recommendationId)
  })

  it("기술 기록 탭 옆에 따로 뺀 건수를 보인다", async () => {
    const { activity, report } = seedRoleplayLearning({ childId: CHILD.childId })
    report.roleplay.technicalExclusions = ["UNCLEAR_INPUT"]

    renderReport(activity.activityId)

    expect(await screen.findByRole("tab", { name: "기술 기록 1건" })).toBeInTheDocument()
  })
})

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
