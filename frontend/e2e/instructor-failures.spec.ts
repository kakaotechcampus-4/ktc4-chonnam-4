import { expect, test, type Page } from "@playwright/test"
import { createClassroom, logOut, marker, signUp } from "./support/instructor.js"

// 강사 흐름의 실패 경로(S1-JEONG-01 완료 기준 "정상·실패 핵심 경로가 자동 검증됨", 06 DoD "장애 상황의 사용자 안내와 복구 경로").
// 실제 백엔드(local)·PostgreSQL·프론트 빌드로 돈다. 서버 오류만 page.route 로 흉내 내고, 나머지는 실제 서버가 답한다.
// 테스트마다 새 강사로 가입한다. 학급 목록이 그 강사의 것만이라 "저장되지 않았다"를 목록으로 바로 확인할 수 있다.
// 이름에는 이번 실행의 표시(marker)를 붙인다. scripts/e2e.sh scan 이 끝난 뒤 백엔드·DB 로그에서 이 문자열을 찾는다.

// 4xx 조회는 다시 시도하지 않고 바로 오류를 보인다(main.tsx, #22 의 6번). 예전처럼 세 번 다시 시도하면 약 7초가 걸려 이 시간을 넘는다.
const WITHOUT_RETRIES = { timeout: 5_000 }

test.beforeEach(async ({ page }) => {
  await signUp(page)
})

async function openCreatePage(page: Page) {
  await page.goto("/classrooms/new")
  await expect(page.getByRole("button", { name: "저장" })).toBeVisible()
}

async function expectNoClassroomNamed(page: Page, prefix: string) {
  await page.goto("/classrooms")
  await expect(page.getByText(/아직 학급이 없습니다/)).toBeVisible()
  await expect(page.getByRole("cell", { name: new RegExp(`^${prefix}`) })).toHaveCount(0)
}

function countClassroomPosts(page: Page) {
  const posts = { count: 0 }
  page.on("request", (request) => {
    if (request.method() === "POST" && new URL(request.url()).pathname === "/api/v1/classrooms") posts.count += 1
  })
  return posts
}

test("너무 긴 학급 이름은 서버가 거절하고, 이유를 보여 주고, 저장하지 않는다", async ({ page }) => {
  const prefix = `긴 이름 ${marker}`
  const tooLong = `${prefix} ${"가".repeat(100)}`
  await openCreatePage(page)

  await page.getByLabel(/학급명/).fill(tooLong)
  await page.getByRole("button", { name: "저장" }).click()

  // 문구는 서버 로캘을 따르는 검증 메시지라 내용 대신 오류가 보이는지만 본다.
  await expect(page.getByRole("alert")).toBeVisible()
  await expect(page.getByLabel(/학급명/)).toHaveValue(tooLong)
  await expect(page).toHaveURL(/\/classrooms\/new$/)
  await expectNoClassroomNamed(page, prefix)
})

test("서버가 500 을 주면 오류 문구를 보여 주고 학급은 생기지 않는다", async ({ page }) => {
  const name = `서버 오류 학급 ${marker}`
  await page.route("**/api/v1/classrooms", async (route) => {
    if (route.request().method() !== "POST") return route.fallback()
    await route.fulfill({
      status: 500,
      contentType: "application/json",
      body: JSON.stringify({
        error: {
          status: 500,
          code: "INTERNAL_ERROR",
          message: "서버 내부 오류가 발생했습니다.",
          path: "/api/v1/classrooms",
          traceId: "e2e-trace",
          fieldErrors: [],
        },
      }),
    })
  })
  await openCreatePage(page)

  await page.getByLabel(/학급명/).fill(name)
  await page.getByRole("button", { name: "저장" }).click()

  await expect(page.getByRole("alert")).toHaveText("서버 내부 오류가 발생했습니다.")
  await page.unroute("**/api/v1/classrooms")
  await expectNoClassroomNamed(page, name)
})

test("연결이 끊긴 동안 누른 생성은 기다렸다가, 다시 연결되면 한 번만 저장된다", async ({ page, context }) => {
  const name = `오프라인 학급 ${marker}`
  const posts = countClassroomPosts(page)
  await openCreatePage(page)
  await page.getByLabel(/학급명/).fill(name)

  // React Query 는 오프라인이면 요청을 보내지 않고 멈춰 둔다(networkMode: online). 버튼은 요청 중 상태로 막힌다.
  await context.setOffline(true)
  await page.getByRole("button", { name: "저장" }).click()
  await expect(page.getByRole("button", { name: "저장 중..." })).toBeDisabled()
  expect(posts.count).toBe(0)

  // 다시 연결되면 멈춰 둔 요청을 한 번 보낸다. 다시 누르지 않아도 된다.
  await context.setOffline(false)
  await expect(page.getByRole("heading", { name: `학급 상세 · ${name}` })).toBeVisible()

  await page.goto("/classrooms")
  await expect(page.getByRole("cell", { name, exact: true })).toHaveCount(1)
  expect(posts.count).toBe(1)
})

test("없는 학급 주소로 들어가면 찾을 수 없다고 한 번만, 다시 시도를 기다리지 않고 알려 준다", async ({ page }) => {
  const unknownClassId = crypto.randomUUID()

  await page.goto(`/classrooms/${unknownClassId}?tab=children`)

  await expect(page.getByText(`학급을 찾을 수 없습니다: ${unknownClassId}`)).toBeVisible(WITHOUT_RETRIES)
  await expect(page.getByText(`학급을 찾을 수 없습니다: ${unknownClassId}`)).toHaveCount(1)
  await expect(page.getByLabel("아동 이름")).toHaveCount(0)
})

test("다른 강사의 학급 주소로 들어가면 없는 학급과 똑같이 찾을 수 없다고 나온다", async ({ page }) => {
  const name = `다른 강사 학급 ${marker}`
  const classId = await createClassroom(page, name)
  await logOut(page)
  await signUp(page)

  await page.goto(`/classrooms/${classId}`)

  await expect(page.getByText(`학급을 찾을 수 없습니다: ${classId}`)).toBeVisible(WITHOUT_RETRIES)
  await expect(page.getByText(name)).toHaveCount(0)
  await page.goto("/classrooms")
  await expect(page.getByText(/아직 학급이 없습니다/)).toBeVisible()
})

test("UUID 가 아닌 학급 주소는 요청 형식 오류로 알려 준다", async ({ page }) => {
  await page.goto("/classrooms/not-a-uuid")

  await expect(page.getByText("요청 형식이 올바르지 않습니다.").first()).toBeVisible(WITHOUT_RETRIES)
})
