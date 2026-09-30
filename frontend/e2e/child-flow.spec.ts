import { expect, test } from "@playwright/test"

// 아동 흐름 종단 검사(VS-003, 07 "Chrome PC·태블릿에서 기본 화면이 열림"). playwright.config.ts 의 chromium(PC)·tablet
// 두 프로젝트에서 같은 흐름을 돈다. 실제 코드 입력은 S1-JIN-01·S1-YOON-01 몫이라 지금은 임시 입장 버튼을 쓴다.
// - 입장 → 내 활동 → 퀴즈 → 사용 종료 → 코드 입력 화면
// - 입장하지 않고 활동 화면 주소를 열거나, 사용 종료한 뒤 뒤로 가기를 눌러도 코드 입력 화면으로 간다(#22 의 5번).
// - 활동 중 창을 닫으려 하면 확인창이 뜨고(#18), 사용 종료한 뒤에는 뜨지 않는다.
// - 버튼은 손가락으로 누를 수 있는 크기다(WCAG 2.5.8 최소 24px, 아동용 주요 버튼은 44px 이상).

test("아동이 입장해 퀴즈 화면까지 가고, 사용 종료하면 코드 입력 화면으로 돌아간다", async ({ page }) => {
  await page.goto("/")
  await page.getByRole("link", { name: "아동 화면 진입" }).click()
  await expect(page.getByText("입장 코드를 입력해줘!")).toBeVisible()

  await page.getByRole("button", { name: "임시로 들어가보기" }).click()
  await expect(page).toHaveURL(/\/child\/activities$/)
  await expect(page.getByText("내 활동")).toBeVisible()

  await page.getByRole("link", { name: "퀴즈 화면 보기" }).click()
  await expect(page).toHaveURL(/\/child\/quiz\/demo$/)
  await expect(page.getByText("표정 퀴즈")).toBeVisible()
  await expect(page.getByText("1/3 문항")).toBeVisible()

  await page.getByRole("button", { name: "사용 종료" }).click()
  await expect(page).toHaveURL(/\/child$/)
  await expect(page.getByText("입장 코드를 입력해줘!")).toBeVisible()
})

for (const path of ["/child/activities", "/child/quiz/demo", "/child/roleplay/demo"]) {
  test(`입장하지 않고 ${path} 를 열면 코드 입력 화면으로 간다`, async ({ page }) => {
    await page.goto(path)

    await expect(page).toHaveURL(/\/child$/)
    await expect(page.getByText("입장 코드를 입력해줘!")).toBeVisible()
  })
}

test("사용 종료한 뒤 뒤로 가기를 눌러도 활동 화면이 다시 열리지 않는다", async ({ page }) => {
  await page.goto("/child")
  await page.getByRole("button", { name: "임시로 들어가보기" }).click()
  await page.getByRole("link", { name: "퀴즈 화면 보기" }).click()
  await expect(page.getByText("표정 퀴즈")).toBeVisible()
  await page.getByRole("button", { name: "사용 종료" }).click()
  // 사람처럼 코드 입력 화면이 뜬 것을 본 뒤에 뒤로 간다. 사용 종료 직후 몇 ms 안에 누르면 가드의 이동과 겹친다.
  await expect(page).toHaveURL(/\/child$/)
  await expect(page.getByText("입장 코드를 입력해줘!")).toBeVisible()

  await page.goBack()

  await expect(page).toHaveURL(/\/child$/)
  await expect(page.getByText("입장 코드를 입력해줘!")).toBeVisible()
  await expect(page.getByText("내 활동")).toHaveCount(0)
})

test("활동 중 창을 닫으려 하면 확인창이 뜬다", async ({ page }) => {
  await page.goto("/child")
  await page.getByRole("button", { name: "임시로 들어가보기" }).click()
  await expect(page.getByText("내 활동")).toBeVisible()

  const dialog = page.waitForEvent("dialog")
  await page.close({ runBeforeUnload: true })

  const shown = await dialog
  expect(shown.type()).toBe("beforeunload")
  await shown.accept()
})

test("사용 종료한 뒤에는 창을 닫아도 확인창이 뜨지 않는다", async ({ page }) => {
  let dialogs = 0
  page.on("dialog", async (dialog) => {
    dialogs += 1
    await dialog.accept()
  })
  await page.goto("/child")
  await page.getByRole("button", { name: "임시로 들어가보기" }).click()
  await page.getByRole("button", { name: "사용 종료" }).click()
  await expect(page.getByText("입장 코드를 입력해줘!")).toBeVisible()

  const closed = page.waitForEvent("close")
  await page.close({ runBeforeUnload: true })
  await closed

  expect(dialogs).toBe(0)
})

test("아동 화면의 버튼은 손가락으로 누를 수 있는 크기다", async ({ page }) => {
  await page.goto("/child/_dev/states")
  await expect(page.getByText("상태 컴포넌트 미리보기 (개발용)")).toBeVisible()

  for (const button of await page.getByRole("button").all()) {
    const box = await button.boundingBox()
    const name = await button.textContent()
    expect(box, `${name} 버튼이 보여야 한다`).not.toBeNull()
    expect(box!.height, `${name} 높이`).toBeGreaterThanOrEqual(24)
    expect(box!.width, `${name} 너비`).toBeGreaterThanOrEqual(24)
  }
  for (const button of await page.locator('[data-slot="child-button"]').all()) {
    const box = await button.boundingBox()
    expect(box!.height, `${await button.textContent()} 높이`).toBeGreaterThanOrEqual(44)
  }
})
