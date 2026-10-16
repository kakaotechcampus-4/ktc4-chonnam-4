import { defineConfig, devices } from "@playwright/test";

// 배포된 테스트 주소(AWS: CloudFront → EC2 의 nginx·백엔드)를 밖에서 여는 테스트. 기본 E2E(playwright.config.ts)와 따로 돈다.
//   DEPLOYED_BASE_URL=https://<테스트 주소> npx playwright test -c playwright.deployed.config.ts
//
// - 테스트 주소는 한국에서만 열린다(CloudFront 국가 제한). 한국에 있는 PC 에서 돌린다. GitHub 러너는 403 을 받는다.
// - 서버가 켜져 있어야 한다(Dev server → 켜기). 도는 동안은 요청이 가서 "안 쓰면 끄기"가 끄지 않는다.
// - 주소는 레포에 적지 않는다(공개 레포). 환경변수로만 받는다.
// - 강사 흐름(deployed/instructor-*.spec.ts)은 그 주소에 미리 만든 테스트 강사 계정이 있을 때만 돈다(DEPLOYED_INSTRUCTOR_EMAIL·_PASSWORD).
const baseURL = process.env.DEPLOYED_BASE_URL;
if (!baseURL) throw new Error("DEPLOYED_BASE_URL 이 필요하다. 예: DEPLOYED_BASE_URL=https://<테스트 주소>");

export default defineConfig({
  testDir: "./e2e/deployed",
  outputDir: "test-results/playwright-deployed",
  // 서버 1대·dev DB 하나를 같이 쓴다. 하나씩 돈다.
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  reporter: [["list"], ["html", { open: "never", outputFolder: "playwright-report/deployed" }]],
  use: {
    baseURL,
    locale: "ko-KR",
    timezoneId: "Asia/Seoul",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    // 주소·헤더·캐시처럼 브라우저 종류와 상관없는 검사와 PC 화면.
    { name: "pc", use: { ...devices["Desktop Chrome"] } },
    // 06 DEC-022 "PC·태블릿 최신 Chrome". 화면 검사만 태블릿 세로·가로로 한 번 더 돈다.
    { name: "tablet", use: { ...devices["Galaxy Tab S4"] }, testMatch: /screens\.spec\.ts/ },
    { name: "tablet-landscape", use: { ...devices["Galaxy Tab S4 landscape"] }, testMatch: /screens\.spec\.ts/ },
  ],
});
