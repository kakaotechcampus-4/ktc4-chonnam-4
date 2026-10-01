import { expect, test } from "@playwright/test"
import { logIn, logOut, newInstructor, signUp } from "./support/instructor.js"

// VS-001 종단 검사. 실제 백엔드(local)·PostgreSQL·프론트 빌드로 가입·로그인·로그아웃과 보호 경로를 확인한다.
// - "인증이 없거나 만료되면 보호된 기능을 사용할 수 없다": 토큰이 없으면 로그인 화면으로 가고, 틀린·폐기된 토큰이면 첫 요청의 401 로
//   토큰을 지우고 로그인 화면으로 간다(api.ts). 로그인하면 보려던 화면으로 돌아간다.
// - "이메일은 공백과 대소문자를 정규화해 … 중복 등록되지 않는다": 대소문자만 다른 이메일로 다시 가입하면 막힌다.
// 강사 이메일에는 이번 실행의 표시(marker)가 들어 있다. scripts/e2e.sh scan 이 백엔드·DB 로그에서 이메일이 새는지도 같이 찾는다.

test("로그인하지 않고 강사 화면을 열면 로그인 화면으로 가고, 로그인하면 그 화면으로 돌아간다", async ({ page }) => {
  const account = await signUp(page)
  await logOut(page)

  await page.goto("/classrooms/new")
  await expect(page).toHaveURL(/\/login$/)
  await logIn(page, account)

  await expect(page).toHaveURL(/\/classrooms\/new$/)
  await expect(page.getByText(account.name)).toBeVisible()
})

test("토큰이 틀리거나 폐기됐으면 첫 요청에서 로그인 화면으로 돌아가고 토큰을 지운다", async ({ page }) => {
  await signUp(page)
  await page.evaluate(() => sessionStorage.setItem("neuringo.instructor.accessToken", "revoked-token"))

  await page.goto("/classrooms")

  await expect(page).toHaveURL(/\/login$/)
  expect(await page.evaluate(() => sessionStorage.getItem("neuringo.instructor.accessToken"))).toBeNull()
})

test("로그아웃한 뒤에는 뒤로 가기로도 강사 화면을 볼 수 없다", async ({ page }) => {
  const account = await signUp(page)
  // 기록에 강사 화면(학급 목록)을 하나 남긴다. 로그아웃은 지금 칸만 로그인 화면으로 바꾼다.
  await page.getByRole("link", { name: "학급 생성" }).click()
  await expect(page).toHaveURL(/\/classrooms\/new$/)
  await logOut(page)

  await page.goBack()

  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByText(account.name)).toHaveCount(0)
})

test("대소문자만 다른 같은 이메일로 다시 가입하면 이미 가입된 이메일이라고 알려 준다", async ({ page }) => {
  const account = await signUp(page)
  await logOut(page)

  await page.goto("/signup")
  await page.getByLabel("이름").fill("같은 사람")
  await page.getByLabel("이메일").fill(account.email.toUpperCase())
  await page.getByLabel("비밀번호", { exact: true }).fill(account.password)
  await page.getByLabel("비밀번호 확인").fill(account.password)
  await page.getByLabel("전체 동의").check()
  await page.getByRole("button", { name: "가입하기" }).click()

  await expect(page.getByRole("alert")).toContainText("이미 가입된 이메일입니다.")
  await expect(page).toHaveURL(/\/signup$/)
})

test("틀린 비밀번호로는 로그인되지 않고, 여러 번 틀린 뒤에도 맞는 비밀번호로는 로그인된다", async ({ page }) => {
  const account = await signUp(page)
  await logOut(page)

  for (let i = 0; i < 3; i++) {
    await logIn(page, { ...account, password: `wrong-${i}-${newInstructor().password}` })
    await expect(page.getByRole("alert")).toHaveText("이메일 또는 비밀번호가 올바르지 않습니다.")
  }
  await logIn(page, account)

  await expect(page).toHaveURL(/\/classrooms$/)
})
