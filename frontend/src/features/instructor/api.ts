const API_BASE_URL = 'http://localhost:8080' // TODO: G0 이후 환경변수로 교체

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
    fieldErrors: string[]
  }
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
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
      throw new ApiError(res.status, body.error.code, body.error.message)
    }

    throw new ApiError(
      res.status,
      'UNEXPECTED_ERROR',
      `요청을 처리하지 못했습니다. (HTTP ${res.status})`,
    )
  }

  const body: ApiEnvelope<T> = await res.json()
  return body.data
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
  // 세션·CSRF 쿠키가 다른 Origin 으로도 오가야 하므로 조회에도 credentials 를 붙인다.
  const res = await fetch(`${API_BASE_URL}${path}`, { credentials: 'include' })
  return parseApiResponse<T>(res)
}

async function writeRequest<T>(path: string, body: unknown): Promise<T> {
  // 토큰 조회가 실패하면 여기서 ApiError 가 던져져, 생성이 성공한 것처럼 처리되지 않는다.
  const csrf = await fetchCsrfToken()

  const res = await fetch(`${API_BASE_URL}${path}`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      [csrf.headerName]: csrf.token,
    },
    body: JSON.stringify(body),
  })
  return parseApiResponse<T>(res)
}

export function listClassrooms(): Promise<Classroom[]> {
  return readRequest<Classroom[]>('/classrooms')
}

export function createClassroom(name: string): Promise<Classroom> {
  return writeRequest<Classroom>('/classrooms', { name })
}

export function getClassroom(classId: string): Promise<Classroom> {
  return readRequest<Classroom>(`/classrooms/${classId}`)
}

export function listChildren(classId: string): Promise<Child[]> {
  return readRequest<Child[]>(`/classrooms/${classId}/children`)
}

export function createChild(classId: string, displayName: string): Promise<Child> {
  return writeRequest<Child>(`/classrooms/${classId}/children`, { displayName })
}
