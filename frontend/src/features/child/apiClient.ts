/**
 * 아동 화면 전용 요청 함수 (API-IAM-08·09, contracts/api/v1/integration-guide.md).
 * 아동 인증은 서버가 심는 HttpOnly 세션 쿠키라서 credentials: "include" 로만 보낸다.
 * 강사 api.ts 의 요청 함수는 강사 Bearer 토큰을 싣고 401 이면 로그인 화면으로 보내므로 아동 요청에 쓰지 않는다.
 */

// 배포 빌드는 같은 출처의 /api/v1 을 넘긴다(frontend/Dockerfile 의 VITE_API_BASE_URL). 없으면 로컬·E2E 처럼 localhost:8080
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1"

/** 서버에 닿지 못한 요청(연결 끊김·서버 꺼짐). 서버가 처리했는지 알 수 없으므로 성공·실패로 단정하지 않는다. */
export const NETWORK_ERROR = "NETWORK_ERROR"

/** 공통 오류 형식이 아닌 응답(프록시의 HTML, Spring 기본 오류, 본문 없는 CSRF 403 등). */
export const UNEXPECTED_ERROR = "UNEXPECTED_ERROR"

export class ChildApiError extends Error {
  /** 서버 응답이 없으면 0 */
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = "ChildApiError"
    this.status = status
    this.code = code
  }
}

type ApiErrorBody = { error: { code: string; message: string } }

function isApiErrorBody(body: unknown): body is ApiErrorBody {
  if (typeof body !== "object" || body === null || !("error" in body)) return false
  const error = (body as { error: unknown }).error
  if (typeof error !== "object" || error === null) return false
  const { code, message } = error as Partial<ApiErrorBody["error"]>
  return typeof code === "string" && typeof message === "string"
}

// fetch 는 서버에 닿지 못하면 브라우저마다 다른 영어 문구의 TypeError 를 던진다. 화면이 code 로 나눌 수 있게 바꾼다.
async function send(url: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(url, { ...init, credentials: "include" })
  } catch (error) {
    if (error instanceof TypeError) {
      throw new ChildApiError(0, NETWORK_ERROR, "서버에 연결할 수 없습니다.")
    }
    throw error
  }
}

// 화면은 message 가 아니라 status·code 로 나눈다. message 는 화면에 그대로 보여 주지 않는다.
async function parseResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body: unknown = await res.json().catch(() => null)
    if (isApiErrorBody(body)) throw new ChildApiError(res.status, body.error.code, body.error.message)
    throw new ChildApiError(res.status, UNEXPECTED_ERROR, `요청을 처리하지 못했습니다. (HTTP ${res.status})`)
  }
  // 사용 종료(204)처럼 본문이 없는 성공 응답은 읽지 않는다.
  if (res.status === 204) return undefined as T
  const body = (await res.json()) as { data: T }
  return body.data
}

type CsrfToken = { headerName: string; token: string }

// 토큰은 요청마다 다르게 인코딩되어 내려오므로 변경 요청 직전에 받는다. 헤더 이름도 서버가 정한다.
async function fetchCsrfToken(): Promise<CsrfToken> {
  return parseResponse<CsrfToken>(await send(`${API_BASE_URL}/csrf`, {}))
}

/**
 * CSRF 토큰을 붙인 변경 요청. 실패해도 자동으로 다시 보내지 않는다(CSRF 403 포함).
 * 다시 보낼지는 화면이 아이에게 "다시 하기"를 보여 주고 정한다.
 */
export async function childWriteRequest<T>(method: "POST" | "DELETE", path: string, body?: unknown): Promise<T> {
  const csrf = await fetchCsrfToken()
  const res = await send(`${API_BASE_URL}${path}`, {
    method,
    headers: {
      Accept: "application/json",
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      [csrf.headerName]: csrf.token,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return parseResponse<T>(res)
}
