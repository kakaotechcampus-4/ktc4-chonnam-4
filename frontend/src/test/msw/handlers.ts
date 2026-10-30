import { http, HttpResponse } from "msw"
import type {
  AccessCode,
  Activity,
  ActivityStatus,
  ActivityType,
  AssignedQuizItem,
  Child,
  Classroom,
  LearningGoal,
  QuizResult,
  QuizType,
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

type PoolItem = Omit<AssignedQuizItem, "activityQuizId" | "activityId" | "questionOrder">

// 독립 퀴즈 10문항(내 감정 10 / 상대 감정 상황 7 + 이미지 3)을 채울 만큼 가짜 서버에만 더 둔 승인 문항.
// 실제 서버의 시드는 이보다 적어 로컬에서는 독립 퀴즈 배정이 422 다.
function fillerItems(quizType: QuizType, count: number): PoolItem[] {
  return Array.from({ length: count }, (_, index) => ({
    itemId: crypto.randomUUID(),
    itemVersion: 1,
    quizType,
    questionText: `채움 문항 ${index + 1}. 어떤 기분일까?`,
    imageUrl: quizType === "OTHER_EMOTION_IMAGE" ? "/quiz-images/sample-anger.svg" : null,
    choices: ["기쁨", "슬픔", "화남"],
  }))
}

const FULL_POOL: PoolItem[] = [
  ...QUIZ_POOL,
  ...fillerItems("SELF_EMOTION_SITUATION", 9),
  ...fillerItems("OTHER_EMOTION_SITUATION", 6),
  ...fillerItems("OTHER_EMOTION_IMAGE", 2),
]

// 서버 QuizAssignmentService.COMPOSITION 과 같은 유형별 문항 구성. 위에서부터 그 순서대로 붙는다.
const COMPOSITION: Record<ActivityType, QuizType[]> = {
  FIRST_COMBINED: ["SELF_EMOTION_SITUATION", "OTHER_EMOTION_SITUATION", "OTHER_EMOTION_IMAGE"],
  SELF_EMOTION_QUIZ: Array<QuizType>(10).fill("SELF_EMOTION_SITUATION"),
  OTHER_EMOTION_QUIZ: [
    ...Array<QuizType>(7).fill("OTHER_EMOTION_SITUATION"),
    ...Array<QuizType>(3).fill("OTHER_EMOTION_IMAGE"),
  ],
  ROLEPLAY: [],
}

/**
 * 서버처럼 유형별 구성대로 문항 풀에서 고른다. 가짜 서버는 무작위 대신 풀 순서대로, 같은 문항은 두 번 쓰지 않는다.
 * 칸을 하나라도 못 채우면 빈 목록이다(서버와 같다).
 */
function pickItems(activityType: ActivityType): PoolItem[] {
  const used = new Set<string>()
  const picks: PoolItem[] = []
  for (const type of COMPOSITION[activityType]) {
    const item = quizPool.find((candidate) => candidate.quizType === type && !used.has(candidate.itemId))
    if (!item) return []
    used.add(item.itemId)
    picks.push(item)
  }
  return picks
}

type StoredActivity = Activity & { idempotencyKey: string | null }

// 아동 접근 코드(API-IAM-08). valid 는 아동 A1-1(김하늘)의 코드, expired 는 24시간이 지난 코드다. 그 밖의 4자리는 틀린 코드다.
// 앞자리 0이 문자열로 유지되는지 보려고 0으로 시작하는 값을 쓴다.
export const childAccessCodes = { valid: "0427", expired: "0999" }

// 만든 학급·아동·계정·세션·목표·활동은 다음 요청에 반영된다(실제 서버처럼). 테스트가 끝나면 setup 이 resetMswData() 로 픽스처만 남긴다.
let accounts: TestInstructor[] = []
let sessions = new Map<string, string>()
let classrooms: Classroom[] = []
let children: Child[] = []
let goals: LearningGoal[] = []
let activities: StoredActivity[] = []
let activityQuizzes: AssignedQuizItem[] = []
let quizResults = new Map<string, QuizResult>()
// 발급 요청 키 → 그 요청이 받은 코드. 같은 키로 다시 보내면 원래 코드·만료를 돌려준다.
let issuedCodes = new Map<string, { childId: string; code: AccessCode }>()
// 비워 두면 승인 문항이 없는 서버처럼 배정이 422 QUIZ_ITEMS_UNAVAILABLE 이다.
let quizPool = FULL_POOL
// 브라우저의 아동 세션 쿠키(JSESSIONID). 입장하면 그 아동 ID, 사용 종료하면 null 이다.
let childSession: string | null = null

export function resetMswData() {
  childSession = null
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
  issuedCodes = new Map()
  quizPool = FULL_POOL
}

/** 아동이 이미 입장해 서버에 아동 세션이 있는 상태로 만든다(사용 종료 요청이 204 가 된다). */
export function startChildSession(childId: string = fixtures.childA1_1.childId) {
  childSession = childId
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
  category,
  activityType = "FIRST_COMBINED",
  status = "NOT_STARTED",
  result,
}: {
  childId: string
  goalTitle?: string
  category?: string
  activityType?: ActivityType
  status?: ActivityStatus
  result?: Omit<QuizResult, "activityId">
}): StoredActivity {
  let goalId: string | null = null
  if (activityType === "FIRST_COMBINED" || activityType === "ROLEPLAY") {
    const goal = newGoal(childId, instructors.a.userId, { title: goalTitle ?? "학습 목표", category })
    goals = [...goals, goal]
    goalId = goal.goalId
  }
  const activity = newActivity(childId, activityType, goalId, null)
  activity.status = status
  if (status !== "NOT_STARTED") activity.startedAt = activity.assignedAt
  if (status === "COMPLETED") activity.completedAt = activity.assignedAt
  activities = [...activities, activity]
  attachQuizzes(activity.activityId, activityType)
  if (result) quizResults.set(activity.activityId, { activityId: activity.activityId, ...result })
  return activity
}

/** 화면 테스트에서 그 아동에게 저장된 목표를 본다. */
export function storedGoals(childId: string): LearningGoal[] {
  return goals.filter((g) => g.childId === childId)
}

type GoalInput = { title: string; category?: string; parentGoalId?: string | null }

function newGoal(childId: string, instructorId: string, input: GoalInput): LearningGoal {
  return {
    goalId: crypto.randomUUID(),
    childId,
    instructorId,
    parentGoalId: input.parentGoalId ?? null,
    title: input.title.trim(),
    situationType: null,
    category: input.category?.trim() || null,
    characters: [],
    requiredElements: [],
    forbiddenExpressions: [],
    // 서버는 정규화한 조건의 SHA-256 이다. 가짜 서버는 같은 내용이면 같은 값이면 충분하다.
    contentHash: `${input.title.trim()}|${input.category?.trim() ?? ""}`,
    createdAt: new Date().toISOString(),
  }
}

/** CreateLearningGoalRequest 검사: @NotBlank @Size(max = 200) title. prefix 는 배정 요청 안의 goal 처럼 감싼 경우의 필드 앞부분이다. */
function goalFieldErrors(body: Record<string, unknown>, prefix = ""): FieldError[] {
  const title = body.title
  if (typeof title !== "string" || !title.trim()) return [{ field: `${prefix}title`, message: "공백일 수 없습니다" }]
  if (title.length > 200) return [{ field: `${prefix}title`, message: "크기가 0에서 200 사이여야 합니다" }]
  return []
}

function goalInput(body: Record<string, unknown>): GoalInput {
  return {
    title: String(body.title),
    category: typeof body.category === "string" ? body.category : undefined,
    parentGoalId: typeof body.parentGoalId === "string" ? body.parentGoalId : null,
  }
}

function newActivity(
  childId: string,
  activityType: ActivityType,
  goalId: string | null,
  idempotencyKey: string | null,
): StoredActivity {
  return {
    activityId: crypto.randomUUID(),
    childId,
    goalId,
    activityType,
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

function attachQuizzes(activityId: string, activityType: ActivityType) {
  activityQuizzes = [
    ...activityQuizzes,
    ...pickItems(activityType).map((item, index) => ({
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
  if (body.termsAgreed !== true) errors.push({ field: "termsAgreed", message: "이용약관에 동의해야 가입할 수 있습니다." })
  if (body.privacyAgreed !== true)
    errors.push({ field: "privacyAgreed", message: "개인정보 처리방침에 동의해야 가입할 수 있습니다." })
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

  // 아동 입장(API-IAM-08): 공개 + CSRF. 다른 코드로 들어오면 이전 아동 세션을 바꾼다.
  http.post("*/api/v1/child-access-sessions", async ({ request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const path = new URL(request.url).pathname
    const body = await readJson(request)
    if (!body) return invalidRequest(path)
    if (typeof body.accessCode !== "string" || !/^\d{4}$/.test(body.accessCode)) {
      return validationFailed(path, [{ field: "accessCode", message: "숫자 4자리여야 합니다" }])
    }
    if (body.accessCode === childAccessCodes.expired) {
      return apiError(401, "ACCESS_CODE_EXPIRED", "입장 코드가 만료되었습니다.", path)
    }
    if (body.accessCode !== childAccessCodes.valid) {
      return apiError(401, "INVALID_ACCESS_CODE", "입장 코드가 올바르지 않습니다.", path)
    }
    const { childId, displayName } = fixtures.childA1_1
    childSession = childId
    return HttpResponse.json(envelope({ childId, displayName }), { status: 201 })
  }),

  // 아동 사용 종료(API-IAM-09): 아동 세션 + CSRF. 세션이 없으면 서버처럼 401 이다.
  http.delete("*/api/v1/child-access-sessions/current", ({ request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    if (childSession === null) {
      return apiError(401, "AUTHENTICATION_REQUIRED", "아동 입장이 필요합니다.", new URL(request.url).pathname)
    }
    childSession = null
    return new HttpResponse(null, { status: 204 })
  }),

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

  // 즉시 탈퇴: 현재 비밀번호가 맞으면 그 강사의 학급·아동·기록, 모든 세션, 계정을 지운다.
  http.post("*/api/v1/users/me/withdrawal", async ({ request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const session = authenticate(request)
    if (session instanceof Response) return session
    const body = (await request.json()) as { password?: unknown }
    const account = accounts.find((a) => a.userId === session.userId)
    if (account && account.password !== body.password)
      return apiError(403, "PASSWORD_MISMATCH", "비밀번호가 올바르지 않습니다.", "/api/v1/users/me/withdrawal")
    const owned = classrooms.filter((c) => c.instructorId === session.userId).map((c) => c.classId)
    removeChildren(children.filter((c) => owned.includes(c.classId)).map((c) => c.childId))
    classrooms = classrooms.filter((c) => c.instructorId !== session.userId)
    for (const [token, userId] of sessions) if (userId === session.userId) sessions.delete(token)
    accounts = accounts.filter((a) => a.userId !== session.userId)
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

  // 입장 코드 발급(API-IAM-07). 실제 서버처럼 원문을 다시 조회하는 API 는 없다.
  http.post("*/api/v1/children/:childId/access-codes", ({ params, request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const session = authenticate(request)
    if (session instanceof Response) return session
    const childId = String(params.childId)
    const path = new URL(request.url).pathname
    const key = request.headers.get("Idempotency-Key")
    if (!UUID_FORMAT.test(childId) || !key || !UUID_FORMAT.test(key)) return invalidRequest(path)
    const child = ownedChild(childId, session)
    if (!child) return childNotFound(path)
    const prior = issuedCodes.get(key)
    if (prior) {
      if (prior.childId !== childId) {
        return apiError(409, "IDEMPOTENCY_KEY_REUSED", "다른 발급 요청에 사용한 키입니다.", path)
      }
      return HttpResponse.json(envelope(prior.code), { status: 201 })
    }
    if (child.status !== "ACTIVE") {
      return apiError(409, "CHILD_NOT_ACTIVE", "활성 상태의 아동만 코드를 발급할 수 있습니다.", path)
    }
    const code: AccessCode = {
      accessCode: String(Math.floor(Math.random() * 10000)).padStart(4, "0"),
      expiresAt: new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString(),
    }
    issuedCodes.set(key, { childId, code })
    return HttpResponse.json(envelope(code), { status: 201 })
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
    const errors = goalFieldErrors(body)
    if (errors.length > 0) return validationFailed(path, errors)
    if (!ownedChild(childId, session)) return childNotFound(path)
    const goal = newGoal(childId, session.userId, goalInput(body))
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

  // 활동 배정. 서버처럼 목표를 함께 저장하고 유형별 구성대로 문항을 붙인다. 같은 요청 키는
  // 처음 활동을 200 으로 돌려준다. 실패하면 목표도 저장하지 않는다.
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
    // 서버처럼 유형을 비우면 최초 결합이다.
    const activityType = (body.activityType ?? "FIRST_COMBINED") as ActivityType
    if (!(activityType in COMPOSITION)) return invalidRequest(path)
    const goalBody = typeof body.goal === "object" && body.goal !== null ? (body.goal as Record<string, unknown>) : null
    const errors: FieldError[] = [
      ...(UUID_FORMAT.test(childId) ? [] : [{ field: "childId", message: "널이어서는 안됩니다" }]),
      ...(goalBody ? goalFieldErrors(goalBody, "goal.") : []),
    ]
    if (errors.length > 0) return validationFailed(path, errors)
    const child = ownedChild(childId, session)
    if (!child) return childNotFound(path)
    const requiresGoal = activityType === "FIRST_COMBINED" || activityType === "ROLEPLAY"
    if (requiresGoal && !goalBody) {
      return apiError(422, "GOAL_REQUIRED", "이 활동에는 학습 목표가 필요합니다.", path)
    }
    if (!requiresGoal && goalBody) {
      return apiError(422, "GOAL_NOT_ALLOWED", "표정 퀴즈 활동에는 학습 목표를 붙이지 않습니다.", path)
    }
    const input = goalBody ? goalInput(goalBody) : null
    if (input?.parentGoalId && !goals.some((g) => g.goalId === input.parentGoalId && g.childId === childId)) {
      return apiError(404, "GOAL_NOT_FOUND", "학습 목표를 찾을 수 없습니다.", path)
    }
    // 아직 저장하지 않는다. 확인을 모두 통과한 뒤에 저장해야 실패한 배정이 목표를 남기지 않는다.
    const goal = input ? newGoal(childId, session.userId, input) : null
    const prior = activities.find((a) => a.idempotencyKey === key)
    if (prior) {
      const priorGoal = goals.find((g) => g.goalId === prior.goalId)
      if (
        prior.childId !== childId ||
        prior.activityType !== activityType ||
        priorGoal?.contentHash !== goal?.contentHash
      ) {
        return apiError(409, "IDEMPOTENCY_KEY_REUSED", "다른 배정 요청에 사용한 키입니다.", path)
      }
      return HttpResponse.json(envelope(toActivity(prior)))
    }
    if (child.status !== "ACTIVE") {
      return apiError(409, "CHILD_NOT_ACTIVE", "참여 중인 아동에게만 활동을 배정할 수 있습니다.", path)
    }
    if (activityType === "ROLEPLAY") {
      return apiError(422, "ACTIVITY_TYPE_NOT_AVAILABLE", "역할극 활동은 아직 배정할 수 없습니다.", path)
    }
    const childActivities = activities.filter((a) => a.childId === childId)
    if (activityType === "FIRST_COMBINED") {
      // 최초 결합은 아동마다 한 번이다.
      if (childActivities.some((a) => a.activityType === "FIRST_COMBINED")) {
        return apiError(409, "FIRST_ACTIVITY_EXISTS", "최초 활동은 아동마다 한 번만 배정할 수 있습니다.", path)
      }
    } else {
      // 독립 퀴즈는 최초 결합을 마친 뒤에만, (아동, 유형) 기준 시작 전 하나만.
      if (!childActivities.some((a) => a.activityType === "FIRST_COMBINED" && a.status === "COMPLETED")) {
        return apiError(409, "FIRST_ACTIVITY_NOT_COMPLETED", "최초 활동을 마친 뒤에 배정할 수 있습니다.", path)
      }
      if (childActivities.some((a) => a.activityType === activityType && a.status === "NOT_STARTED")) {
        return apiError(409, "DUPLICATE_ASSIGNMENT", "같은 활동이 시작 전 상태로 이미 있습니다.", path)
      }
    }
    if (pickItems(activityType).length === 0) {
      return apiError(422, "QUIZ_ITEMS_UNAVAILABLE", "배정할 수 있는 승인된 퀴즈 문항이 모자랍니다.", path)
    }
    if (goal) goals = [...goals, goal]
    const activity = newActivity(childId, activityType, goal?.goalId ?? null, key)
    activities = [...activities, activity]
    attachQuizzes(activity.activityId, activityType)
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
