import { http, HttpResponse } from "msw"
import { beforeEach, describe, expect, it } from "vitest"
import { CSRF, envelope, fixtures, instructors } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { signIn } from "@/test/render"
import {
  ApiError,
  createChild,
  createClassroom,
  getClassroom,
  getCurrentUser,
  hasAccessToken,
  listChildren,
  listClassrooms,
  login,
  logout,
  signup,
} from "./api"
import { getAccessToken } from "./auth/tokenStorage"

// FE ↔ BE API 명세 검사. api.ts 주석의 약속(CSRF 는 변경 직전에 받고 서버가 준 헤더 이름을 쓴다,
// 토큰 조회가 실패하면 생성이 성공한 것처럼 처리되지 않는다, 공통 오류만 공통 오류로 읽는다,
// 강사 요청에는 로그인 토큰을 싣고 가입·로그인에는 싣지 않는다)을 고정한다.
// 의도적으로 API 명세를 바꾸면 이 테스트도 같이 고치세요.

const UNKNOWN_CLASS_ID = "00000000-0000-4000-8000-00000000ffff"

describe("instructor api 명세", () => {
  beforeEach(() => signIn())


  it("성공 응답의 envelope 에서 data 만 꺼낸다(로그인한 강사의 학급만 온다)", async () => {
    await expect(listClassrooms()).resolves.toEqual([fixtures.classroomA1, fixtures.classroomB1])
  })

  it("조회·변경 요청에 로그인 토큰을 Authorization: Bearer 로 싣는다", async () => {
    const seen: string[] = []
    server.events.on("request:start", ({ request }) => {
      const { pathname } = new URL(request.url)
      if (pathname !== "/api/v1/csrf") seen.push(`${request.method} ${request.headers.get("Authorization")}`)
    })

    await listClassrooms()
    await createClassroom("햇살반")
    server.events.removeAllListeners()

    const bearer = `Bearer ${instructors.a.accessToken}`
    expect(seen).toEqual([`GET ${bearer}`, `POST ${bearer}`])
  })

  it("가입·로그인 요청에는 이전 토큰이 남아 있어도 토큰을 싣지 않는다", async () => {
    const seen: (string | null)[] = []
    server.events.on("request:start", ({ request }) => {
      if (request.method === "POST") seen.push(request.headers.get("Authorization"))
    })

    await signup({ email: "new-instructor@example.com", password: "password-1", name: "새 강사" })
    await login("new-instructor@example.com", "password-1")
    server.events.removeAllListeners()

    expect(seen).toEqual([null, null])
  })

  it("로그인하면 새 토큰을 보관하고, 그 토큰으로 본인 정보를 읽는다", async () => {
    const user = await login(instructors.b.email, instructors.b.password)

    expect(user).toMatchObject({ userId: instructors.b.userId, email: instructors.b.email })
    expect(getAccessToken()).not.toBe(instructors.a.accessToken)
    await expect(getCurrentUser()).resolves.toMatchObject({ userId: instructors.b.userId })
  })

  it("로그인에 실패하면 INVALID_CREDENTIALS 를 던지고 토큰을 바꾸지 않는다", async () => {
    await expect(login(instructors.a.email, "wrong-password")).rejects.toMatchObject({
      status: 401,
      code: "INVALID_CREDENTIALS",
    })
    expect(getAccessToken()).toBe(instructors.a.accessToken)
  })

  it("로그아웃하면 CSRF 를 실어 서버 세션을 폐기하고 토큰을 지운다", async () => {
    const paths: string[] = []
    server.events.on("request:start", ({ request }) => paths.push(`${request.method} ${new URL(request.url).pathname}`))

    await logout()
    server.events.removeAllListeners()

    expect(paths).toEqual(["GET /api/v1/csrf", "DELETE /api/v1/auth/session"])
    expect(hasAccessToken()).toBe(false)
  })

  it("로그아웃 요청이 실패해도 이 브라우저의 토큰은 지운다", async () => {
    server.use(http.delete("*/api/v1/auth/session", () => HttpResponse.error()))

    await logout().catch(() => undefined)

    expect(hasAccessToken()).toBe(false)
  })

  it("이미 가입된 이메일은 409 DUPLICATE_RESOURCE 로 읽는다", async () => {
    await expect(
      signup({ email: `  ${instructors.a.email.toUpperCase()} `, password: "password-1", name: "같은 사람" }),
    ).rejects.toMatchObject({ status: 409, code: "DUPLICATE_RESOURCE" })
  })

  it("다른 강사의 학급은 없는 학급과 같은 404 로 읽는다", async () => {
    await expect(getClassroom(fixtures.classroomC1.classId)).rejects.toMatchObject({
      status: 404,
      code: "CLASSROOM_NOT_FOUND",
    })
    await expect(listChildren(fixtures.classroomC1.classId)).rejects.toMatchObject({ status: 404 })
  })

  it("동명이인 아동도 childId 로 구분해 모두 돌려준다", async () => {
    const children = await listChildren(fixtures.classroomA1.classId)

    expect(children.map((c) => c.displayName)).toEqual(["김하늘", "김하늘"])
    expect(new Set(children.map((c) => c.childId)).size).toBe(2)
  })

  it("조회와 변경 요청 모두 쿠키를 싣도록 credentials: include 로 보낸다", async () => {
    const seen: RequestCredentials[] = []
    server.events.on("request:start", ({ request }) => seen.push(request.credentials))

    await createClassroom("햇살반")
    server.events.removeAllListeners()

    expect(seen.length).toBeGreaterThanOrEqual(2)
    expect(seen.every((credentials) => credentials === "include")).toBe(true)
  })

  it("변경 요청은 서버가 내려준 헤더 이름으로 CSRF 토큰을 싣는다", async () => {
    let seenToken: string | null = null
    server.use(
      http.get("*/api/v1/csrf", () =>
        HttpResponse.json(envelope({ headerName: "X-ROTATED-CSRF", token: "rotated-token" })),
      ),
      http.post("*/api/v1/classrooms", ({ request }) => {
        seenToken = request.headers.get("X-ROTATED-CSRF")
        return HttpResponse.json(envelope(fixtures.classroomA1))
      }),
    )

    await createClassroom("햇살반")

    expect(seenToken).toBe("rotated-token")
  })

  it("CSRF 토큰을 받지 못하면 변경 요청을 보내지 않고 오류를 던진다", async () => {
    let posted = false
    server.use(
      http.get("*/api/v1/csrf", () => new HttpResponse(null, { status: 503 })),
      http.post("*/api/v1/classrooms", () => {
        posted = true
        return HttpResponse.json(envelope(fixtures.classroomA1))
      }),
    )

    await expect(createClassroom("햇살반")).rejects.toBeInstanceOf(ApiError)
    expect(posted).toBe(false)
  })

  it("기본 핸들러는 토큰 없는 변경 요청을 403 으로 거부한다", async () => {
    server.use(
      http.get("*/api/v1/csrf", () =>
        HttpResponse.json(envelope({ headerName: CSRF.headerName, token: "wrong-token" })),
      ),
    )

    await expect(createClassroom("햇살반")).rejects.toMatchObject({
      status: 403,
      code: "UNEXPECTED_ERROR",
    })
  })

  it("공통 오류 envelope 을 ApiError 의 status·code·message 로 옮긴다", async () => {
    await expect(getClassroom(UNKNOWN_CLASS_ID)).rejects.toMatchObject({
      status: 404,
      code: "CLASSROOM_NOT_FOUND",
      message: `학급을 찾을 수 없습니다: ${UNKNOWN_CLASS_ID}`,
    })
  })

  it("검증 오류(422)는 fieldErrors 가 들어 있어도 공통 오류로 읽는다", async () => {
    await expect(createClassroom("가".repeat(101))).rejects.toMatchObject({
      status: 422,
      code: "VALIDATION_FAILED",
      message: "요청 값이 올바르지 않습니다.",
    })
  })

  it.each([
    [
      "HTML 오류 페이지",
      () =>
        new HttpResponse("<html>502 Bad Gateway</html>", {
          status: 502,
          headers: { "Content-Type": "text/html" },
        }),
      502,
    ],
    [
      "Spring 기본 오류 본문",
      () =>
        HttpResponse.json(
          { timestamp: "2026-09-25T00:00:00Z", status: 400, error: "Bad Request", path: "/api/v1/classrooms" },
          { status: 400 },
        ),
      400,
    ],
    [
      "message 가 빈 오류 envelope",
      () =>
        HttpResponse.json(
          { error: { status: 500, code: "INTERNAL", message: "", path: "/", traceId: "t", fieldErrors: [] } },
          { status: 500 },
        ),
      500,
    ],
  ])("%s 는 UNEXPECTED_ERROR 로 바꾸고 HTTP 상태를 문구에 남긴다", async (_label, respond, status) => {
    server.use(http.get("*/api/v1/classrooms", respond))

    const error = await listClassrooms().catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status, code: "UNEXPECTED_ERROR" })
    expect((error as ApiError).message).toContain(`HTTP ${status}`)
  })

  it.each([
    ["error 가 null", { error: null }],
    ["error 가 문자열", { error: "문제가 생겼습니다" }],
    ["code 가 숫자", { error: { code: 500, message: "서버 오류" } }],
    ["message 가 없음", { error: { code: "INTERNAL_ERROR" } }],
  ])("공통 오류 모양이 아닌 본문(%s)은 UNEXPECTED_ERROR 로 읽는다", async (_label, body) => {
    server.use(http.get("*/api/v1/classrooms", () => HttpResponse.json(body, { status: 500 })))

    await expect(listClassrooms()).rejects.toMatchObject({ status: 500, code: "UNEXPECTED_ERROR" })
  })

  it("상태 코드는 본문의 error.status 가 아니라 HTTP 응답에서 읽는다", async () => {
    server.use(
      http.get("*/api/v1/classrooms", () =>
        HttpResponse.json(
          { error: { status: 400, code: "CONFLICT", message: "충돌", path: "/", traceId: "t", fieldErrors: [] } },
          { status: 409 },
        ),
      ),
    )

    await expect(listClassrooms()).rejects.toMatchObject({ status: 409, code: "CONFLICT" })
  })

  it("학급 조회는 envelope 의 학급 하나를 돌려준다", async () => {
    await expect(getClassroom(fixtures.classroomA1.classId)).resolves.toEqual(fixtures.classroomA1)
  })

  it("아동 등록은 그 학급 경로로 displayName 만 JSON 으로 보낸다", async () => {
    let seen: { path: string; contentType: string | null; body: unknown } | null = null
    server.use(
      http.post("*/api/v1/classrooms/:classId/children", async ({ request }) => {
        seen = {
          path: new URL(request.url).pathname,
          contentType: request.headers.get("Content-Type"),
          body: await request.json(),
        }
        return HttpResponse.json(envelope(fixtures.childA1_1))
      }),
    )

    await createChild(fixtures.classroomA1.classId, "김하늘")

    expect(seen).toEqual({
      path: `/api/v1/classrooms/${fixtures.classroomA1.classId}/children`,
      contentType: "application/json",
      body: { displayName: "김하늘" },
    })
  })

  it("학급 생성은 name 만 JSON 으로 보낸다", async () => {
    let body: unknown = null
    server.use(
      http.post("*/api/v1/classrooms", async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(envelope(fixtures.classroomA1))
      }),
    )

    await createClassroom("햇살반")

    expect(body).toEqual({ name: "햇살반" })
  })

  it("변경 요청마다 CSRF 토큰을 새로 받고, 조회 요청은 토큰을 받지 않는다", async () => {
    const paths: string[] = []
    server.events.on("request:start", ({ request }) => paths.push(`${request.method} ${new URL(request.url).pathname}`))

    await listClassrooms()
    await createClassroom("햇살반")
    await createChild(fixtures.classroomA1.classId, "김하늘")
    server.events.removeAllListeners()

    expect(paths).toEqual([
      "GET /api/v1/classrooms",
      "GET /api/v1/csrf",
      "POST /api/v1/classrooms",
      "GET /api/v1/csrf",
      `POST /api/v1/classrooms/${fixtures.classroomA1.classId}/children`,
    ])
  })

  it("네트워크가 끊기면 성공한 것처럼 처리하지 않고 실패한다", async () => {
    server.use(http.post("*/api/v1/classrooms", () => HttpResponse.error()))

    await expect(createClassroom("햇살반")).rejects.toThrow()
  })

  it("없는 학급의 아동 목록은 404 공통 오류로 읽는다", async () => {
    await expect(listChildren(UNKNOWN_CLASS_ID)).rejects.toMatchObject({
      status: 404,
      code: "CLASSROOM_NOT_FOUND",
    })
  })
})
