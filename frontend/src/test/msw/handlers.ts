import { http, HttpResponse } from "msw"
import type {
  Activity,
  ActivityStatus,
  AssignedQuizItem,
  Child,
  Classroom,
  LearningGoal,
  QuizResult,
  User,
} from "@/features/instructor/api"

// 백엔드 API 명세(ApiResponse·ApiErrorResponse, /api/v1 경로)을 흉내 내는 기본 핸들러.
// 테스트 하나에서만 다르게 응답하려면 server.use(...) 로 덮어쓴다. 끝나면 setup 이 되돌린다.
//
// origin 은 와일드카드(*/api/v1/...)로 둔다. API 주소가 환경변수로 바뀌어도(api.ts 의 G0 TODO)
// 핸들러를 고칠 필요가 없다.
//
// 실제 서버와 같은 순서로 막는다: CSRF(403, 변경 요청만) → 토큰(401) → 경로의 UUID 형식(400) → JSON 형식(400)
// → 입력 검증(422) → 학급 존재·소유(404). 다른 강사의 학급은 없는 학급과 똑같이 404 다.
// 이 모양은 contracts/api-v1.json API 명세로 백엔드와 같이 검사한다(src/test/contract/apiContract.test.ts).

export const CSRF = { headerName: "X-XSRF-TOKEN", token: "test-csrf-token" }

type TestInstructor = User & { password: string; accessToken: string }

// 테스트 강사. 비밀번호와 토큰은 실행마다 새로 만든다. 레포에 고정 자격증명을 두지 않는다(백엔드 TestFixtures 와 같은 규칙).
// 이메일은 예약 도메인(example.com)이다. 강사 B 는 소유권 검사용 "다른 강사"다.
export const instructors = {
  a: {
    userId: "00000000-0000-4000-8000-0000000000e1",
    role: "INSTRUCTOR",
    email: "instructor-a@example.com",
    name: "테스트 강사",
    orgName: null,
    status: "ACTIVE",
    password: `pw-${crypto.randomUUID()}`,
    accessToken: `token-a-${crypto.randomUUID()}`,
  },
  b: {
    userId: "00000000-0000-4000-8000-0000000000e2",
    role: "INSTRUCTOR",
    email: "instructor-b@example.com",
    name: "다른 강사",
    orgName: null,
    status: "ACTIVE",
    password: `pw-${crypto.randomUUID()}`,
    accessToken: `token-b-${crypto.randomUUID()}`,
  },
} satisfies Record<string, TestInstructor>

// 공통 픽스처 — 학급 A1·B1 은 강사 A, C1 은 강사 B 의 학급이다. 아동 A1-1·A1-2 는 동명이인이다(실명 중복 허용, 내부 childId 로만 구분).
// 실제 아동 데이터는 쓰지 않는다. 공개 레포다.
export const fixtures = {
  classroomA1: {
    classId: "00000000-0000-4000-8000-0000000000a1",
    instructorId: instructors.a.userId,
    name: "햇살반",
    status: "ACTIVE",
  },
  classroomB1: {
    classId: "00000000-0000-4000-8000-0000000000b1",
    instructorId: instructors.a.userId,
    name: "바람반",
    status: "ACTIVE",
  },
  classroomC1: {
    classId: "00000000-0000-4000-8000-0000000000c1",
    instructorId: instructors.b.userId,
    name: "구름반",
    status: "ACTIVE",
  },
  childA1_1: {
    childId: "00000000-0000-4000-8000-00000000a101",
    classId: "00000000-0000-4000-8000-0000000000a1",
    displayName: "김하늘",
    status: "ACTIVE",
  },
  childA1_2: {
    childId: "00000000-0000-4000-8000-00000000a102",
    classId: "00000000-0000-4000-8000-0000000000a1",
    displayName: "김하늘",
    status: "ACTIVE",
  },
  childB1_1: {
    childId: "00000000-0000-4000-8000-00000000b101",
    classId: "00000000-0000-4000-8000-0000000000b1",
    displayName: "이바다",
    status: "ACTIVE",
  },
  childC1_1: {
    childId: "00000000-0000-4000-8000-00000000c101",
    classId: "00000000-0000-4000-8000-0000000000c1",
    displayName: "박구름",
    status: "ACTIVE",
  },
} satisfies Record<string, Classroom | Child>

// 서버의 승인 문항 풀(V6 시드와 같은 유형 3개). 배정하면 이 순서로 활동에 붙는다(ADR 2026-10-03 D4).
const QUIZ_POOL: Omit<AssignedQuizItem, "activityQuizId" | "activityId" | "questionOrder">[] = [
  {
    itemId: "5eed0000-0000-4000-8000-000000000001",
    itemVersion: 1,
    quizType: "SELF_EMOTION_SITUATION",
    questionText: "생일에 친구가 선물을 줬어. 너는 어떤 기분이 들까?",
    imageUrl: null,
    choices: ["기쁨", "슬픔", "화남"],
  },
  {
    itemId: "5eed0000-0000-4000-8000-000000000002",
    itemVersion: 1,
    quizType: "OTHER_EMOTION_SITUATION",
    questionText: "친구가 좋아하던 풍선이 하늘로 날아가 버렸어. 친구는 지금 어떤 기분일까?",
    imageUrl: null,
    choices: ["기쁨", "슬픔", "놀람"],
  },
  {
    itemId: "5eed0000-0000-4000-8000-000000000003",
    itemVersion: 1,
    quizType: "OTHER_EMOTION_IMAGE",
    questionText: "이 친구는 지금 어떤 기분일까?",
    imageUrl: "/quiz-images/sample-anger.svg",
    choices: ["화남", "기쁨", "슬픔"],
  },
]

type StoredActivity = Activity & { idempotencyKey: string | null }

// 만든 학급·아동·계정·세션·목표·활동은 다음 요청에 반영된다(실제 서버처럼). 테스트가 끝나면 setup 이 resetMswData() 로 픽스처만 남긴다.
let accounts: TestInstructor[] = []
let sessions = new Map<string, string>()
let classrooms: Classroom[] = []
let children: Child[] = []
let goals: LearningGoal[] = []
let activities: StoredActivity[] = []
let activityQuizzes: AssignedQuizItem[] = []
let quizResults = new Map<string, QuizResult>()
// 비워 두면 승인 문항이 없는 서버처럼 배정이 422 QUIZ_ITEMS_UNAVAILABLE 이다.
let quizPool = QUIZ_POOL

export function resetMswData() {
  accounts = [instructors.a, instructors.b]
  sessions = new Map([
    [instructors.a.accessToken, instructors.a.userId],
    [instructors.b.accessToken, instructors.b.userId],
  ])
  classrooms = [fixtures.classroomA1, fixtures.classroomB1, fixtures.classroomC1]
  children = [fixtures.childA1_1, fixtures.childA1_2, fixtures.childB1_1, fixtures.childC1_1]
  goals = []
  activities = []
  activityQuizzes = []
  quizResults = new Map()
  quizPool = QUIZ_POOL
}

/** 승인 문항이 없는 서버로 바꾼다. 배정이 422 QUIZ_ITEMS_UNAVAILABLE 이 된다. */
export function emptyQuizPool() {
  quizPool = []
}

/**
 * 화면 테스트용으로 목표와 활동을 바로 넣는다(배정 API 를 거치지 않고). result 를 주면 아동이 퀴즈를 마친 것처럼 결과도 넣는다.
 * 문항은 서버처럼 문항 풀에서 붙인다.
 */
export function seedActivity({
  childId,
  goalTitle,
  situationType,
  status = "NOT_STARTED",
  result,
}: {
  childId: string
  goalTitle: string
  situationType?: string
  status?: ActivityStatus
  result?: Omit<QuizResult, "activityId">
}): StoredActivity {
  const goal = newGoal(childId, instructors.a.userId, { title: goalTitle, situationType })
  goals = [...goals, goal]
  const activity = newActivity(childId, goal.goalId, null)
  activity.status = status
  if (status !== "NOT_STARTED") activity.startedAt = activity.assignedAt
  activities = [...activities, activity]
  attachQuizzes(activity.activityId)
  if (result) quizResults.set(activity.activityId, { activityId: activity.activityId, ...result })
  return activity
}

function newGoal(childId: string, instructorId: string, input: { title: string; situationType?: string }): LearningGoal {
  return {
    goalId: crypto.randomUUID(),
    childId,
    instructorId,
    parentGoalId: null,
    title: input.title.trim(),
    situationType: input.situationType?.trim() || null,
    characters: [],
    requiredElements: [],
    forbiddenExpressions: [],
    // 서버는 정규화한 조건의 SHA-256 이다. 가짜 서버는 같은 내용이면 같은 값이면 충분하다.
    contentHash: `${input.title.trim()}|${input.situationType?.trim() ?? ""}`,
    createdAt: new Date().toISOString(),
  }
}

function newActivity(childId: string, goalId: string, idempotencyKey: string | null): StoredActivity {
  return {
    activityId: crypto.randomUUID(),
    childId,
    goalId,
    scenarioId: null,
    scenarioSource: null,
    initialSupportLevel: null,
    difficultyFallbackApplied: false,
    status: "NOT_STARTED",
    assignedAt: new Date().toISOString(),
    startedAt: null,
    completedAt: null,
    rewardIssuedAt: null,
    idempotencyKey,
  }
}

function attachQuizzes(activityId: string) {
  activityQuizzes = [
    ...activityQuizzes,
    ...quizPool.map((item, index) => ({
      ...item,
      activityQuizId: crypto.randomUUID(),
      activityId,
      questionOrder: index + 1,
    })),
  ]
}

/** 응답에 싣는 활동. 서버 ActivityResponse 와 같은 키만 남긴다(요청 키는 내부 값). */
function toActivity(stored: StoredActivity): Activity {
  const activity: Partial<StoredActivity> = { ...stored }
  delete activity.idempotencyKey
  return activity as Activity
}
resetMswData()

export function envelope<T>(data: T) {
  return { data, meta: { traceId: crypto.randomUUID() } }
}

// 백엔드 ApiErrorResponse.FieldError 와 같은 모양이다. 백엔드는 입력값(rejectedValue)을 담지 않는다(개인정보).
export type FieldError = { field: string; message: string }

export function apiError(
  status: number,
  code: string,
  message: string,
  path: string,
  fieldErrors: FieldError[] = [],
) {
  return HttpResponse.json(
    { error: { status, code, message, path, traceId: crypto.randomUUID(), fieldErrors } },
    { status },
  )
}

/** 응답에 싣는 강사 정보. 비밀번호·토큰은 빼고 백엔드 UserResponse 와 같은 키만 남긴다. */
export function toUser({ userId, role, email, name, orgName, status }: TestInstructor): User {
  return { userId, role, email, name, orgName, status }
}

// 이름 검증(@NotBlank @Size(max = 100) @NameText)을 흉내 낸다. 문구는 서버 로캘을 따르는 기본 메시지다.
// 서버처럼 공백뿐인 이름은 @NotBlank 와 @NameText 둘 다 실패한다. 보이지 않는 문자는 공백·구분 공백·서식·제어 문자다(TextRules).
const CONTROL_CHARACTER = /\p{Cc}/u
const VISIBLE_CHARACTER = /[^\p{White_Space}\p{Zs}\p{Cf}\p{Cc}]/u

function nameViolations(value: unknown): string[] {
  if (typeof value !== "string") return ["공백일 수 없습니다"]
  const violations: string[] = []
  if (!value.trim()) violations.push("공백일 수 없습니다")
  if (value.length > 100) violations.push("크기가 0에서 100 사이여야 합니다")
  if (CONTROL_CHARACTER.test(value)) violations.push("사용할 수 없는 문자가 포함되어 있습니다.")
  else if (!VISIBLE_CHARACTER.test(value)) violations.push("보이는 글자를 1자 이상 입력해 주세요.")
  return violations
}

// 가입 검증(SignupRequest). 이메일은 앞뒤 공백을 지운 뒤 본다.
function signupViolations(body: Record<string, unknown>): FieldError[] {
  const errors: FieldError[] = []
  const email = typeof body.email === "string" ? body.email.trim() : body.email
  if (typeof email !== "string" || email === "") errors.push({ field: "email", message: "공백일 수 없습니다" })
  else if (!/^[^\s@]+@[^\s@]+$/.test(email) || email.length > 254)
    errors.push({ field: "email", message: "올바른 형식의 이메일 주소여야 합니다" })
  const password = body.password
  if (typeof password !== "string" || !password.trim()) errors.push({ field: "password", message: "공백일 수 없습니다" })
  else if (password.length < 8 || password.length > 72)
    errors.push({ field: "password", message: "크기가 8에서 72 사이여야 합니다" })
  else if (new TextEncoder().encode(password).length > 72)
    errors.push({ field: "passwordWithinByteLimit", message: "비밀번호가 너무 깁니다." })
  for (const message of nameViolations(body.name)) errors.push({ field: "name", message })
  return errors
}

function validationFailed(path: string, fieldErrors: FieldError[]) {
  return apiError(422, "VALIDATION_FAILED", "요청 값이 올바르지 않습니다.", path, fieldErrors)
}

// Spring Security 의 CSRF 거부는 본문 없는 403 이다.
function hasValidCsrf(request: Request) {
  return request.headers.get(CSRF.headerName) === CSRF.token
}

type Session = { userId: string; token: string }

// 실제 서버(BearerTokenFilter·ApiAuthenticationEntryPoint)처럼 헤더가 없으면 401 AUTHENTICATION_REQUIRED,
// 헤더는 있는데 토큰이 틀리거나 로그아웃한 토큰이면 401 INVALID_TOKEN 이다.
function authenticate(request: Request): Session | Response {
  const path = new URL(request.url).pathname
  const header = request.headers.get("Authorization")
  if (header === null) return apiError(401, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다.", path)
  const token = header.startsWith("Bearer ") ? header.slice("Bearer ".length).trim() : ""
  const userId = token ? sessions.get(token) : undefined
  if (!userId) return apiError(401, "INVALID_TOKEN", "인증 정보가 만료되었거나 올바르지 않습니다.", path)
  return { userId, token }
}

// 백엔드는 경로의 classId 를 UUID 로 바꾸지 못하면 400 INVALID_REQUEST 다.
const UUID_FORMAT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function invalidRequest(path: string) {
  return apiError(400, "INVALID_REQUEST", "요청 형식이 올바르지 않습니다.", path)
}

function classroomNotFound(classId: string, path: string) {
  return apiError(404, "CLASSROOM_NOT_FOUND", `학급을 찾을 수 없습니다: ${classId}`, path)
}

// 로그인한 강사의 학급만 찾는다. 다른 강사의 학급도 없는 학급과 같다(존재 여부를 드러내지 않는다).
function ownedClassroom(classId: string, session: Session) {
  return classrooms.find((c) => c.classId === classId && c.instructorId === session.userId)
}

function childNotFound(path: string) {
  return apiError(404, "CHILD_NOT_FOUND", "아동을 찾을 수 없습니다.", path)
}

// 담당 학급의 아동만 찾는다. 다른 강사의 아동도 없는 아동과 같은 404 다(서버 ChildAccessScope).
function ownedChild(childId: string, session: Session) {
  const child = children.find((c) => c.childId === childId)
  return child && ownedClassroom(child.classId, session) ? child : undefined
}

// 아동 영구 삭제(ADR 2026-10-04). 서버처럼 목표·활동·배정 문항·결과까지 함께 지운다.
function removeChildren(childIds: string[]) {
  const removedActivities = new Set(activities.filter((a) => childIds.includes(a.childId)).map((a) => a.activityId))
  children = children.filter((c) => !childIds.includes(c.childId))
  goals = goals.filter((g) => !childIds.includes(g.childId))
  activities = activities.filter((a) => !removedActivities.has(a.activityId))
  activityQuizzes = activityQuizzes.filter((q) => !removedActivities.has(q.activityId))
  for (const activityId of removedActivities) quizResults.delete(activityId)
}

async function readJson(request: Request): Promise<Record<string, unknown> | null> {
  try {
    const body: unknown = await request.json()
    return typeof body === "object" && body !== null ? (body as Record<string, unknown>) : null
  } catch {
    return null
  }
}

function normalizeEmail(email: string) {
  return email.trim().toLowerCase()
}

export const handlers = [
  http.get("*/api/v1/csrf", () => HttpResponse.json(envelope(CSRF))),

  http.post("*/api/v1/users", async ({ request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const path = new URL(request.url).pathname
    const body = await readJson(request)
    if (!body) return invalidRequest(path)
    const violations = signupViolations(body)
    if (violations.length > 0) return validationFailed(path, violations)
    const email = normalizeEmail(String(body.email))
    if (accounts.some((account) => account.email === email)) {
      return apiError(409, "DUPLICATE_RESOURCE", "이미 가입된 이메일입니다.", path)
    }
    const account: TestInstructor = {
      userId: crypto.randomUUID(),
      role: "INSTRUCTOR",
      email,
      name: String(body.name).trim(),
      orgName: typeof body.orgName === "string" && body.orgName.trim() ? body.orgName.trim() : null,
      status: "ACTIVE",
      password: String(body.password),
      accessToken: "",
    }
    accounts = [...accounts, account]
    return HttpResponse.json(envelope(toUser(account)))
  }),

  http.post("*/api/v1/auth/sessions", async ({ request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const path = new URL(request.url).pathname
    const body = await readJson(request)
    if (!body) return invalidRequest(path)
    const email = typeof body.email === "string" ? normalizeEmail(body.email) : ""
    const account = accounts.find((a) => a.email === email && a.password === body.password)
    if (!account) {
      return apiError(401, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.", path)
    }
    const accessToken = `token-${crypto.randomUUID()}`
    sessions.set(accessToken, account.userId)
    const expiresAt = new Date(Date.now() + 12 * 60 * 60 * 1000).toISOString()
    return HttpResponse.json(envelope({ accessToken, expiresAt, user: toUser(account) }))
  }),

  http.get("*/api/v1/auth/session", ({ request }) => {
    const session = authenticate(request)
    if (session instanceof Response) return session
    const account = accounts.find((a) => a.userId === session.userId)!
    return HttpResponse.json(envelope({ user: toUser(account) }))
  }),

  http.delete("*/api/v1/auth/session", ({ request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const session = authenticate(request)
    if (session instanceof Response) return session
    sessions.delete(session.token)
    return new HttpResponse(null, { status: 204 })
  }),

  http.get("*/api/v1/classrooms", ({ request }) => {
    const session = authenticate(request)
    if (session instanceof Response) return session
    return HttpResponse.json(envelope(classrooms.filter((c) => c.instructorId === session.userId)))
  }),

  http.get("*/api/v1/classrooms/:classId", ({ params, request }) => {
    const session = authenticate(request)
    if (session instanceof Response) return session
    const classId = String(params.classId)
    const path = new URL(request.url).pathname
    if (!UUID_FORMAT.test(classId)) return invalidRequest(path)
    const classroom = ownedClassroom(classId, session)
    if (!classroom) return classroomNotFound(classId, path)
    return HttpResponse.json(envelope(classroom))
  }),

  http.delete("*/api/v1/classrooms/:classId", ({ params, request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const session = authenticate(request)
    if (session instanceof Response) return session
    const classId = String(params.classId)
    const path = new URL(request.url).pathname
    if (!UUID_FORMAT.test(classId)) return invalidRequest(path)
    if (!ownedClassroom(classId, session)) return classroomNotFound(classId, path)
    removeChildren(children.filter((c) => c.classId === classId).map((c) => c.childId))
    classrooms = classrooms.filter((c) => c.classId !== classId)
    return new HttpResponse(null, { status: 204 })
  }),

  http.get("*/api/v1/classrooms/:classId/children", ({ params, request }) => {
    const session = authenticate(request)
    if (session instanceof Response) return session
    const classId = String(params.classId)
    const path = new URL(request.url).pathname
    if (!UUID_FORMAT.test(classId)) return invalidRequest(path)
    if (!ownedClassroom(classId, session)) return classroomNotFound(classId, path)
    return HttpResponse.json(envelope(children.filter((c) => c.classId === classId)))
  }),

  http.post("*/api/v1/classrooms", async ({ request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const session = authenticate(request)
    if (session instanceof Response) return session
    const path = new URL(request.url).pathname
    const body = await readJson(request)
    if (!body) return invalidRequest(path)
    const violations = nameViolations(body.name)
    if (violations.length > 0) return validationFailed(path, violations.map((message) => ({ field: "name", message })))
    const classroom: Classroom = {
      classId: crypto.randomUUID(),
      instructorId: session.userId,
      name: String(body.name),
      status: "ACTIVE",
    }
    classrooms = [...classrooms, classroom]
    return HttpResponse.json(envelope(classroom))
  }),

  http.post("*/api/v1/classrooms/:classId/children", async ({ params, request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const session = authenticate(request)
    if (session instanceof Response) return session
    const classId = String(params.classId)
    const path = new URL(request.url).pathname
    if (!UUID_FORMAT.test(classId)) return invalidRequest(path)
    const body = await readJson(request)
    if (!body) return invalidRequest(path)
    const violations = nameViolations(body.displayName)
    if (violations.length > 0) {
      return validationFailed(path, violations.map((message) => ({ field: "displayName", message })))
    }
    if (!ownedClassroom(classId, session)) return classroomNotFound(classId, path)
    const child: Child = {
      childId: crypto.randomUUID(),
      classId,
      displayName: String(body.displayName),
      status: "ACTIVE",
    }
    children = [...children, child]
    return HttpResponse.json(envelope(child))
  }),

  http.get("*/api/v1/children/:childId", ({ params, request }) => {
    const session = authenticate(request)
    if (session instanceof Response) return session
    const childId = String(params.childId)
    const path = new URL(request.url).pathname
    if (!UUID_FORMAT.test(childId)) return invalidRequest(path)
    const child = ownedChild(childId, session)
    if (!child) return childNotFound(path)
    return HttpResponse.json(envelope(child))
  }),

  http.delete("*/api/v1/children/:childId", ({ params, request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const session = authenticate(request)
    if (session instanceof Response) return session
    const childId = String(params.childId)
    const path = new URL(request.url).pathname
    if (!UUID_FORMAT.test(childId)) return invalidRequest(path)
    if (!ownedChild(childId, session)) return childNotFound(path)
    removeChildren([childId])
    return new HttpResponse(null, { status: 204 })
  }),

  http.get("*/api/v1/children/:childId/learning-goals", ({ params, request }) => {
    const session = authenticate(request)
    if (session instanceof Response) return session
    const childId = String(params.childId)
    const path = new URL(request.url).pathname
    if (!UUID_FORMAT.test(childId)) return invalidRequest(path)
    if (!ownedChild(childId, session)) return childNotFound(path)
    return HttpResponse.json(envelope(goals.filter((g) => g.childId === childId).reverse()))
  }),

  http.post("*/api/v1/children/:childId/learning-goals", async ({ params, request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const session = authenticate(request)
    if (session instanceof Response) return session
    const childId = String(params.childId)
    const path = new URL(request.url).pathname
    if (!UUID_FORMAT.test(childId)) return invalidRequest(path)
    const body = await readJson(request)
    if (!body) return invalidRequest(path)
    // CreateLearningGoalRequest: @NotBlank @Size(max = 200) title, @Size(max = 30) situationType
    const title = body.title
    if (typeof title !== "string" || !title.trim()) {
      return validationFailed(path, [{ field: "title", message: "공백일 수 없습니다" }])
    }
    if (title.length > 200) {
      return validationFailed(path, [{ field: "title", message: "크기가 0에서 200 사이여야 합니다" }])
    }
    if (!ownedChild(childId, session)) return childNotFound(path)
    const goal = newGoal(childId, session.userId, {
      title,
      situationType: typeof body.situationType === "string" ? body.situationType : undefined,
    })
    goals = [...goals, goal]
    return HttpResponse.json(envelope(goal), { status: 201 })
  }),

  http.get("*/api/v1/learning-goals/:goalId", ({ params, request }) => {
    const session = authenticate(request)
    if (session instanceof Response) return session
    const path = new URL(request.url).pathname
    const goal = goals.find((g) => g.goalId === String(params.goalId))
    if (!goal) return apiError(404, "GOAL_NOT_FOUND", "학습 목표를 찾을 수 없습니다.", path)
    if (!ownedChild(goal.childId, session)) return childNotFound(path)
    return HttpResponse.json(envelope(goal))
  }),

  http.get("*/api/v1/children/:childId/activities", ({ params, request }) => {
    const session = authenticate(request)
    if (session instanceof Response) return session
    const childId = String(params.childId)
    const path = new URL(request.url).pathname
    if (!UUID_FORMAT.test(childId)) return invalidRequest(path)
    if (!ownedChild(childId, session)) return childNotFound(path)
    // 서버는 배정 시각 최신순이고, page·size(기본 0·20, 최대 100)로 나눠 준다.
    const url = new URL(request.url)
    const page = Number(url.searchParams.get("page") ?? 0)
    const size = Number(url.searchParams.get("size") ?? 20)
    if (!Number.isInteger(page) || !Number.isInteger(size) || page < 0 || size < 1 || size > 100) {
      return invalidRequest(path)
    }
    const list = activities.filter((a) => a.childId === childId).reverse()
    return HttpResponse.json(envelope(list.slice(page * size, (page + 1) * size).map(toActivity)))
  }),

  // 활동 배정(ADR 2026-10-03 D4). 서버처럼 문항을 자동으로 붙이고, 같은 요청 키는 처음 활동을 200 으로, 같은 목표의 시작 전 활동은 409 다.
  http.post("*/api/v1/activities", async ({ request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const session = authenticate(request)
    if (session instanceof Response) return session
    const path = new URL(request.url).pathname
    const key = request.headers.get("Idempotency-Key")
    if (!key || !UUID_FORMAT.test(key)) return invalidRequest(path)
    const body = await readJson(request)
    if (!body) return invalidRequest(path)
    const childId = String(body.childId ?? "")
    const goalId = String(body.goalId ?? "")
    if (!UUID_FORMAT.test(childId) || !UUID_FORMAT.test(goalId)) {
      return validationFailed(path, [
        ...(UUID_FORMAT.test(childId) ? [] : [{ field: "childId", message: "널이어서는 안됩니다" }]),
        ...(UUID_FORMAT.test(goalId) ? [] : [{ field: "goalId", message: "널이어서는 안됩니다" }]),
      ])
    }
    const child = ownedChild(childId, session)
    if (!child) return childNotFound(path)
    const prior = activities.find((a) => a.idempotencyKey === key)
    if (prior) {
      if (prior.childId !== childId || prior.goalId !== goalId) {
        return apiError(409, "IDEMPOTENCY_KEY_REUSED", "다른 배정 요청에 사용한 키입니다.", path)
      }
      return HttpResponse.json(envelope(toActivity(prior)))
    }
    if (child.status !== "ACTIVE") {
      return apiError(409, "CHILD_NOT_ACTIVE", "참여 중인 아동에게만 활동을 배정할 수 있습니다.", path)
    }
    if (!goals.some((g) => g.goalId === goalId && g.childId === childId)) {
      return apiError(404, "GOAL_NOT_FOUND", "학습 목표를 찾을 수 없습니다.", path)
    }
    // 같은 목표는 ID 가 아니라 내용(contentHash)으로 판단한다(ADR 2026-10-03 D6).
    const goal = goals.find((g) => g.goalId === goalId)!
    const sameGoals = new Set(
      goals.filter((g) => g.childId === childId && g.contentHash === goal.contentHash).map((g) => g.goalId),
    )
    if (activities.some((a) => a.childId === childId && sameGoals.has(a.goalId) && a.status === "NOT_STARTED")) {
      return apiError(409, "DUPLICATE_ASSIGNMENT", "같은 목표로 시작하지 않은 활동이 이미 있습니다.", path)
    }
    if (quizPool.length === 0) {
      return apiError(422, "QUIZ_ITEMS_UNAVAILABLE", "배정할 수 있는 승인된 퀴즈 문항이 없습니다.", path)
    }
    const activity = newActivity(childId, goalId, key)
    activities = [...activities, activity]
    attachQuizzes(activity.activityId)
    return HttpResponse.json(envelope(toActivity(activity)), {
      status: 201,
      headers: { Location: `/api/v1/activities/${activity.activityId}` },
    })
  }),

  http.get("*/api/v1/activities/:activityId", ({ params, request }) => {
    const session = authenticate(request)
    if (session instanceof Response) return session
    const activityId = String(params.activityId)
    const path = new URL(request.url).pathname
    if (!UUID_FORMAT.test(activityId)) return invalidRequest(path)
    const activity = activities.find((a) => a.activityId === activityId)
    if (!activity) return apiError(404, "ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다.", path)
    if (!ownedChild(activity.childId, session)) return childNotFound(path)
    return HttpResponse.json(
      envelope({
        activity: toActivity(activity),
        quizItems: activityQuizzes.filter((q) => q.activityId === activityId),
        sessionSummary: null,
        learningRecord: null,
      }),
    )
  }),

  http.get("*/api/v1/activities/:activityId/quiz-result", ({ params, request }) => {
    const session = authenticate(request)
    if (session instanceof Response) return session
    const activityId = String(params.activityId)
    const path = new URL(request.url).pathname
    if (!UUID_FORMAT.test(activityId)) return invalidRequest(path)
    const activity = activities.find((a) => a.activityId === activityId)
    if (!activity) return apiError(404, "ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다.", path)
    if (!ownedChild(activity.childId, session)) return childNotFound(path)
    const result = quizResults.get(activityId)
    if (!result) return apiError(409, "QUIZ_NOT_COMPLETED", "퀴즈가 완료되지 않았습니다.", path)
    return HttpResponse.json(envelope(result))
  }),
]
