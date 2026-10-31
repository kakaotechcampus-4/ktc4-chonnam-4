// v1 Markdown 명세의 연동 예제. 기준 63e4314 / 2026-10-22.
// Draft* 는 미구현/미합의 DTO 제안이며 런타임 호출은 기본 차단합니다.
export interface ApiResponse<T> { data: T; meta: { traceId: string }; }
export interface ApiFieldError { field: string; message: string; }
export interface ApiErrorDetail {
  status: number; code: string; message: string; path: string;
  traceId: string; fieldErrors: ApiFieldError[];
}
export type UUID = string;
export type ISODateTime = string;
export type UserRole = 'INSTRUCTOR';
export type AccountStatus = 'ACTIVE';
export type ClassroomStatus = 'ACTIVE' | 'ARCHIVED';
export type ChildStatus = 'ACTIVE' | 'PAUSED' | 'REMOVED';
export type ActivityStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'PAUSED' | 'COMPLETED' | 'RECOVERY_NEEDED';
export type ScenarioLevel = 'L1' | 'L2' | 'L3';
export type SupportLevel = 'S0' | 'S1' | 'S2' | 'S3';
export type QuizType = 'SELF_EMOTION_SITUATION' | 'OTHER_EMOTION_SITUATION' | 'OTHER_EMOTION_IMAGE';
export type Emotion = 'JOY' | 'SADNESS' | 'ANGER' | 'FEAR' | 'SURPRISE' | 'UPSET' | 'PAIN' | 'CALM';
export type ExpressionMatchResult = 'MATCH' | 'MISMATCH' | 'NOT_ANALYZABLE';
export type TechnicalFailureReason = 'CAMERA_PERMISSION_DENIED' | 'IMAGE_LOAD_FAILED';
export type DraftScenarioPrepareData = DraftScenarioData | Record<string, unknown>;
export type DraftSupportChange = Record<string, unknown>;

export interface CsrfData {
  /** 응답의 이름을 변경 요청 Header로 사용 */
  headerName: string;
  /** CSRF 토큰; 로그/URL에 기록 금지 */
  token: string;
}

export interface SignupBody {
  /** 정규화 후 유일; 현재 Email/최대254자 검증 */
  email: string;
  /** 8~72자, UTF-8 72바이트 이하 (한글·이모지는 바이트 수도 확인) */
  password: string;
  /** 실명; 현재 최대100자 */
  name: string;
  /** 선택 레거시, 최대100자; 필수 수집 금지 */
  orgName?: string | null;
  /** 필수 동의. false/null/누락은 422 */
  termsAgreed: true;
  /** 필수 동의. 버전과 동의 시각은 서버가 결정 */
  privacyAgreed: true;
}

export interface LoginBody {
  /** 빈 값 불가 */
  email: string;
  /** 빈 값 불가; 실패는 동일한 일반 안내 */
  password: string;
}

export interface UserData {
  /** 강사 ID */
  userId: UUID;
  /** 현재 역할은 INSTRUCTOR만 사용 */
  role: UserRole;
  /** 로그인 이메일 */
  email: string;
  /** 강사 이름 */
  name: string;
  /** 선택 조직명, null 포함 */
  orgName: string | null;
  /** 현재 계정 상태는 ACTIVE만 사용 */
  status: AccountStatus;
}

export interface LoginData {
  /** 불투명 Bearer 토큰; JWT라고 가정하지 않음 */
  accessToken: string;
  /** 로그인+12시간, 연장 없음 */
  expiresAt: ISODateTime;
  /** 강사 정보 */
  user: UserData;
}

export interface SessionData {
  /** 현재 강사 정보; 토큰 연장/재발급 없음 */
  user: UserData;
}

export interface AccessCodeData {
  /** 정확히 숫자4자리 문자열; 앞자리0 유지 */
  accessCode: string;
  /** 24시간 만료; 재전송은 원래 만료 유지 */
  expiresAt: ISODateTime;
}

export interface ChildEntryBody {
  /** 숫자4자리, 정수로 변환하지 않음 */
  accessCode: string;
}

export interface ChildEntryData {
  /** 현재 아동 ID */
  childId: UUID;
  /** 실명; childName 아님 */
  displayName: string;
}

export interface ClassBody {
  /** 빈 값 불가, 현재 최대100자 */
  name: string;
}

export interface ClassData {
  /** 학급 ID */
  classId: UUID;
  /** 담당 강사 ID; 서버 결정 */
  instructorId: UUID;
  /** 학급명 */
  name: string;
  /** ACTIVE/레거시ARCHIVED; 삭제=영구DELETE */
  status: ClassroomStatus;
}

export interface ChildBody {
  /** 실명만; 현재 최대100자, 동명이인 허용 */
  displayName: string;
}

export interface ChildData {
  /** 아동 식별자 */
  childId: UUID;
  /** 소속 학급 */
  classId: UUID;
  /** 아동 실명 */
  displayName: string;
  /** 현재 ACTIVE; REMOVED 보관으로 삭제를 대체하지 않음 */
  status: ChildStatus;
}

export interface GoalBody {
  /** 최대200자 */
  title: string;
  /** 최대30자, 선택 */
  situationType?: string | null;
  /** 목표 분류, 최대30자. 별도 enum 아님 */
  category?: string | null;
  /** 추가 등장인물; 누락/null은 현재 [] 정리 */
  characters?: Record<string, unknown>[] | null;
  /** 빈/공백/null 원소는 조건 오류 */
  requiredElements?: string[] | null;
  /** 빈/공백/null 원소는 조건 오류 */
  forbiddenExpressions?: string[] | null;
  /** 접근 가능한 동일 아동의 이전 목표 */
  parentGoalId?: UUID | null;
}

export interface GoalData {
  /** 목표 ID */
  goalId: UUID;
  /** 아동 ID */
  childId: UUID;
  /** 담당 강사 */
  instructorId: UUID;
  /** 이전 목표, 없으면 null */
  parentGoalId: UUID | null;
  /** 목표명 */
  title: string;
  /** 상황 유형 */
  situationType: string | null;
  /** 목표 분류, 최대30자. 별도 enum 아님 */
  category: string | null;
  /** 등장인물 */
  characters: Record<string, unknown>[];
  /** 필수 조건 */
  requiredElements: string[];
  /** 금지 조건 */
  forbiddenExpressions: string[];
  /** 서버 계산; FE 수정 금지 */
  contentHash: string;
  /** 생성 시각 */
  createdAt: ISODateTime;
}

export interface ActivityBody {
  /** 담당 아동 UUID */
  childId: UUID;
  /** 생략/null은 FIRST_COMBINED. ROLEPLAY는 현재 422로 거부 */
  activityType?: ActivityType | null;
  /** FIRST_COMBINED/ROLEPLAY 필수, 독립 퀴즈 금지. goalId 대신 목표 내용 전달 */
  goal?: GoalBody | null;
}

export interface ActivityData {
  /** 아동별 독립 활동 */
  activityId: UUID;
  /** 아동 */
  childId: UUID;
  /** 목표. 독립 퀴즈는 null */
  goalId: UUID | null;
  /** 활동 유형 */
  activityType: ActivityType;
  /** 현재 생성 시 null */
  scenarioId: UUID | null;
  /** 현재 null; 연결 방식 보완 */
  scenarioSource: string | null;
  /** 초기 지원; 아동 평가 projection 합의 필요 */
  initialSupportLevel: SupportLevel | null;
  /** 기본값 적용 */
  difficultyFallbackApplied: boolean;
  /** 수행 상태; 준비상태와 구분 */
  status: ActivityStatus;
  /** 배정/생성 시각 */
  assignedAt: ISODateTime;
  /** 시작 시각 */
  startedAt: ISODateTime | null;
  /** 완료 시각. 독립 퀴즈는 마지막 문항 확정 때 채워짐. 최초 결합은 역할극 종료 연결 전이라 아직 null */
  completedAt: ISODateTime | null;
  /** 스탬프 지급 시각. completedAt과 함께 한 번만 채워짐 */
  rewardIssuedAt: ISODateTime | null;
}

export interface HintSpec {
  /** 등록된 힌트 종류, 고정 enum 아님 */
  type: string;
  /** 사전 검증된 힌트 */
  text: string;
}


export interface AssignedQuizData {
  /** 응답/힌트 호출에 사용 */
  activityQuizId: UUID;
  /** 활동 */
  activityId: UUID;
  /** 원문항 */
  itemId: UUID;
  /** 배정 시점 문항 버전 */
  itemVersion: number;
  /** 최초 결합 1~3, 독립 퀴즈 1~10 표시 순서 */
  questionOrder: number;
  /** 유형 */
  quizType: QuizType;
  /** 질문 */
  questionText: string;
  /** 기본 문항 이미지, 카메라 원본 아님 */
  imageUrl: string | null;
  /** 보기; 정답/힌트 미포함 */
  choices: string[];
}

export interface QuizAttemptBody {
  /** 최대200자; 재PUT에서도 최초 값을 유지 */
  firstResponse?: string | null;
  /** SELF 유형만, 서버 원본 업로드 없음 */
  expressionMatchResult?: ExpressionMatchResult | null;
  /** 판정 있으면 필요; 최대100자 */
  cameraModelVersion?: string | null;
  /** 최대200자; 최초는null, 힌트후 최종 추가 */
  finalResponse?: string | null;
  /** 현재 두 enum; 다른 문자열 금지 */
  technicalFailureReason?: TechnicalFailureReason | null;
}

export interface QuizAttemptData {
  /** 응답 ID */
  attemptId: UUID;
  /** 배정 문항 */
  activityQuizId: UUID;
  /** 최초 답안 */
  firstResponse: string | null;
  /** 서버 최초 판정 */
  firstResponseCorrect: boolean;
  /** 표정 판정 */
  expressionMatchResult: ExpressionMatchResult | null;
  /** 모델 버전 */
  cameraModelVersion: string | null;
  /** 실제 발급된 힌트 종류 */
  usedHintTypes: string[];
  /** 최종 답안 */
  finalResponse: string | null;
  /** 힌트 후 해결 */
  resolvedAfterHint: boolean;
  /** 최종 문항 판정 */
  itemCorrect: boolean;
  /** 기술 제외 */
  excludedFromScoring: boolean;
  /** 현재 응답 상태 */
  status: 'DRAFT' | 'FINALIZED';
  /** 응답 시각 */
  respondedAt: ISODateTime;
}

export interface QuizHintData {
  /** 힌트 ID */
  hintId: UUID;
  /** 1~2; 같은키 동일순서 */
  hintOrder: number;
  /** 문항 등록 힌트 종류 */
  type: string;
  /** 아동 표시 문구 */
  text: string;
}

export interface QuizResultData {
  /** 활동 */
  activityId: UUID;
  /** 기술 제외 후 분모 */
  validQuestionCount: number;
  /** 힌트 전 최초 정답 수 */
  correctQuestionCount: number;
  /** 0~1 비율, 유효0개면null */
  overallAccuracy: number | null;
  /** 힌트 총수 */
  totalHintCount: number;
  /** 힌트 후 해결 별도 집계 */
  resolvedAfterHintCount: number;
  /** 최초 난이도 */
  scenarioLevel: ScenarioLevel | null;
  /** 초기 지원 */
  initialSupportLevel: SupportLevel | null;
  /** 기본값 사용 */
  difficultyFallbackApplied: boolean;
  /** FIRST_COMBINED 결과만 최초 난이도에 적용; 독립 퀴즈는 false */
  initialDifficultyUsed: boolean;
  /** 정책 버전 */
  policyVersion: string;
}

// 설계 제안, 실제 DTO 확정 필요
export interface DraftGoalPatch {
  /** 변경할 제목; 부분 수정 */
  title?: string;
  /** 상황 변경 */
  situationType?: string | null;
  /** 목표 분류, 최대30자. 별도 enum 아님 */
  category?: string | null;
  /** 등장인물 */
  characters?: Record<string, unknown>[];
  /** 필수 조건 */
  requiredElements?: string[];
  /** 금지 조건 */
  forbiddenExpressions?: string[];
}

// 설계 제안, 실제 DTO 확정 필요
export interface DraftScenarioBody {
  /** 대상 아동 */
  childId: UUID;
  /** 확정 목표 */
  goalId: UUID;
}

// 설계 제안, 실제 DTO 확정 필요
export interface DraftMicroGoal {
  /** 목표 ID, 순서/번호 선행조건 아님 */
  microGoalId: UUID;
  /** 관찰 목표 */
  description: string;
  /** 관찰 가능한 학습 근거 */
  requiredEvidence: string[];
}

// 설계 제안, 실제 DTO 확정 필요
export interface DraftScenarioData {
  /** 시나리오 */
  scenarioId: UUID;
  /** 승인 버전 */
  version: number;
  /** 관련 목표 */
  goalId: UUID;
  /** NEWLY_GENERATED/REUSED 제안 */
  scenarioSource: string;
  /** DRAFT/APPROVED/REVISION_REQUIRED/REJECTED 제안 */
  status: string;
  /** 난이도 */
  scenarioLevel: ScenarioLevel;
  /** 제목 */
  title: string;
  /** 요약 */
  summary: string;
  /** 승인 사실 */
  scenarioFacts: string[];
  /** 금지 추론 */
  prohibitedInferences: string[];
  /** 실명 프로필 포함 금지 */
  characters: Record<string, unknown>[];
  /** 순서 없는 목표 */
  microGoals: DraftMicroGoal[];
  /** 지원별 시작 질문; 구체 S운용 미정 */
  openingPrompts: Record<string, string>;
}

// 설계 제안, 실제 DTO 확정 필요
export interface DraftScenarioPatch {
  /** 편집/검토 대상 버전 일치 확인 */
  version: number;
  /** 검토 상태 변경 제안 */
  status?: string;
  /** 미승인 초안 제목 */
  title?: string;
  /** 미승인 초안 요약 */
  summary?: string;
  /** 초안 목표; 승인본 직접 수정 금지 */
  microGoals?: DraftMicroGoal[];
}

// 설계 제안, 실제 DTO 확정 필요
export interface DraftScenarioVersionBody {
  /** 기반 버전; 정확한 필드명/필수 여부 합의 필요 */
  version: number;
  /** 새 초안 제목 */
  title?: string;
  /** 새 초안 요약 */
  summary?: string;
  /** 새 버전 목표 */
  microGoals?: DraftMicroGoal[];
}

// 설계 제안, 실제 DTO 확정 필요
export interface DraftMessage {
  /** 화면 출력 ID */
  messageId: UUID;
  /** UI 표시 주체 제안; 에이전트 분리 아님 */
  speakerRole: 'DIALOGUE' | 'HINT';
  /** QUESTION/HINT/FEEDBACK 등 제안 */
  purpose: string;
  /** 검증된 출력만 */
  text: string;
  /** 전달방식/TTL 미정 */
  speech: DraftSpeech;
}

// 설계 제안, 실제 DTO 확정 필요
export interface DraftSpeech {
  /** TTS 준비 여부 제안 */
  available: boolean;
  /** URL 반환/수명/권한 미정 */
  url: string | null;
}

// 설계 제안, 실제 DTO 확정 필요
export interface DraftDelivery {
  /** 메시지 배열 제안; 제공 트리거/순서 미정 */
  messages: DraftMessage[];
}

export interface RoleplayStartData {
  sessionId: UUID;
  activityId: UUID;
  scenarioId: UUID;
  scenarioVersion: number;
  status: 'IN_PROGRESS' | 'PAUSED' | 'COMPLETED';
  activityStatus: ActivityStatus;
  lastDeliveredTurnId: UUID | null;
  checkpointVersion: number;
  delivery: {
    messages: Array<{
      messageId: UUID;
      speakerRole: 'DIALOGUE';
      purpose: 'QUESTION' | 'RESUME';
      text: string;
      speech: { available: false; url: null };
    }>;
  };
}

// 설계 제안, 실제 DTO 확정 필요
export interface DraftRoleplaySession {
  /** 세션 */
  sessionId: UUID;
  /** 본인 활동 */
  activityId: UUID;
  /** 승인 콘텐츠 */
  scenarioId: UUID;
  /** 승인 버전 */
  scenarioVersion: number;
  /** IN_PROGRESS/PAUSED 등 제안 */
  status: string;
  /** 마지막 확정 턴 */
  lastDeliveredTurnId: UUID | null;
  /** 공개 검증 출력 */
  delivery: DraftDelivery;
}

// 설계 제안, 실제 DTO 확정 필요
export interface DraftSessionPatch {
  /** 정지/재개만 제안; COMPLETED 쓰기 금지 */
  status: 'PAUSED' | 'IN_PROGRESS';
}


// 설계 제안, 실제 DTO 확정 필요
export interface DraftTextBody {
  /** 한 새 입력, 재전송 유지 */
  clientInputId: UUID;
  /** 원문 입력; 길이/세부 검증 미정 */
  text: string;
}

// 설계 제안, 실제 DTO 확정 필요
export interface DraftInputAccepted {
  /** 입력 ID */
  inputId: UUID;
  /** 조회할 턴 */
  turnId: UUID;
  /** 접수 제안, 전달/완료 성공 아님 */
  status: 'PROCESSING';
}

// 설계 제안, 실제 DTO 확정 필요
export interface DraftInputQuality {
  /** 품질 코드 제안 */
  status: string;
  /** 저신뢰+음성 실패 최초 포함 합산 */
  voiceFailureCount: number;
  /** 3회부터 텍스트 권유 */
  textSuggested: boolean;
  /** 3회 이후에도 음성 유지 */
  voiceInputEnabled: boolean;
}

// 설계 제안, 실제 DTO 확정 필요
export interface DraftTurnResult {
  /** 턴 */
  turnId: UUID;
  /** PROCESSING/DELIVERED/REINPUT_REQUIRED 제안 */
  status: string;
  /** 실제 검증 출력이 있을 때만 */
  delivery?: DraftDelivery;
  /** 재입력 품질 안내 제안 */
  inputQuality?: DraftInputQuality;
}

// 설계 제안, 실제 DTO 확정 필요
export interface DraftGoalObservation {
  /** 목표별 ID */
  microGoalId: UUID;
  /** 정제된 관찰 근거; 원문/위험 사건 제외 */
  observedEvidence: string[];
  /** 시작 지원 */
  initialSupportLevel: SupportLevel;
  /** 현재 지원 */
  currentSupportLevel: SupportLevel;
  /** 최종 지원 */
  finalSupportLevel: SupportLevel | null;
  /** 유효 도달 턴 */
  turnsToAchievement: number | null;
  /** 유효 유도 횟수 */
  promptCount: number;
  /** 정답 제공 */
  answerRevealed: boolean;
  /** 독립 도달 */
  achievedWithoutAnswerReveal: boolean;
  /** 기술 제외 */
  excludedFromScoring: boolean;
}

export interface ActivityStatusData {
  /** status만; 읽기로 보상/완료 실행 금지 */
  status: ActivityStatus;
}

// 설계 제안, 실제 DTO 확정 필요
export interface DraftReportData {
  /** 기록 */
  recordId: UUID;
  /** 완료 활동 */
  activityId: UUID;
  /** 담당 아동 */
  childId: UUID;
  /** 완료 시각 */
  completedAt: ISODateTime;
  /** 관찰 근거 요약 */
  summary: string;
  /** 필드 세부 합의; 초기/독립 활동별 유무 미정 */
  quiz: Record<string, unknown>;
  /** 관찰/지원/일반 기술 제외 */
  roleplay: Record<string, unknown>;
  /** 최종 판정/정책 버전 */
  difficultyDecision: Record<string, unknown>;
}

// 설계 제안, 실제 DTO 확정 필요
export interface DraftRecommendationData {
  /** 추천 */
  recommendationId: UUID;
  /** 근거 기록 */
  recordId: UUID;
  /** 보완/반복/확장/적용 코드 미정 */
  type: string;
  /** 목표 제안 */
  suggestedGoal: Record<string, unknown>;
  /** 추천 이유 */
  reason: string;
  /** 관찰 근거 */
  evidence: string[];
  /** 충분성 코드 미정 */
  evidenceSufficiency: string;
  /** 미결정/강사 결정 */
  decision: string | null;
  /** 채택 목표 연결; 생성 방식 합의 */
  createdGoalId: UUID | null;
}

// 설계 제안, 실제 DTO 확정 필요
export interface DraftRecommendationDecision {
  /** 제안값; 확정 전 실서버 연결 금지 */
  decision: 'ACCEPT' | 'MODIFIED_ACCEPT' | 'DEFER' | 'REJECT';
  /** 수정 채택 목표/새 목표 생성 방식 미정 */
  goal?: Partial<GoalBody>;
  /** 거절 이유 선택 */
  rejectionReason?: string | null;
}


export type ActivityType = 'FIRST_COMBINED' | 'SELF_EMOTION_QUIZ' | 'OTHER_EMOTION_QUIZ' | 'ROLEPLAY';
export interface WithdrawalBody {
  /** 현재 비밀번호. 불일치 403 PASSWORD_MISMATCH; 공개 로그/URL에 기록 금지 */
  password: string;
}
export interface ActivityDetailData {
  /** 수행 인스턴스. goalId는 독립 퀴즈에서 null */
  activity: ActivityData;
  /** 배정된 문항; 정답/미발급 힌트 미포함 */
  quizItems: AssignedQuizData[];
  /** 현재 미연결로 항상 null. 향후 DTO는 별도 변경 */
  sessionSummary: null;
  /** 현재 미연결로 항상 null. 학습 리포트 구현을 의미하지 않음 */
  learningRecord: null;
}
export interface VoiceInputBody {
  /** 실제 음성 File. MIME/크기 제한은 서버 배포 설정 확인; 현재 길이 디코딩 검증 없음 */
  audio: File;
}
export type RoleplayDeliveryStatus = 'DELIVERED' | 'REINPUT_REQUIRED' | 'RETRY_REQUIRED' | 'STOPPED' | 'NOTICE_UNAVAILABLE' | 'PROCESSING';
export type RoleplayDeliveryAction = 'NONE' | 'REQUEST_NEW_INPUT' | 'RETRY_TURN' | 'STOP_DIALOGUE' | 'WAIT';
export interface RoleplaySpeechData {
  /** TTS 발행 URL이 있을 때만 true */
  available: boolean;
  /** 인증된 임시 음성 주소; 미제공/재전송은 null */
  url: string | null;
}
export interface RoleplayMessageData {
  /** 검증된 응답 또는 승인 고정 안내. 실패 후보 제외 */
  text: string;
  /** 음성 제공 정보 */
  speech: RoleplaySpeechData;
}
export interface RoleplayVoiceData {
  /** 실제 HTTP 200/202 상태. CONFLICT/BUSY는 공통 오류 envelope */
  status: RoleplayDeliveryStatus;
  /** FE의 다음 행동. PROCESSING은 WAIT */
  action: RoleplayDeliveryAction;
  /** 검증 출력/고정 안내. NOTICE_UNAVAILABLE 등에서는 [] */
  messages: RoleplayMessageData[];
  /** 신규 성공의 표준 발화만; 재전송/실패는 null. 완료 시 클라이언트 사본 삭제 */
  acceptedInput: string | null;
  /** 저장 확정 버전. 복구/처리 중에는 null */
  checkpointVersion: number | null;
  /** 같은 세션/키/입력의 저장 결과 재전송 여부 */
  replayed: boolean;
}
