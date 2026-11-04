import { screen, within } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"
import { fixtures, seedRoleplayLearning } from "@/test/msw/handlers"
import { renderRoutes, signIn } from "@/test/render"
import { ClassroomDetailPage } from "./ClassroomDetailPage"
import { RecommendationsPage } from "./RecommendationsPage"

// 추천 학습 목록·추천 상세(시안 T-REC-01·02)와 학급 개요의 최근 학습 요약(T-CLS-03). 기준은 아동의 최신 다음 역할극 난이도이고,
// AI 요약이 없어도 그 학습 기록으로 제안을 보인다. 제안을 고르기 전에는 아무것도 배정하지 않는다.

const A1 = fixtures.classroomA1.classId
const CHILD = fixtures.childA1_1

function renderRecommendations(search: string) {
  return renderRoutes(
    [
      { path: "/recommendations", element: <RecommendationsPage /> },
      { path: "/activities/:activityId/report", element: <p>활동 리포트 화면</p> },
      { path: "/activities/new", element: <p>활동 만들기 화면</p> },
    ],
    `/recommendations${search}`,
  )
}

describe("추천 학습 목록", () => {
  beforeEach(() => signIn())

  it("고른 아동의 제안을 카드로 보이고, 역할극을 마치지 않은 아동은 그렇게 알린다", async () => {
    const { activity } = seedRoleplayLearning({ childId: CHILD.childId })

    const { user } = renderRecommendations(`?classId=${A1}`)

    // 아동을 고르지 않으면 학급의 첫 아동이다. 결정하지 않은 첫 제안이 골라진 채로 연다.
    expect(await screen.findByRole("heading", { name: `추천 학습 목록 · ${CHILD.displayName}` })).toBeInTheDocument()
    const board = await screen.findByRole("radiogroup", { name: "다음 활동으로 무엇을 할까요?" })
    const cards = within(board).getAllByRole("radio")
    expect(cards).toHaveLength(3)
    expect(cards[0]).toBeChecked()
    expect(within(board).getAllByText("권장 L2 / S1")).toHaveLength(3)
    expect(await screen.findByRole("link", { name: /기준 리포트 · 오늘 넘어진 친구의 속상한 마음 알아보기/ })).toHaveAttribute(
      "href",
      `/activities/${activity.activityId}/report`,
    )

    // 햇살반에는 동명이인 김하늘이 둘 있다(내부 childId 로만 구분).
    await user.selectOptions(screen.getByLabelText("아동"), fixtures.childA1_2.childId)
    expect(await screen.findByText("아직 마친 역할극이 없어요.")).toBeInTheDocument()
  })

  it("카드를 골라 추천 상세를 열면 이유·예상 조건·다른 추천이 보인다(고르기만으로는 결정을 남기지 않는다)", async () => {
    const { recommendations } = seedRoleplayLearning({ childId: CHILD.childId })
    const { user, router } = renderRecommendations(`?classId=${A1}&childId=${CHILD.childId}`)

    const board = await screen.findByRole("radiogroup", { name: "다음 활동으로 무엇을 할까요?" })
    await user.click(within(board).getAllByRole("radio")[1])
    await user.click(screen.getByRole("button", { name: "추천 상세 보기" }))

    expect(new URLSearchParams(router.state.location.search).get("recommendationId")).toBe(
      recommendations[1].recommendationId,
    )
    expect(await screen.findByRole("heading", { name: "추천 상세" })).toBeInTheDocument()
    const detail = screen.getByRole("article")
    expect(within(detail).getByText("반복")).toBeInTheDocument()
    expect(within(detail).getByText(recommendations[1].reason)).toBeInTheDocument()
    expect(screen.getByText("S1 (상황 단서)")).toBeInTheDocument()
    const others = screen.getByRole("region", { name: "다른 추천" })
    expect(within(others).getAllByRole("button")).toHaveLength(2)
    expect(within(detail).queryByText("채택함")).not.toBeInTheDocument()
  })

  it("이 추천으로 다음 목표를 정하면 채택을 남기고 목표가 채워진 역할극 만들기로 간다(배정은 하지 않는다)", async () => {
    const { activity, recommendations } = seedRoleplayLearning({ childId: CHILD.childId })
    const { user, router } = renderRecommendations(
      `?classId=${A1}&childId=${CHILD.childId}&recommendationId=${recommendations[0].recommendationId}`,
    )

    await user.click(await screen.findByRole("button", { name: "이 추천으로 다음 목표 설정" }))

    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/activities/new"))
    const params = new URLSearchParams(router.state.location.search)
    expect(params.get("type")).toBe("ROLEPLAY")
    expect(params.get("childId")).toBe(CHILD.childId)
    expect(params.get("goalTitle")).toBe("넘어진 친구의 속상한 마음 알아보기")
    expect(params.get("parentGoalId")).toBe(activity.goalId)
  })

  it("목표 문장을 고쳐 정하면 고친 목표로 활동 만들기에 간다", async () => {
    const { recommendations } = seedRoleplayLearning({ childId: CHILD.childId })
    const { user, router } = renderRecommendations(
      `?classId=${A1}&childId=${CHILD.childId}&recommendationId=${recommendations[0].recommendationId}`,
    )

    await user.click(await screen.findByRole("button", { name: "목표 문장 고치기" }))
    const input = screen.getByLabelText("목표 문장 수정")
    await user.clear(input)
    await user.type(input, "넘어진 친구에게 괜찮은지 물어본다")
    await user.click(screen.getByRole("button", { name: "이 추천으로 다음 목표 설정" }))

    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/activities/new"))
    expect(new URLSearchParams(router.state.location.search).get("goalTitle")).toBe("넘어진 친구에게 괜찮은지 물어본다")
  })

  it("거절은 이유를 골라 남기고 확정된 제안에는 결정 버튼이 사라진다", async () => {
    const { recommendations } = seedRoleplayLearning({ childId: CHILD.childId })
    const { user } = renderRecommendations(
      `?classId=${A1}&childId=${CHILD.childId}&recommendationId=${recommendations[1].recommendationId}`,
    )

    await user.click(await screen.findByRole("button", { name: "거절" }))
    await user.selectOptions(screen.getByLabelText("거절 이유"), "ALREADY_PLANNED")
    await user.click(screen.getByRole("button", { name: "거절하기" }))

    const detail = screen.getByRole("article")
    expect(await within(detail).findByText("거절함")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "이 추천으로 다음 목표 설정" })).not.toBeInTheDocument()
  })

  it("직접 목표 입력은 제안 없이 이 아동의 역할극 만들기로 간다", async () => {
    seedRoleplayLearning({ childId: CHILD.childId })
    const { user, router } = renderRecommendations(`?classId=${A1}&childId=${CHILD.childId}`)

    await user.click(await screen.findByRole("button", { name: "직접 목표 입력" }))

    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/activities/new"))
    const params = new URLSearchParams(router.state.location.search)
    expect(params.get("type")).toBe("ROLEPLAY")
    expect(params.get("childId")).toBe(CHILD.childId)
    expect(params.get("goalTitle")).toBeNull()
  })
})

describe("학급 개요 최근 학습 요약", () => {
  beforeEach(() => signIn())

  it("역할극을 마친 아동을 아동 · 활동 목표, 상태, 날짜 한 줄로 보이고 리포트로 잇는다", async () => {
    const { activity } = seedRoleplayLearning({ childId: CHILD.childId })

    renderRoutes([{ path: "/classrooms/:classId", element: <ClassroomDetailPage /> }], `/classrooms/${A1}`)

    const list = await screen.findByRole("list", { name: "최근 학습" })
    const link = await within(list).findByRole("link")
    expect(link).toHaveAttribute("href", `/activities/${activity.activityId}/report`)
    expect(await within(link).findByText(`${CHILD.displayName} · 넘어진 친구의 속상한 마음 알아보기`)).toBeInTheDocument()
    expect(within(link).getByText("완료")).toBeInTheDocument()
    expect(within(link).getByText("오늘")).toBeInTheDocument()
  })
})
