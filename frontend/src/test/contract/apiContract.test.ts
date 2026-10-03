import { describe, expect, it } from "vitest"
import contractJson from "../../../../contracts/api-v1.json?raw"
import { fixtures, instructors } from "@/test/msw/handlers"

// FE ↔ BE API 명세 검사. contracts/api-v1.json 의 시나리오를 프론트 테스트의 MSW 가짜 서버에 보낸다.
// 백엔드 ApiContractTest 는 같은 파일을 실제 API 에 보낸다. 가짜 서버가 실제 서버와 달라지면(예: UUID 가 아닌 ID 에
// 400 대신 404) 프론트 테스트는 통과하는데 실제로는 깨지는 일이 생긴다. 그걸 여기서 막는다.
// 비교 규칙은 contracts/README.md. API 명세를 바꿀 때는 파일과 양쪽 코드를 같은 PR 에서 고친다.

type Scenario = {
  name: string
  request: {
    method: "GET" | "POST"
    path: string
    body?: unknown
    rawBody?: string
    csrf?: boolean
    // 없거나 true 면 로그인한 강사(A)의 토큰, false 면 싣지 않음, "invalid" 면 틀린 토큰(contracts/README.md).
    auth?: boolean | "invalid"
    // 더 실을 헤더(Idempotency-Key 등). 값의 {자리표시자} 도 채운다.
    headers?: Record<string, string>
  }
  response: { status: number; body: unknown }
}

const scenarios = (JSON.parse(contractJson) as { scenarios: Scenario[] }).scenarios

const values: Record<string, string> = {
  classId: fixtures.classroomA1.classId,
  otherClassId: fixtures.classroomC1.classId,
  unknownClassId: "00000000-0000-4000-8000-00000000ffff",
  malformedId: "not-a-uuid",
  instructorId: instructors.a.userId,
  instructorEmail: instructors.a.email,
  instructorPassword: instructors.a.password,
  newEmail: `new-${crypto.randomUUID()}@example.com`,
  newPassword: `pw-${crypto.randomUUID()}`,
  childId: fixtures.childA1_1.childId,
  otherChildId: fixtures.childC1_1.childId,
  requestKey: crypto.randomUUID(),
}

const ORIGIN = "http://localhost:8080"
const UUID_FORMAT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

function fill(template: string) {
  return template.replace(/\{(\w+)\}/g, (placeholder, key: string) => {
    const value = values[key]
    if (value === undefined) throw new Error(`contracts/api-v1.json 에 모르는 자리표시자: ${placeholder}`)
    return value
  })
}

function authHeader(auth: Scenario["request"]["auth"]): Record<string, string> {
  if (auth === false) return {}
  if (auth === "invalid") return { Authorization: `Bearer invalid-${crypto.randomUUID()}` }
  return { Authorization: `Bearer ${instructors.a.accessToken}` }
}

function extraHeaders(headers: Scenario["request"]["headers"]): Record<string, string> {
  return Object.fromEntries(Object.entries(headers ?? {}).map(([name, value]) => [name, fill(value)]))
}

async function send({ request }: Scenario) {
  const url = `${ORIGIN}${fill(request.path)}`
  if (request.method === "GET") {
    return fetch(url, { credentials: "include", headers: { ...authHeader(request.auth), ...extraHeaders(request.headers) } })
  }

  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...authHeader(request.auth),
    ...extraHeaders(request.headers),
  }
  if (request.csrf !== false) {
    // 실제 프론트(api.ts)처럼 변경 직전에 토큰을 받는다.
    const csrf = (await (await fetch(`${ORIGIN}/api/v1/csrf`, { credentials: "include" })).json()) as {
      data: { headerName: string; token: string }
    }
    headers[csrf.data.headerName] = csrf.data.token
  }
  return fetch(url, {
    method: "POST",
    credentials: "include",
    headers,
    // 본문 안의 {자리표시자} 도 채운다(가입 이메일 등). JSON 의 괄호 뒤에는 따옴표가 와서 자리표시자로 읽히지 않는다.
    body: request.rawBody ?? fill(JSON.stringify(request.body)),
  })
}

// 백엔드 ApiContractTest#match 와 같은 규칙. 틀린 곳을 전부 모아 한 번에 보여 준다.
function mismatches(expected: unknown, actual: unknown, at: string): string[] {
  if (Array.isArray(expected)) {
    if (!Array.isArray(actual)) return [`${at}: 배열이어야 하는데 ${JSON.stringify(actual)}`]
    if (expected.length === 0) return actual.length === 0 ? [] : [`${at}: 비어 있어야 하는데 ${JSON.stringify(actual)}`]
    if (actual.length === 0) return [`${at}: 항목이 하나 이상 있어야 한다`]
    return actual.flatMap((item, i) => mismatches(expected[0], item, `${at}[${i}]`))
  }
  if (typeof expected === "object" && expected !== null) {
    if (typeof actual !== "object" || actual === null || Array.isArray(actual)) {
      return [`${at}: 객체여야 하는데 ${JSON.stringify(actual)}`]
    }
    const expectedKeys = Object.keys(expected).sort()
    const actualKeys = Object.keys(actual).sort()
    const keyProblems =
      expectedKeys.join() === actualKeys.join() ? [] : [`${at}: 키가 [${expectedKeys}] 여야 하는데 [${actualKeys}]`]
    return [
      ...keyProblems,
      ...expectedKeys
        .filter((key) => key in actual)
        .flatMap((key) =>
          mismatches((expected as Record<string, unknown>)[key], (actual as Record<string, unknown>)[key], `${at}.${key}`),
        ),
    ]
  }
  if (typeof expected === "string") {
    const ok =
      expected === "<any>" ||
      (expected === "<string>" && typeof actual === "string" && actual !== "") ||
      (expected === "<uuid>" && typeof actual === "string" && UUID_FORMAT.test(actual)) ||
      (!expected.startsWith("<") && actual === fill(expected))
    return ok ? [] : [`${at}: ${fill(expected)} 여야 하는데 ${JSON.stringify(actual)}`]
  }
  return expected === actual ? [] : [`${at}: ${JSON.stringify(expected)} 여야 하는데 ${JSON.stringify(actual)}`]
}

describe("MSW 가짜 서버 ↔ API 명세(contracts/api-v1.json)", () => {
  it("API 명세 시나리오가 비어 있지 않다", () => {
    expect(scenarios.length).toBeGreaterThanOrEqual(20)
  })

  it.each(scenarios.map((scenario) => [scenario.name, scenario] as const))("%s", async (_name, scenario) => {
    const response = await send(scenario)

    expect(response.status).toBe(scenario.response.status)
    if (scenario.response.body === "<any>") return
    expect(mismatches(scenario.response.body, await response.json(), "$")).toEqual([])
  })
})
