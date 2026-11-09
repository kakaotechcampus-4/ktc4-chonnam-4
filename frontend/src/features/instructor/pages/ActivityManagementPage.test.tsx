import { screen, within } from "@testing-library/react"
import { beforeEach, describe, expect, it } from "vitest"
import { fixtures, seedActivity } from "@/test/msw/handlers"
import { renderRoutes, signIn } from "@/test/render"
import { ActivityManagementPage } from "./ActivityManagementPage"

// 활동 관리(시안 T-MGT 축소판). 담당 학급 전체의 활동을 모아 보고, 일시 중지·복구 필요 활동을 따로 모은다.
// 조치 API 가 없어 줄마다 아동 상세·새 활동 배정으로 가는 링크를 둔다.

function renderManagement(search = "") {
  return renderRoutes(
    [
      { path: "/activities", element: <ActivityManagementPage /> },
      { path: "/activities/new", element: <p>활동 만들기 화면</p> },
      { path: "/activities/:activityId/report", element: <p>활동 리포트 화면</p> },
    ],
    `/activities${search}`,
  )
}

describe("활동 관리", () => {
  beforeEach(() => {
    signIn()
    seedActivity({ childId: fixtures.childA1_1.childId, goalTitle: "위로하는 말 하기", status: "COMPLETED" })
    seedActivity({ childId: fixtures.childB1_1.childId, goalTitle: "차례 기다리기", status: "RECOVERY_NEEDED" })
  })

  it("담당 학급 전체의 활동을 한 표로 보이고, 상태 분포를 센다", async () => {
    renderManagement()

    expect(await screen.findByRole("link", { name: "위로하는 말 하기" })).toBeInTheDocument()
    expect(screen.getByRole("link", { name: "차례 기다리기" })).toBeInTheDocument()
    expect(screen.getByText("김하늘 · 햇살반")).toBeInTheDocument()
    expect(screen.getByText("이바다 · 바람반")).toBeInTheDocument()
    expect(screen.getByRole("link", { name: "활동 관리" })).toHaveAttribute("aria-current", "page")

    const distribution = screen.getByRole("region", { name: "전체 활동 상태 분포" })
    const rows = within(distribution).getAllByRole("listitem")
    expect(rows.map((row) => row.textContent)).toEqual(["시작 전0", "진행 중0", "일시 중지0", "복구 필요1", "완료1"])
  })

  it("학급을 고르면 그 학급 활동만 보이고 주소에 남긴다", async () => {
    const { router, user } = renderManagement()
    await screen.findByRole("link", { name: "위로하는 말 하기" })

    await user.selectOptions(screen.getByLabelText("학급"), fixtures.classroomB1.classId)

    expect(screen.queryByRole("link", { name: "위로하는 말 하기" })).not.toBeInTheDocument()
    expect(screen.getByRole("link", { name: "차례 기다리기" })).toBeInTheDocument()
    expect(new URLSearchParams(router.state.location.search).get("classId")).toBe(fixtures.classroomB1.classId)
  })

  it("미완료 · 복구 탭은 챙길 활동만 보이고, 아동 상세와 새 활동 배정으로 보낸다", async () => {
    const { router, user } = renderManagement()
    await screen.findByRole("link", { name: "위로하는 말 하기" })

    await user.click(screen.getByRole("tab", { name: /미완료 · 복구/ }))

    expect(new URLSearchParams(router.state.location.search).get("tab")).toBe("attention")
    expect(screen.getByText("챙길 활동 1건")).toBeInTheDocument()
    expect(screen.queryByRole("link", { name: "위로하는 말 하기" })).not.toBeInTheDocument()
    expect(screen.getByRole("link", { name: "이바다 아동 상세" })).toHaveAttribute(
      "href",
      `/classrooms/${fixtures.classroomB1.classId}/children/${fixtures.childB1_1.childId}`,
    )

    await user.click(screen.getByRole("link", { name: "이바다에게 새 활동 배정" }))
    expect(await screen.findByText("활동 만들기 화면")).toBeInTheDocument()
    expect(router.state.location.search).toBe(
      `?classId=${fixtures.classroomB1.classId}&childId=${fixtures.childB1_1.childId}`,
    )
  })

  it("챙길 활동이 없으면 없다고 알린다", async () => {
    renderManagement(`?tab=attention&classId=${fixtures.classroomA1.classId}`)

    expect(await screen.findByText("일시 중지되거나 복구가 필요한 활동이 없어요.")).toBeInTheDocument()
  })
})
