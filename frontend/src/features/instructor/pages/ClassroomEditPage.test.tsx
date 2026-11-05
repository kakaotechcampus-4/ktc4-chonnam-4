import { screen } from "@testing-library/react"
import { beforeEach, describe, expect, it } from "vitest"
import { fixtures } from "@/test/msw/handlers"
import { renderRoutes, signIn } from "@/test/render"
import { ClassroomEditPage } from "./ClassroomCreatePage"
import { ClassroomDetailPage } from "./ClassroomDetailPage"

// 학급 수정(시안 T-CLS-02). 학급명만 고치고, 고친 이름이 학급 상세에 바로 보인다. 다른 강사의 학급은 없는 학급처럼 보인다.

const A1 = fixtures.classroomA1

function renderEdit(classId = A1.classId) {
  return renderRoutes(
    [
      { path: "/classrooms/:classId", element: <ClassroomDetailPage /> },
      { path: "/classrooms/:classId/edit", element: <ClassroomEditPage /> },
    ],
    `/classrooms/${classId}`,
  )
}

describe("학급 수정 화면", () => {
  beforeEach(() => signIn())

  it("학급 상세에서 학급 수정을 눌러 이름을 바꾸면 상세로 돌아가 바뀐 이름을 보여 준다", async () => {
    const { user, router } = renderEdit()

    await user.click(await screen.findByRole("link", { name: "학급 수정" }))
    const name = await screen.findByLabelText(/학급명/)
    expect(name).toHaveValue(A1.name)

    await user.clear(name)
    await user.type(name, "  새싹반 ")
    await user.click(screen.getByRole("button", { name: "저장" }))

    expect(await screen.findByRole("heading", { name: "학급 상세 · 새싹반" })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe(`/classrooms/${A1.classId}`)
  })

  it("이름이 그대로거나 비어 있으면 저장할 수 없다", async () => {
    const { user } = renderRoutes(
      [{ path: "/classrooms/:classId/edit", element: <ClassroomEditPage /> }],
      `/classrooms/${A1.classId}/edit`,
    )

    const name = await screen.findByLabelText(/학급명/)
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled()
    await user.clear(name)
    await user.type(name, "   ")
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled()
  })

  it("다른 강사의 학급은 수정 폼 없이 찾을 수 없다고 알린다", async () => {
    renderRoutes(
      [{ path: "/classrooms/:classId/edit", element: <ClassroomEditPage /> }],
      `/classrooms/${fixtures.classroomC1.classId}/edit`,
    )

    expect(await screen.findByText(/학급을 찾을 수 없습니다/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/학급명/)).not.toBeInTheDocument()
  })
})
