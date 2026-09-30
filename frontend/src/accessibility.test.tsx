import axe from "axe-core"
import { screen, within } from "@testing-library/react"
import { describe, expect, it } from "vitest"
import { fixtures } from "@/test/msw/handlers"
import { enterAsChild, renderRoutes, signIn } from "@/test/render"
import { router } from "./router"

// 접근성 검사. 05 추적성 매트릭스 "A11Y → 아동 화면 사용성·상태 문구 검증", 07 "아동 화면의 상태·오류·로딩·접근성"을 자동으로 확인한다.
// - axe-core 로 WCAG 2.x A·AA 규칙을 모든 화면에 돌린다. 색 대비는 실제 CSS 가 있어야 계산되므로 E2E(e2e/accessibility.spec.ts)가 본다.
// - 키보드만으로 사용 종료할 수 있고, 아동 상태 화면은 화면 낭독기에 status 로 알려진다.
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
  ["/child/activities", "활동을 준비하고 있어요", "아동"],
  ["/child/quiz/q-1", "표정 퀴즈", "아동"],
  ["/child/roleplay/r-1", "1턴", "아동"],
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

  it("아동 상태 화면(로딩·오류·권한·만료·네트워크)은 화면 낭독기에 상태로 알려지고 문구가 있다", async () => {
    renderRoutes(router.routes, "/child/_dev/states")
    await screen.findByText("상태 컴포넌트 미리보기 (개발용)")

    const statuses = screen.getAllByRole("status")

    expect(statuses).toHaveLength(6)
    for (const status of statuses) {
      expect(status.textContent?.trim()).not.toBe("")
    }
  })

  it("다시 시도 버튼은 버튼으로 알려지고 키보드로 누를 수 있다", async () => {
    renderRoutes(router.routes, "/child/_dev/states")
    await screen.findByText("상태 컴포넌트 미리보기 (개발용)")

    const retryButtons = screen
      .getAllByRole("status")
      .flatMap((status) => within(status).queryAllByRole("button"))

    expect(retryButtons.map((button) => button.textContent)).toEqual([
      "다시 하기",
      "다시 확인하기",
      "다시 확인하기",
      "다시 시도하기",
    ])
    for (const button of retryButtons) {
      expect(button).not.toHaveAttribute("tabindex", "-1")
      expect(button).toBeEnabled()
    }
  })
})
