import { defineConfig, devices } from "@playwright/test";

// 종단(E2E) 테스트 설정. 백엔드·DB 는 scripts/e2e.sh 가 띄우고, 프론트는 아래 webServer 가 빌드해 띄운다.
//   bash scripts/verify.sh e2e   # 로컬 (Docker·JDK 21·Node 24 필요)
//
// 주소는 고정이다. 프론트가 API 주소를 http://localhost:8080 으로 하드코딩했고(api.ts),
// 백엔드 local 프로필 CORS 가 http://localhost:5173 만 연다. 그래서 미리보기 서버도 5173 에 띄운다.
const isCI = !!process.env.CI;

export default defineConfig({
  testDir: "./e2e",
  // Vitest 의 test-results/vitest-junit.xml 을 지우지 않도록 하위 폴더를 쓴다(Playwright 는 시작할 때 outputDir 을 비운다).
  outputDir: "test-results/playwright",
  // 한 DB 를 같이 쓰는 흐름 테스트라 순서대로 하나씩 돌린다.
  fullyParallel: false,
  workers: 1,
  retries: isCI ? 1 : 0,
  forbidOnly: isCI,
  reporter: isCI
    ? [
        ["github"],
        ["list"],
        ["html", { open: "never" }],
        ["junit", { outputFile: "test-results/playwright-junit.xml" }],
        // 실패·재시도 끝에 통과한 테스트를 Issue 에 기록할 때 읽는다(scripts/e2e-record.sh).
        ["json", { outputFile: "test-results/playwright-results.json" }],
      ]
    : [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: "http://localhost:5173",
    locale: "ko-KR",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"] } },
    // 07 "Chrome PC·태블릿에서 기본 화면이 열림". 아동 흐름만 태블릿(Chromium 기반 기기 설정)으로 한 번 더 돈다.
    { name: "tablet", use: { ...devices["Galaxy Tab S4"] }, testMatch: /child-.*\.spec\.ts/ },
  ],
  webServer: {
    // 개발 서버가 아니라 실제 배포와 같은 빌드 결과를 띄운다.
    command: "npm run build && npm run preview -- --port 5173 --strictPort",
    url: "http://localhost:5173",
    // 로컬에서는 이미 떠 있는 5173 을 그대로 쓴다. CI 는 항상 새로 빌드한다.
    reuseExistingServer: !isCI,
    timeout: 120_000,
  },
});
