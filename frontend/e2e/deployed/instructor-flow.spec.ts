import { expect, test, type Page } from "@playwright/test"
import { createClassroom, logIn, logOut, marker, signUp } from "../support/instructor.js"
import { isLocal } from "./support.js"

// 테스트 주소에서 강사 흐름(03 VS-001·002·004·009·014)을 끝까지 돌고, 만든 학급을 지워 dev DB 에 남기지 않는다.
// 계정은 둘 중 하나다.
//   - 미리 만든 테스트 강사 계정(DEPLOYED_INSTRUCTOR_EMAIL·_PASSWORD — 레포·로그에 적지 않는다)
//   - 실행마다 새로 가입(임의 이메일 e2e-<표시>-…@example.com·임의 비밀번호, e2e/support/instructor.ts). 로컬은 늘, 배포 주소는
//     DEPLOYED_ALLOW_SIGNUP=1 일 때만. 학급·아동·활동은 테스트가 지우지만 강사 계정은 지우는 API 가 없어 dev DB 에 남는다(테스트 1개당 1개).
const email = process.env.DEPLOYED_INSTRUCTOR_EMAIL
const password = process.env.DEPLOYED_INSTRUCTOR_PASSWORD
const allowSignup = isLocal || process.env.DEPLOYED_ALLOW_SIGNUP === "1"
test.skip(!allowSignup && !(email && password), "배포 주소에서는 테스트 강사 계정(DEPLOYED_INSTRUCTOR_EMAIL·_PASSWORD)이나 DEPLOYED_ALLOW_SIGNUP=1 이 있어야 돈다")

async function enter(page: Page) {
  if (email && password) {
    await page.goto("/login")
    await logIn(page, { email, password, name: "" })
    await expect(page).toHaveURL(/\/classrooms$/)
    return
  }
  await signUp(page)
}

test("학급·아동·활동을 만들고 리포트까지 본 뒤 학급을 지우면 목록과 화면에 남지 않는다", async ({ page }) => {
  const classroomName = `UX점검반 ${marker}`
  const childName = `김하늘 ${marker}`
  await enter(page)
  const classId = await createClassroom(page, classroomName)

  try {
    // VS-002: 아동 등록
    await page.getByRole("tab", { name: /아동 목록/ }).click()
    await page.getByLabel("아동 이름").fill(childName)
    await page.getByRole("button", { name: "아동 등록" }).click()
    const childLink = page.getByRole("link", { name: childName, exact: true })
    await expect(childLink).toHaveCount(1)
    const childId = (await childLink.getAttribute("href"))!.split("/").pop()!

    // VS-004·005·009: 활동 만들기 3단계(대상 → 목표 → 확인·배정)
    await page.goto(`/activities/new?classId=${classId}&childId=${childId}`)
    await page.getByRole("button", { name: "다음 · 학습 목표 설정" }).click()
    // 1단계(대상 아동)에도 라디오가 있다. 2단계 화면으로 바뀐 것을 본 뒤에 목표를 고른다.
    await expect(page.getByText("목표는 활동당 1개만 선택합니다.")).toBeVisible()
    const goal = page.getByRole("radio").first()
    const goalTitle = (await goal.evaluate((element) => element.closest("label")?.innerText ?? "")).split("\n")[0].trim()
    await goal.check()
    await page.getByRole("button", { name: "다음 · 확인" }).click()
    await expect(page.getByText("유형별로 1개씩, 최대 3문항")).toBeVisible()
    await page.getByRole("button", { name: "배정하기" }).click()

    // 배정 뒤 아동 활동 이력 → VS-014 리포트(아동이 아직 안 했으므로 빈 상태)
    await expect(page).toHaveURL(new RegExp(`/children/${childId}$`))
    const activity = page.getByRole("link", { name: goalTitle })
    await expect(activity).toBeVisible()
    await expect(page.getByText("시작 전")).toBeVisible()
    await activity.click()
    await expect(page).toHaveURL(/\/activities\/[0-9a-f-]+\/report$/)
    await expect(page.getByText("아직 시작 전이에요")).toBeVisible()
  } finally {
    // 정리: 학급을 지우면 아동·목표·활동·입장 코드가 함께 지워진다(VS-002). 이름을 입력해야 지워진다.
    await page.goto(`/classrooms/${classId}`)
    await page.getByRole("button", { name: "학급 삭제" }).click()
    const dialog = page.getByRole("alertdialog")
    await dialog.getByRole("textbox").fill(classroomName)
    await dialog.getByRole("button", { name: "영구 삭제" }).click()
    await expect(page).toHaveURL(/\/classrooms$/)
    await expect(page.getByRole("cell", { name: classroomName, exact: true })).toHaveCount(0)
  }
})

test("새 탭은 로그인을 이어받지 않고(06 DEC-002), 로그아웃하면 보호 화면이 다시 닫힌다", async ({ page, context }) => {
  await enter(page)
  const other = await context.newPage()
  await other.goto("/classrooms")
  await expect(other).toHaveURL(/\/login$/)
  await other.close()

  await logOut(page)
  await page.goto("/classrooms")
  await expect(page).toHaveURL(/\/login$/)
})
