import type { RunPath, StepKind, StepStatus, Workflow } from '../api'

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

const STAGE_LABEL: Record<string, string> = {
  HTTP_REQUEST: '분류 안 된 시간',
  REQUEST_PREPARATION: '요청 준비',
  UPLOAD_VALIDATION: '음성 파일 검사',
  WORKER_QUEUE: '작업 대기',
  WORKER_EXECUTION: '작업 실행(기타)',
  TURN_PREPARATION: '턴 준비',
  REQUEST_CLAIM: '중복 요청 확인',
  CHECKPOINT_LOOKUP: '저장된 결과 조회',
  CONTEXT_PREPARATION: '문맥 준비',
  INPUT_PROCESSING: '입력 처리',
  STT: '음성 인식',
  LLM_CALL: 'AI 호출',
  OUTPUT_VALIDATION: '출력 검사',
  TTS: '음성 합성',
  RESULT_COMMIT: '결과 저장',
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
