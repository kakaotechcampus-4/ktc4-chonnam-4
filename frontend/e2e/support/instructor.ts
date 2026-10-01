import { expect, type Page } from "@playwright/test"

// 이번 실행에만 쓰는 표시. scripts/e2e.sh 가 테스트가 끝난 뒤 백엔드·DB 로그에서 이 문자열을 전부 찾는다.
// 아동 이름과 강사 이메일에 넣어 두므로, 둘 중 하나라도 로그에 한 줄 남으면 개인정보가 새는 것이라 실패한다.
export const marker = process.env.E2E_MARKER ?? `local-${Date.now()}`

export type InstructorAccount = { email: string; password: string; name: string }

/**
 * 테스트 강사 계정을 새로 정한다. 이메일은 예약 도메인(example.com)에 이번 실행의 표시와 무작위 값을 붙여 실행·테스트끼리 겹치지 않는다.
 * 비밀번호도 부를 때마다 새로 만든다. 레포와 CI secret 에 고정 계정·비밀번호가 없다(E2E 는 매번 빈 DB 로 돈다).
 */
export function newInstructor(): InstructorAccount {
  return {
    email: `e2e-${marker}-${crypto.randomUUID().slice(0, 8)}@example.com`,
    password: `pw-${crypto.randomUUID()}`,
    name: "E2E 강사",
  }
}

/** 가입 화면으로 가입한다. 가입하면 바로 로그인되어 학급 목록으로 간다. */
export async function signUp(page: Page, account: InstructorAccount = newInstructor()) {
  await page.goto("/signup")
  await page.getByLabel("이름").fill(account.name)
  await page.getByLabel("이메일").fill(account.email)
  await page.getByLabel("비밀번호", { exact: true }).fill(account.password)
  await page.getByLabel("비밀번호 확인").fill(account.password)
  await page.getByLabel("전체 동의").check()
  await page.getByRole("button", { name: "가입하기" }).click()
  await expect(page).toHaveURL(/\/classrooms$/)
  await expect(page.getByText(account.name)).toBeVisible()
  return account
}

/** 로그인 화면에서 로그인한다. 어느 화면으로 가는지는 부르는 쪽이 확인한다. */
export async function logIn(page: Page, account: InstructorAccount) {
  await page.getByLabel("이메일").fill(account.email)
  await page.getByLabel("비밀번호", { exact: true }).fill(account.password)
  // "기관 SSO 로그인 (향후 제공)" 버튼도 이름에 "로그인" 이 들어 있어 정확히 맞춘다.
  await page.getByRole("button", { name: "로그인", exact: true }).click()
}

export async function logOut(page: Page) {
  await page.getByRole("button", { name: "로그아웃" }).click()
  await expect(page).toHaveURL(/\/login$/)
}

/** 학급 생성 화면에서 학급을 만든다. 만들면 그 학급의 상세 화면으로 간다. 학급 ID 를 돌려준다. */
export async function createClassroom(page: Page, name: string) {
  await page.goto("/classrooms/new")
  await page.getByLabel(/학급명/).fill(name)
  await page.getByRole("button", { name: "저장" }).click()
  await expect(page.getByRole("heading", { name: `학급 상세 · ${name}` })).toBeVisible()
  return new URL(page.url()).pathname.split("/").pop()!
}
