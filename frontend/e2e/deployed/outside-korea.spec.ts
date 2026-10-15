import { expect, test } from "@playwright/test"
import { xCache } from "./support.js"

// 국가 제한(infra/modules/edge allowed_countries = ["KR"])이 실제로 걸려 있는지. 한국 밖에서 돌 때만 의미가 있다.
//   EXPECT_GEO_BLOCK=1 DEPLOYED_BASE_URL=https://<테스트 주소> npx playwright test -c playwright.deployed.config.ts outside-korea
// GitHub 러너(미국)에서 돌리면 된다. 주소는 secret 으로 넘기고 로그에서 가린다.
test.skip(process.env.EXPECT_GEO_BLOCK !== "1", "한국 밖에서 돌 때만(EXPECT_GEO_BLOCK=1) 본다")

test("한국 밖에서 열면 서버까지 가지 않고 CloudFront 가 403 으로 막는다", async ({ request }) => {
  for (const path of ["/", "/api/v1/csrf"]) {
    const response = await request.get(path)
    expect(response.status(), path).toBe(403)
    // 서버(nginx·백엔드)의 403 이 아니라 CloudFront 의 거절인지 본다.
    expect(xCache(response), path).toMatch(/^Error from cloudfront$/i)
    expect(response.headers()["x-neuringo-release"], `${path} 서버 응답이 섞이면 안 된다`).toBeUndefined()
  }
})
