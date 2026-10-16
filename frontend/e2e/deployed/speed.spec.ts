import { devices, expect, test, type Page } from "@playwright/test"
import { isLocal, record } from "./support.js"

// 한국에서 테스트 주소를 처음 열 때의 체감 속도(CloudFront 서울 근처 엣지 → EC2 t3.medium).
// 기획안에는 웹 화면 속도 숫자가 없다(VS-011 의 "표시 목표 10초"는 AI 응답). 그래서 Core Web Vitals 의 경계를 기준으로 둔다:
//   PC·실제 망 LCP 2.5초("좋음"), 태블릿·교실 와이파이 정도의 망 LCP 4초("개선 필요" 경계). 숫자는 통과해도 보고서에 남긴다.

async function measure(page: Page) {
  return page.evaluate(async () => {
    const lcp = await new Promise<number>((resolve) => {
      let last = 0
      new PerformanceObserver((list) => {
        const entries = list.getEntries()
        last = entries[entries.length - 1].startTime
      }).observe({ type: "largest-contentful-paint", buffered: true })
      setTimeout(() => resolve(last), 1500)
    })
    const navigation = performance.getEntriesByType("navigation")[0] as PerformanceNavigationTiming
    const resources = performance.getEntriesByType("resource") as PerformanceResourceTiming[]
    const scripts = resources.filter((entry) => new URL(entry.name).pathname.endsWith(".js"))
    const kb = (bytes: number) => Math.round(bytes / 1024)
    return {
      ttfb: Math.round(navigation.responseStart),
      fcp: Math.round(performance.getEntriesByName("first-contentful-paint")[0]?.startTime ?? 0),
      lcp: Math.round(lcp),
      scriptTransferKb: kb(scripts.reduce((sum, entry) => sum + entry.transferSize, 0)),
      scriptSizeKb: kb(scripts.reduce((sum, entry) => sum + entry.decodedBodySize, 0)),
      totalTransferKb: kb(navigation.transferSize + resources.reduce((sum, entry) => sum + entry.transferSize, 0)),
    }
  })
}

const summary = (value: Awaited<ReturnType<typeof measure>>) =>
  `TTFB ${value.ttfb}ms · FCP ${value.fcp}ms · LCP ${value.lcp}ms · JS ${value.scriptTransferKb}KB(풀면 ${value.scriptSizeKb}KB) · 전체 ${value.totalTransferKb}KB`

test("PC 로 처음 열 때(빈 캐시) 로그인·아동 코드 화면의 LCP 가 2.5초 안이다", async ({ browser }, testInfo) => {
  for (const path of ["/login", "/child"]) {
    const context = await browser.newContext({ ...devices["Desktop Chrome"], locale: "ko-KR" })
    const page = await context.newPage()
    await page.goto(path)
    const value = await measure(page)
    record(testInfo, `PC ${path}`, summary(value))
    expect(value.ttfb, `${path} 첫 바이트`).toBeLessThanOrEqual(800)
    expect(value.lcp, `${path} LCP`).toBeLessThanOrEqual(2500)
    await context.close()
  }
})

test("태블릿·교실 와이파이 정도의 망(10Mbps·지연 60ms·CPU 4배 느림)에서 아동 코드 화면의 LCP 가 4초 안이다", async ({ browser }, testInfo) => {
  const context = await browser.newContext({ ...devices["Galaxy Tab S4 landscape"], locale: "ko-KR" })
  const page = await context.newPage()
  const cdp = await context.newCDPSession(page)
  await cdp.send("Network.enable")
  await cdp.send("Network.emulateNetworkConditions", {
    offline: false,
    latency: 60,
    downloadThroughput: (10 * 1024 * 1024) / 8,
    uploadThroughput: (3 * 1024 * 1024) / 8,
  })
  await cdp.send("Emulation.setCPUThrottlingRate", { rate: 4 })
  await page.goto("/child")
  await expect(page.getByText("입장 코드를 입력해줘!")).toBeVisible()
  const value = await measure(page)
  record(testInfo, "태블릿·느린 망 /child", summary(value))
  expect(value.lcp).toBeLessThanOrEqual(4000)
  await context.close()
})

test("API 한 번 왕복(CloudFront → EC2 → 백엔드)이 20번 중 95% 가 300ms 안이다", async ({ request }, testInfo) => {
  // 로그인 없이 부를 수 있는 가벼운 API 로 앞단·서버 구간 지연만 본다(DB·AI 는 들어가지 않는다).
  const times: number[] = []
  for (let round = 0; round < 20; round += 1) {
    const started = performance.now()
    const response = await request.get("/api/v1/csrf")
    times.push(performance.now() - started)
    expect(response.status()).toBe(200)
  }
  const sorted = [...times].sort((a, b) => a - b)
  const p50 = Math.round(sorted[9])
  const p95 = Math.round(sorted[18])
  record(testInfo, "API 왕복", `p50 ${p50}ms · p95 ${p95}ms · 최대 ${Math.round(sorted[19])}ms`)
  expect(p95).toBeLessThanOrEqual(300)
})

test("화면 JS 는 압축되어 300KB 안으로 오고, 두 번째 화면부터는 다시 받지 않는다", async ({ page }, testInfo) => {
  await page.goto("/login")
  const first = await measure(page)
  record(testInfo, "첫 화면 JS", `${first.scriptTransferKb}KB 받음 · 풀면 ${first.scriptSizeKb}KB`)
  if (!isLocal) {
    expect(first.scriptTransferKb, "압축해서 받은 JS").toBeLessThanOrEqual(300)
    expect(first.scriptTransferKb, "압축이 됐다(받은 크기가 푼 크기의 60% 이하)").toBeLessThanOrEqual(first.scriptSizeKb * 0.6)
  }

  await page.goto("/child")
  const second = await measure(page)
  record(testInfo, "다음 화면 JS", `${second.scriptTransferKb}KB 받음`)
  expect(second.scriptTransferKb, "브라우저 캐시에서 읽는다").toBeLessThanOrEqual(1)
})
