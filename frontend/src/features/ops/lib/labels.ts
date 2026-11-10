import type { Outcome, RunPath, StepKind, StepStatus, Workflow } from '../api'

/**
 * 서버가 주는 고정 코드에 붙이는 한국어 이름. 판정·실패 사유 코드(REGENERATE, TIMEOUT 등)는 로그·코드와 맞춰 보도록 그대로 둔다.
 * 목록에 없는 코드는 코드 그대로 보여 준다. 서버에 새 단계가 생겨도 화면이 깨지지 않는다.
 */
export const WORKFLOWS: Workflow[] = ['ROLEPLAY_TURN', 'LEARNING_REPORT', 'SCENARIO_GENERATION']

export const WORKFLOW_LABEL: Record<Workflow, string> = {
  ROLEPLAY_TURN: '역할극 턴',
  LEARNING_REPORT: '학습 리포트',
  SCENARIO_GENERATION: '시나리오 생성',
}

export const PATHS: RunPath[] = ['NORMAL', 'RETRIED', 'GUIDED', 'FAILED']

export const PATH_LABEL: Record<RunPath, string> = {
  NORMAL: '한 번에 통과',
  RETRIED: '재시도 후 정상',
  GUIDED: '안내로 종료',
  FAILED: '오류',
}

export const PATH_HINT: Record<RunPath, string> = {
  NORMAL: '거절·실패 없이 정상 결과',
  RETRIED: '거절·실패 뒤 다시 돌아 정상 결과',
  GUIDED: '입력 문제·안전 판정으로 안내 문구',
  FAILED: '호출 실패·한도·시간 초과·서버 오류',
}

/** 성공 = 정상 결과로 끝남(한 번에 통과·재시도 후 정상), 실패 = 정상 결과 없이 끝남(안내로 종료·오류). */
export const OUTCOMES: Outcome[] = ['SUCCESS', 'FAILURE']

export const OUTCOME_PATHS: Record<Outcome, RunPath[]> = {
  SUCCESS: ['NORMAL', 'RETRIED'],
  FAILURE: ['GUIDED', 'FAILED'],
}

export const OUTCOME_LABEL: Record<Outcome, string> = {
  SUCCESS: '성공 흐름',
  FAILURE: '실패 흐름',
}

export const OUTCOME_HINT: Record<Outcome, string> = {
  SUCCESS: '정상 결과(응답 전달·리포트 완성·시나리오 완성)로 끝난 실행. 중간에 거절·재시도가 있었어도 결과가 나갔으면 여기입니다.',
  FAILURE: '정상 결과 없이 끝난 실행. 입력·안전 판정으로 다시 입력을 요청했거나, 호출 실패·한도·시간 초과로 끝났습니다.',
}

/** PathCounts 의 키. 색 변수 이름(--ops-path-*)에도 같은 말을 쓴다. */
export const PATH_KEY: Record<RunPath, 'normal' | 'retried' | 'guided' | 'failed'> = {
  NORMAL: 'normal',
  RETRIED: 'retried',
  GUIDED: 'guided',
  FAILED: 'failed',
}

export const STATUS_LABEL: Record<StepStatus, string> = {
  PASSED: '통과',
  REJECTED: '거절',
  FAILED: '실패',
}

export const KIND_LABEL: Record<StepKind, string> = {
  CALL: 'AI 호출',
  CHECK: '코드 판정',
  RESULT: '최종 결과',
}

const STEP_LABEL: Record<string, string> = {
  SPEECH_TRANSCRIPTION: '음성 인식',
  INPUT_VALIDATION: '입력 판정',
  CAUSE_ANALYSIS: '원인 분석',
  RESPONSE_GENERATION: '응답 생성',
  RESPONSE_EVALUATION: '응답 검수',
  SPEECH_SYNTHESIS: '음성 합성',
  TURN_RESOLUTION: '턴 결과 판정',
  REPORT_GENERATION: '리포트 초안',
  REPORT_RULE_CHECK: '규칙 검사',
  REPORT_REVIEW: '리포트 검수',
  REPORT_QUALITY_GATE: '통과 기준 확인',
  REPORT_CALL_LIMIT: '호출 한도',
  SCENARIO_GENERATION: '시나리오 생성',
  RESULT: '최종 결과',
  // 턴 결과 판정이 지목하는 곳(declaredNext). 실제 결과 코드와 같은 이름이면 화면에서 겹쳐 보이지 않는다.
  RESPONSE_DELIVERY: '응답 전달',
  SAFETY_STOP: '대화 중단',
  NOTICE_INPUT_RECOVERY: '다시 입력 요청',
  NOTICE_INPUT_QUALITY: '다시 입력 요청',
  NOTICE_TIMEOUT: '시간 초과 안내',
  NOTICE_INPUT_CONFIRMATION: '입력 확인 질문',
  NOTICE_SAFE_FALLBACK: '안전한 대체 응답',
  NOTICE_SAFETY_CHECK_ERROR: '안전 확인 오류 안내',
  NOTICE_SAFETY_ESCALATION_CHILD: '대화 중단 안내(아동)',
  NOTICE_SAFETY_ESCALATION_TEACHER: '대화 중단 안내(교사 확인)',
  // 흐름의 끝(최종 결과 코드)
  DELIVERED: '응답 전달',
  REINPUT_REQUIRED: '다시 입력 요청',
  RETRY_REQUIRED: '다시 시도 요청',
  STOPPED: '대화 중단',
  NOTICE_UNAVAILABLE: '안내 문구 없음',
  CONFLICT: '중복 요청',
  PROCESSING: '처리 중',
  BUSY: '작업자 부족',
  COMPLETED: '리포트 완성',
  GENERATED: '시나리오 완성',
  FAILED: '생성 실패',
  ABORTED: '예외로 중단',
}

/**
 * 시간 구간 이름. HTTP_REQUEST 의 자체 시간은 안쪽 구간 어디에도 들지 않은 요청 처리 시간(인증·응답 쓰기 등)이다.
 * WORKER_* 는 역할극 턴을 처리하는 작업자 스레드의 대기·실행이다.
 */
const STAGE_LABEL: Record<string, string> = {
  HTTP_REQUEST: '그 밖의 요청 처리',
  REQUEST_PREPARATION: '요청 확인',
  UPLOAD_VALIDATION: '음성 파일 검사',
  WORKER_QUEUE: '처리 순서 대기',
  WORKER_EXECUTION: '턴 처리',
  TURN_PREPARATION: '턴 준비',
  REQUEST_CLAIM: '중복 요청 확인',
  CHECKPOINT_LOOKUP: '이전 결과 조회',
  CONTEXT_PREPARATION: '대화 문맥 준비',
  INPUT_PROCESSING: '판정·응답 만들기',
  STT: '음성 인식',
  LLM_CALL: 'AI 호출',
  OUTPUT_VALIDATION: 'AI 출력 형식 검사',
  TTS: '음성 합성',
  RESULT_COMMIT: '결과 저장',
}

/**
 * 판정·사유 코드의 뜻. 화면에는 뜻을 먼저 쓰고 코드를 옆에 그대로 둔다(로그·코드에서 찾을 수 있게).
 * 서버 enum: 입력 판정(ModelRoleplayInputProcessor.Decision), 응답 검수(EvaluationDecision·RoleplayCodes.FAILURE_CODES),
 * 리포트(ReportReviewDecision·RetryingLearningReportGenerator), 턴 결과 판정(RoleplayTurnOutcomeResolver·RoleplayTurnResult).
 */
const CODE_LABEL: Record<string, string> = {
  // 입력 판정
  ANALYZABLE: '분석할 수 있음',
  REINPUT: '다시 말해야 함',
  UNSAFE: '안전하지 않은 입력',
  UNAVAILABLE: '판정할 수 없음',
  // AI 출력 형식
  VALID: '형식 맞음',
  INVALID_OUTPUT_FORMAT: 'AI 출력 형식 오류',
  INDETERMINATE_OUTPUT: '판단할 근거 부족',
  // 응답 검수
  PASS: '통과',
  REGENERATE: '응답 다시 만들기',
  REANALYZE: '원인 다시 분석',
  CONFIRM_INPUT: '입력 뜻 다시 확인',
  RETRY_EVALUATION: '검수 다시',
  SAFETY_REGENERATE: '안전하게 다시 만들기',
  SAFE_FALLBACK: '안전한 대체 응답',
  SAFETY_ESCALATION: '안전 문제로 중단',
  UNSAFE_EXPRESSION: '안전하지 않은 표현',
  ASKS_PERSONAL_INFO: '개인정보를 물음',
  SCENARIO_CONTRADICTION: '시나리오와 어긋남',
  REINFORCES_INCORRECT_ANSWER: '틀린 답을 굳힘',
  INTERNAL_INSTRUCTION_LEAK: '내부 지시가 드러남',
  IMMEDIATE_RISK_IN_INPUT: '입력에 즉시 위험',
  EMPTY_OR_TRUNCATED: '비었거나 잘림',
  MISSING_QUESTION: '질문이 없음',
  MULTIPLE_QUESTIONS: '질문이 여러 개',
  TOO_LONG: '너무 김',
  TOO_DIFFICULT: '너무 어려움',
  ANSWER_TOO_DIRECT: '답을 바로 알려 줌',
  EMOTION_ASSERTED_AS_FACT: '감정을 사실처럼 단정',
  PROHIBITED_INFERENCE: '금지된 추측',
  UNSUPPORTED_CAUSE_ANALYSIS: '근거 없는 원인 분석',
  STATE_STRATEGY_MISMATCH: '상태와 전략이 안 맞음',
  STT_TREATED_AS_LEARNING_GAP: '인식 오류를 학습 부족으로 봄',
  ANALYSIS_RESPONSE_MISMATCH: '분석과 응답이 안 맞음',
  GOAL_SKIPPED: '목표를 건너뜀',
  SUPPORT_LEVEL_MISMATCH: '지원 수준이 안 맞음',
  REPEATED_QUESTION: '같은 질문 반복',
  NOT_ANSWERABLE: '답하기 어려운 질문',
  JUDGMENTAL_TONE: '평가하는 말투',
  INPUT_MEANING_UNCLEAR: '입력 뜻이 불분명',
  CHARACTER_BREAK: '역할에서 벗어남',
  // 시나리오 생성
  CONDITION_VIOLATED: '강사 조건 위반',
  NO_MATCHING_CANDIDATE: '맞는 후보 없음',
  CANDIDATES_EXHAUSTED: '후보를 다 씀',
  SCENARIO_GENERATOR_UNAVAILABLE: '시나리오 생성기 꺼짐',
  // 음성 합성
  OK: '정상',
  // 리포트
  REVISE: '고쳐 쓰기',
  RULES_PASSED: '규칙 통과',
  RULES_REJECTED: '규칙 위반',
  PASSED: '기준 통과',
  REVIEW_REJECTED: '검수 기준 미달',
  QUALITY_LIMIT_REACHED: '고쳐 쓰기 한도 도달',
  CALL_LIMIT_REACHED: '호출 한도 도달',
  // 턴 결과 판정
  APPROVED: '응답 승인',
  INPUT_REJECTED: '입력 거절',
  INPUT_UNSAFE: '안전하지 않은 입력',
  INPUT_CHECK_UNAVAILABLE: '입력 확인 불가',
  TIMED_OUT: '시간 초과',
  CALL_FAILED: '호출 실패',
  REJECTED: '거절',
  LIMIT_REACHED: '재시도 한도 도달',
  NON_RETRYABLE_FAILURE: '다시 해도 안 되는 오류',
  MODEL_REQUESTED: '모델이 다른 경로 요청',
  INPUT_SAFETY_REJECTED: '입력 안전 거절',
  INCONSISTENT_EVALUATION: '검수 결과가 서로 모순',
  // 입력 실패 사유
  NO_SPEECH: '말소리 없음',
  LOW_CONFIDENCE: '음성 인식 신뢰도 낮음',
  UNKNOWN_CONFIDENCE: '인식 신뢰도 모름',
  TRANSCRIPTION_FAILED: '음성 인식 실패',
  INVALID_INPUT: '알아들을 수 없는 입력',
  EMPTY_UPLOAD: '빈 음성 파일',
  UNSUPPORTED_AUDIO: '지원하지 않는 음성 형식',
  INVALID_AUDIO: '잘못된 음성 파일',
  AUDIO_INSPECTION_UNAVAILABLE: '음성 검사 불가',
  UPLOAD_TOO_LARGE: '음성 파일이 너무 큼',
  AUDIO_TOO_LONG: '음성이 너무 김',
  TIMEOUT: '시간 초과',
}

/** 코드의 한국어 뜻. 모르는 코드는 null 이라 화면은 코드만 보인다. 호출 실패는 공급자 오류 종류가 붙어 접두어로 본다. */
export function codeLabel(code: string): string | null {
  if (code.startsWith('PROVIDER_FAILURE')) return 'AI 호출 실패'
  return CODE_LABEL[code] ?? null
}

/** 워크플로우 안에서 단계가 도는 순서. 표를 흐름 순서대로 줄 세울 때 쓴다. 모르는 단계는 뒤로 간다. */
const STEP_ORDER = [
  'SPEECH_TRANSCRIPTION',
  'INPUT_VALIDATION',
  'CAUSE_ANALYSIS',
  'RESPONSE_GENERATION',
  'RESPONSE_EVALUATION',
  'SPEECH_SYNTHESIS',
  'TURN_RESOLUTION',
  'REPORT_GENERATION',
  'REPORT_RULE_CHECK',
  'REPORT_REVIEW',
  'REPORT_QUALITY_GATE',
  'SCENARIO_GENERATION',
]

export function stepOrder(code: string): number {
  const index = STEP_ORDER.indexOf(code)
  return index < 0 ? STEP_ORDER.length : index
}

export function stepLabel(code: string | null | undefined): string {
  if (!code) return '—'
  return STEP_LABEL[code] ?? code
}

export function stageLabel(code: string): string {
  return STAGE_LABEL[code] ?? code
}

/** 같은 단계가 여러 번 돌았을 때만 순번을 붙인다. */
export function stepTitle(step: string, attempt: number): string {
  return attempt > 1 ? `${stepLabel(step)} ${attempt}번째` : stepLabel(step)
}
