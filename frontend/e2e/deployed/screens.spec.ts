import { expect, test, type Page } from "@playwright/test"
import { isLocal, record } from "./support.js"

// 실제 주소를 사용자 기기(PC·태블릿 세로·가로, playwright.deployed.config.ts 의 세 프로젝트)로 연다.
// 로그인 없이 열리는 화면만 본다. 06 DEC-022 "PC·태블릿 최신 Chrome", 03 VS-001·VS-003 의 보호 경로.

const publicScreens = [
  { path: "/login", ready: (page: Page) => page.getByRole("heading", { name: "로그인", exact: true }) },
  { path: "/signup", ready: (page: Page) => page.getByRole("heading", { name: "강사 가입" }) },
  { path: "/child", ready: (page: Page) => page.getByText("입장 코드를 입력해줘!") },
]

for (const screen of publicScreens) {
  test(`${screen.path} 가 https·HTTP/2 이상으로 열리고, 가로로 넘치지 않고, 스크립트 오류가 없다`, async ({ page }, testInfo) => {
    const scriptErrors: string[] = []
    const failedLoads: string[] = []
    const plainHttp: string[] = []
    page.on("pageerror", (error) => scriptErrors.push(error.message))
    page.on("console", (message) => {
      if (message.type() !== "error") return
      // 로그인 전이라 401 이 나는 것은 정상 흐름이다. 따로 적어 두기만 한다.
      if (message.text().startsWith("Failed to load resource")) failedLoads.push(message.text())
      else scriptErrors.push(message.text())
    })
    page.on("request", (request) => {
      if (!isLocal && request.url().startsWith("http://")) plainHttp.push(new URL(request.url()).pathname)
    })

    await page.goto(screen.path)
    await expect(screen.ready(page)).toBeVisible()

    const shape = await page.evaluate(() => ({
      overflow: document.documentElement.scrollWidth - window.innerWidth,
      viewport: `${window.innerWidth}x${window.innerHeight}`,
      protocol: (performance.getEntriesByType("navigation")[0] as PerformanceNavigationTiming).nextHopProtocol,
    }))
    record(testInfo, `${screen.path} 화면`, `${shape.viewport} · ${shape.protocol}`)
    if (failedLoads.length) record(testInfo, `${screen.path} 실패한 요청`, failedLoads.length)

    expect(shape.overflow, "가로 스크롤이 생긴다").toBeLessThanOrEqual(0)
    expect(scriptErrors, "콘솔·스크립트 오류").toEqual([])
    expect(plainHttp, "https 화면에서 http 로 부르는 것(섞인 내용)").toEqual([])
    if (!isLocal) expect(shape.protocol).toMatch(/^h[23]$/)
  })
}

test("로그인 없이 보호된 주소를 바로 열면 강사는 로그인, 아동은 코드 입력 화면으로 간다", async ({ page }) => {
  await page.goto("/classrooms")
  await expect(page).toHaveURL(/\/login$/)
  await page.goto("/child/activities")
  await expect(page).toHaveURL(/\/child$/)
})

test("아동 코드 키패드는 손가락으로 누를 수 있는 크기다(44px 이상)", async ({ page }) => {
  await page.goto("/child")
  for (const name of ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "지우기", "입장하기"]) {
    const box = await page.getByRole("button", { name, exact: true }).boundingBox()
    expect(box, name).not.toBeNull()
    expect(Math.min(box!.width, box!.height), `${name} 버튼 크기`).toBeGreaterThanOrEqual(44)
  }
})

test.describe("기기를 다크 모드로 쓸 때", () => {
  test.use({ colorScheme: "dark" })

  // 화면은 밝은 디자인인데 페이지가 color-scheme 에 dark 를 열어 두면(frontend/src/index.css) 브라우저가 체크박스·라디오를
  // 어두운 모양으로 그린다. 체크 전 상자가 까맣게 채워져 "이미 동의한 것"처럼 보인다(2026-10-11 실제 Chrome 에서 확인).
  test("가입 화면의 동의 체크박스가 체크 전에는 비어 보인다", async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== "pc", "브라우저 설정 문제라 pc 에서 한 번만 본다")
    await page.goto("/signup")
    const box = page.getByLabel("전체 동의")
    await expect(box).not.toBeChecked()
    const scheme = await box.evaluate((element) => getComputedStyle(element).colorScheme)
    record(testInfo, "체크박스 color-scheme", scheme)
    expect(scheme, "밝은 화면의 입력 요소가 어두운 모양으로 그려진다").not.toContain("dark")
  })
})
