import { expect, request, type APIRequestContext, type Page } from "@playwright/test"
import { marker, newInstructor } from "./instructor.js"

// 아동 화면 E2E 공통 단계. 입장·사용 종료는 실제 API(API-IAM-08·09)다. 아동 화면만 보려는 단계라 강사 화면의
// 코드 받기(아동 상세 "접근 코드" 탭)를 거치지 않고 강사 API 로 가입 → 학급 → 아동 → 코드 발급을 한 뒤 그 코드로 입장한다.
// 활동·퀴즈·역할극은 아직 화면 쪽 mock(src/features/child/api.ts)이라 아래 MOCK_ACTIVITY 로 연다.

export const MOCK_ACTIVITY = "mock-activity-1"

// playwright.config.ts 와 같은 고정 주소. 프론트는 VITE_API_BASE_URL 없이 빌드되어 이 주소를 부른다.
const API_ORIGIN = "http://localhost:8080"

export type E2EChild = { name: string; accessCode: string }

async function csrfHeaders(api: APIRequestContext): Promise<Record<string, string>> {
  const res = await api.get("/api/v1/csrf")
  expect(res.status(), "CSRF 토큰 조회").toBe(200)
  const { data } = (await res.json()) as { data: { headerName: string; token: string } }
  return { [data.headerName]: data.token }
}

async function post<T>(api: APIRequestContext, path: string, body: unknown, headers: Record<string, string> = {}) {
  const res = await api.post(path, { data: body, headers: { ...(await csrfHeaders(api)), ...headers } })
  expect(res.ok(), `POST ${path} → ${res.status()}`).toBe(true)
  return ((await res.json()) as { data: T }).data
}

/**
 * 강사 API 로 아동을 등록하고 입장 코드를 발급한다. 브라우저와 쿠키가 섞이지 않게 따로 만든 요청 컨텍스트를 쓴다.
 * 아동 이름에는 이번 실행의 표시(marker)를 넣는다. scripts/e2e.sh 가 백엔드·DB 로그에서 찾아 개인정보가 새는지 본다.
 * 화면은 이 이름(실명)을 그대로 불러서 "안녕, {이름}!", "{이름}의 활동"으로 보인다.
 */
export async function issueChildCode(): Promise<E2EChild> {
  const api = await request.newContext({ baseURL: API_ORIGIN })
  try {
    const instructor = newInstructor()
    await post(api, "/api/v1/users", { ...instructor, termsAgreed: true, privacyAgreed: true })
    const { accessToken } = await post<{ accessToken: string }>(api, "/api/v1/auth/sessions", {
      email: instructor.email,
      password: instructor.password,
    })
    const auth = { Authorization: `Bearer ${accessToken}` }

    const { classId } = await post<{ classId: string }>(api, "/api/v1/classrooms", { name: `아동 E2E 학급 ${marker}` }, auth)
    const name = `하늘 ${marker} 서연`
    const { childId } = await post<{ childId: string }>(api, `/api/v1/classrooms/${classId}/children`, { displayName: name }, auth)
    const { accessCode } = await post<{ accessCode: string }>(api, `/api/v1/children/${childId}/access-codes`, undefined, {
      ...auth,
      "Idempotency-Key": crypto.randomUUID(),
    })
    return { name, accessCode }
  } finally {
    await api.dispose()
  }
}

/** 새 아동의 코드를 발급받아 코드 입력 화면에서 숫자 버튼으로 입장한다. 입장하면 인사 화면(/child/hello)으로 간다. */
export async function enterChild(page: Page): Promise<E2EChild> {
  const child = await issueChildCode()
  await page.goto("/child")
  await expect(page.getByText("입장 코드를 입력해줘!")).toBeVisible()
  for (const digit of child.accessCode) {
    await page.getByRole("button", { name: digit, exact: true }).click()
  }
  await page.getByRole("button", { name: "입장하기" }).click()
  await expect(page).toHaveURL(/\/child\/hello$/)
  await expect(page.getByText(`안녕, ${child.name}!`)).toBeVisible()
  return child
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
