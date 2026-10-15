import { expect, test } from "@playwright/test"
import { cookieAttributes, firstScript, httpOrigin, isLocal, origin, record, setCookies, xCache } from "./support.js"

// AWS 앞단(CloudFront → EC2 nginx → 백엔드)이 사용자 요청에 실제로 어떻게 걸리는지 본다. 기대값의 근거는 infra/modules/edge/main.tf:
//   화면 기본 경로 redirect-to-https·GET/HEAD 만·캐시 안 함 / /assets/* 캐시(CachingOptimized) / /api/* https-only·캐시 안 함
//   세 경로 모두 compress·Managed-SecurityHeadersPolicy, 국가 제한 KR
// 브라우저 없이 HTTP 로만 본다(pc 프로젝트만 이 파일을 돈다). 로컬(서버 구성)에는 CloudFront 가 없어 건너뛴다.
test.skip(isLocal, "CloudFront 가 있는 배포 주소에서만 의미가 있다")

test.describe("전송", () => {
  test("화면 주소를 http 로 열면 같은 주소의 https 로 보낸다", async ({ request }) => {
    const response = await request.get(`${httpOrigin}/login`, { maxRedirects: 0 })
    expect(response.status()).toBe(301)
    expect(response.headers()["location"]).toBe(`${origin}/login`)
  })

  test("API 는 http 로 받지 않는다 — 평문으로 로그인·아동 정보가 오가지 않는다", async ({ request }) => {
    const response = await request.get(`${httpOrigin}/api/v1/csrf`, { maxRedirects: 0 })
    expect(response.status()).toBe(403)
    expect(xCache(response)).toMatch(/^Error from cloudfront$/i)
  })

  test("화면 주소에는 쓰기 요청이 들어가지 않는다 — 쓰기는 /api 만 받는다", async ({ request }) => {
    const response = await request.post("/classrooms", { data: {} })
    expect(response.status()).toBe(403)
    expect(xCache(response)).toMatch(/^Error from cloudfront$/i)
  })
})

test.describe("보안 헤더", () => {
  test("화면·API·빌드 파일 응답에 HSTS·nosniff·X-Frame-Options·Referrer-Policy 가 붙는다", async ({ request }, testInfo) => {
    const asset = firstScript(await (await request.get("/")).text())
    for (const path of ["/", "/classrooms", "/api/v1/classrooms", asset]) {
      const headers = (await request.get(path)).headers()
      const hsts = headers["strict-transport-security"] ?? ""
      expect(Number(hsts.match(/max-age=(\d+)/)?.[1] ?? 0), `${path} HSTS`).toBeGreaterThanOrEqual(31_536_000)
      expect(headers["x-content-type-options"], `${path} nosniff`).toBe("nosniff")
      expect(headers["x-frame-options"], `${path} X-Frame-Options`).toMatch(/^(DENY|SAMEORIGIN)$/i)
      expect(headers["referrer-policy"], `${path} Referrer-Policy`).toBeTruthy()
      if (path === "/") record(testInfo, "HSTS", hsts)
    }
  })
})

test.describe("캐시·압축", () => {
  test("화면(index.html·SPA 주소)은 CloudFront 에 캐시되지 않는다 — 배포하면 바로 새 화면이 간다", async ({ request }) => {
    for (const path of ["/", "/classrooms", "/child/activities"]) {
      for (let round = 0; round < 2; round += 1) {
        const response = await request.get(path)
        expect(response.status(), path).toBe(200)
        expect(response.headers()["content-type"], path).toContain("text/html")
        expect(xCache(response), path).not.toMatch(/^(Hit|RefreshHit) from cloudfront$/i)
      }
    }
  })

  test("빌드 파일(/assets/*)은 CloudFront 에 캐시되고 압축되어 간다", async ({ request }, testInfo) => {
    const asset = firstScript(await (await request.get("/")).text())
    const seen: string[] = []
    for (let round = 0; round < 3; round += 1) {
      const response = await request.get(asset, { headers: { "Accept-Encoding": "br, gzip" } })
      expect(response.status()).toBe(200)
      expect(response.headers()["content-encoding"]).toMatch(/^(br|gzip)$/)
      expect(Number(response.headers()["cache-control"]?.match(/max-age=(\d+)/)?.[1] ?? 0)).toBeGreaterThanOrEqual(86_400)
      seen.push(xCache(response))
    }
    record(testInfo, "assets x-cache", seen.join(" → "))
    expect(seen.some((value) => /^(Hit|RefreshHit) from cloudfront$/i.test(value))).toBe(true)
  })

  test("API 응답은 CloudFront 에 캐시되지 않는다", async ({ request }) => {
    for (let round = 0; round < 2; round += 1) {
      const response = await request.get("/api/v1/csrf")
      expect(response.status()).toBe(200)
      expect(xCache(response)).toMatch(/^Miss from cloudfront$/i)
    }
  })

  test("없는 빌드 파일은 404 다 — index.html 을 JS 자리에 주지 않는다", async ({ request }) => {
    const response = await request.get("/assets/no-such-file-for-test.js")
    expect(response.status()).toBe(404)
  })
})

test.describe("원본(EC2)까지 가는 길", () => {
  test("화면 응답의 커밋 표시(X-Neuringo-Release)가 CloudFront 를 지나서도 남는다", async ({ request }, testInfo) => {
    const release = (await request.get("/")).headers()["x-neuringo-release"]
    expect(release).toBeTruthy()
    record(testInfo, "release", release)
    if (process.env.EXPECTED_RELEASE) expect(release).toContain(process.env.EXPECTED_RELEASE)
  })

  test("로그인 없이 보호된 API 를 부르면 CloudFront 오류 화면이 아니라 백엔드의 401 JSON 이 온다", async ({ request }) => {
    const response = await request.get("/api/v1/classrooms")
    expect(response.status()).toBe(401)
    expect(response.headers()["content-type"]).toContain("application/json")
    const body = await response.json()
    expect(body.error.code).toBe("AUTHENTICATION_REQUIRED")
    expect(JSON.stringify(body)).not.toMatch(/Exception|at com\.|SQL/)
  })

  test("다른 사이트에서 API 를 부르려는 preflight 는 허락하지 않는다", async ({ request }) => {
    const response = await request.fetch("/api/v1/classrooms", {
      method: "OPTIONS",
      headers: { Origin: "https://evil.example", "Access-Control-Request-Method": "POST" },
    })
    expect(response.headers()["access-control-allow-origin"]).toBeUndefined()
  })
})

test.describe("쿠키", () => {
  // 사용자는 https 로 쓰지만 CloudFront → 서버 구간은 http 다(origin_protocol_policy http-only).
  // 백엔드가 "사용자는 https" 를 알아야 쿠키에 Secure 를 붙인다(프록시 헤더). 없으면 쿠키가 평문 http 요청에도 실린다.
  test("https 로 쓰면 CSRF 쿠키에 HttpOnly·Secure 가 붙는다", async ({ request }, testInfo) => {
    const cookie = setCookies(await request.get("/api/v1/csrf")).find((value) => value.startsWith("XSRF-TOKEN="))
    expect(cookie, "XSRF-TOKEN 쿠키가 온다").toBeTruthy()
    record(testInfo, "XSRF-TOKEN", cookieAttributes(cookie!))
    expect(cookieAttributes(cookie!)).toMatch(/;\s*HttpOnly/i)
    expect(cookieAttributes(cookie!)).toMatch(/;\s*Secure/i)
  })
})
