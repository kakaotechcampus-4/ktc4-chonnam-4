import { screen, within } from "@testing-library/react"
import { beforeEach, describe, expect, it } from "vitest"
import { fixtures, seedRoleplayLearning } from "@/test/msw/handlers"
import { renderRoutes, signIn } from "@/test/render"
import { ClassroomDetailPage } from "./ClassroomDetailPage"
import { RecommendationsPage } from "./RecommendationsPage"

// 추천·다음목표 화면과 학급 개요의 최근 학습 요약. 기준은 아동의 최신 다음 역할극 난이도이고,
// AI 요약이 없어도 그 학습 기록으로 제안을 보인다. 제안을 고르기 전에는 아무것도 배정하지 않는다.

const A1 = fixtures.classroomA1.classId

describe("추천·다음목표 화면", () => {
  beforeEach(() => signIn())

  it("학급의 아동마다 다음 난이도와 제안을 보이고, 역할극을 마치지 않은 아동은 그렇게 알린다", async () => {
    seedRoleplayLearning({ childId: fixtures.childA1_1.childId })

    renderRoutes([{ path: "/recommendations", element: <RecommendationsPage /> }], `/recommendations?classId=${A1}`)

    // 햇살반에는 동명이인 김하늘이 둘 있다(내부 childId 로만 구분). 화면 순서는 아동 목록 순서다.
    const [done, notYet] = await screen.findAllByRole("region", { name: fixtures.childA1_1.displayName })
    expect(await within(done).findByText(/감정 이름은 처음 단서로 말했고/)).toBeInTheDocument()
    expect(within(done).getAllByRole("article")).toHaveLength(3)
    expect(within(done).getByRole("link", { name: "리포트 보기" })).toBeInTheDocument()
    expect(await within(notYet).findByText("아직 마친 역할극이 없어요.")).toBeInTheDocument()
  })
})

describe("학급 개요 최근 학습 요약", () => {
  beforeEach(() => signIn())

  it("역할극을 마친 아동의 요약과 확인할 제안 수를 보이고 리포트로 잇는다", async () => {
    const { activity } = seedRoleplayLearning({ childId: fixtures.childA1_1.childId })

    renderRoutes([{ path: "/classrooms/:classId", element: <ClassroomDetailPage /> }], `/classrooms/${A1}`)

    const list = await screen.findByRole("list", { name: "최근 학습" })
    const link = await within(list).findByRole("link")
    expect(link).toHaveAttribute("href", `/activities/${activity.activityId}/report`)
    expect(link).toHaveTextContent("확인할 제안 3개")
    expect(link).toHaveTextContent(/감정 이름은 처음 단서로 말했고/)
  })
})
