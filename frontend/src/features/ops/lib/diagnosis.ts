import type { DiagnosisFinding, DiagnosisSeverity } from '../api'
import { count, duration } from './format'
import { codeLabel, stepLabel, WORKFLOW_LABEL } from './labels'

/**
 * 자동 진단 문장. 서버는 kind·step·code·숫자만 주고, 문장과 "볼 곳"은 여기서 만든다.
 * 볼 곳은 판정·사유 코드가 있으면 코드로, 없으면 단계로 고른다.
 */
export const SEVERITY_LABEL: Record<DiagnosisSeverity, string> = {
  CRITICAL: '심각',
  WARNING: '주의',
  INFO: '참고',
}

/** 문장 안의 코드는 뜻을 앞에 두고 코드를 괄호에 남긴다. 모르는 코드는 코드만 쓴다. */
function named(code: string): string {
  const label = codeLabel(code) ?? stepLabel(code)
  return label === code ? code : `「${label}」(${code})`
}

function rate(value: number): string {
  const percent = value * 100
  return `${percent > 0 && percent < 1 ? '<1' : Math.round(percent)}%`
}

function value(finding: DiagnosisFinding, raw: number): string {
  if (finding.unit === 'RATE') return rate(raw)
  if (finding.unit === 'MS') return duration(raw)
  return `${count(raw)}건`
}

function where(finding: DiagnosisFinding): string {
  const workflow = finding.workflow ? `${WORKFLOW_LABEL[finding.workflow]} ` : ''
  return finding.step ? `${workflow}${stepLabel(finding.step)}` : workflow.trim()
}

/** 한 줄 요약. 앞 기간 값이 있으면 괄호로 붙인다. */
export function findingTitle(finding: DiagnosisFinding): string {
  const before = finding.previous === null ? '' : ` (앞 기간 ${value(finding, finding.previous)})`
  switch (finding.kind) {
    case 'FAILURE_RATE':
      return `실패 비율이 ${rate(finding.current)}입니다${before}`
    case 'NEW_FAILURE_FLOW':
      return `앞 기간에 없던 실패 흐름이 ${count(finding.current)}건 나왔습니다: ${where(finding)}${
        finding.code ? ` ${named(finding.code)}` : ''
      }`
    case 'STEP_BLOCKED':
      return `${where(finding)} 단계의 거절·실패가 ${rate(finding.current)}입니다${before}${
        finding.code ? `. 가장 많은 판정은 ${named(finding.code)}` : ''
      }`
    case 'REASON_SURGE':
      return `${finding.code ? named(finding.code) : '같은'} 사유로 끝난 실행이 ${count(finding.current)}건으로 늘었습니다${before}${
        finding.step ? ` · ${where(finding)}` : ''
      }`
    case 'LATENCY':
      return `역할극 턴 응답 시간 p95가 ${duration(finding.current)}입니다${before}`
  }
}

const CODE_HINT: Record<string, string> = {
  LOW_CONFIDENCE: '음성 인식 신뢰도 기준(STT confidence threshold)과 STT 모델·logprobs 설정을 확인합니다.',
  UNKNOWN_CONFIDENCE: 'STT 가 신뢰도를 돌려주지 않습니다. STT_INCLUDE_LOGPROBS·STT_MODEL 설정을 확인합니다.',
  NO_SPEECH: '말소리가 잡히지 않습니다. 마이크 입력과 녹음 길이를 확인합니다.',
  TRANSCRIPTION_FAILED: 'STT 호출이 실패했습니다. STT 제공자 연결과 키를 확인합니다.',
  TIMEOUT: 'AI 제공자 응답이 늦습니다. 모델 상태와 호출 시간 제한(AI_TIMEOUT·턴 제한 시간)을 확인합니다.',
  LIMIT_REACHED: '재시도 한도까지 실패했습니다. 같은 실행 앞 단계의 판정(TIMEOUT·INVALID_OUTPUT_FORMAT 등)을 확인합니다.',
  INVALID_OUTPUT_FORMAT: '모델 출력이 형식을 어겼습니다. 출력 토큰 상한(종료 이유 length)과 응답 스키마를 확인합니다.',
  INDETERMINATE_OUTPUT: '모델이 판단을 보류했습니다. 입력 문맥이 모자란지 상세에서 확인합니다.',
  REGENERATE: '검수가 응답을 자주 돌려보냅니다. 거절·실패 사유 코드를 보고 생성 프롬프트와 검수 기준이 맞는지 점검합니다.',
  REANALYZE: '검수가 원인 분석을 다시 하라고 자주 판정합니다. 분석 프롬프트와 검수 기준을 점검합니다.',
  UNSAFE: '안전 판정이 늘었습니다. 상세에서 실제로 위험한 입력인지, 판정이 지나친지 확인합니다.',
  INPUT_UNSAFE: '안전 판정이 늘었습니다. 상세에서 실제로 위험한 입력인지, 판정이 지나친지 확인합니다.',
  INPUT_CHECK_UNAVAILABLE: '입력 판정 호출이 실패했습니다. AI 제공자 연결을 확인합니다.',
  INCONSISTENT_EVALUATION: '검수 출력이 앞뒤가 맞지 않습니다. 검수 응답 스키마와 프롬프트를 확인합니다.',
  RULES_REJECTED: '리포트 초안이 규칙 검사에 자주 걸립니다. 거절·실패 사유 코드(TOO_LONG 등)를 확인합니다.',
  REVIEW_REJECTED: '리포트 검수가 초안을 자주 돌려보냅니다. 초안 프롬프트와 검수 기준을 점검합니다.',
  QUALITY_LIMIT_REACHED: '리포트가 세 바퀴 안에 통과하지 못했습니다. 검수 사유 코드와 초안 프롬프트를 점검합니다.',
  CONDITION_VIOLATED: '시나리오가 강사 조건을 어깁니다. 등장인물·피할 표현 조건과 생성 프롬프트를 확인합니다.',
}

const STEP_HINT: Record<string, string> = {
  SPEECH_TRANSCRIPTION: 'STT 설정과 녹음 환경을 확인합니다.',
  INPUT_VALIDATION: '입력 판정 기준(입력 판정 프롬프트)을 확인합니다.',
  CAUSE_ANALYSIS: '원인 분석 출력 형식과 출력 토큰 상한을 확인합니다.',
  RESPONSE_GENERATION: '응답 생성 프롬프트와 출력 토큰 상한을 확인합니다.',
  RESPONSE_EVALUATION: '검수 기준과 응답 생성 프롬프트가 맞는지 점검합니다.',
  SPEECH_SYNTHESIS: 'TTS 제공자 연결과 키를 확인합니다.',
  REPORT_GENERATION: '리포트 초안 프롬프트와 출력 토큰 상한을 확인합니다.',
  REPORT_REVIEW: '리포트 검수 기준을 확인합니다.',
  SCENARIO_GENERATION: '시나리오 생성 프롬프트와 강사 조건을 확인합니다.',
}

/** 사람이 먼저 볼 곳. */
export function findingHint(finding: DiagnosisFinding): string {
  switch (finding.kind) {
    case 'FAILURE_RATE':
      return '아래 실패 흐름 구역의 흐름 패턴과 실패 원인에서 가장 많은 줄부터 봅니다.'
    case 'LATENCY':
      return `턴 제한 시간에 가깝습니다(주의 기준 ${duration(finding.threshold)}). 단계 분석의 구간별 시간에서 오래 걸린 구간을 보고, AI 호출이면 모델과 출력 토큰 상한을 확인합니다.`
    case 'NEW_FAILURE_FLOW':
      return `앞 기간에는 없던 흐름입니다. ${
        (finding.code && CODE_HINT[finding.code]) || 'Trace ID 로 상세를 열어 어느 판정에서 갈렸는지 확인합니다.'
      }`
    default:
      return (
        (finding.code && CODE_HINT[finding.code]) ||
        (finding.step && STEP_HINT[finding.step]) ||
        'Trace ID 로 상세를 열어 그 단계의 판정을 확인합니다.'
      )
  }
}
