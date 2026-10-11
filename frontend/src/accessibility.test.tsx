import axe from "axe-core"
import { screen, waitFor, within } from "@testing-library/react"
import { describe, expect, it } from "vitest"
import { fixtures } from "@/test/msw/handlers"
import { enterAsChild, renderRoutes, signIn } from "@/test/render"
import { router } from "./router"

// 접근성 검사. 05 추적성 매트릭스 "A11Y → 아동 화면 사용성·상태 문구 검증", 07 "아동 화면의 상태·오류·로딩·접근성"을 자동으로 확인한다.
// - axe-core 로 WCAG 2.x A·AA 규칙을 모든 화면에 돌린다. 색 대비는 실제 CSS 가 있어야 계산되므로 E2E(e2e/accessibility.spec.ts)가 본다.
// - 키보드만으로 사용 종료할 수 있고, 아동 로딩 상태는 status 로, 오류·권한·만료·네트워크 모달은 이름 있는 대화상자로 알려진다.
// 새 화면을 라우터에 추가하면 아래 PAGES 에도 넣는다. 위반이 나오면 규칙을 끄지 말고 화면을 고친다.

const WCAG_A_AA = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"]

async function violationsIn(container: Element) {
  const results = await axe.run(container, {
    runOnly: { type: "tag", values: WCAG_A_AA },
    rules: { "color-contrast": { enabled: false } },
  })
  return results.violations.map(
    (violation) => `${violation.id}: ${violation.help} — ${violation.nodes.map((node) => node.target.join(" ")).join(", ")}`,
  )
}

type Access = "공개" | "강사" | "아동"

// 화면마다 다 그려졌다고 볼 수 있는 문구와, 그 화면을 열려면 필요한 상태. 문구가 보인 뒤에 검사해야 로딩 화면만 검사하는 일이 없다.
const PAGES: [string, string, Access][] = [
  ["/", "느링고", "공개"],
  ["/login", "강사 · 기관 계정으로 로그인하세요", "공개"],
  ["/signup", "기본 정보를 입력해 계정을 만드세요", "공개"],
  ["/classrooms", "햇살반", "강사"],
  ["/classrooms/new", "기본 정보", "강사"],
  [`/classrooms/${fixtures.classroomA1.classId}`, "바로 하기", "강사"],
  [`/classrooms/${fixtures.classroomA1.classId}?tab=children`, "김하늘", "강사"],
  ["/child", "입장 코드를 입력해줘!", "공개"],
  ["/child/hello", "오늘도 만나서 반가워", "아동"],
  ["/child/activities", "친구 마음 알아보기", "아동"],
  ["/child/activities/mock-activity-1", "이어서 하기", "아동"],
  ["/child/quiz/mock-activity-1", "지금 네 기분은 어때?", "아동"],
  ["/child/roleplay/mock-activity-1", "친구가 넘어져서 울고 있어. 몸은 어떤 느낌일까?", "아동"],
  ["/child/_dev/states", "상태 컴포넌트 미리보기 (개발용)", "공개"],
]

function prepare(access: Access) {
  if (access === "강사") signIn()
  if (access === "아동") enterAsChild()
}

describe("접근성", () => {
  it.each(PAGES)("%s 에 WCAG A·AA 위반이 없다", async (path, ready, access) => {
    prepare(access)
    const { container } = renderRoutes(router.routes, path)
    await screen.findAllByText(ready)

    expect(await violationsIn(container)).toEqual([])
  })

  it.each([
    [`/classrooms/${fixtures.classroomA1.classId}?tab=children`, "김하늘", "강사"],
    ["/classrooms/new", "기본 정보", "강사"],
    ["/login", "강사 · 기관 계정으로 로그인하세요", "공개"],
    ["/signup", "기본 정보를 입력해 계정을 만드세요", "공개"],
  ] as const)("%s 의 입력칸은 모두 이름(레이블)으로 찾을 수 있다", async (path, ready, access) => {
    prepare(access)
    renderRoutes(router.routes, path)
    await screen.findAllByText(ready)

    const inputs = [...screen.queryAllByRole("textbox"), ...document.querySelectorAll('input[type="password"]')]

    expect(inputs.length).toBeGreaterThan(0)
    for (const input of inputs) {
      expect(input).toHaveAccessibleName()
    }
  })

  it("키보드만으로 사용 종료할 수 있다", async () => {
    enterAsChild()
    const { user, router: memoryRouter } = renderRoutes(router.routes, "/child/activities")
    const exit = await screen.findByRole("button", { name: "사용 종료" })

    for (let i = 0; i < 10 && document.activeElement !== exit; i++) {
      await user.tab()
    }
    expect(exit).toHaveFocus()
    await user.keyboard("{Enter}")

    expect(await screen.findByText("입장 코드를 입력해줘!")).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe("/child")
  })

  it("아동 로딩 상태는 화면 낭독기에 status 로 알려지고 문구가 있다", async () => {
    renderRoutes(router.routes, "/child/_dev/states")
    await screen.findByText("상태 컴포넌트 미리보기 (개발용)")

    const statuses = screen.getAllByRole("status")

    expect(statuses).toHaveLength(1)
    expect(statuses[0].textContent?.trim()).not.toBe("")
  })

  // 오류·권한·만료·네트워크 상태는 모달(StateDialog)로 뜬다. 화면 낭독기에는 제목·설명이 붙은 대화상자로 알려지고,
  // 포커스가 모달 안으로 옮겨져 다시 시도 버튼을 키보드로 바로 누를 수 있어야 한다.
  it.each([
    ["오류 모달", "다시 하기"],
    ["카메라 권한 모달", "다시 확인하기"],
    ["마이크 권한 모달", "다시 확인하기"],
    ["코드 만료 모달", "다시 입력하기"],
    ["네트워크 모달", "다시 시도하기"],
  ])("%s 은 제목·설명이 있는 대화상자로 알려지고 '%s' 를 키보드로 누를 수 있다", async (opener, retry) => {
    const { user } = renderRoutes(router.routes, "/child/_dev/states")
    await user.click(await screen.findByRole("button", { name: opener }))

    const dialog = await screen.findByRole("dialog")
    expect(dialog).toHaveAccessibleName()
    expect(dialog).toHaveAccessibleDescription()
    expect(await violationsIn(dialog)).toEqual([])

    const button = within(dialog).getByRole("button", { name: retry })
    await waitFor(() => expect(button).toHaveFocus())
    await user.keyboard("{Enter}")

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
  })
})
