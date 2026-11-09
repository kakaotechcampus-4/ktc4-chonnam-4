import { ChildApiError, childGetRequest, childWriteRequest } from "./apiClient"

export { ChildApiError, NETWORK_ERROR, TOO_MANY_REQUESTS } from "./apiClient"

/**
 * 아동 흐름 API (contracts/api/v1).
 * 입장·사용 종료(API-IAM-08·09), 활동 목록·완료 상태(API-ACT-02·04), 표정 퀴즈(API-QUIZ-03·04·05·06),
 * 역할극 시작·말로 답하기·글자 입력(API-RPL-01·04·05)을 실제 서버로 부른다.
 */

export type ChildAccess = {
  childId: string
  childName: string
}

/** 입장 오류 코드 (API-IAM-08). 만료도 401 이다. */
export const INVALID_ACCESS_CODE = "INVALID_ACCESS_CODE"
export const ACCESS_CODE_EXPIRED = "ACCESS_CODE_EXPIRED"

type ChildEntryData = {
  childId: string
  /** 아동 실명. 화면에서는 childName 으로 쓴다. */
  displayName: string
}

/** 접근 코드로 입장한다. 서버가 아동 세션 쿠키를 심고, 같은 브라우저의 이전 아동 세션은 끊는다. */
export async function verifyAccessCode(code: string): Promise<ChildAccess> {
  // 앞자리 0이 사라지지 않게 숫자로 바꾸지 않고 문자열로 보낸다.
  const data = await childWriteRequest<ChildEntryData>("POST", "/child-access-sessions", { accessCode: code })
  return { childId: data.childId, childName: data.displayName }
}

/** 사용 종료. 서버 세션만 끝내고 코드는 만료 전까지 그대로라 같은 코드로 다시 들어올 수 있다. */
export function exitChildSession(): Promise<void> {
  return childWriteRequest<void>("DELETE", "/child-access-sessions/current")
}

/** API-ACT-02 의 수행 상태. 일시 중단·복구 필요는 아이 화면에서 "진행 중"과 같게 보인다. */
export type ChildActivityStatus = "NOT_STARTED" | "IN_PROGRESS" | "PAUSED" | "COMPLETED" | "RECOVERY_NEEDED"

export type ChildActivityType = "FIRST_COMBINED" | "SELF_EMOTION_QUIZ" | "OTHER_EMOTION_QUIZ" | "ROLEPLAY"

/**
 * 서버가 계산한 다음 학습 동작(API-ACT-02 nextAction). 화면은 이 값으로 갈 곳을 정한다.
 * WAIT_FOR_SCENARIO 는 퀴즈를 마치고 선생님의 역할극 시나리오 확정을 기다리는 정상 상태다.
 */
export type ChildNextAction = "QUIZ" | "WAIT_FOR_SCENARIO" | "ROLEPLAY" | "NONE" | "BLOCKED"

export type ChildActivity = {
  activityId: string
  activityType: ChildActivityType
  title: string
  description: string
  status: ChildActivityStatus
  nextAction: ChildNextAction
  quizCount: number
  roleplayCount: number
  /** 완료 시각(ISO). 홈 열기구에 캐릭터를 완료한 순서대로 태우는 데 쓴다. */
  completedAt: string | null
}

/** 시작했지만 끝나지 않은 활동. 활동 소개에서 "이어서 하기"로 보이고, 다음 활동 제안에서 먼저 고른다. */
export function isActivityInProgress(status: ChildActivityStatus) {
  return status === "IN_PROGRESS" || status === "PAUSED" || status === "RECOVERY_NEEDED"
}

/** 다음 학습 동작으로 갈 화면. 퀴즈·역할극이 아니면(대기·차단·완료) 갈 곳이 없다. */
export function nextActivityPath(activity: Pick<ChildActivity, "activityId" | "nextAction">): string | null {
  if (activity.nextAction === "QUIZ") return `/child/quiz/${activity.activityId}`
  if (activity.nextAction === "ROLEPLAY") return `/child/roleplay/${activity.activityId}`
  return null
}

/** "표정 퀴즈 3개 + 역할극 1개"처럼 활동 구성을 보여 준다. 0개인 부분은 뺀다. */
export function activityParts(activity: Pick<ChildActivity, "quizCount" | "roleplayCount">) {
  return [
    activity.quizCount > 0 ? `표정 퀴즈 ${activity.quizCount}개` : null,
    activity.roleplayCount > 0 ? `역할극 ${activity.roleplayCount}개` : null,
  ]
    .filter(Boolean)
    .join(" + ")
}

/** 세션이 다른 아동의 캐시를 섞어 쓰지 않도록 childId를 키에 포함한다. */
export function myActivitiesQueryKey(childId: string | null) {
  return ["child", childId, "activities"] as const
}

// 서버 응답에는 아이에게 보여 줄 제목·설명이 없어서 활동 유형으로 정한다. 문항 수는 서버 배정 구성(QuizAssignmentService)과 같다.
// 최초 결합·상대 감정 제목은 Figma C-HOME-01 시안 문구이고, 나머지 둘은 시안에 없어 임시로 정했다.
const ACTIVITY_CONTENT: Record<
  ChildActivityType,
  Pick<ChildActivity, "title" | "description" | "quizCount" | "roleplayCount">
> = {
  FIRST_COMBINED: {
    title: "친구 마음 알아보기",
    description: "친구 표정을 보고 어떤 마음일지 함께 생각해볼 거야",
    quizCount: 3,
    roleplayCount: 1,
  },
  SELF_EMOTION_QUIZ: {
    title: "내 마음 알아보기",
    description: "여러 일을 떠올리며 내 마음을 표정으로 알려줄 거야",
    quizCount: 10,
    roleplayCount: 0,
  },
  OTHER_EMOTION_QUIZ: {
    title: "상대방 마음 맞히기",
    description: "친구의 표정을 보고 어떤 마음인지 맞혀볼 거야",
    quizCount: 10,
    roleplayCount: 0,
  },
  ROLEPLAY: {
    title: "친구랑 이야기하기",
    description: "친구랑 이야기하면서 내 마음을 전해볼 거야",
    quizCount: 0,
    roleplayCount: 1,
  },
}

/** API-ACT-02 아동 세션 응답. 서버가 아동에게는 이 5개 칸만 준다(지원 수준 같은 평가 정보와 목표·시나리오 ID 는 오지 않는다). */
type ActivityData = {
  activityId: string
  activityType: ChildActivityType
  status: ChildActivityStatus
  nextAction: ChildNextAction
  completedAt: string | null
}

// 한 아이에게 배정되는 활동은 많지 않아 서버 최대 크기(100) 한 쪽만 받는다.
const ACTIVITY_PAGE_SIZE = 100

/**
 * 현재 아동 세션에 배정된 활동 (API-ACT-02, VS-003).
 * 서버가 세션 쿠키의 아동과 경로의 childId 가 다르면 404 CHILD_NOT_FOUND 로 막는다(다른 아동 목록 차단).
 */
export async function getMyActivities(childId: string, signal?: AbortSignal): Promise<ChildActivity[]> {
  const data = await childGetRequest<ActivityData[]>(
    `/children/${encodeURIComponent(childId)}/activities?size=${ACTIVITY_PAGE_SIZE}`,
    signal
  )
  return data.map((activity) => ({
    activityId: activity.activityId,
    activityType: activity.activityType,
    status: activity.status,
    nextAction: activity.nextAction,
    completedAt: activity.completedAt,
    ...ACTIVITY_CONTENT[activity.activityType],
  }))
}

export type Emotion = "HAPPY" | "SAD" | "ANGRY" | "SURPRISED" | "UPSET"

/** API-ACT-04. 완료·스탬프는 서버가 정하고 화면은 상태만 읽는다. 읽기만으로 완료가 실행되지 않는다. */
export async function getActivityStatus(activityId: string, signal?: AbortSignal): Promise<ChildActivityStatus> {
  const data = await childGetRequest<{ status: ChildActivityStatus }>(
    `/activities/${encodeURIComponent(activityId)}/status`,
    signal
  )
  return data.status
}

/** 완료 화면(C-DONE-02)으로 넘기는 값. 서버가 COMPLETED 를 확인해 준 활동만 넘긴다. */
export type ActivityCompletion = { activityId: string }

/** VS-005의 세 가지 퀴즈 유형 */
export type QuizType = "SELF_EMOTION_SITUATION" | "OTHER_EMOTION_SITUATION" | "OTHER_EMOTION_IMAGE"

export type QuizChoice = {
  /** 서버가 준 보기 글자 그대로. 답으로도 이 글자를 보낸다. */
  value: string
  /** 표정 그림. 모르는 감정 글자면 그림 없이 글자만 보여 준다. */
  emotion: Emotion | null
}

export type QuizQuestion = {
  /** 답·힌트를 보낼 때 쓰는 배정 문항 ID */
  activityQuizId: string
  questionOrder: number
  type: QuizType
  questionText: string
  /** 이미지 제시 문항의 친구 얼굴 그림 주소 */
  imageUrl: string | null
  choices: QuizChoice[]
}

/** 지금까지 낸 답. 다시 들어왔을 때 이어서 풀 문항과 단계를 정한다. */
export type QuizAttempt = {
  status: "DRAFT" | "FINALIZED"
  firstResponse: string | null
  /** 받은 힌트 수(0~2) */
  hintCount: number
  /** 최종 판정. 확정 전에는 첫 답 판정과 같다. */
  itemCorrect: boolean
  /** 카메라·이미지 문제로 점수에서 뺀 문항 */
  excludedFromScoring: boolean
  /** 첫 답과 함께 보낸 카메라 판정. 힌트 뒤 최종 답에도 똑같이 다시 보내야 한다(바꾸면 409). */
  camera: QuizCameraFields | null
}

export type QuizItemWithAttempt = QuizQuestion & { attempt: QuizAttempt | null }

// 서버 보기 글자(V6 시드: 기쁨·슬픔·화남·놀람)를 표정 그림으로 바꾼다. 정답은 서버만 안다.
const EMOTION_BY_WORD: Record<string, Emotion> = {
  기쁨: "HAPPY",
  슬픔: "SAD",
  화남: "ANGRY",
  놀람: "SURPRISED",
  속상함: "UPSET",
}

export function quizQuestionsQueryKey(childId: string | null, activityId: string | undefined) {
  return ["child", childId, "quiz", activityId] as const
}

type AssignedQuizData = {
  activityQuizId: string
  questionOrder: number
  quizType: QuizType
  questionText: string
  imageUrl: string | null
  choices: string[]
}

type QuizAttemptData = {
  firstResponse: string | null
  expressionMatchResult: ExpressionMatchResult | null
  cameraModelVersion: string | null
  usedHintTypes: string[]
  itemCorrect: boolean
  excludedFromScoring: boolean
  status: "DRAFT" | "FINALIZED"
}

function toAttempt(data: QuizAttemptData): QuizAttempt {
  return {
    status: data.status,
    firstResponse: data.firstResponse,
    hintCount: data.usedHintTypes.length,
    itemCorrect: data.itemCorrect,
    excludedFromScoring: data.excludedFromScoring,
    // 카메라 권한 거부(technicalFailureReason)는 첫 답에서 바로 확정되므로 이어서 풀 때 다시 보낼 일이 없다.
    camera: data.expressionMatchResult
      ? { expressionMatchResult: data.expressionMatchResult, cameraModelVersion: data.cameraModelVersion ?? "" }
      : null,
  }
}

/** 답을 아직 내지 않은 문항은 404 ATTEMPT_NOT_FOUND 다(API-QUIZ-05). */
async function getQuizAttempt(activityQuizId: string, signal?: AbortSignal): Promise<QuizAttempt | null> {
  try {
    const data = await childGetRequest<QuizAttemptData>(
      `/activity-quiz-items/${encodeURIComponent(activityQuizId)}/attempt`,
      signal
    )
    return toAttempt(data)
  } catch (error) {
    if (error instanceof ChildApiError && error.code === "ATTEMPT_NOT_FOUND") return null
    throw error
  }
}

/**
 * 활동에 고정된 문항(API-QUIZ-03)과 문항마다 지금까지 낸 답(API-QUIZ-05).
 * 다시 들어와도 다시 추첨하지 않으므로, 이미 확정한 문항은 건너뛰고 이어서 푼다.
 */
export async function getQuizQuestions(activityId: string, signal?: AbortSignal): Promise<QuizItemWithAttempt[]> {
  const items = await childGetRequest<AssignedQuizData[]>(
    `/activities/${encodeURIComponent(activityId)}/quiz-items`,
    signal
  )
  const questions = [...items]
    .sort((a, b) => a.questionOrder - b.questionOrder)
    .map((item) => ({
      activityQuizId: item.activityQuizId,
      questionOrder: item.questionOrder,
      type: item.quizType,
      questionText: item.questionText,
      imageUrl: item.imageUrl,
      choices: item.choices.map((value) => ({ value, emotion: EMOTION_BY_WORD[value] ?? null })),
    }))
  const attempts = await Promise.all(questions.map((question) => getQuizAttempt(question.activityQuizId, signal)))
  return questions.map((question, index) => ({ ...question, attempt: attempts[index] }))
}

export type ExpressionMatchResult = "MATCH" | "MISMATCH" | "NOT_ANALYZABLE"

/**
 * 내 감정 문항은 서버가 카메라 표정 판정을 반드시 함께 받는다(API-QUIZ-04).
 * 판정은 브라우저 안 표정 모델이 하고(camera/), 영상은 보내지 않는다.
 * - 표정 판정: MATCH(고른 표정과 같음)·MISMATCH(다름)·NOT_ANALYZABLE(카메라·모델을 못 씀, 점수에서 빠짐) + 모델 버전
 * - 카메라 권한 거부: CAMERA_PERMISSION_DENIED (점수에서 빠짐)
 */
export type QuizCameraFields =
  | { expressionMatchResult: ExpressionMatchResult; cameraModelVersion: string }
  | { technicalFailureReason: "CAMERA_PERMISSION_DENIED" }

export type QuizAnswerInput = {
  activityQuizId: string
  type: QuizType
  /** 첫 답. 힌트 뒤 최종 답을 보낼 때도 처음 값 그대로 보낸다. */
  firstResponse: string
  /** 힌트 뒤 최종 답. 첫 답일 때는 없다. */
  finalResponse?: string
  /** 내 감정 문항만. 힌트 뒤 최종 답에도 첫 답 때 값을 그대로 보낸다. */
  camera?: QuizCameraFields | null
}

/**
 * 답 제출 (API-QUIZ-04). 첫 답이 맞거나 점수에서 빠지면 바로 확정(FINALIZED), 틀리면 DRAFT 로 남아 힌트를 받을 수 있다.
 * 힌트 뒤 최종 답은 한 번만 보내고 그때 확정된다. 같은 본문을 다시 보내면 같은 결과가 온다(재전송 안전).
 */
export async function submitQuizAnswer(input: QuizAnswerInput): Promise<QuizAttempt> {
  const data = await childWriteRequest<QuizAttemptData>(
    "PUT",
    `/activity-quiz-items/${encodeURIComponent(input.activityQuizId)}/attempt`,
    {
      firstResponse: input.firstResponse,
      ...(input.type === "SELF_EMOTION_SITUATION" && input.camera ? input.camera : {}),
      ...(input.finalResponse === undefined ? {} : { finalResponse: input.finalResponse }),
    }
  )
  return toAttempt(data)
}

export type QuizHint = { order: number; text: string }

export type QuizHintInput = {
  activityQuizId: string
  /** 힌트 한 번에 하나. 응답을 못 받아 다시 보낼 때 같은 키를 써야 힌트가 한 번만 발급된다. */
  requestKey: string
}

/** 문항에 준비된 힌트가 더 없거나 이미 2개를 받았다(API-QUIZ-06 409). */
export const NO_MORE_HINTS = "NO_MORE_HINTS"

/** 힌트 요청 (API-QUIZ-06). 첫 답이 틀린 뒤에만, 문항당 최대 2개다. */
export async function requestQuizHint(input: QuizHintInput): Promise<QuizHint> {
  const data = await childWriteRequest<{ hintOrder: number; text: string }>(
    "POST",
    `/activity-quiz-items/${encodeURIComponent(input.activityQuizId)}/hints`,
    undefined,
    { idempotencyKey: input.requestKey }
  )
  return { order: data.hintOrder, text: data.text }
}

/** 역할극 말풍선 주인. DIALOGUE 는 거북이(캐릭터 대사), HINT 는 깡총이(마스코트 힌트·고정 안내)다. */
export type RoleplaySpeaker = "turtle" | "rabbit"

export type RoleplayMessage = {
  speaker: RoleplaySpeaker
  text: string
  /** 캐릭터 대사의 음성(TTS). 서버가 음성을 함께 보냈을 때만 있다(roleplay.speech.delivery=inline 이면 data URL). */
  audioUrl?: string
}

export type RoleplaySessionStatus = "IN_PROGRESS" | "PAUSED" | "COMPLETED"

export type RoleplaySession = {
  sessionId: string
  status: RoleplaySessionStatus
  activityStatus: ChildActivityStatus
  /** 거북이가 연기하는 상대 이름(예: 지우). 말풍선 이름표에 쓴다. 상대 인물이 없는 시나리오면 null. */
  characterName: string | null
  /** 첫 질문 또는 이어서 할 때 마지막 응답. 진행할 수 없는 상태면 비어 있다. */
  messages: (RoleplayMessage & { id: string })[]
}

export function roleplaySessionQueryKey(childId: string | null, activityId: string | undefined) {
  return ["child", childId, "roleplay", activityId] as const
}

type ServerSpeech = { available: boolean; url: string | null }

type ServerMessage = {
  messageId?: string
  speakerRole: "DIALOGUE" | "HINT"
  text: string
  speech?: ServerSpeech | null
}

function toMessage(message: ServerMessage): RoleplayMessage {
  const speaker: RoleplaySpeaker = message.speakerRole === "HINT" ? "rabbit" : "turtle"
  const audioUrl = message.speech?.available && message.speech.url ? message.speech.url : undefined
  return audioUrl ? { speaker, text: message.text, audioUrl } : { speaker, text: message.text }
}

type RoleplayStartData = {
  sessionId: string
  status: RoleplaySessionStatus
  activityStatus: ChildActivityStatus
  characterName?: string | null
  delivery: { messages: (ServerMessage & { messageId: string })[] }
}

/**
 * 역할극 시작·이어 하기 (API-RPL-01). 이미 세션이 있으면 새 키로 불러도 그 세션을 돌려준다.
 * requestKey 는 화면에 머무는 동안 하나를 쓴다. 응답을 못 받아 다시 불러도 세션이 하나만 생긴다.
 */
export async function startRoleplay(activityId: string, requestKey: string): Promise<RoleplaySession> {
  const data = await childWriteRequest<RoleplayStartData>(
    "POST",
    `/activities/${encodeURIComponent(activityId)}/roleplay-sessions`,
    undefined,
    { idempotencyKey: requestKey }
  )
  return {
    sessionId: data.sessionId,
    status: data.status,
    activityStatus: data.activityStatus,
    characterName: data.characterName?.trim() || null,
    messages: data.delivery.messages.map((message) => ({ id: message.messageId, ...toMessage(message) })),
  }
}

type RoleplayTurnBase = {
  sessionId: string
  /** 네트워크 재전송 시 같은 턴이 두 번 만들어지지 않도록 같은 값을 다시 보낸다 (VS-011). 새 답은 새 키. */
  requestKey: string
}

export type RoleplayTextTurnInput = RoleplayTurnBase & { kind: "text"; text: string }

/** 녹음한 음성. 다시 보낼 때는 같은 키와 같은 바이트(같은 Blob)를 보내야 서버가 같은 요청으로 본다. */
export type RoleplayVoiceTurnInput = RoleplayTurnBase & { kind: "voice"; audio: Blob }

export type RoleplayTurnInput = RoleplayTextTurnInput | RoleplayVoiceTurnInput

export type RoleplayTurnStatus =
  | "DELIVERED"
  | "REINPUT_REQUIRED"
  | "RETRY_REQUIRED"
  | "STOPPED"
  | "NOTICE_UNAVAILABLE"
  | "PROCESSING"

export type RoleplayTurnAction =
  | "NONE"
  | "REQUEST_NEW_INPUT"
  | "SUGGEST_TEXT_KEEP_VOICE"
  | "RETRY_TURN"
  | "STOP_DIALOGUE"
  | "WAIT"

export type RoleplayTurnResult = {
  status: RoleplayTurnStatus
  action: RoleplayTurnAction
  /** 마스코트 힌트가 붙으면 [깡총이, 거북이] 순서로 온다. 고정 안내는 깡총이 하나다. */
  messages: RoleplayMessage[]
  /**
   * 서버가 받아들인 아이 답(표준 발화). 말로 답했을 때 STT 결과를 정제한 문장이라 아이 말풍선에 쓴다.
   * 새로 성공한 턴에만 오고, 재전송(replayed)·실패에는 null 이다.
   */
  acceptedInput: string | null
}

/** 같은 키의 요청을 서버가 아직 처리하고 있다(202). 같은 키로 다시 보내면 결과를 받는다. */
export const ROLEPLAY_PROCESSING = "ROLEPLAY_PROCESSING"

type RoleplayTurnData = {
  status: RoleplayTurnStatus
  action: RoleplayTurnAction
  messages: ServerMessage[]
  acceptedInput: string | null
}

function toTurnResult(data: RoleplayTurnData): RoleplayTurnResult {
  if (data.status === "PROCESSING") {
    throw new ChildApiError(202, ROLEPLAY_PROCESSING, "아직 답을 만들고 있어요.")
  }
  return {
    status: data.status,
    action: data.action,
    messages: data.messages.map(toMessage),
    acceptedInput: data.acceptedInput ?? null,
  }
}

/**
 * 글자로 답하기 (API-RPL-05). 서버 기능(roleplay.text.enabled)이 꺼져 있으면 404 다.
 * HTTP 200 이어도 status 가 DELIVERED 일 때만 대화가 이어진 것이다.
 */
export async function sendRoleplayText(input: Omit<RoleplayTextTurnInput, "kind">): Promise<RoleplayTurnResult> {
  const data = await childWriteRequest<RoleplayTurnData>(
    "POST",
    `/roleplay-sessions/${encodeURIComponent(input.sessionId)}/text-inputs`,
    { text: input.text },
    { idempotencyKey: input.requestKey }
  )
  return toTurnResult(data)
}

// 서버(AudioFormat)가 아는 음성 형식별 파일 확장자. 서버는 파일 이름이 아니라 파트의 MIME 과 실제 바이트로 형식을 본다.
const AUDIO_EXTENSIONS: Record<string, string> = {
  "audio/webm": "webm",
  "video/webm": "webm",
  "audio/ogg": "ogg",
  "audio/wav": "wav",
  "audio/mpeg": "mp3",
  "audio/mp4": "m4a",
}

function audioFileName(audio: Blob) {
  const base = audio.type.split(";", 1)[0].trim().toLowerCase()
  return `voice.${AUDIO_EXTENSIONS[base] ?? "webm"}`
}

/**
 * 말로 답하기 (API-RPL-04). 녹음 파일을 multipart audio 파트 하나로 보낸다. 서버가 STT 로 글자로 바꾸고 대화를 잇는다.
 * 잘 안 들림·무음·형식 오류는 HTTP 200 REINPUT_REQUIRED 로 오고, 실패가 3번 쌓이면 action 이 SUGGEST_TEXT_KEEP_VOICE 다.
 * 서버 기능(roleplay.http.enabled)이 꺼져 있으면 404 다.
 */
export async function sendRoleplayVoice(input: Omit<RoleplayVoiceTurnInput, "kind">): Promise<RoleplayTurnResult> {
  const form = new FormData()
  form.set("audio", input.audio, audioFileName(input.audio))
  const data = await childWriteRequest<RoleplayTurnData>(
    "POST",
    `/roleplay-sessions/${encodeURIComponent(input.sessionId)}/voice-inputs`,
    form,
    { idempotencyKey: input.requestKey }
  )
  return toTurnResult(data)
}

/** 말·글자 답을 입력 방식에 맞는 API 로 보낸다. */
export function sendRoleplayTurn(input: RoleplayTurnInput): Promise<RoleplayTurnResult> {
  return input.kind === "voice" ? sendRoleplayVoice(input) : sendRoleplayText(input)
}
