import { clearAccessToken, getAccessToken, setAccessToken } from './auth/tokenStorage'
import type { GoalCharacter } from './lib/goalConditions'

// 배포 빌드는 같은 출처의 /api/v1 을 넘긴다(frontend/Dockerfile 의 VITE_API_BASE_URL). 없으면 로컬·E2E 처럼 localhost:8080
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api/v1'

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

// 운영자 역할과 정지·초대·탈퇴 상태는 쓰지 않아 뺐다. 탈퇴하면 계정 행이 지워진다.
export type UserRole = 'INSTRUCTOR'

export type AccountStatus = 'ACTIVE'

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

// 요청은 "시작할 때의 로그인 세션"에 묶는다. 변경 요청은 CSRF 조회를 기다리는 사이 로그아웃하거나 다른 강사로
// 로그인할 수 있는데, 전송 직전에 토큰을 다시 읽으면 이전 강사가 입력한 작업이 다음 강사의 인증으로 실행된다.
// 그래서 토큰은 시작 시 한 번만 읽고, 전송 전에 세션이 바뀌었으면 보내지 않는다.
type RequestSession = {
  authenticated: boolean
  // 요청을 시작할 때의 토큰. 인증 요청이 아니면 null.
  token: string | null
}

function startSession(authenticated: boolean): RequestSession {
  return { authenticated, token: authenticated ? getAccessToken() : null }
}

function isSessionChanged(session: RequestSession): boolean {
  return session.authenticated && getAccessToken() !== session.token
}

function authHeaders(session: RequestSession): Record<string, string> {
  return session.token ? { Authorization: `Bearer ${session.token}` } : {}
}

// 인증 요청에 401 이 오면 토큰이 만료·폐기된 것이다. 토큰을 지우고 로그인 화면으로 보낸다(DEC-001 1.1절 프론트 규칙).
// 전체 페이지 이동이라 React Query 캐시에 남은 이전 강사의 데이터도 함께 사라진다.
// 요청 당시 토큰이 지금 토큰과 같을 때만 처리한다. 이전 강사의 요청이 늦게 401 을 받아도 새로 로그인한 강사의 토큰을 지우지 않는다.
// 가입·로그인 요청은 여기를 거치지 않는다. 로그인 실패(401 INVALID_CREDENTIALS)는 화면에서 오류로 보여 줘야 하기 때문이다.
function redirectToLoginOnUnauthorized(res: Response, session: RequestSession) {
  if (res.status === 401 && session.authenticated && !isSessionChanged(session)) {
    clearAccessToken()
    window.location.replace('/login')
  }
}

export const REQUEST_CANCELLED = 'REQUEST_CANCELLED'

// 세션이 바뀌어 취소한 요청. 상태 0 은 서버 응답이 없다는 뜻이다. 재시도해도 소용없으므로 main.tsx 의 재시도 대상에서 뺀다.
function cancelledError(): ApiError {
  return new ApiError(0, REQUEST_CANCELLED, '로그인 상태가 바뀌어 요청을 취소했습니다.')
}

export const NETWORK_ERROR = 'NETWORK_ERROR'

// 서버에 닿지 못한 요청(네트워크 끊김·서버 꺼짐). 일시적일 수 있어 main.tsx 는 조회를 다시 시도한다.
function networkError(): ApiError {
  return new ApiError(0, NETWORK_ERROR, '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.')
}

// fetch 는 서버에 닿지 못하면 브라우저마다 다른 영어 문구("Failed to fetch" 등)의 TypeError 를 던진다. 화면에 그 문구가 그대로
// 보이지 않게 공통 오류로 바꾼다(#22 의 6번). 로그아웃으로 취소한 요청(AbortError)은 withCancellation 이 따로 처리한다.
async function send(url: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(url, init)
  } catch (error) {
    if (error instanceof TypeError) {
      throw networkError()
    }
    throw error
  }
}

// 진행 중인 요청. 로그아웃할 때 모두 취소해, 이전 강사의 대기 중인 요청이 끝까지 가지 않게 한다.
const pendingRequests = new Set<AbortController>()

function cancelPendingRequests() {
  for (const controller of pendingRequests) {
    controller.abort()
  }
  pendingRequests.clear()
}

async function withCancellation<T>(run: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const controller = new AbortController()
  pendingRequests.add(controller)
  try {
    return await run(controller.signal)
  } catch (error) {
    if (controller.signal.aborted) {
      throw cancelledError()
    }
    throw error
  } finally {
    pendingRequests.delete(controller)
  }
}

type RequestOptions = {
  // false 면 Authorization 을 싣지 않고 401 이어도 로그인 화면으로 보내지 않는다(가입·로그인 전용).
  authenticated?: boolean
  // 요청마다 덧붙일 헤더(예: 재전송 방지용 Idempotency-Key).
  headers?: Record<string, string>
}

// API 명세(contracts/api/v1 의 각 API 문서 "Headers·쿠키" 표)의 공통 헤더. 모든 요청은 공통 envelope(JSON)을 받겠다고 알리고,
// JSON 본문은 charset 을 밝혀 보낸다. 경로의 ID 는 명세 예제(examples/endpoints.ts)처럼 encodeURIComponent 로 넣는다.
const JSON_TYPE = 'application/json'
const JSON_BODY_TYPE = 'application/json; charset=utf-8'

type CsrfToken = {
  headerName: string
  token: string
}

// 토큰은 요청마다 다르게 인코딩되어 내려오므로 재사용하지 않고 변경 요청 직전에 받아온다.
// 서버가 헤더 이름도 함께 내려주므로 프론트에 하드코딩하지 않는다.
async function fetchCsrfToken(signal: AbortSignal): Promise<CsrfToken> {
  const res = await send(`${API_BASE_URL}/csrf`, { credentials: 'include', headers: { Accept: JSON_TYPE }, signal })
  return parseApiResponse<CsrfToken>(res)
}

function readRequest<T>(path: string): Promise<T> {
  const session = startSession(true)
  return withCancellation(async (signal) => {
    // CSRF 쿠키가 다른 Origin 으로도 오가야 하므로 조회에도 credentials 를 붙인다.
    const res = await send(`${API_BASE_URL}${path}`, {
      credentials: 'include',
      headers: { Accept: JSON_TYPE, ...authHeaders(session) },
      signal,
    })
    redirectToLoginOnUnauthorized(res, session)
    return parseApiResponse<T>(res)
  })
}

function writeRequest<T>(
  method: 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  path: string,
  body?: unknown,
  { authenticated = true, headers = {} }: RequestOptions = {},
): Promise<T> {
  // 첫 대기(CSRF 조회) 전에 세션을 고정한다.
  const session = startSession(authenticated)
  return withCancellation(async (signal) => {
    // 토큰 조회가 실패하면 여기서 ApiError 가 던져져, 생성이 성공한 것처럼 처리되지 않는다.
    const csrf = await fetchCsrfToken(signal)

    if (isSessionChanged(session)) {
      throw cancelledError()
    }

    const res = await send(`${API_BASE_URL}${path}`, {
      method,
      credentials: 'include',
      headers: {
        Accept: JSON_TYPE,
        ...(body === undefined ? {} : { 'Content-Type': JSON_BODY_TYPE }),
        ...headers,
        [csrf.headerName]: csrf.token,
        ...authHeaders(session),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    })
    redirectToLoginOnUnauthorized(res, session)
    return parseApiResponse<T>(res)
  })
}

export type SignupInput = {
  email: string
  password: string
  name: string
  orgName?: string
  // 이용약관·개인정보 처리방침 각각 필수 동의. 버전과 시각은 서버가 정해 저장한다.
  termsAgreed: boolean
  privacyAgreed: boolean
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

/**
 * 대기 중인 요청을 모두 취소한 뒤 서버 세션을 폐기하고 토큰을 지운다. 서버 요청이 실패해도 이 브라우저에서는 로그아웃한다.
 */
export async function logout(): Promise<void> {
  cancelPendingRequests()
  try {
    await writeRequest<void>('DELETE', '/auth/session')
  } finally {
    clearAccessToken()
  }
}

/**
 * 즉시 탈퇴한다. 서버가 현재 비밀번호를 다시 확인한 뒤 계정과 학급·아동·학습 기록을 모두 지운다. 성공했을 때만 토큰을 지운다.
 * 실패하면 계정이 그대로라 로그인 상태를 유지한다. 비밀번호가 틀리면 401 이 아니라 403(PASSWORD_MISMATCH)이라 로그아웃되지 않는다.
 */
export async function withdraw(password: string): Promise<void> {
  cancelPendingRequests()
  await writeRequest<void>('POST', '/users/me/withdrawal', { password })
  clearAccessToken()
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
  return readRequest<Classroom>(`/classrooms/${encodeURIComponent(classId)}`)
}

/** 학급명만 고친다. */
export function updateClassroom(classId: string, name: string): Promise<Classroom> {
  return writeRequest<Classroom>('PATCH', `/classrooms/${encodeURIComponent(classId)}`, { name })
}

/** 학급과 그 학급의 아동·기록을 모두 영구 삭제한다(ADR 2026-10-04). */
export function deleteClassroom(classId: string): Promise<void> {
  return writeRequest<void>('DELETE', `/classrooms/${encodeURIComponent(classId)}`)
}

export function listChildren(classId: string): Promise<Child[]> {
  return readRequest<Child[]>(`/classrooms/${encodeURIComponent(classId)}/children`)
}

export function getChild(childId: string): Promise<Child> {
  return readRequest<Child>(`/children/${encodeURIComponent(childId)}`)
}

/** 아동 이름만 고친다. 학급은 옮기지 않는다. */
export function updateChild(childId: string, displayName: string): Promise<Child> {
  return writeRequest<Child>('PATCH', `/children/${encodeURIComponent(childId)}`, { displayName })
}

/** 아동과 그 아동의 목표·활동·퀴즈 응답·결과·입장 코드를 영구 삭제한다(ADR 2026-10-04). */
export function deleteChild(childId: string): Promise<void> {
  return writeRequest<void>('DELETE', `/children/${encodeURIComponent(childId)}`)
}

export function createChild(classId: string, displayName: string): Promise<Child> {
  return writeRequest<Child>('POST', `/classrooms/${encodeURIComponent(classId)}/children`, { displayName })
}

export type AccessCode = {
  // 숫자 4자리 문자열. 앞자리 0 이 있으니 숫자로 바꾸지 않는다.
  accessCode: string
  expiresAt: string
}

/**
 * 아동 입장 코드 발급. 새로 받으면 그 아동의 이전 코드는 바로 못 쓰게 된다.
 * 서버는 코드 원문을 저장하지 않아 나중에 다시 조회할 수 없다. 받은 응답이 코드를 볼 수 있는 유일한 때다.
 * requestKey 는 "코드 받기" 한 번에 하나다. 응답을 못 받아 다시 보낼 때 같은 키를 쓰면 같은 코드가 돌아온다.
 */
export function issueChildCode(childId: string, requestKey: string): Promise<AccessCode> {
  return writeRequest<AccessCode>('POST', `/children/${encodeURIComponent(childId)}/access-codes`, undefined, {
    headers: { 'Idempotency-Key': requestKey },
  })
}


export type LearningGoal = {
  goalId: string
  childId: string
  instructorId: string
  parentGoalId: string | null
  title: string
  situationType: string | null
  category: string | null
  // 서버가 정리해 저장한 등장인물. 비운 id 는 OTHER, 겹친 id 는 FRIEND_2 처럼 번호가 붙는다(API-GOAL-01).
  characters: { id: string; role: string }[]
  requiredElements: string[]
  forbiddenExpressions: string[]
  contentHash: string
  createdAt: string
}

export type LearningGoalInput = {
  title: string
  category?: string
  // 추천을 채택해 만드는 다음 활동은 지금 목표를 이어받는다(서버가 같은 아동의 목표인지 확인한다).
  parentGoalId?: string
  // 역할극 추가 조건(시안 T-ACT-04). 모두 선택이다. 상황은 장면 설명 문장이다.
  situationType?: string
  characters?: GoalCharacter[]
  requiredElements?: string[]
  forbiddenExpressions?: string[]
}

export function listLearningGoals(childId: string): Promise<LearningGoal[]> {
  return readRequest<LearningGoal[]>(`/children/${encodeURIComponent(childId)}/learning-goals`)
}

export function getLearningGoal(goalId: string): Promise<LearningGoal> {
  return readRequest<LearningGoal>(`/learning-goals/${encodeURIComponent(goalId)}`)
}


export type ActivityStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'PAUSED' | 'RECOVERY_NEEDED' | 'COMPLETED'

/**
 * 활동 유형. 최초 결합은 아동당 한 번이고, 그것을 마친 뒤에 독립 퀴즈와 역할극을 배정한다.
 * 역할극 단독 활동은 마친 역할극이 있어야(다음 역할극 난이도가 있어야) 서버가 받는다.
 */
export type ActivityType = 'FIRST_COMBINED' | 'SELF_EMOTION_QUIZ' | 'OTHER_EMOTION_QUIZ' | 'ROLEPLAY'

export type Activity = {
  activityId: string
  childId: string
  // 독립 퀴즈는 학습 목표가 없다.
  goalId: string | null
  activityType: ActivityType
  scenarioId: string | null
  scenarioSource: string | null
  initialSupportLevel: string | null
  difficultyFallbackApplied: boolean
  status: ActivityStatus
  assignedAt: string
  startedAt: string | null
  completedAt: string | null
  rewardIssuedAt: string | null
  /** 목록·상세에서만 오는 서버 계산 다음 동작(API-ACT-02·03). 생성 응답에는 없다. */
  nextAction?: 'QUIZ' | 'WAIT_FOR_SCENARIO' | 'ROLEPLAY' | 'NONE' | 'BLOCKED'
  blockedReason?: string | null
}

export type QuizType = 'SELF_EMOTION_SITUATION' | 'OTHER_EMOTION_SITUATION' | 'OTHER_EMOTION_IMAGE'

/** 활동에 붙은 문항. 정답·힌트 내용은 서버가 빼고 보낸다. */
export type AssignedQuizItem = {
  activityQuizId: string
  activityId: string
  itemId: string
  itemVersion: number
  questionOrder: number
  quizType: QuizType
  questionText: string
  imageUrl: string | null
  choices: string[]
}

export type ActivityDetail = {
  activity: Activity
  quizItems: AssignedQuizItem[]
}

const ACTIVITY_PAGE_SIZE = 100 // 서버 최대 페이지 크기

/**
 * 아동의 활동을 전부 받는다. 응답에 전체 개수가 없으므로, 받은 개수가 페이지 크기보다 적을 때까지 다음 페이지를 이어 받는다.
 * 삭제 확인 창이 이 목록으로 함께 지워지는 활동 수를 알리므로 첫 페이지에서 끊으면 안 된다.
 */
export async function listChildActivities(childId: string): Promise<Activity[]> {
  const all: Activity[] = []
  for (let page = 0; ; page++) {
    const batch = await readRequest<Activity[]>(
      `/children/${encodeURIComponent(childId)}/activities?page=${page}&size=${ACTIVITY_PAGE_SIZE}`,
    )
    all.push(...batch)
    if (batch.length < ACTIVITY_PAGE_SIZE) return all
  }
}

export function getActivity(activityId: string): Promise<ActivityDetail> {
  return readRequest<ActivityDetail>(`/activities/${encodeURIComponent(activityId)}`)
}

/**
 * 활동 배정. 서버가 목표를 저장하고 승인 문항을 골라 활동과 함께 한 번에 저장한다(ADR 2026-10-03 D4).
 * 배정이 실패하면 목표도 저장되지 않는다.
 * requestKey 는 "배정하기" 한 번에 하나다. 응답을 못 받아 다시 보낼 때는 같은 키를 써야 활동이 하나만 남는다.
 */
export function createActivity(
  childId: string,
  activityType: ActivityType,
  goal: LearningGoalInput | null,
  requestKey: string,
): Promise<Activity> {
  return writeRequest<Activity>(
    'POST',
    '/activities',
    {
      childId,
      activityType,
      ...(goal ? { goal } : {}),
    },
    { headers: { 'Idempotency-Key': requestKey } },
  )
}

export type QuizResult = {
  activityId: string
  validQuestionCount: number
  correctQuestionCount: number
  overallAccuracy: number | null
  totalHintCount: number
  resolvedAfterHintCount: number
  scenarioLevel: string | null
  initialSupportLevel: string | null
  difficultyFallbackApplied: boolean
  initialDifficultyUsed: boolean
  policyVersion: string
}

/** 퀴즈가 아직 끝나지 않았으면 409 QUIZ_NOT_COMPLETED 다. */
export function getQuizResult(activityId: string): Promise<QuizResult> {
  return readRequest<QuizResult>(`/activities/${encodeURIComponent(activityId)}/quiz-result`)
}

export type ScenarioCharacter = { id: string; role: string }

export type ScenarioMicroGoal = {
  microGoalId: string
  description: string
  requiredEvidence: string[]
  achievementExample: string | null
}

/** 검토 중인 시나리오 한 개(재사용 후보 또는 새로 만든 것). 후보 목록·개수는 서버가 내리지 않는다. */
export type ReviewScenario = {
  scenarioId: string
  /** 콘텐츠 버전. 검토 번호(revision)와 다르다 */
  scenarioVersion: number
  scenarioLevel: string
  title: string
  summary: string
  characters: ScenarioCharacter[]
  scenarioFacts: string[]
  microGoals: ScenarioMicroGoal[]
  /** S0·S1·S2 시작 질문 */
  startingQuestions: Record<string, string>
  /** 시작 질문마다 이어지는 작은 목표 ID */
  startingQuestionGoalIds: Record<string, string>
}

/** 이번 활동의 목표와 조건. 서버에 저장된 값이다. */
export type ReviewConditions = {
  title: string
  category: string | null
  situationType: string | null
  characters: Record<string, unknown>[]
  requiredElements: string[]
  forbiddenExpressions: string[]
  scenarioLevel: string
}

export type ScenarioReview = {
  reviewId: string
  activityId: string
  /** 검토 번호. 다음 동작에 그대로 돌려보내고, 이보다 낮은 번호의 늦은 응답은 버린다 */
  revision: number
  conditions: ReviewConditions
  scenario: ReviewScenario
}

export type ScenarioReviewAction =
  | { action: 'START_OR_RESUME' }
  | { action: 'NEXT'; reviewId: string; revision: number }

/** 고친 칸만 싣는다. 사실·등장인물·시작 질문은 고칠 수 없다. */
export type ScenarioEdits = {
  title?: string
  summary?: string
  microGoals?: { microGoalId: string; description?: string; requiredEvidence?: string[] }[]
}

export type ScenarioConfirmation = {
  reviewId: string
  revision: number
  scenarioId: string
  scenarioVersion: number
  edits?: ScenarioEdits
}

/** 확정·취소 결과. 최종 시나리오 ID 는 활동 상세의 scenarioId 로 확인한다. */
export type ScenarioReviewClosure = {
  reviewId: string
  activityId: string
  outcome: 'CONFIRMED' | 'CANCELLED'
  closedAt: string
}

/**
 * 검토 시작·이어 보기(START_OR_RESUME)와 다른 후보(NEXT). 후보가 없거나 다 넘기면 같은 요청 안에서 새로 만들어 오래 걸릴 수 있다.
 * 응답을 못 받아 다시 보낼 때는 같은 키와 같은 본문을 쓴다.
 */
export function prepareScenarioReview(
  activityId: string,
  action: ScenarioReviewAction,
  requestKey: string,
): Promise<ScenarioReview> {
  return writeRequest<ScenarioReview>('POST', `/activities/${encodeURIComponent(activityId)}/scenario-review`, action, {
    headers: { 'Idempotency-Key': requestKey },
  })
}

/** [승인]: 보고 있는 시나리오를 (고친 내용이 있으면 새 버전으로) 이 활동에 붙인다. */
export function confirmScenarioReview(
  activityId: string,
  confirmation: ScenarioConfirmation,
  requestKey: string,
): Promise<ScenarioReviewClosure> {
  return writeRequest<ScenarioReviewClosure>('PUT', `/activities/${encodeURIComponent(activityId)}/scenario`, confirmation, {
    headers: { 'Idempotency-Key': requestKey },
  })
}

/** [검토 그만하기]: 이번 검토를 끝낸다. 활동은 계속 시나리오를 기다린다. */
export function cancelScenarioReview(
  activityId: string,
  review: { reviewId: string; revision: number },
  requestKey: string,
): Promise<ScenarioReviewClosure> {
  return writeRequest<ScenarioReviewClosure>('DELETE', `/activities/${encodeURIComponent(activityId)}/scenario-review`, review, {
    headers: { 'Idempotency-Key': requestKey },
  })
}

export type ScenarioApprovalState = 'WAITING' | 'IN_REVIEW'

/** 승인 대기함의 한 줄. 시나리오를 기다리는 최초 결합 활동 하나다. */
export type ScenarioApprovalItem = {
  activityId: string
  childId: string
  childName: string
  classId: string
  className: string
  goalId: string
  goalTitle: string
  scenarioLevel: string
  waitingSince: string
  state: ScenarioApprovalState
}

/** 내 학급에서 시나리오 승인을 기다리는 활동. 기다린 순서대로 온다. */
export function listScenarioApprovals(): Promise<ScenarioApprovalItem[]> {
  return readRequest<ScenarioApprovalItem[]>('/scenario-approvals')
}

// ── 리포트·추천·다음 역할극 난이도 (contracts/api/v1/reports-recommendations) ──

export type SupportLevel = 'S0' | 'S1' | 'S2' | 'S3'
export type ScenarioLevel = 'L1' | 'L2' | 'L3'

/** 서버가 센 값의 분자·분모. 작은 분모를 퍼센트 대신 "3개 중 2개"로 보이려고 함께 받는다. */
export type RatioView = { numerator: number; denominator: number; value: number }

export type ObservationStatus = 'EXPLICIT' | 'IMPLICIT' | 'NOT_EXPRESSED' | 'MISUNDERSTOOD' | 'CONTRADICTORY'

/** 혼자·처음 지원으로·지원을 더 받고·답을 듣고·못 함·제외 */
export type GoalStatus =
  | 'NO_SUPPORT_NEEDED'
  | 'REACHED_AT_START'
  | 'EXTRA_SUPPORT'
  | 'ANSWER_GIVEN'
  | 'NOT_REACHED'
  | 'EXCLUDED'

export type ReportGoal = {
  microGoalId: string
  title: string
  startSupport: SupportLevel
  finalSupport: SupportLevel
  result: string
  status: GoalStatus
  criteria: { description: string; status: ObservationStatus | null }[]
  // 지원 단계와 기준으로 만든 짧은 문장. 아동 발화 원문이 아니다.
  observations: string[]
}

export type ReportDifficultyDecision = {
  decisionType: 'FOLLOW_UP'
  scenarioLevel: ScenarioLevel
  previousLevel: ScenarioLevel
  nextSupportLevel: SupportLevel
  direction: 'LOWER' | 'KEEP' | 'RAISE'
  reason: string
  scoreRate: RatioView | null
  consecutiveUpgradeCandidates: number
  policyVersion: string
}

export type ReportNarrativeItem = { text: string; evidenceIds: string[] }

export type LearningReport = {
  recordId: string
  activityId: string
  childId: string
  completedAt: string
  summary: string
  // 역할극 단독 활동은 퀴즈가 없다.
  quiz: {
    validQuestionCount: number
    correctQuestionCount: number
    overallAccuracy: number | null
    resolvedAfterHintCount: number
  } | null
  roleplay: {
    scenarioLevel: ScenarioLevel
    startSupportLevel: SupportLevel
    goals: ReportGoal[]
    observations: string[]
    effectiveSupport: string[]
    technicalExclusions: string[]
  }
  difficultyDecision: ReportDifficultyDecision
  metrics: {
    roleplayScore: RatioView | null
    goalAchievement: RatioView | null
    independentAchievement: RatioView | null
    quizAccuracy: RatioView | null
    quizHintRecovery: RatioView | null
    validGoalCount: number
    excludedGoalCount: number
  }
  narrative: {
    observations: ReportNarrativeItem[]
    effectiveSupports: ReportNarrativeItem[]
    difficultPoints: ReportNarrativeItem[]
  } | null
}

export type RecommendationType = 'REINFORCE' | 'REPEAT' | 'APPLY' | 'EXTEND'
export type RecommendationDecision = 'ACCEPT' | 'MODIFIED_ACCEPT' | 'DEFER' | 'REJECT'
export type RejectionReason = 'NOT_NOW' | 'NOT_SUITABLE' | 'ALREADY_PLANNED' | 'OTHER'

export type SuggestedGoal = {
  // 확장(EXTEND)은 강사가 넓힐 목표를 써야 해서 비어 있다.
  title: string | null
  category: string | null
  parentGoalId: string | null
}

export type Recommendation = {
  recommendationId: string
  recordId: string
  activityId: string
  childId: string
  type: RecommendationType
  displayOrder: number
  suggestedGoal: SuggestedGoal
  reason: string
  reasonCode: string
  basisGoalTitle: string | null
  evidence: string[]
  evidenceSufficiency: 'SUFFICIENT' | 'LIMITED' | 'INSUFFICIENT'
  decision: RecommendationDecision | null
  decidedGoal: SuggestedGoal | null
  rejectionReason: RejectionReason | null
  createdGoalId: string | null
  policyVersion: string
  createdAt: string
  decidedAt: string | null
}

export type RecommendationDecisionInput = {
  decision: RecommendationDecision
  goal?: { title: string; category?: string | null; parentGoalId?: string | null }
  rejectionReason?: RejectionReason
}

export type RoleplayDifficulty = {
  childId: string
  scenarioLevel: ScenarioLevel
  initialSupportLevel: SupportLevel
  previousLevel: ScenarioLevel
  direction: 'LOWER' | 'KEEP' | 'RAISE'
  reason: string
  scoreNumerator: number
  scoreDenominator: number
  consecutiveUpgradeCandidates: number
  policyVersion: string
  sourceActivityId: string
  // AI 요약이 실패해 리포트가 404여도 이 기록으로 추천을 읽는다.
  sourceRecordId: string | null
  decidedAt: string
}

/**
 * 완료한 리포트만 온다. AI 요약을 만드는 중이거나 만들지 못한 리포트는 없는 것과 같은 404 LEARNING_RECORD_NOT_FOUND 다.
 */
export function getLearningReport(activityId: string): Promise<LearningReport> {
  return readRequest<LearningReport>(`/activities/${activityId}/learning-record`)
}

/** 최근 완료 순. 학급 요약·추천 화면은 아동마다 첫 몇 개만 읽는다. */
export function listLearningReports(childId: string, size = 5): Promise<LearningReport[]> {
  return readRequest<LearningReport[]>(`/learning-records?childId=${childId}&page=0&size=${size}`)
}

/** 추천은 AI 요약과 따로 만들어져서 리포트가 아직 없어도 있을 수 있다. */
export function listRecommendations(recordId: string): Promise<Recommendation[]> {
  return readRequest<Recommendation[]>(`/learning-records/${recordId}/recommendations`)
}

/**
 * 결정만 남기고 활동은 만들지 않는다(자동 배정 금지). 채택 뒤 활동 만들기는 화면이 따로 연다.
 * requestKey 는 결정 한 번에 하나다. 응답을 못 받아 다시 보낼 때는 같은 키를 쓴다.
 */
export function decideRecommendation(
  recommendationId: string,
  input: RecommendationDecisionInput,
  requestKey: string,
): Promise<Recommendation> {
  return writeRequest<Recommendation>('PATCH', `/recommendations/${recommendationId}`, input, {
    headers: { 'Idempotency-Key': requestKey },
  })
}

/** 아직 마친 역할극이 없으면 404 ROLEPLAY_DIFFICULTY_NOT_FOUND 다. */
export function getRoleplayDifficulty(childId: string): Promise<RoleplayDifficulty> {
  return readRequest<RoleplayDifficulty>(`/children/${childId}/roleplay-difficulty`)
}
