import { AxeBuilder } from "@axe-core/playwright"
import { expect, test, type Page } from "@playwright/test"
import { createClassroom, marker, signUp } from "./support/instructor.js"

// 실제 브라우저 접근성 검사(axe-core). jsdom 검사(src/accessibility.test.tsx)가 못 보는 색 대비·실제 배치를 본다.
// 05 추적성 매트릭스 "A11Y → 아동 화면 사용성·상태 문구 검증"의 자동 부분이다. WCAG 2.x A·AA 규칙만 쓴다.
// 강사 화면은 로그인해야, 아동 활동 화면은 입장해야 열린다. 이름에는 이번 실행의 표시(marker)를 붙인다(scripts/e2e.sh scan).
// 새 화면을 라우터에 추가하면 여기에도 넣는다.

const WCAG_A_AA = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"]

// 지금 있는 위반. 규칙을 끄지 않고 화면마다 몇 곳인지 적어 둔다. 여기 없는 규칙이 나오거나 이 수를 넘으면 실패한다.
// 기본 보라색(--child-primary #7b68ee)이 흰 바탕의 "느링고" 글자·흰 글자의 아동 버튼과 만나 대비가 약 4.2:1 로 AA(4.5:1)에 못 미친다.
// 디자인 시스템 팔레트에 맞춰 지금 색을 유지하기로 했다(#22 의 4번, PR #33). 색을 바꾸면 여기 수를 줄이거나 지운다
// (줄일 수 있으면 실행 보고서에 알림이 뜬다).
// 강사 화면(#28)은 보조 글자색 --instructor-text-muted(#7c7870)가 흰 바탕에서 4.40:1, 표 머리·사이드바 바탕(#f5f3ee·#f3f1ec)에서 3.9:1 안팎이고,
// 로그인 화면의 "또는" 구분 글자(#b3afa7)는 2.19:1 이다. 시안 PNG 에서 추정한 색이라 확정되면 고친다.
// 곳 수는 이 테스트가 만드는 데이터(학급 1개·아동 1명) 기준이다.
const KNOWN_VIOLATIONS: Record<string, Record<string, number>> = {
  "/login": { "color-contrast": 3 },
  "/signup": { "color-contrast": 5 },
  "/classrooms": { "color-contrast": 9 },
  "/classrooms/new": { "color-contrast": 2 },
  "/classrooms/:classId": { "color-contrast": 4 },
  "/classrooms/:classId?tab=children": { "color-contrast": 7 },
  "/child": { "color-contrast": 1 },
  "/child/activities": { "color-contrast": 1 },
  "/child/quiz/demo": { "color-contrast": 1 },
  "/child/roleplay/demo": { "color-contrast": 1 },
  "/child/_dev/states": { "color-contrast": 5 },
}

async function checkAccessibility(page: Page, path: string) {
  const results = await new AxeBuilder({ page }).withTags(WCAG_A_AA).analyze()
  const known = KNOWN_VIOLATIONS[path] ?? {}

  const unexpected = results.violations.flatMap((violation) => {
    const allowed = known[violation.id] ?? 0
    if (violation.nodes.length <= allowed) return []
    return [
      `${violation.id} ${violation.nodes.length}곳(알려진 ${allowed}곳): ${violation.help} — ` +
        violation.nodes.map((node) => `${node.target.join(" ")} ${node.failureSummary ?? ""}`.trim()).join(" | "),
    ]
  })
  // 한 테스트에서 여러 화면을 보므로 soft 로 단정한다. 앞 화면이 실패해도 뒤 화면까지 검사해 한 번에 보고한다.
  expect.soft(unexpected, `${path} 의 새 접근성 위반`).toEqual([])

  for (const [rule, allowed] of Object.entries(known)) {
    const now = results.violations.find((violation) => violation.id === rule)?.nodes.length ?? 0
    if (now < allowed) {
      test.info().annotations.push({
        type: "알려진 위반이 줄었다",
        description: `${path} ${rule}: ${allowed}곳 → ${now}곳. KNOWN_VIOLATIONS 의 수를 줄이세요.`,
      })
    }
  }
}

const PUBLIC_PAGES = [
  { path: "/login", ready: "강사 · 기관 계정으로 로그인하세요" },
  { path: "/signup", ready: "기본 정보를 입력해 계정을 만드세요" },
  { path: "/child", ready: "입장 코드를 입력해줘!" },
  { path: "/child/_dev/states", ready: "상태 컴포넌트 미리보기 (개발용)" },
]

for (const { path, ready } of PUBLIC_PAGES) {
  test(`${path} 에 새 WCAG A·AA 위반이 없다`, async ({ page }) => {
    await page.goto(path)
    await expect(page.getByText(ready).first()).toBeVisible()

    await checkAccessibility(page, path)
  })
}

// 아동 상태는 새로고침하면 사라진다(메모리 저장). 입장한 뒤 화면 안의 링크로 옮겨 간다.
const CHILD_ACTIVITY_PAGES = [
  { path: "/child/activities", link: null, ready: "활동을 준비하고 있어요" },
  { path: "/child/quiz/demo", link: "퀴즈 화면 보기", ready: "1/3 문항" },
  { path: "/child/roleplay/demo", link: "역할극 화면 보기", ready: "1턴" },
]

for (const { path, link, ready } of CHILD_ACTIVITY_PAGES) {
  test(`${path} 에 새 WCAG A·AA 위반이 없다`, async ({ page }) => {
    await page.goto("/child")
    await page.getByRole("button", { name: "임시로 들어가보기" }).click()
    if (link) await page.getByRole("link", { name: link }).click()
    await expect(page).toHaveURL(new RegExp(`${path}$`))
    await expect(page.getByText(ready).first()).toBeVisible()

    await checkAccessibility(page, path)
  })
}

test("학급 목록·학급 생성 화면에 새 WCAG A·AA 위반이 없다", async ({ page }) => {
  await signUp(page)
  await createClassroom(page, `접근성 학급 ${marker}`)

  await page.goto("/classrooms")
  await expect(page.getByRole("cell", { name: `접근성 학급 ${marker}`, exact: true })).toBeVisible()
  await checkAccessibility(page, "/classrooms")

  await page.goto("/classrooms/new")
  await expect(page.getByText("기본 정보")).toBeVisible()
  await checkAccessibility(page, "/classrooms/new")
})

test("학급 상세 화면(개요·아동 목록)에 새 WCAG A·AA 위반이 없다", async ({ page }) => {
  const childName = `김하늘 ${marker}`
  await signUp(page)
  await createClassroom(page, `접근성 학급 ${marker}`)
  await expect(page.getByText("바로 하기")).toBeVisible()
  await checkAccessibility(page, "/classrooms/:classId")

  await page.getByRole("tab", { name: /아동 목록/ }).click()
  await page.getByLabel("아동 이름").fill(childName)
  await page.getByRole("button", { name: "아동 등록" }).click()
  await expect(page.getByRole("cell", { name: childName, exact: true })).toBeVisible()
  await checkAccessibility(page, "/classrooms/:classId?tab=children")
})
