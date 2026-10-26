import { screen } from "@testing-library/react"
import { beforeEach, describe, expect, it } from "vitest"
import { fixtures } from "@/test/msw/handlers"
import { renderRoutes, signIn } from "@/test/render"
import { ChildDetailPage } from "./ChildDetailPage"
import { ChildEditPage } from "./ChildEditPage"

// 아동 정보 수정(시안 T-LRN-06). 표시 이름만 고친다. 학급·연령·접근 방식과 학급에서 제외는 자리만 있다.
// 다른 강사의 아동, 주소의 학급과 다른 아동은 없는 아동처럼 보인다.

const A1 = fixtures.classroomA1.classId
const CHILD = fixtures.childA1_1

function editPath(classId = A1, childId = CHILD.childId) {
  return `/classrooms/${classId}/children/${childId}/edit`
}

function renderEdit(initialEntry = editPath()) {
  return renderRoutes(
    [
      { path: "/classrooms/:classId/children/:childId", element: <ChildDetailPage /> },
      { path: "/classrooms/:classId/children/:childId/edit", element: <ChildEditPage /> },
    ],
    initialEntry,
  )
}

describe("아동 정보 수정 화면", () => {
  beforeEach(() => signIn())

  it("아동 상세에서 정보 수정을 눌러 이름을 바꾸면 상세로 돌아가 바뀐 이름을 보여 준다", async () => {
    const { user, router } = renderEdit(`/classrooms/${A1}/children/${CHILD.childId}`)

    await user.click(await screen.findByRole("link", { name: "정보 수정" }))
    const name = await screen.findByLabelText(/표시 이름/)
    expect(name).toHaveValue(CHILD.displayName)

    await user.clear(name)
    await user.type(name, "김하람")
    await user.click(screen.getByRole("button", { name: "저장" }))

    expect(await screen.findByRole("heading", { name: "아동 상세 · 김하람 (햇살반)" })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe(`/classrooms/${A1}/children/${CHILD.childId}`)
  })

  it("이름 말고는 고칠 수 없고, 이름이 그대로면 저장할 수 없다", async () => {
    renderEdit()

    await screen.findByLabelText(/표시 이름/)
    expect(await screen.findByRole("option", { name: "햇살반" })).toBeInTheDocument()
    expect(screen.getByLabelText("학급")).toBeDisabled()
    expect(screen.getByLabelText("연령")).toBeDisabled()
    expect(screen.getByLabelText("접근 방식")).toBeDisabled()
    expect(screen.getByRole("button", { name: "학급에서 제외" })).toBeDisabled()
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled()
  })

  it("다른 강사의 아동은 수정 폼 없이 찾을 수 없다고 알린다", async () => {
    renderEdit(editPath(fixtures.classroomC1.classId, fixtures.childC1_1.childId))

    expect(await screen.findByText("아동을 찾을 수 없습니다.")).toBeInTheDocument()
    expect(screen.queryByLabelText(/표시 이름/)).not.toBeInTheDocument()
  })

  it("주소의 학급과 아동의 학급이 다르면 수정 폼을 보이지 않는다", async () => {
    renderEdit(editPath(fixtures.classroomB1.classId))

    expect(await screen.findByText("아동을 찾을 수 없습니다.")).toBeInTheDocument()
    expect(screen.queryByLabelText(/표시 이름/)).not.toBeInTheDocument()
  })
})
