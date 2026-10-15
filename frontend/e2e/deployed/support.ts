import type { APIResponse, TestInfo } from "@playwright/test"

// 배포 주소 테스트 공통. 주소는 DEPLOYED_BASE_URL 로만 받는다(playwright.deployed.config.ts).

/** 테스트 주소 원점(https://<주소>). */
export const origin = new URL(process.env.DEPLOYED_BASE_URL ?? "http://localhost").origin

/** 같은 주소의 http:// 판. CloudFront 의 viewer_protocol_policy 를 볼 때 쓴다. */
export const httpOrigin = origin.replace(/^https:/, "http:")

/** 로컬(서버와 같은 구성을 이 PC 에 띄운 것)인가. CloudFront 가 없으니 앞단 검사는 건너뛴다. */
export const isLocal = /^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(origin)

/** CloudFront 가 붙이는 x-cache 값(예: "Hit from cloudfront"). 없으면 "". */
export function xCache(response: APIResponse) {
  return response.headers()["x-cache"] ?? ""
}

/** 응답의 Set-Cookie 줄 전부. 쿠키 값은 테스트 결과에 남기지 않는다. */
export function setCookies(response: APIResponse) {
  return response
    .headersArray()
    .filter((header) => header.name.toLowerCase() === "set-cookie")
    .map((header) => header.value)
}

/** 쿠키의 속성만 남긴다(값은 지운다). 실패 메시지에 토큰이 찍히지 않게 한다. */
export function cookieAttributes(cookie: string) {
  return cookie.replace(/^([^=]+)=[^;]*/, "$1=…")
}

/** 화면 HTML 에서 /assets/ 아래 첫 JS 파일 경로를 찾는다(빌드마다 이름이 바뀐다). */
export function firstScript(html: string) {
  const path = html.match(/\/assets\/[\w.-]+\.js/)?.[0]
  if (!path) throw new Error("index.html 에서 /assets/*.js 를 찾지 못했다")
  return path
}

/** 측정값을 보고서(annotations)와 콘솔에 남긴다. 통과해도 숫자가 보이게 한다. */
export function record(testInfo: TestInfo, name: string, value: string | number) {
  testInfo.annotations.push({ type: name, description: String(value) })
  console.log(`  [${testInfo.project.name}] ${name}: ${value}`)
}
