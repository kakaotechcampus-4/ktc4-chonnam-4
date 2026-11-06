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
import { contradictingForbidden, OTHER_CHARACTER_ID, type GoalCharacter } from "@/features/instructor/lib/goalConditions"

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

// 문항의 정답·힌트. 서버처럼 아동에게 주는 문항 응답에는 싣지 않고 판정·힌트 발급에만 쓴다.
type ItemSecret = { answer: string; hints: { type: string; text: string }[] }
const itemSecrets = new Map<string, ItemSecret>()

function withSecret<T extends { itemId: string }>(item: T, secret: ItemSecret): T {
  itemSecrets.set(item.itemId, secret)
  return item
}

// 서버의 승인 문항 풀(V6 시드와 같은 유형 3개). 배정하면 이 순서로 활동에 붙는다(ADR 2026-10-03 D4).
const QUIZ_POOL: Omit<AssignedQuizItem, "activityQuizId" | "activityId" | "questionOrder">[] = [
  withSecret(
    {
      itemId: "5eed0000-0000-4000-8000-000000000001",
      itemVersion: 1,
      quizType: "SELF_EMOTION_SITUATION",
      questionText: "생일에 친구가 선물을 줬어. 너는 어떤 기분이 들까?",
      imageUrl: null,
      choices: ["기쁨", "슬픔", "화남"],
    },
    {
      answer: "기쁨",
      hints: [
        { type: "OBSERVABLE_BODY_CUE", text: "선물을 받으면 웃음이 나요." },
        { type: "CHOICE_REDUCTION", text: "기쁨과 슬픔 중에서 골라 봐요." },
      ],
    },
  ),
  withSecret(
    {
      itemId: "5eed0000-0000-4000-8000-000000000002",
      itemVersion: 1,
      quizType: "OTHER_EMOTION_SITUATION",
      questionText: "친구가 좋아하던 풍선이 하늘로 날아가 버렸어. 친구는 지금 어떤 기분일까?",
      imageUrl: null,
      choices: ["기쁨", "슬픔", "놀람"],
    },
    {
      answer: "슬픔",
      hints: [
        { type: "OBSERVABLE_FACE_CUE", text: "좋아하던 것을 잃으면 눈물이 날 수 있어요." },
        { type: "CHOICE_REDUCTION", text: "기쁨과 슬픔 중에서 골라 봐요." },
      ],
    },
  ),
  withSecret(
    {
      itemId: "5eed0000-0000-4000-8000-000000000003",
      itemVersion: 1,
      quizType: "OTHER_EMOTION_IMAGE",
      questionText: "이 친구는 지금 어떤 기분일까?",
      imageUrl: "/quiz-images/sample-anger.svg",
      choices: ["화남", "기쁨", "슬픔"],
    },
    {
      answer: "화남",
      hints: [
        { type: "OBSERVABLE_FACE_CUE", text: "눈썹이 아래로 모이고 입이 꾹 다물려 있어요." },
        { type: "CHOICE_REDUCTION", text: "화남과 기쁨 중에서 골라 봐요." },
      ],
    },
  ),
]

type PoolItem = Omit<AssignedQuizItem, "activityQuizId" | "activityId" | "questionOrder">

// 독립 퀴즈 10문항(내 감정 10 / 상대 감정 상황 7 + 이미지 3)을 채울 만큼 가짜 서버에만 더 둔 승인 문항.
// 실제 서버의 시드는 이보다 적어 로컬에서는 독립 퀴즈 배정이 422 다.
function fillerItems(quizType: QuizType, count: number): PoolItem[] {
  return Array.from({ length: count }, (_, index) =>
    withSecret(
      {
        itemId: crypto.randomUUID(),
        itemVersion: 1,
        quizType,
        questionText: `채움 문항 ${index + 1}. 어떤 기분일까?`,
        imageUrl: quizType === "OTHER_EMOTION_IMAGE" ? "/quiz-images/sample-anger.svg" : null,
        choices: ["기쁨", "슬픔", "화남"],
      },
      { answer: "기쁨", hints: [{ type: "OBSERVABLE_FACE_CUE", text: "입꼬리를 살펴봐요." }] },
    ),
  )
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
// 배정 문항(activityQuizId)별 답안과 발급한 힌트(API-QUIZ-04·06).
let quizAttempts = new Map<string, StoredAttempt>()
// 활동별 역할극 세션(API-RPL-01·05).
let roleplaySessions = new Map<string, StoredRoleplaySession>()

type StoredAttempt = {
  attemptId: string
  activityQuizId: string
  firstResponse: string | null
  firstResponseCorrect: boolean
  expressionMatchResult: string | null
  cameraModelVersion: string | null
  technicalFailureReason: string | null
  finalResponse: string | null
  resolvedAfterHint: boolean
  itemCorrect: boolean
  excludedFromScoring: boolean
  status: "DRAFT" | "FINALIZED"
  respondedAt: string
  hints: { key: string; hintId: string; hintOrder: number; type: string; text: string }[]
}

type RoleplayTurnBody = {
  status: string
  action: string
  messages: { speakerRole: "DIALOGUE" | "HINT"; text: string; speech: { available: false; url: null } }[]
  acceptedInput: string | null
  checkpointVersion: number | null
  replayed: boolean
}

type StoredRoleplaySession = {
  sessionId: string
  activityId: string
  scenarioId: string
  status: "IN_PROGRESS" | "PAUSED" | "COMPLETED"
  openingId: string
  turns: { key: string; text: string; turnId: string; response: RoleplayTurnBody }[]
  /** 음성 실패 횟수(API-RPL-04). 3번부터 SUGGEST_TEXT_KEEP_VOICE 다. 정상 입력으로 줄지 않는다. */
  voiceFailures: number
}

/** 역할극 첫 질문(승인 시나리오 opening_prompts 대신). 대본은 두 번째 답에서 목표를 달성해 활동이 완료된다. */
export const ROLEPLAY_OPENING = "친구가 넘어져서 울고 있어. 몸은 어떤 느낌일까?"
export const ROLEPLAY_SCRIPT = [
  "친구 눈썹이 아래로 축 처져 있어. 어떤 마음일 때 이런 표정이 될까?",
  "맞아, 넘어지면 아프고 속상하지. 친구 마음을 잘 알아줬어!",
]
export const ROLEPLAY_REINPUT_NOTICE = "다시 한 번만 말해줄래?"

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
  quizAttempts = new Map()
  roleplaySessions = new Map()
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
  activityId,
  childId,
  goalTitle,
  category,
  activityType = "FIRST_COMBINED",
  status = "NOT_STARTED",
  result,
  quizFinished = false,
  scenarioConfirmed,
}: {
  /** 테스트가 주소에 미리 넣어 둘 고정 ID. 없으면 새로 만든다. */
  activityId?: string
  childId: string
  goalTitle?: string
  category?: string
  activityType?: ActivityType
  status?: ActivityStatus
  result?: Omit<QuizResult, "activityId">
  /**
   * 아동이 배정 문항을 맞혀 확정한 상태로 둔다. true 면 모두(최초 결합이면 역할극을 바로 시작할 수 있다),
   * 숫자면 앞에서부터 그 수만큼이다. 활동 완료는 실제 마지막 답에서 일어나므로 여기서는 하지 않는다.
   */
  quizFinished?: boolean | number
  /** 최초 결합에서 선생님이 시나리오를 확정해 둔다(API-SCN-04). 없으면 퀴즈를 모두 마친 경우(quizFinished: true)에만 확정한다. */
  scenarioConfirmed?: boolean
}): StoredActivity {
  let goalId: string | null = null
  if (activityType === "FIRST_COMBINED" || activityType === "ROLEPLAY") {
    const goal = newGoal(childId, instructors.a.userId, {
      title: goalTitle ?? "학습 목표",
      category,
      characters: [],
      requiredElements: [],
      forbiddenExpressions: [],
    })
    goals = [...goals, goal]
    goalId = goal.goalId
  }
  const activity = newActivity(childId, activityType, goalId, null)
  if (activityId) activity.activityId = activityId
  activity.status = status
  if (status !== "NOT_STARTED") activity.startedAt = activity.assignedAt
  if (status === "COMPLETED") activity.completedAt = activity.assignedAt
  activities = [...activities, activity]
  attachQuizzes(activity.activityId, activityType)
  if (quizFinished) finishQuizzes(activity.activityId, quizFinished === true ? undefined : quizFinished)
  if (scenarioConfirmed ?? quizFinished === true) confirmScenario(activity.activityId)
  if (result) quizResults.set(activity.activityId, { activityId: activity.activityId, ...result })
  return activity
}

/** 화면 테스트용: 활동의 배정 문항을 앞에서부터 count 개(없으면 모두) 맞혀 확정한 상태로 둔다. */
export function finishQuizzes(activityId: string, count?: number) {
  const quizzes = storedQuizItems(activityId)
  for (const quiz of quizzes.slice(0, count ?? quizzes.length)) {
    const answer = itemSecrets.get(quiz.itemId)?.answer ?? quiz.choices[0]
    const attempt = newAttempt(quiz.activityQuizId, answer, true, {}, false)
    finalizeAttempt(attempt, answer, true, false)
    quizAttempts.set(quiz.activityQuizId, attempt)
  }
}

function newAttempt(
  activityQuizId: string,
  firstResponse: string | null,
  firstResponseCorrect: boolean,
  camera: { expressionMatchResult?: string | null; cameraModelVersion?: string | null; technicalFailureReason?: string | null },
  excludedFromScoring: boolean,
): StoredAttempt {
  return {
    attemptId: crypto.randomUUID(),
    activityQuizId,
    firstResponse,
    firstResponseCorrect,
    expressionMatchResult: camera.expressionMatchResult ?? null,
    cameraModelVersion: camera.cameraModelVersion ?? null,
    technicalFailureReason: camera.technicalFailureReason ?? null,
    finalResponse: null,
    resolvedAfterHint: false,
    itemCorrect: firstResponseCorrect,
    excludedFromScoring,
    status: "DRAFT",
    respondedAt: new Date().toISOString(),
    hints: [],
  }
}

function finalizeAttempt(attempt: StoredAttempt, finalResponse: string | null, correct: boolean, resolvedAfterHint: boolean) {
  attempt.finalResponse = finalResponse
  attempt.itemCorrect = correct
  attempt.resolvedAfterHint = resolvedAfterHint
  attempt.status = "FINALIZED"
}

/** 응답에 싣는 답안. 서버 QuizAttemptResponse 와 같은 키만 남긴다. */
function toAttemptBody(attempt: StoredAttempt) {
  return {
    attemptId: attempt.attemptId,
    activityQuizId: attempt.activityQuizId,
    firstResponse: attempt.firstResponse,
    firstResponseCorrect: attempt.firstResponseCorrect,
    expressionMatchResult: attempt.expressionMatchResult,
    cameraModelVersion: attempt.cameraModelVersion,
    usedHintTypes: attempt.hints.map((hint) => hint.type),
    finalResponse: attempt.finalResponse,
    resolvedAfterHint: attempt.resolvedAfterHint,
    itemCorrect: attempt.itemCorrect,
    excludedFromScoring: attempt.excludedFromScoring,
    status: attempt.status,
    respondedAt: attempt.respondedAt,
  }
}

/** 선생님이 시나리오 검토를 마치고 확정한 것처럼 활동에 시나리오를 붙인다(API-SCN-04 PUT 의 결과). */
export function confirmScenario(activityId: string) {
  const activity = activities.find((a) => a.activityId === activityId)
  if (activity && activity.scenarioId === null) {
    activity.scenarioId = crypto.randomUUID()
    activity.scenarioSource = "REUSE"
  }
}

type NextAction = "QUIZ" | "WAIT_FOR_SCENARIO" | "ROLEPLAY" | "NONE" | "BLOCKED"

/** 서버 ActivityReadinessPolicy 처럼 저장된 근거로 다음 학습 동작을 계산한다(API-ACT-02·03 nextAction). */
function nextActionOf(activity: StoredActivity): NextAction {
  if (activity.status === "COMPLETED") return "NONE"
  if (roleplaySessions.has(activity.activityId)) return "ROLEPLAY"
  const quizzes = activityQuizzes.filter((q) => q.activityId === activity.activityId)
  if (quizzes.some((q) => quizAttempts.get(q.activityQuizId)?.status !== "FINALIZED")) return "QUIZ"
  if (activity.activityType === "FIRST_COMBINED" || activity.activityType === "ROLEPLAY") {
    return activity.scenarioId ? "ROLEPLAY" : "WAIT_FOR_SCENARIO"
  }
  return "NONE"
}

/** 목록·상세 응답의 활동. 생성 응답(ACT-01)에는 준비 상태를 싣지 않는다. */
function toListedActivity(stored: StoredActivity) {
  return { ...toActivity(stored), nextAction: nextActionOf(stored), blockedReason: null }
}

/** 화면 테스트에서 그 배정 문항에 저장된 답안을 본다. */
export function storedQuizAttempt(activityQuizId: string) {
  return quizAttempts.get(activityQuizId)
}

/** 화면 테스트에서 활동에 붙은 배정 문항을 본다. */
export function storedQuizItems(activityId: string) {
  return activityQuizzes.filter((q) => q.activityId === activityId).sort((a, b) => a.questionOrder - b.questionOrder)
}

/** 화면 테스트에서 활동의 현재 상태를 본다. */
export function storedActivity(activityId: string) {
  return activities.find((a) => a.activityId === activityId)
}

/** 화면 테스트에서 활동의 역할극 세션을 본다. */
export function storedRoleplaySession(activityId: string) {
  return roleplaySessions.get(activityId)
}

/** 서버 ActivityCompletionService 처럼 완료·스탬프 지급 시각을 한 번만 남긴다. */
function completeActivity(activity: StoredActivity) {
  if (activity.status === "COMPLETED") return
  const now = new Date().toISOString()
  activity.status = "COMPLETED"
  activity.completedAt = now
  activity.rewardIssuedAt = now
}

/** 화면 테스트에서 그 아동에게 저장된 목표를 본다. */
export function storedGoals(childId: string): LearningGoal[] {
  return goals.filter((g) => g.childId === childId)
}

type GoalInput = {
  title: string
  category?: string
  parentGoalId?: string | null
  situationType?: string
  characters: GoalCharacter[]
  requiredElements: string[]
  forbiddenExpressions: string[]
}

// 서버 GoalConditions 처럼 앞뒤·안쪽 여러 칸 공백을 정리하고 같은 값은 한 번만 남긴다(입력 순서 유지).
function tidy(value: string) {
  return value.trim().replace(/\s+/g, " ")
}

function distinct(values: string[]) {
  return [...new Set(values.map(tidy))]
}

/** 서버 GoalConditions 의 등장인물 저장: 정리, 같은 {id, role} 은 하나만, 빈 id 는 OTHER, 같은 id 는 role 글자 순서로 _2, _3 을 붙인다. */
function storedCharacters(characters: GoalCharacter[]): { id: string; role: string }[] {
  const seen = new Set<string>()
  const tidied = characters
    .map((c) => ({ id: c.id?.trim() || OTHER_CHARACTER_ID, role: tidy(c.role) }))
    .filter((c) => {
      const key = `${c.id}|${c.role}`
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })
  const taken = new Set(tidied.map((c) => c.id))
  const renamed = new Map<(typeof tidied)[number], string>()
  for (const id of new Set(tidied.map((c) => c.id))) {
    // 서버(Java String 비교)와 같게 UTF-16 코드 단위 순서로 정렬한다.
    const sameId = tidied.filter((c) => c.id === id).sort((a, b) => (a.role < b.role ? -1 : a.role > b.role ? 1 : 0))
    let number = 2
    for (const c of sameId.slice(1)) {
      while (taken.has(`${id}_${number}`)) number++
      taken.add(`${id}_${number}`)
      renamed.set(c, `${id}_${number}`)
    }
  }
  return tidied.map((c) => ({ id: renamed.get(c) ?? c.id, role: c.role }))
}

function newGoal(childId: string, instructorId: string, input: GoalInput): LearningGoal {
  const characters = storedCharacters(input.characters)
  return {
    goalId: crypto.randomUUID(),
    childId,
    instructorId,
    parentGoalId: input.parentGoalId ?? null,
    title: tidy(input.title),
    situationType: input.situationType ? tidy(input.situationType) || null : null,
    category: input.category?.trim() || null,
    characters,
    requiredElements: distinct(input.requiredElements),
    forbiddenExpressions: distinct(input.forbiddenExpressions),
    // 서버는 정규화한 조건(목록은 정렬)의 SHA-256 이다. 가짜 서버는 같은 내용이면 같은 값이면 충분하다.
    contentHash: JSON.stringify([
      tidy(input.title),
      input.category?.trim() ?? "",
      input.situationType ? tidy(input.situationType) : "",
      distinct(input.requiredElements).sort(),
      distinct(input.forbiddenExpressions).sort(),
      characters.map((c) => `${c.role}|${c.id}`).sort(),
    ]),
    createdAt: new Date().toISOString(),
  }
}

/**
 * CreateLearningGoalRequest 검사: @NotBlank @Size(max = 200) title, @Size(max = 200) situationType,
 * 등장인물 role @NotBlank, id 는 비우거나 영문 대문자 코드.
 * prefix 는 배정 요청 안의 goal 처럼 감싼 경우의 필드 앞부분이다.
 */
function goalFieldErrors(body: Record<string, unknown>, prefix = ""): FieldError[] {
  const title = body.title
  if (typeof title !== "string" || !title.trim()) return [{ field: `${prefix}title`, message: "공백일 수 없습니다" }]
  if (title.length > 200) return [{ field: `${prefix}title`, message: "크기가 0에서 200 사이여야 합니다" }]
  if (typeof body.situationType === "string" && body.situationType.length > 200) {
    return [{ field: `${prefix}situationType`, message: "크기가 0에서 200 사이여야 합니다" }]
  }
  const characters = Array.isArray(body.characters) ? (body.characters as Partial<GoalCharacter>[]) : []
  const noRole = characters.findIndex((c) => typeof c?.role !== "string" || !c.role.trim())
  if (noRole >= 0) return [{ field: `${prefix}characters[${noRole}].role`, message: "공백일 수 없습니다" }]
  const badId = characters.findIndex((c) => c.id != null && !/^([A-Z][A-Z0-9_]*)?$/.test(c.id))
  if (badId >= 0) {
    return [{ field: `${prefix}characters[${badId}].id`, message: "영문 대문자로 시작하고 영문 대문자·숫자·밑줄만 쓸 수 있습니다." }]
  }
  return []
}

/** 서버 GoalConditions 의 모순 검사(422 INVALID_GOAL_CONDITIONS). 요청 검증(422 VALIDATION_FAILED)을 통과한 뒤에 본다. */
function goalConditionErrors(input: GoalInput, prefix = ""): FieldError[] {
  const learner: FieldError[] = input.characters.flatMap((c, i) =>
    c.id === "LEARNER"
      ? [{ field: `${prefix}characters[${i}].id`, message: "LEARNER 는 역할극의 아이 자리라 등장인물로 둘 수 없습니다." }]
      : []
  )
  if (learner.length > 0) return learner
  const roles = input.characters.map((c) => c.role)
  return contradictingForbidden(input.requiredElements, input.forbiddenExpressions, roles).map((index) => ({
    field: `${prefix}forbiddenExpressions[${index}]`,
    message: "필수 요소나 등장인물에 들어 있는 표현은 피할 표현으로 둘 수 없습니다.",
  }))
}

function invalidGoalConditions(path: string, fieldErrors: FieldError[]) {
  return apiError(422, "INVALID_GOAL_CONDITIONS", "목표 조건이 올바르지 않습니다.", path, fieldErrors)
}

function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : []
}

function goalInput(body: Record<string, unknown>): GoalInput {
  return {
    title: String(body.title),
    category: typeof body.category === "string" ? body.category : undefined,
    parentGoalId: typeof body.parentGoalId === "string" ? body.parentGoalId : null,
    situationType: typeof body.situationType === "string" ? body.situationType : undefined,
    characters: Array.isArray(body.characters) ? (body.characters as GoalCharacter[]) : [],
    requiredElements: stringList(body.requiredElements),
    forbiddenExpressions: stringList(body.forbiddenExpressions),
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

// 아동 전용 경로(퀴즈 답·힌트, 역할극). 서버 SecurityConfig 처럼 아동 세션이 없으면 401, 강사 토큰이면 403 이다.
function requireChild(request: Request): string | Response {
  const path = new URL(request.url).pathname
  if (request.headers.get("Authorization") !== null) {
    return apiError(403, "ACCESS_DENIED", "접근 권한이 없습니다.", path)
  }
  if (childSession === null) return apiError(401, "AUTHENTICATION_REQUIRED", "아동 입장이 필요합니다.", path)
  return childSession
}

// 아동·강사 둘 다 읽는 활동 경로(문항 목록·완료 상태). 본인 아동·담당 강사가 아니면 없는 활동과 같은 404 다.
function readableActivity(request: Request, activityId: string): StoredActivity | Response {
  const path = new URL(request.url).pathname
  const notFound = () => apiError(404, "ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다.", path)
  const asChild = request.headers.get("Authorization") === null && childSession !== null
  if (!asChild) {
    const session = authenticate(request)
    if (session instanceof Response) return session
    if (!UUID_FORMAT.test(activityId)) return invalidRequest(path)
    const activity = activities.find((a) => a.activityId === activityId)
    return activity && ownedChild(activity.childId, session) ? activity : notFound()
  }
  if (!UUID_FORMAT.test(activityId)) return invalidRequest(path)
  const activity = activities.find((a) => a.activityId === activityId)
  return activity && activity.childId === childSession ? activity : notFound()
}

function quizItemNotFound(path: string) {
  return apiError(404, "ACTIVITY_QUIZ_NOT_FOUND", "배정 문항을 찾을 수 없습니다.", path)
}

function invalidQuizResult(path: string) {
  return apiError(422, "INVALID_QUIZ_RESULT", "퀴즈 응답이 올바르지 않습니다.", path)
}

// 본인 활동의 배정 문항만 찾는다. 다른 아동의 문항도 없는 문항과 같은 404 다.
function ownedQuizItem(activityQuizId: string, childId: string) {
  const quiz = activityQuizzes.find((q) => q.activityQuizId === activityQuizId)
  const activity = quiz && activities.find((a) => a.activityId === quiz.activityId)
  return quiz && activity && activity.childId === childId ? { quiz, activity } : undefined
}

/** 서버 QuizJudgement: 보기 안의 답이어야 하고, 내 감정 문항은 표정 판정이 MATCH 여야 맞다. */
function judge(quiz: AssignedQuizItem, response: string, expressionMatchResult: string | null) {
  const answer = itemSecrets.get(quiz.itemId)?.answer
  return response === answer && (quiz.quizType !== "SELF_EMOTION_SITUATION" || expressionMatchResult === "MATCH")
}

/** 서버 snapshotIfComplete: 모든 문항을 확정하면 결과를 남기고, 독립 퀴즈는 그 요청에서 활동을 완료한다. */
function snapshotIfComplete(activity: StoredActivity) {
  const quizzes = activityQuizzes.filter((q) => q.activityId === activity.activityId)
  if (!quizzes.every((q) => quizAttempts.get(q.activityQuizId)?.status === "FINALIZED")) return
  const counted = quizzes.map((q) => quizAttempts.get(q.activityQuizId)!).filter((a) => !a.excludedFromScoring)
  const correct = counted.filter((a) => a.firstResponseCorrect).length
  quizResults.set(activity.activityId, {
    activityId: activity.activityId,
    validQuestionCount: counted.length,
    correctQuestionCount: correct,
    overallAccuracy: counted.length ? Number((correct / counted.length).toFixed(6)) : null,
    totalHintCount: counted.reduce((sum, a) => sum + a.hints.length, 0),
    resolvedAfterHintCount: counted.filter((a) => a.resolvedAfterHint).length,
    scenarioLevel: null,
    initialSupportLevel: null,
    difficultyFallbackApplied: false,
    initialDifficultyUsed: activity.activityType === "FIRST_COMBINED",
    policyVersion: "test",
  })
  if (activity.activityType !== "FIRST_COMBINED") completeActivity(activity)
}

function roleplayMessage(speakerRole: "DIALOGUE" | "HINT", text: string) {
  return { speakerRole, text, speech: { available: false as const, url: null } }
}

function roleplayStartBody(session: StoredRoleplaySession, activity: StoredActivity) {
  const canContinue = session.status === "IN_PROGRESS" && activity.status === "IN_PROGRESS"
  const lastTurn = session.turns.filter((t) => t.response.status === "DELIVERED").at(-1)
  const lastDialogue = lastTurn?.response.messages.filter((m) => m.speakerRole === "DIALOGUE").at(-1)
  const messages = !canContinue
    ? []
    : lastTurn && lastDialogue
      ? [{ messageId: lastTurn.turnId, purpose: "RESUME", ...roleplayMessage("DIALOGUE", lastDialogue.text) }]
      : [{ messageId: session.openingId, purpose: "QUESTION", ...roleplayMessage("DIALOGUE", ROLEPLAY_OPENING) }]
  return {
    sessionId: session.sessionId,
    activityId: session.activityId,
    scenarioId: session.scenarioId,
    scenarioVersion: 1,
    status: session.status,
    activityStatus: activity.status,
    lastDeliveredTurnId: lastTurn?.turnId ?? null,
    checkpointVersion: session.turns.length,
    delivery: { messages },
  }
}

function roleplayReinput(action: "REQUEST_NEW_INPUT" | "SUGGEST_TEXT_KEEP_VOICE"): RoleplayTurnBody {
  return {
    status: "REINPUT_REQUIRED",
    action,
    messages: [roleplayMessage("HINT", ROLEPLAY_REINPUT_NOTICE)],
    acceptedInput: null,
    checkpointVersion: null,
    replayed: false,
  }
}

/** 대본(ROLEPLAY_SCRIPT)대로 답하고 마지막 답에서 활동을 완료한다(서버 RoleplayCompletionService). */
function deliverRoleplayTurn(session: StoredRoleplaySession, activity: StoredActivity, text: string): RoleplayTurnBody {
  const delivered = session.turns.filter((turn) => turn.response.status === "DELIVERED").length
  const reply = ROLEPLAY_SCRIPT[Math.min(delivered, ROLEPLAY_SCRIPT.length - 1)]
  if (delivered + 1 >= ROLEPLAY_SCRIPT.length) {
    completeActivity(activity)
    session.status = "COMPLETED"
  }
  return {
    status: "DELIVERED",
    action: "NONE",
    messages: [roleplayMessage("DIALOGUE", reply)],
    acceptedInput: text,
    checkpointVersion: session.turns.length + 1,
    replayed: false,
  }
}

/** 음성 실패가 3번 쌓이면 서버가 글자 입력을 권한다(API-RPL-04). */
const VOICE_FAILURES_BEFORE_TEXT = 3

export const handlers = [
  http.get("*/api/v1/csrf", () => HttpResponse.json(envelope(CSRF))),

  // 아동 입장(API-IAM-08): 공개 + CSRF. 다른 코드로 들어오면 이전 아동 세션을 바꾼다.
  http.post("*/api/v1/child-access-sessions", async ({ request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const path = new URL(request.url).pathname
    const body = await readJson(request)
    if (!body) return invalidRequest(path)
    // 서버 DTO 는 @Pattern 만 있어 누락/null 은 검증을 지나 서비스에서 401 INVALID_ACCESS_CODE 다(명세 API-IAM-08).
    if (body.accessCode === undefined || body.accessCode === null) {
      return apiError(401, "INVALID_ACCESS_CODE", "입장 코드가 올바르지 않습니다.", path)
    }
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

  // 서버처럼 본문 검증(422)이 소유권 확인(404)보다 먼저다. 픽스처 객체는 고치지 않고 바꾼 사본으로 갈아 끼운다.
  http.patch("*/api/v1/classrooms/:classId", async ({ params, request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const session = authenticate(request)
    if (session instanceof Response) return session
    const classId = String(params.classId)
    const path = new URL(request.url).pathname
    if (!UUID_FORMAT.test(classId)) return invalidRequest(path)
    const body = await readJson(request)
    if (!body) return invalidRequest(path)
    const violations = nameViolations(body.name)
    if (violations.length > 0) return validationFailed(path, violations.map((message) => ({ field: "name", message })))
    const classroom = ownedClassroom(classId, session)
    if (!classroom) return classroomNotFound(classId, path)
    const renamed: Classroom = { ...classroom, name: String(body.name) }
    classrooms = classrooms.map((c) => (c.classId === classId ? renamed : c))
    return HttpResponse.json(envelope(renamed))
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

  http.patch("*/api/v1/children/:childId", async ({ params, request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const session = authenticate(request)
    if (session instanceof Response) return session
    const childId = String(params.childId)
    const path = new URL(request.url).pathname
    if (!UUID_FORMAT.test(childId)) return invalidRequest(path)
    const body = await readJson(request)
    if (!body) return invalidRequest(path)
    const violations = nameViolations(body.displayName)
    if (violations.length > 0) {
      return validationFailed(path, violations.map((message) => ({ field: "displayName", message })))
    }
    const child = ownedChild(childId, session)
    if (!child) return childNotFound(path)
    const renamed: Child = { ...child, displayName: String(body.displayName) }
    children = children.map((c) => (c.childId === childId ? renamed : c))
    return HttpResponse.json(envelope(renamed))
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
    const input = goalInput(body)
    const conditionErrors = goalConditionErrors(input)
    if (conditionErrors.length > 0) return invalidGoalConditions(path, conditionErrors)
    const goal = newGoal(childId, session.userId, input)
    goals = [...goals, goal]
    return HttpResponse.json(envelope(goal), { status: 201 })
  }),

  http.get("*/api/v1/learning-goals/:goalId", ({ params, request }) => {
    const session = authenticate(request)
    if (session instanceof Response) return session
    const path = new URL(request.url).pathname
    const goalId = String(params.goalId)
    if (!UUID_FORMAT.test(goalId)) return invalidRequest(path)
    // 담당 범위 밖 목표도 없는 목표와 같은 404 GOAL_NOT_FOUND 다(명세 API-GOAL-03, 서버 LearningGoalService.get).
    const goal = goals.find((g) => g.goalId === goalId)
    if (!goal || !ownedChild(goal.childId, session)) {
      return apiError(404, "GOAL_NOT_FOUND", "학습 목표를 찾을 수 없습니다.", path)
    }
    return HttpResponse.json(envelope(goal))
  }),

  // 강사(Bearer)와 입장한 아동(세션 쿠키) 둘 다 부른다. 아동은 자기 목록만 보고, 다른 아동 ID 는 서버처럼 404 로 숨긴다.
  http.get("*/api/v1/children/:childId/activities", ({ params, request }) => {
    const childId = String(params.childId)
    const path = new URL(request.url).pathname
    const asChild = request.headers.get("Authorization") === null && childSession !== null
    if (!asChild) {
      const session = authenticate(request)
      if (session instanceof Response) return session
      if (!UUID_FORMAT.test(childId)) return invalidRequest(path)
      if (!ownedChild(childId, session)) return childNotFound(path)
    } else {
      if (!UUID_FORMAT.test(childId)) return invalidRequest(path)
      if (childId !== childSession) return childNotFound(path)
    }
    // 서버는 배정 시각 최신순이고, page·size(기본 0·20, 최대 100)로 나눠 준다.
    const url = new URL(request.url)
    const page = Number(url.searchParams.get("page") ?? 0)
    const size = Number(url.searchParams.get("size") ?? 20)
    if (!Number.isInteger(page) || !Number.isInteger(size) || page < 0 || size < 1 || size > 100) {
      return invalidRequest(path)
    }
    const list = activities.filter((a) => a.childId === childId).reverse()
    return HttpResponse.json(envelope(list.slice(page * size, (page + 1) * size).map(toListedActivity)))
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
    // 서버처럼 이전 목표 확인 뒤에 조건을 본다.
    const conditionErrors = input ? goalConditionErrors(input, "goal.") : []
    if (conditionErrors.length > 0) return invalidGoalConditions(path, conditionErrors)
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
        activity: toListedActivity(activity),
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

  // 활동에 고정된 문항(API-QUIZ-03). 정답·힌트 원문은 싣지 않는다.
  http.get("*/api/v1/activities/:activityId/quiz-items", ({ params, request }) => {
    const activity = readableActivity(request, String(params.activityId))
    if (activity instanceof Response) return activity
    return HttpResponse.json(envelope(storedQuizItems(activity.activityId)))
  }),

  // 활동 완료 상태(API-ACT-04). 읽기만 하고 완료를 실행하지 않는다.
  http.get("*/api/v1/activities/:activityId/status", ({ params, request }) => {
    const activity = readableActivity(request, String(params.activityId))
    if (activity instanceof Response) return activity
    return HttpResponse.json(envelope({ status: activity.status }))
  }),

  // 답안 조회(API-QUIZ-05). 아직 답을 내지 않았으면 404 ATTEMPT_NOT_FOUND 다.
  http.get("*/api/v1/activity-quiz-items/:activityQuizId/attempt", ({ params, request }) => {
    const childId = requireChild(request)
    if (childId instanceof Response) return childId
    const path = new URL(request.url).pathname
    const activityQuizId = String(params.activityQuizId)
    if (!UUID_FORMAT.test(activityQuizId)) return invalidRequest(path)
    if (!ownedQuizItem(activityQuizId, childId)) return quizItemNotFound(path)
    const attempt = quizAttempts.get(activityQuizId)
    if (!attempt) return apiError(404, "ATTEMPT_NOT_FOUND", "응답을 찾을 수 없습니다.", path)
    return HttpResponse.json(envelope(toAttemptBody(attempt)))
  }),

  // 답안 제출(API-QUIZ-04). 서버 QuizAttemptService.put 과 같은 순서로 판정·확정한다.
  http.put("*/api/v1/activity-quiz-items/:activityQuizId/attempt", async ({ params, request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const childId = requireChild(request)
    if (childId instanceof Response) return childId
    const path = new URL(request.url).pathname
    const activityQuizId = String(params.activityQuizId)
    if (!UUID_FORMAT.test(activityQuizId)) return invalidRequest(path)
    const body = await readJson(request)
    if (!body) return invalidRequest(path)
    const owned = ownedQuizItem(activityQuizId, childId)
    if (!owned) return quizItemNotFound(path)
    const { quiz, activity } = owned
    const str = (value: unknown) => (typeof value === "string" ? value : null)
    const firstResponse = str(body.firstResponse)
    const finalResponse = str(body.finalResponse)
    const expressionMatchResult = str(body.expressionMatchResult)
    const cameraModelVersion = str(body.cameraModelVersion)
    const technicalFailureReason = str(body.technicalFailureReason)
    // validateCameraFields
    if (quiz.quizType === "SELF_EMOTION_SITUATION") {
      if (expressionMatchResult && !cameraModelVersion?.trim()) return invalidQuizResult(path)
      if (technicalFailureReason === "IMAGE_LOAD_FAILED") return invalidQuizResult(path)
    } else {
      if (expressionMatchResult || cameraModelVersion) return invalidQuizResult(path)
      if (technicalFailureReason && !(quiz.quizType === "OTHER_EMOTION_IMAGE" && technicalFailureReason === "IMAGE_LOAD_FAILED")) {
        return invalidQuizResult(path)
      }
    }
    const existing = quizAttempts.get(activityQuizId)
    if (existing) {
      if (
        existing.firstResponse !== firstResponse ||
        existing.expressionMatchResult !== expressionMatchResult ||
        existing.cameraModelVersion !== cameraModelVersion ||
        existing.technicalFailureReason !== technicalFailureReason
      ) {
        return apiError(409, "FIRST_RESPONSE_IMMUTABLE", "첫 응답은 변경할 수 없습니다.", path)
      }
      if (existing.status === "FINALIZED") {
        const same =
          existing.finalResponse === finalResponse ||
          (finalResponse === null && existing.finalResponse === existing.firstResponse)
        if (same) return HttpResponse.json(envelope(toAttemptBody(existing)))
        return apiError(409, "ATTEMPT_FINALIZED", "확정된 응답은 변경할 수 없습니다.", path)
      }
      if (finalResponse === null) return HttpResponse.json(envelope(toAttemptBody(existing)))
      if (existing.hints.length === 0 && finalResponse !== firstResponse) return invalidQuizResult(path)
      if (!quiz.choices.includes(finalResponse)) return invalidQuizResult(path)
      const correct = judge(quiz, finalResponse, expressionMatchResult)
      finalizeAttempt(existing, finalResponse, correct, correct && !existing.firstResponseCorrect && existing.hints.length > 0)
      snapshotIfComplete(activity)
      return HttpResponse.json(envelope(toAttemptBody(existing)))
    }
    const excluded = technicalFailureReason !== null || expressionMatchResult === "NOT_ANALYZABLE"
    if (!excluded) {
      if (!firstResponse?.trim() || !quiz.choices.includes(firstResponse)) return invalidQuizResult(path)
      if (quiz.quizType === "SELF_EMOTION_SITUATION" && !expressionMatchResult) return invalidQuizResult(path)
    }
    const correct = !excluded && judge(quiz, firstResponse ?? "", expressionMatchResult)
    const attempt = newAttempt(
      activityQuizId,
      firstResponse,
      correct,
      { expressionMatchResult, cameraModelVersion, technicalFailureReason },
      excluded,
    )
    if (activity.status === "NOT_STARTED") {
      activity.status = "IN_PROGRESS"
      activity.startedAt = new Date().toISOString()
    }
    if (correct || excluded || finalResponse !== null) {
      if (finalResponse !== null && finalResponse !== firstResponse) return invalidQuizResult(path)
      quizAttempts.set(activityQuizId, attempt)
      finalizeAttempt(attempt, firstResponse, correct, false)
      snapshotIfComplete(activity)
    } else {
      quizAttempts.set(activityQuizId, attempt)
    }
    return HttpResponse.json(envelope(toAttemptBody(attempt)), { status: 201 })
  }),

  // 힌트 발급(API-QUIZ-06). 첫 답이 틀린 DRAFT 답안에만, 최대 2개다. 같은 키는 같은 힌트를 201 로 돌려준다.
  http.post("*/api/v1/activity-quiz-items/:activityQuizId/hints", ({ params, request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const childId = requireChild(request)
    if (childId instanceof Response) return childId
    const path = new URL(request.url).pathname
    const activityQuizId = String(params.activityQuizId)
    const key = request.headers.get("Idempotency-Key")
    if (!UUID_FORMAT.test(activityQuizId) || !key || !UUID_FORMAT.test(key)) return invalidRequest(path)
    const owned = ownedQuizItem(activityQuizId, childId)
    if (!owned) return quizItemNotFound(path)
    const attempt = quizAttempts.get(activityQuizId)
    if (!attempt) return apiError(404, "ATTEMPT_NOT_FOUND", "첫 응답을 먼저 제출해야 합니다.", path)
    const toHintBody = (hint: StoredAttempt["hints"][number]) => ({
      hintId: hint.hintId,
      hintOrder: hint.hintOrder,
      type: hint.type,
      text: hint.text,
    })
    for (const stored of quizAttempts.values()) {
      const previous = stored.hints.find((hint) => hint.key === key)
      if (!previous) continue
      if (stored !== attempt) return apiError(409, "IDEMPOTENCY_KEY_REUSED", "다른 힌트 요청에 사용한 키입니다.", path)
      return HttpResponse.json(envelope(toHintBody(previous)), { status: 201 })
    }
    if (attempt.status === "FINALIZED" || attempt.excludedFromScoring || attempt.firstResponseCorrect) {
      return apiError(409, "ATTEMPT_FINALIZED", "이 응답에는 힌트를 발급할 수 없습니다.", path)
    }
    const next = attempt.hints.length + 1
    const spec = itemSecrets.get(owned.quiz.itemId)?.hints[next - 1]
    if (next > 2 || !spec) return apiError(409, "NO_MORE_HINTS", "제공 가능한 힌트가 없습니다.", path)
    const hint = { key, hintId: crypto.randomUUID(), hintOrder: next, ...spec }
    attempt.hints.push(hint)
    return HttpResponse.json(envelope(toHintBody(hint)), { status: 201 })
  }),

  // 역할극 시작·이어 하기(API-RPL-01). 활동에 세션이 있으면 새 키여도 그 세션을 200 으로 돌려준다.
  http.post("*/api/v1/activities/:activityId/roleplay-sessions", async ({ params, request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const childId = requireChild(request)
    if (childId instanceof Response) return childId
    const path = new URL(request.url).pathname
    const activityId = String(params.activityId)
    const key = request.headers.get("Idempotency-Key")
    if (!UUID_FORMAT.test(activityId) || !key || !UUID_FORMAT.test(key)) return invalidRequest(path)
    if ((await request.text()) !== "") return invalidRequest(path)
    const activity = activities.find((a) => a.activityId === activityId && a.childId === childId)
    if (!activity) return apiError(404, "ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다.", path)
    const existing = roleplaySessions.get(activityId)
    if (existing) return HttpResponse.json(envelope(roleplayStartBody(existing, activity)))
    // 신규 시작은 최초 결합에서 퀴즈를 모두 확정해 최초 난이도가 정해진 뒤에만 된다.
    const quizzes = activityQuizzes.filter((q) => q.activityId === activityId)
    const quizDone = quizzes.length > 0 && quizzes.every((q) => quizAttempts.get(q.activityQuizId)?.status === "FINALIZED")
    // 신규 시작은 지금 최초 결합만 지원한다(서버 RoleplayStartService).
    if (activity.activityType !== "FIRST_COMBINED") {
      return apiError(409, "INITIAL_ROLEPLAY_NOT_READY", "역할극을 시작할 준비가 되지 않았습니다.", path)
    }
    if (!quizDone) {
      return apiError(409, "INITIAL_DIFFICULTY_NOT_READY", "최초 퀴즈 결과가 아직 준비되지 않았습니다.", path)
    }
    // 퀴즈를 마쳐도 시나리오가 자동으로 붙지 않는다. 선생님이 확정해야 시작된다(S4-JIN-01).
    if (!activity.scenarioId) {
      return apiError(409, "INITIAL_ROLEPLAY_NOT_READY", "최초 역할극 시작에 필요한 저장 근거가 준비되지 않았습니다.", path)
    }
    if (activity.status === "NOT_STARTED") {
      activity.status = "IN_PROGRESS"
      activity.startedAt = new Date().toISOString()
    }
    const session: StoredRoleplaySession = {
      sessionId: crypto.randomUUID(),
      activityId,
      scenarioId: crypto.randomUUID(),
      status: "IN_PROGRESS",
      openingId: crypto.randomUUID(),
      turns: [], voiceFailures: 0,
    }
    roleplaySessions.set(activityId, session)
    return HttpResponse.json(envelope(roleplayStartBody(session, activity)), { status: 201 })
  }),

  // 글자로 답하기(API-RPL-05). 대본(ROLEPLAY_SCRIPT)대로 답하고 마지막 답에서 활동을 완료한다.
  // 같은 키·같은 글자는 저장된 결과를 replayed=true 로 돌려주고, 같은 키에 다른 글자는 409 다.
  http.post("*/api/v1/roleplay-sessions/:sessionId/text-inputs", async ({ params, request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const childId = requireChild(request)
    if (childId instanceof Response) return childId
    const path = new URL(request.url).pathname
    const sessionId = String(params.sessionId)
    const key = request.headers.get("Idempotency-Key")
    if (!UUID_FORMAT.test(sessionId) || !key || !UUID_FORMAT.test(key)) return invalidRequest(path)
    const body = await readJson(request)
    if (!body) return invalidRequest(path)
    if (typeof body.text !== "string") return validationFailed(path, [{ field: "text", message: "널이어서는 안됩니다" }])
    const session = [...roleplaySessions.values()].find((s) => s.sessionId === sessionId)
    const activity = session && activities.find((a) => a.activityId === session.activityId && a.childId === childId)
    if (!session || !activity) return apiError(404, "ROLEPLAY_NOT_FOUND", "역할극을 찾을 수 없습니다.", path)
    const previous = session.turns.find((turn) => turn.key === key)
    if (previous) {
      if (previous.text !== body.text) return apiError(409, "ROLEPLAY_CONFLICT", "다른 입력에 사용한 키입니다.", path)
      return HttpResponse.json(envelope({ ...previous.response, acceptedInput: null, replayed: true }))
    }
    if (session.status !== "IN_PROGRESS") {
      return apiError(409, "ROLEPLAY_CONFLICT", "진행 중인 역할극이 아닙니다.", path)
    }
    const text = body.text.trim().replace(/\s+/g, " ")
    const response = text ? deliverRoleplayTurn(session, activity, text) : roleplayReinput("REQUEST_NEW_INPUT")
    session.turns.push({ key, text: body.text, turnId: crypto.randomUUID(), response })
    return HttpResponse.json(envelope(response))
  }),

  // 말로 답하기(API-RPL-04). 가짜 STT 는 음성 파일의 바이트를 UTF-8 글자로 읽어 그대로 알아들은 말로 쓴다(테스트가 정한다).
  // 비었거나 공백뿐이면 잘 안 들린 것으로 보고 재입력이고, 3번째 실패부터 SUGGEST_TEXT_KEEP_VOICE 다.
  // 같은 키·같은 바이트는 저장된 결과를 replayed=true 로 돌려주고, 같은 키에 다른 바이트는 409 다.
  http.post("*/api/v1/roleplay-sessions/:sessionId/voice-inputs", async ({ params, request }) => {
    if (!hasValidCsrf(request)) return new HttpResponse(null, { status: 403 })
    const childId = requireChild(request)
    if (childId instanceof Response) return childId
    const path = new URL(request.url).pathname
    const sessionId = String(params.sessionId)
    const key = request.headers.get("Idempotency-Key")
    if (!UUID_FORMAT.test(sessionId) || !key || !UUID_FORMAT.test(key)) return invalidRequest(path)
    if (!request.headers.get("Content-Type")?.startsWith("multipart/form-data")) {
      return apiError(415, "UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 형식입니다.", path)
    }
    const form = await request.formData().catch(() => null)
    const audio = form?.get("audio")
    if (!audio || typeof audio === "string") return invalidRequest(path)
    const bytes = await audio.text()
    const session = [...roleplaySessions.values()].find((s) => s.sessionId === sessionId)
    const activity = session && activities.find((a) => a.activityId === session.activityId && a.childId === childId)
    if (!session || !activity) return apiError(404, "ROLEPLAY_NOT_FOUND", "역할극을 찾을 수 없습니다.", path)
    const fingerprint = `voice:${audio.type}:${bytes}`
    const previous = session.turns.find((turn) => turn.key === key)
    if (previous) {
      if (previous.text !== fingerprint) return apiError(409, "ROLEPLAY_CONFLICT", "다른 입력에 사용한 키입니다.", path)
      const action =
        previous.response.status === "REINPUT_REQUIRED" && session.voiceFailures >= VOICE_FAILURES_BEFORE_TEXT
          ? "SUGGEST_TEXT_KEEP_VOICE"
          : previous.response.action
      return HttpResponse.json(envelope({ ...previous.response, action, acceptedInput: null, replayed: true }))
    }
    if (session.status !== "IN_PROGRESS") {
      return apiError(409, "ROLEPLAY_CONFLICT", "진행 중인 역할극이 아닙니다.", path)
    }
    const transcript = bytes.trim().replace(/\s+/g, " ")
    let response: RoleplayTurnBody
    if (!audio.type.startsWith("audio/") || !transcript) {
      session.voiceFailures += 1
      response = roleplayReinput(
        session.voiceFailures >= VOICE_FAILURES_BEFORE_TEXT ? "SUGGEST_TEXT_KEEP_VOICE" : "REQUEST_NEW_INPUT"
      )
    } else {
      response = deliverRoleplayTurn(session, activity, transcript)
    }
    session.turns.push({ key, text: fingerprint, turnId: crypto.randomUUID(), response })
    return HttpResponse.json(envelope(response))
  }),
]
