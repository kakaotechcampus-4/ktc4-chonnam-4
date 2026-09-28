import { clearAccessToken, getAccessToken, setAccessToken } from './auth/tokenStorage'

const API_BASE_URL = 'http://localhost:8080/api/v1' // TODO: G0 이후 환경변수로 교체

export type ClassroomStatus = 'ACTIVE' | 'ARCHIVED'

export type Classroom = {
  classId: string
  instructorId: string
  name: string
  status: ClassroomStatus
}

export type ChildStatus = 'ACTIVE' | 'PAUSED' | 'REMOVED'

export type Child = {
  childId: string
  classId: string
  displayName: string
  status: ChildStatus
}

export type UserRole = 'INSTRUCTOR' | 'OPERATOR'

export type AccountStatus = 'INVITED' | 'ACTIVE' | 'SUSPENDED' | 'WITHDRAWN'

export type User = {
  userId: string
  role: UserRole
  email: string
  name: string
  orgName: string | null
  status: AccountStatus
}

type LoginResponse = {
  accessToken: string
  expiresAt: string
  user: User
}

type SessionResponse = {
  user: User
}

export type FieldError = { field: string; message: string }

type ApiEnvelope<T> = {
  data: T
  meta: { traceId: string }
}

type ApiErrorBody = {
  error: {
    status: number
    code: string
    message: string
    path: string
    traceId: string
    fieldErrors: FieldError[]
  }
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: FieldError[]

  constructor(status: number, code: string, message: string, fieldErrors: FieldError[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
  }
}

// 백엔드 공통 오류(GlobalExceptionHandler)가 아닌 Spring 기본 오류나 프록시의 HTML 응답도
// 비성공 응답으로 도착하므로, 형태를 확인한 뒤에만 공통 오류로 읽는다.
function isApiErrorBody(body: unknown): body is ApiErrorBody {
  if (typeof body !== 'object' || body === null || !('error' in body)) {
    return false
  }

  const error = (body as { error: unknown }).error
  if (typeof error !== 'object' || error === null) {
    return false
  }

  const { code, message } = error as Partial<ApiErrorBody['error']>
  return typeof code === 'string' && typeof message === 'string' && message !== ''
}

async function parseApiResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body: unknown = await res.json().catch(() => null)

    if (isApiErrorBody(body)) {
      throw new ApiError(
        res.status,
        body.error.code,
        body.error.message,
        Array.isArray(body.error.fieldErrors) ? body.error.fieldErrors : [],
      )
    }

    throw new ApiError(
      res.status,
      'UNEXPECTED_ERROR',
      `요청을 처리하지 못했습니다. (HTTP ${res.status})`,
    )
  }

  // 로그아웃(204)처럼 본문이 없는 성공 응답
  if (res.status === 204) {
    return undefined as T
  }

  const body: ApiEnvelope<T> = await res.json()
  return body.data
}

// 인증 요청에 401 이 오면 토큰이 만료·폐기된 것이다. 토큰을 지우고 로그인 화면으로 보낸다(DEC-001 1.1절 프론트 규칙).
// 전체 페이지 이동이라 React Query 캐시에 남은 이전 강사의 데이터도 함께 사라진다.
// 가입·로그인 요청은 여기를 거치지 않는다. 로그인 실패(401 INVALID_CREDENTIALS)는 화면에서 오류로 보여 줘야 하기 때문이다.
function redirectToLoginOnUnauthorized(res: Response) {
  if (res.status === 401) {
    clearAccessToken()
    window.location.replace('/login')
  }
}

type RequestOptions = {
  // false 면 Authorization 을 싣지 않고 401 이어도 로그인 화면으로 보내지 않는다(가입·로그인 전용).
  authenticated?: boolean
}

function authHeaders(authenticated: boolean): Record<string, string> {
  const token = authenticated ? getAccessToken() : null
  return token ? { Authorization: `Bearer ${token}` } : {}
}

type CsrfToken = {
  headerName: string
  token: string
}

// 토큰은 요청마다 다르게 인코딩되어 내려오므로 재사용하지 않고 변경 요청 직전에 받아온다.
// 서버가 헤더 이름도 함께 내려주므로 프론트에 하드코딩하지 않는다.
async function fetchCsrfToken(): Promise<CsrfToken> {
  const res = await fetch(`${API_BASE_URL}/csrf`, { credentials: 'include' })
  return parseApiResponse<CsrfToken>(res)
}

async function readRequest<T>(path: string): Promise<T> {
  // CSRF 쿠키가 다른 Origin 으로도 오가야 하므로 조회에도 credentials 를 붙인다.
  const res = await fetch(`${API_BASE_URL}${path}`, {
    credentials: 'include',
    headers: authHeaders(true),
  })
  redirectToLoginOnUnauthorized(res)
  return parseApiResponse<T>(res)
}

async function writeRequest<T>(
  method: 'POST' | 'DELETE',
  path: string,
  body?: unknown,
  { authenticated = true }: RequestOptions = {},
): Promise<T> {
  // 토큰 조회가 실패하면 여기서 ApiError 가 던져져, 생성이 성공한 것처럼 처리되지 않는다.
  const csrf = await fetchCsrfToken()

  const res = await fetch(`${API_BASE_URL}${path}`, {
    method,
    credentials: 'include',
    headers: {
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      [csrf.headerName]: csrf.token,
      ...authHeaders(authenticated),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (authenticated) {
    redirectToLoginOnUnauthorized(res)
  }
  return parseApiResponse<T>(res)
}

export type SignupInput = {
  email: string
  password: string
  name: string
  orgName?: string
}

export function signup(input: SignupInput): Promise<User> {
  return writeRequest<User>('POST', '/users', input, { authenticated: false })
}

/** 로그인에 성공하면 토큰을 보관한다. 이전 토큰이 남아 있어도 싣지 않는다. */
export async function login(email: string, password: string): Promise<User> {
  const result = await writeRequest<LoginResponse>(
    'POST',
    '/auth/sessions',
    { email, password },
    { authenticated: false },
  )
  setAccessToken(result.accessToken)
  return result.user
}

export async function getCurrentUser(): Promise<User> {
  const result = await readRequest<SessionResponse>('/auth/session')
  return result.user
}

/** 서버 세션을 폐기하고 토큰을 지운다. 서버 요청이 실패해도 이 브라우저에서는 로그아웃한다. */
export async function logout(): Promise<void> {
  try {
    await writeRequest<void>('DELETE', '/auth/session')
  } finally {
    clearAccessToken()
  }
}

export function hasAccessToken(): boolean {
  return getAccessToken() !== null
}

export function listClassrooms(): Promise<Classroom[]> {
  return readRequest<Classroom[]>('/classrooms')
}

export function createClassroom(name: string): Promise<Classroom> {
  return writeRequest<Classroom>('POST', '/classrooms', { name })
}

export function getClassroom(classId: string): Promise<Classroom> {
  return readRequest<Classroom>(`/classrooms/${classId}`)
}

export function listChildren(classId: string): Promise<Child[]> {
  return readRequest<Child[]>(`/classrooms/${classId}/children`)
}

export function createChild(classId: string, displayName: string): Promise<Child> {
  return writeRequest<Child>('POST', `/classrooms/${classId}/children`, { displayName })
}
