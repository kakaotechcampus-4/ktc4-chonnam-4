import { expect, type Page } from "@playwright/test"

// 아동 화면 E2E 공통 단계. 코드 검증은 아직 화면 쪽 mock(src/features/child/api.ts)이라 1234 가 입장 코드다.
// 실제 API(S1-JIN-01)가 붙으면 강사 화면에서 코드를 발급받아 넣도록 바꾼다.

export const MOCK_ACCESS_CODE = "1234"
export const MOCK_CHILD_NAME = "서연"
export const MOCK_ACTIVITY = "mock-activity-1"

/** 코드 입력 화면에서 숫자 버튼으로 코드를 넣고 입장한다. 입장하면 인사 화면(/child/hello)으로 간다. */
export async function enterChild(page: Page, code = MOCK_ACCESS_CODE) {
  await page.goto("/child")
  await expect(page.getByText("입장 코드를 입력해줘!")).toBeVisible()
  for (const digit of code) {
    await page.getByRole("button", { name: digit, exact: true }).click()
  }
  await page.getByRole("button", { name: "입장하기" }).click()
  await expect(page).toHaveURL(/\/child\/hello$/)
  await expect(page.getByText(`안녕, ${MOCK_CHILD_NAME}아!`)).toBeVisible()
}

/**
 * 새로고침 없이 앱 안에서 주소를 옮긴다. 아동 세션은 메모리에만 있어서(PRV) page.goto 로 열면 세션이 사라져 코드 입력 화면으로 간다.
 * 라우터(createBrowserRouter)는 popstate 로 주소 변화를 알아챈다.
 */
export async function openInApp(page: Page, path: string) {
  await page.evaluate((target) => {
    window.history.pushState(null, "", target)
    window.dispatchEvent(new PopStateEvent("popstate"))
  }, path)
  await expect(page).toHaveURL(new RegExp(`${path}$`))
}
