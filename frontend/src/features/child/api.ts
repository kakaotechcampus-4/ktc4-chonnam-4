/**
 * 아동 흐름 API.
 * 아동용 백엔드 API(S1-JIN-01 코드 검증 등)가 아직 없어 mock 응답을 돌려준다.
 * 실제 API가 나오면 각 함수 안쪽만 fetch 호출로 바꾸고, 화면 코드는 그대로 둔다.
 */

export type ChildAccess = {
  childId: string
  childName: string
}

export type ChildApiErrorCode = "ACCESS_CODE_INVALID" | "ACCESS_CODE_EXPIRED"

export class ChildApiError extends Error {
  readonly status: number
  readonly code: ChildApiErrorCode

  constructor(status: number, code: ChildApiErrorCode, message: string) {
    super(message)
    this.name = "ChildApiError"
    this.status = status
    this.code = code
  }
}

const MOCK_DELAY_MS = 500

// mock 테스트용 코드: 1234 성공, 9999 만료, 0000 네트워크 오류, 그 외 틀린 코드
const MOCK_VALID_CODE = "1234"
const MOCK_EXPIRED_CODE = "9999"
const MOCK_NETWORK_ERROR_CODE = "0000"

function wait(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export async function verifyAccessCode(code: string): Promise<ChildAccess> {
  await wait(MOCK_DELAY_MS)

  if (code === MOCK_NETWORK_ERROR_CODE) {
    // fetch가 연결 실패 시 던지는 오류와 같은 형태로 흉내 낸다.
    throw new TypeError("Failed to fetch")
  }
  if (code === MOCK_EXPIRED_CODE) {
    throw new ChildApiError(410, "ACCESS_CODE_EXPIRED", "만료된 접근 코드입니다.")
  }
  if (code !== MOCK_VALID_CODE) {
    throw new ChildApiError(401, "ACCESS_CODE_INVALID", "유효하지 않은 접근 코드입니다.")
  }

  return { childId: "mock-child-1", childName: "서연" }
}

export type ChildActivityStatus = "NOT_STARTED" | "IN_PROGRESS" | "COMPLETED"

export type ChildActivity = {
  activityId: string
  title: string
  description: string
  status: ChildActivityStatus
  quizCount: number
  roleplayCount: number
  /** 완료 시각(ISO). 홈 열기구에 캐릭터를 완료한 순서대로 태우는 데 쓴다. */
  completedAt?: string
}

/** 세션이 다른 아동의 캐시를 섞어 쓰지 않도록 childId를 키에 포함한다. */
export function myActivitiesQueryKey(childId: string | null) {
  return ["child", childId, "activities"] as const
}

// Figma C-HOME-01 시안과 같은 3개 활동
const MOCK_ACTIVITIES: ChildActivity[] = [
  {
    activityId: "mock-activity-1",
    title: "친구 마음 알아보기",
    description: "넘어진 친구를 보고 어떤 마음일지 함께 생각해볼 거야",
    status: "IN_PROGRESS",
    quizCount: 3,
    roleplayCount: 1,
  },
  {
    activityId: "mock-activity-2",
    title: "교실에서 있었던 일",
    description: "교실에서 친구와 있었던 일을 떠올리며 마음을 표현해볼 거야",
    status: "NOT_STARTED",
    quizCount: 3,
    roleplayCount: 1,
  },
  {
    activityId: "mock-activity-3",
    title: "상대방 마음 맞히기",
    description: "친구의 표정을 보고 어떤 마음인지 맞혀볼 거야",
    status: "COMPLETED",
    quizCount: 3,
    roleplayCount: 1,
    completedAt: "2026-09-29T10:00:00+09:00",
  },
]

/** 현재 아동 세션에 배정된 활동만 돌려준다. 실제 API는 세션 쿠키로 아동을 식별한다 (VS-003). */
export async function getMyActivities(): Promise<ChildActivity[]> {
  await wait(MOCK_DELAY_MS)
  // 완료 처리로 mock 상태가 바뀌므로, 캐시와 참조를 공유하지 않게 복사해서 돌려준다.
  return MOCK_ACTIVITIES.map((activity) => ({ ...activity }))
}

export type Emotion = "HAPPY" | "SAD" | "ANGRY" | "SURPRISED" | "UPSET"

/** VS-005의 세 가지 퀴즈 유형 */
export type QuizType = "SELF_EMOTION_SITUATION" | "OTHER_EMOTION_SITUATION" | "OTHER_EMOTION_IMAGE"

export type QuizChoice = {
  choiceId: string
  emotion: Emotion
  label: string
  description?: string
}

export type QuizQuestion = {
  questionId: string
  type: QuizType
  /** 거북이가 들려주는 상황 설명 */
  situation?: string
  question: string
  /** 이미지 제시 문항의 친구 얼굴. S1은 실제 사진 대신 감정 일러스트로 대신한다. */
  imageEmotion?: Emotion
  choices: QuizChoice[]
}

export type QuizHint = {
  level: 1 | 2
  text: string
  /** 힌트 뒤에 남겨 둘 보기 (정답 포함 2개) */
  choiceIds: string[]
}

export type QuizAnswerResult =
  | { result: "CORRECT" }
  | { result: "INCORRECT"; hint: QuizHint }
  | { result: "FINAL_INCORRECT" }

export type QuizAnswerInput = {
  activityId: string
  questionId: string
  choiceId: string
  /** 1: 첫 응답, 2·3: 힌트 뒤 재응답 */
  attempt: number
}

export function quizQuestionsQueryKey(childId: string | null, activityId: string | undefined) {
  return ["child", childId, "quiz", activityId] as const
}

// Figma C-QZ-01~04 문구. 정답과 힌트는 서버(mock)에만 두고 화면으로는 보내지 않는다 (VS-006).
const MOCK_QUIZ: (QuizQuestion & { answerChoiceId: string; hintTexts: [string, string]; hintChoiceIds: string[] })[] = [
  {
    questionId: "mock-quiz-1",
    type: "SELF_EMOTION_SITUATION",
    situation: "친구가 실수로 네 장난감을 밟아서 부서졌어.",
    question: "지금 네 기분은 어때?",
    choices: [
      { choiceId: "q1-happy", emotion: "HAPPY", label: "기뻐요", description: "활짝 웃는 표정" },
      { choiceId: "q1-sad", emotion: "SAD", label: "슬퍼요", description: "눈꼬리가 내려간 표정" },
      { choiceId: "q1-angry", emotion: "ANGRY", label: "화나요", description: "눈썹을 찡그린 표정" },
      { choiceId: "q1-surprised", emotion: "SURPRISED", label: "놀랐어요", description: "눈과 입을 동그랗게" },
    ],
    answerChoiceId: "q1-sad",
    hintTexts: ["장난감이 부서지면 어떤 기분이 들지 떠올려 봐", "소중한 게 망가지면 눈물이 날 것 같은 마음이 들어"],
    hintChoiceIds: ["q1-sad", "q1-angry"],
  },
  {
    questionId: "mock-quiz-2",
    type: "OTHER_EMOTION_SITUATION",
    situation: "친구가 좋아하던 풍선이 하늘로 날아가 버렸어.",
    question: "친구는 지금 어떤 표정일까?",
    choices: [
      { choiceId: "q2-happy", emotion: "HAPPY", label: "기쁜 얼굴" },
      { choiceId: "q2-upset", emotion: "UPSET", label: "속상한 얼굴" },
      { choiceId: "q2-angry", emotion: "ANGRY", label: "화난 얼굴" },
      { choiceId: "q2-surprised", emotion: "SURPRISED", label: "놀란 얼굴" },
    ],
    answerChoiceId: "q2-upset",
    hintTexts: ["좋아하던 걸 잃어버리면 어떤 마음일까?", "갖고 싶던 풍선이 멀리 가 버리면 속상한 마음이 들어"],
    hintChoiceIds: ["q2-happy", "q2-upset"],
  },
  {
    questionId: "mock-quiz-3",
    type: "OTHER_EMOTION_IMAGE",
    question: "이 친구는 지금 어떤 마음일까?",
    imageEmotion: "SURPRISED",
    choices: [
      { choiceId: "q3-happy", emotion: "HAPPY", label: "기뻐요" },
      { choiceId: "q3-sad", emotion: "SAD", label: "슬퍼요" },
      { choiceId: "q3-surprised", emotion: "SURPRISED", label: "놀랐어요" },
      { choiceId: "q3-upset", emotion: "UPSET", label: "속상해요" },
    ],
    answerChoiceId: "q3-surprised",
    hintTexts: ["눈썹을 자세히 봐봐", "눈과 입이 동그랗게 커졌어"],
    hintChoiceIds: ["q3-happy", "q3-surprised"],
  },
]

export async function getQuizQuestions(activityId: string): Promise<QuizQuestion[]> {
  void activityId
  await wait(MOCK_DELAY_MS)
  return MOCK_QUIZ.map((item) => ({
    questionId: item.questionId,
    type: item.type,
    situation: item.situation,
    question: item.question,
    imageEmotion: item.imageEmotion,
    choices: item.choices,
  }))
}

/** 응답을 제출하면 서버가 판정한다. 첫 응답·힌트 2회·최종 응답 순서로 최대 3번 받는다 (VS-006). */
export async function submitQuizAnswer(input: QuizAnswerInput): Promise<QuizAnswerResult> {
  await wait(MOCK_DELAY_MS)
  const item = MOCK_QUIZ.find((quiz) => quiz.questionId === input.questionId)
  if (!item) throw new Error("알 수 없는 문항입니다.")

  if (input.choiceId === item.answerChoiceId) return { result: "CORRECT" }
  if (input.attempt >= 3) return { result: "FINAL_INCORRECT" }

  const level = input.attempt === 1 ? 1 : 2
  return {
    result: "INCORRECT",
    hint: { level, text: item.hintTexts[level - 1], choiceIds: item.hintChoiceIds },
  }
}

export type RoleplaySpeaker = "turtle" | "rabbit"

export type RoleplayMessage = {
  speaker: RoleplaySpeaker
  text: string
}

export type RoleplayScenario = {
  scenarioId: string
  /** 헤더에 보여줄 장소. 예: 놀이터에서 */
  place: string
  opening: RoleplayMessage
}

export type RoleplayTurnInput = {
  activityId: string
  turnIndex: number
  text: string
  /** 네트워크 재전송 시 같은 턴이 두 번 만들어지지 않도록 같은 값을 다시 보낸다 (VS-011). */
  requestId: string
}

export type RoleplayTurnResult =
  | { status: "DELIVERED"; reply: RoleplayMessage; isFinished: boolean }
  /** 응답 생성·검증에 실패해 사전 승인된 고정 안내를 대신 보낸다. 턴은 소모하지 않는다 (VS-011·013). */
  | { status: "FALLBACK"; reply: RoleplayMessage }

export function roleplayScenarioQueryKey(childId: string | null, activityId: string | undefined) {
  return ["child", childId, "roleplay", activityId] as const
}

// Figma C-RP-02 대사로 만든 mock 대본. 실제 응답은 STT→LLM→안전 검사를 거친다 (S1-JIN-02).
const MOCK_ROLEPLAY_REPLIES: { reply: RoleplayMessage; isFinished: boolean }[] = [
  {
    reply: { speaker: "rabbit", text: "친구 눈썹이 아래로 축 처져 있어. 어떤 마음일 때 이런 표정이 될까?" },
    isFinished: false,
  },
  {
    reply: { speaker: "turtle", text: "맞아, 넘어지면 아프고 속상하지. 친구 마음을 잘 알아줬어!" },
    isFinished: true,
  },
]

export async function getRoleplayScenario(activityId: string): Promise<RoleplayScenario> {
  void activityId
  await wait(MOCK_DELAY_MS)
  return {
    scenarioId: "mock-scenario-1",
    place: "놀이터에서",
    opening: { speaker: "turtle", text: "친구가 넘어져서 울고 있어. 몸은 어떤 느낌일까?" },
  }
}

// mock은 항상 대본대로 응답한다. 고정 안내(FALLBACK) 응답은 실제 API(S1-JIN-02)가 나오면 연동해 확인한다.
export async function sendRoleplayTurn(input: RoleplayTurnInput): Promise<RoleplayTurnResult> {
  await wait(MOCK_DELAY_MS * 2)
  const scripted = MOCK_ROLEPLAY_REPLIES[Math.min(input.turnIndex, MOCK_ROLEPLAY_REPLIES.length - 1)]
  return { status: "DELIVERED", ...scripted }
}

export type ActivityCompletion = {
  activityId: string
  /** 이번 요청으로 스탬프가 지급됐는지. 정확도와 무관하게 활동당 1개다 (VS-013). */
  stampGranted: boolean
}

export type CompleteActivityInput = {
  activityId: string
  /** 재전송해도 스탬프가 두 번 지급되지 않도록 같은 값을 다시 보낸다 (VS-013 중복 완료 방지). */
  requestId: string
}

const mockCompletions = new Map<string, ActivityCompletion>()

/**
 * 활동 완료 처리와 스탬프 지급.
 * 완료를 서버가 마지막 턴에서 자동 처리할지, FE가 요청할지는 API 계약이 아직 없다.
 * 서버 자동 처리로 정해지면 이 함수 안쪽만 "완료 결과 조회"로 바꾸고 화면은 그대로 둔다.
 */
export async function completeActivity(input: CompleteActivityInput): Promise<ActivityCompletion> {
  await wait(MOCK_DELAY_MS)
  const previous = mockCompletions.get(input.requestId)
  if (previous) return previous

  const activity = MOCK_ACTIVITIES.find((item) => item.activityId === input.activityId)
  if (!activity) throw new Error("알 수 없는 활동입니다.")

  const completion = { activityId: activity.activityId, stampGranted: activity.status !== "COMPLETED" }
  if (activity.status !== "COMPLETED") activity.completedAt = new Date().toISOString()
  activity.status = "COMPLETED"
  mockCompletions.set(input.requestId, completion)
  return completion
}
