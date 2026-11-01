import { expect, test, type Page } from "@playwright/test"
import { createClassroom, marker, signUp } from "./support/instructor.js"

// 역할극 리포트·추천·다음목표(VS-014·015·016). 실제 백엔드(local)·PostgreSQL·프론트 빌드로 돈다.
// 역할극 답 기록은 아동 음성 입력과 AI 판단을 거쳐야 생겨서 E2E 의 빈 DB 에는 없다. 그래서 "아직 마친 역할극이 없음"은 실제 서버로,
// 역할극을 마친 뒤의 화면은 그 아동의 다음 난이도·추천 API 만 page.route 로 흉내 내 확인한다(계약 contracts/api/v1/reports-recommendations).
// 실제 답 기록으로 끝까지 도는 확인은 docs/roleplay-report-e2e-*.md 의 로컬 실행 기록에 있다.

test.beforeEach(async ({ page }) => {
  await signUp(page)
})

async function registerChild(page: Page, classId: string, name: string) {
  await page.goto(`/classrooms/${classId}?tab=children`)
  await page.getByLabel("아동 이름").fill(name)
  await page.getByRole("button", { name: "아동 등록" }).click()
  await expect(page.getByRole("cell", { name })).toBeVisible()
  return page.url()
}

test("역할극을 마치지 않은 아동은 추천 화면에 그렇게 보이고 역할극 활동은 닫혀 있다", async ({ page }) => {
  const classId = await createClassroom(page, `리포트 학급 ${marker}`)
  const childName = `리포트 아동 ${marker}`
  await registerChild(page, classId, childName)

  await page.goto(`/recommendations?classId=${classId}`)
  const region = page.getByRole("region", { name: childName })
  await expect(region.getByText("아직 마친 역할극이 없어요.")).toBeVisible()

  await page.goto(`/activities/new?classId=${classId}`)
  await page.getByRole("radio", { name: childName }).click()
  await page.getByRole("button", { name: "다음 · 활동 유형 선택" }).click()
  await expect(page.getByRole("radio", { name: "역할극만" })).toHaveAttribute("aria-disabled", "true")
})

test("다음 난이도와 제안이 있으면 채택해 목표가 채워진 역할극 만들기로 이어진다", async ({ page }) => {
  const classId = await createClassroom(page, `추천 학급 ${marker}`)
  const childName = `추천 아동 ${marker}`
  await registerChild(page, classId, childName)

  const activityId = "40000000-0000-4000-8000-000000000001"
  const recordId = "86000000-0000-4000-8000-000000000001"
  const recommendationId = "88000000-0000-4000-8000-000000000001"
  let decided: unknown = null
  await page.route("**/api/v1/children/*/roleplay-difficulty", (route) =>
    route.fulfill({
      json: {
        data: {
          childId: route.request().url().split("/children/")[1].split("/")[0],
          scenarioLevel: "L2",
          initialSupportLevel: "S1",
          previousLevel: "L2",
          direction: "KEEP",
          reason: "SCORE_IN_KEEP_RANGE",
          scoreNumerator: 9,
          scoreDenominator: 18,
          consecutiveUpgradeCandidates: 0,
          policyVersion: "FOLLOW_UP_ROLEPLAY_V1",
          sourceActivityId: activityId,
          sourceRecordId: recordId,
          decidedAt: "2026-10-31T14:20:00+09:00",
        },
        meta: { traceId: "e2e" },
      },
    }),
  )
  await page.route("**/api/v1/learning-records?*", (route) => route.fulfill({ json: { data: [], meta: { traceId: "e2e" } } }))
  const recommendation = {
    recommendationId,
    recordId,
    activityId,
    childId: "",
    type: "REPEAT",
    displayOrder: 1,
    suggestedGoal: { title: "넘어진 친구의 속상한 마음 알아보기", category: null, parentGoalId: null },
    reason: "'감정의 이유 말하기' 목표는 두 선택지를 받고 도달했어요. 다른 장면으로 한 번 더 해 봐요.",
    reasonCode: "REACHED_WITH_EXTRA_SUPPORT",
    basisGoalTitle: "감정의 이유 말하기",
    evidence: [],
    evidenceSufficiency: "SUFFICIENT",
    decision: null,
    decidedGoal: null,
    rejectionReason: null,
    createdGoalId: null,
    policyVersion: "RECOMMENDATION_V1",
    createdAt: "2026-10-31T14:20:01+09:00",
    decidedAt: null,
  }
  await page.route(`**/api/v1/learning-records/${recordId}/recommendations`, (route) =>
    route.fulfill({ json: { data: [recommendation], meta: { traceId: "e2e" } } }),
  )
  await page.route(`**/api/v1/recommendations/${recommendationId}`, async (route) => {
    decided = route.request().postDataJSON()
    await route.fulfill({
      json: { data: { ...recommendation, decision: "ACCEPT", decidedAt: "2026-10-31T15:00:00+09:00" }, meta: { traceId: "e2e" } },
    })
  })

  await page.goto(`/recommendations?classId=${classId}`)
  const region = page.getByRole("region", { name: childName })
  await expect(region.getByText("다음 역할극")).toBeVisible()
  await region.getByRole("button", { name: "이 제안으로 활동 만들기" }).click()

  await expect(page).toHaveURL(/\/activities\/new\?/)
  expect(decided).toEqual({ decision: "ACCEPT" })
  const url = new URL(page.url())
  expect(url.searchParams.get("type")).toBe("ROLEPLAY")
  expect(url.searchParams.get("goalTitle")).toBe("넘어진 친구의 속상한 마음 알아보기")
})
