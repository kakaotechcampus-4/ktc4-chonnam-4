import fc from "fast-check"
import { http, HttpResponse } from "msw"
import { beforeEach, describe, expect, it } from "vitest"
import { envelope, instructors } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { signIn } from "@/test/render"
import { ApiError, createClassroom, listClassrooms } from "./api"

// 속성 기반 테스트(fast-check). api.ts 의 응답 해석 약속을 무작위 입력 수백 개로 확인한다.
// - 실패 응답은 본문이 무엇이든 ApiError 로 바뀌고 HTTP 상태를 그대로 담는다. 화면이 TypeError·SyntaxError 로 깨지지 않는다.
// - 공통 오류 envelope 이면 서버의 code·message 를 그대로 쓴다.
// - 성공 envelope 은 data 를 그대로 돌려준다.
// - CSRF 는 서버가 준 헤더 이름·토큰을 그대로 싣는다.
// 실패하면 fast-check 가 가장 작은 반례와 seed 를 출력한다. 같은 seed 로 다시 돌리려면 fc.assert 의 옵션에 { seed, path } 를 넣는다.

const failureStatus = fc.integer({ min: 400, max: 599 })
const nonEmptyText = fc.string({ minLength: 1 })

function respondToList(respond: () => Response) {
  server.use(http.get("*/api/v1/classrooms", respond))
}

describe("instructor api 응답 해석 — 속성", () => {
  beforeEach(() => signIn())

  it("실패 응답은 어떤 JSON 본문이 와도 HTTP 상태를 담은 ApiError 가 된다", async () => {
    await fc.assert(
      fc.asyncProperty(failureStatus, fc.jsonValue(), async (status, body) => {
        respondToList(() => HttpResponse.json(body, { status }))

        const error = await listClassrooms().catch((e: unknown) => e)

        expect(error).toBeInstanceOf(ApiError)
        expect((error as ApiError).status).toBe(status)
        expect(typeof (error as ApiError).code).toBe("string")
        expect((error as ApiError).message).not.toBe("")
      }),
      { numRuns: 100 },
    )
  })

  it("공통 오류 envelope 이면 서버의 code·message 를 그대로 쓴다", async () => {
    await fc.assert(
      fc.asyncProperty(failureStatus, nonEmptyText, nonEmptyText, async (status, code, message) => {
        respondToList(() =>
          HttpResponse.json(
            { error: { status, code, message, path: "/api/v1/classrooms", traceId: "t", fieldErrors: [] } },
            { status },
          ),
        )

        await expect(listClassrooms()).rejects.toMatchObject({ status, code, message })
      }),
      { numRuns: 100 },
    )
  })

  it("JSON 이 아닌 실패 본문은 UNEXPECTED_ERROR 로 바꾸고 HTTP 상태를 문구에 남긴다", async () => {
    await fc.assert(
      fc.asyncProperty(failureStatus, fc.string(), async (status, text) => {
        respondToList(() => new HttpResponse(`<html>${text}`, { status, headers: { "Content-Type": "text/html" } }))

        const error = await listClassrooms().catch((e: unknown) => e)

        expect(error).toMatchObject({ status, code: "UNEXPECTED_ERROR" })
        expect((error as ApiError).message).toContain(`HTTP ${status}`)
      }),
      { numRuns: 100 },
    )
  })

  it("성공 envelope 은 data 를 그대로 돌려준다", async () => {
    await fc.assert(
      fc.asyncProperty(fc.jsonValue(), async (data) => {
        respondToList(() => HttpResponse.json(envelope(data)))

        // JSON 을 한 번 오간 값과 비교한다(-0 → 0 처럼 JSON 이 바꾸는 값이 있다).
        await expect(listClassrooms()).resolves.toEqual(JSON.parse(JSON.stringify(data)))
      }),
      { numRuns: 100 },
    )
  })

  it("변경 요청은 서버가 준 CSRF 헤더 이름과 토큰을 그대로 싣는다", async () => {
    const headerName = fc.stringMatching(/^X-[A-Za-z][A-Za-z0-9-]{0,20}$/)
    const token = fc.stringMatching(/^[A-Za-z0-9_-]{1,64}$/)

    await fc.assert(
      fc.asyncProperty(headerName, token, async (name, value) => {
        let seen: string | null = null
        server.use(
          http.get("*/api/v1/csrf", () => HttpResponse.json(envelope({ headerName: name, token: value }))),
          http.post("*/api/v1/classrooms", ({ request }) => {
            seen = request.headers.get(name)
            return HttpResponse.json(
              envelope({ classId: crypto.randomUUID(), instructorId: instructors.a.userId, name: "햇살반", status: "ACTIVE" }),
            )
          }),
        )

        await createClassroom("햇살반")

        expect(seen).toBe(value)
      }),
      { numRuns: 60 },
    )
  })
})
