import { screen, within } from "@testing-library/react"
import { beforeEach, describe, expect, it } from "vitest"
import { fixtures, seedRoleplayLearning } from "@/test/msw/handlers"
import { renderRoutes, signIn } from "@/test/render"
import { ReportsPage } from "./ReportsPage"

// 리포트 목록(시안 T-RPT-01). 학급 하나의 완료 리포트를 한 번에 받아 표로 보이고, 학급·아동·기간·유형은 주소에 남긴다.

function renderReports(search = "") {
  return renderRoutes(
    [
      { path: "/reports", element: <ReportsPage /> },
      { path: "/activities/:activityId/report", element: <p>활동 리포트 화면</p> },
    ],
    `/reports${search}`,
  )
}

describe("리포트 목록", () => {
  beforeEach(() => signIn())

  it("학급을 고르지 않았으면 첫 학급을 골라 주소에 남기고 그 학급 리포트를 보인다", async () => {
    seedRoleplayLearning({ childId: fixtures.childA1_1.childId, goalTitle: "햇살반 리포트" })
    seedRoleplayLearning({ childId: fixtures.childB1_1.childId, goalTitle: "바람반 리포트" })
    const { router } = renderReports()

    expect(await screen.findByRole("link", { name: "햇살반 리포트" })).toBeInTheDocument()
    expect(screen.queryByText("바람반 리포트")).not.toBeInTheDocument()
    expect(new URLSearchParams(router.state.location.search).get("classId")).toBe(fixtures.classroomA1.classId)
  })

  it("학급을 바꾸면 그 학급 리포트로 바뀌고, 표에 유형·난이도·지원 변화가 보인다", async () => {
    seedRoleplayLearning({ childId: fixtures.childB1_1.childId, goalTitle: "바람반 리포트" })
    const { user, router } = renderReports(`?classId=${fixtures.classroomA1.classId}`)

    expect(await screen.findByText("아직 완료한 리포트가 없습니다.")).toBeInTheDocument()
    await user.selectOptions(screen.getByRole("combobox", { name: "학급" }), "바람반")

    const link = await screen.findByRole("link", { name: "바람반 리포트" })
    const row = link.closest("tr")!
    expect(within(row).getByText("이바다")).toBeInTheDocument()
    expect(within(row).getByText("최초 결합 활동")).toBeInTheDocument()
    expect(new URLSearchParams(router.state.location.search).get("classId")).toBe(fixtures.classroomB1.classId)
  })

  it("유형을 고르면 맞는 리포트가 없을 때 조건에 맞는 리포트가 없다고 알린다", async () => {
    seedRoleplayLearning({ childId: fixtures.childA1_1.childId, goalTitle: "햇살반 리포트" })
    const { user } = renderReports()

    await screen.findByRole("link", { name: "햇살반 리포트" })
    await user.selectOptions(screen.getByRole("combobox", { name: "활동 유형" }), "역할극")
    expect(await screen.findByText("조건에 맞는 리포트가 없습니다.")).toBeInTheDocument()
  })
})
