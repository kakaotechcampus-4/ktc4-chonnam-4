/**
 * 내 감정 문항(SELF_EMOTION_SITUATION)의 카메라 표정 판정 규칙. 화면·카메라와 떨어진 순수 함수다.
 *
 * 브라우저 표정 모델(face-api)은 얼굴 하나에 대해 7가지 표정 확률(합 1)을 준다.
 * 아이가 고른 보기 글자를 모델 표정으로 바꾼 뒤, 확률이 가장 높은 표정과 비교한다.
 * - 가장 높은 표정의 확률이 MIN_EXPRESSION_CONFIDENCE(50%) 미만이면 모델도 확신이 없으므로 판정하지 않는다.
 * - 서버(API-QUIZ-04)는 MATCH·MISMATCH·NOT_ANALYZABLE 만 받는다. 다시 찍기는 첫 제출 전에 화면에서 끝낸다.
 */

/** face-api 표정 이름 */
export type FaceExpression = "neutral" | "happy" | "sad" | "angry" | "fearful" | "disgusted" | "surprised"

export type ExpressionScores = Record<FaceExpression, number>

/** 이 확률 이상일 때만 표정을 판정한다. */
export const MIN_EXPRESSION_CONFIDENCE = 0.5

/** 첫 제출 전에 다시 찍을 수 있는 횟수 (API-QUIZ-04 "재촬영 최대 2회"). 처음 1번 + 다시 2번 = 3번 찍는다. */
export const MAX_RETAKES = 2

/** 판정에 쓰는 최근 측정. 한 장면만 보면 눈 깜빡임 등으로 흔들려서 짧은 구간을 평균 낸다. */
export const READING_WINDOW_MS = 1_500
export const MAX_READINGS = 3

const FACE_EXPRESSIONS: FaceExpression[] = ["neutral", "happy", "sad", "angry", "fearful", "disgusted", "surprised"]

/**
 * 서버 보기 글자 → 모델 표정. 문항 시드(R__quiz_items.sql)의 보기는 기쁨·슬픔·화남·놀람이다.
 * 속상함은 모델에 따로 없어 얼굴로 가장 가까운 슬픔으로 본다. 여기에 없는 글자는 카메라로 판정할 수 없다.
 */
const CHOICE_TO_EXPRESSION: Record<string, FaceExpression> = {
  기쁨: "happy",
  슬픔: "sad",
  속상함: "sad",
  화남: "angry",
  놀람: "surprised",
  무서움: "fearful",
}

/** 아이에게 들려줄 모델 표정 이름 */
export const EXPRESSION_LABELS: Record<FaceExpression, string> = {
  neutral: "무표정",
  happy: "기쁨",
  sad: "슬픔",
  angry: "화남",
  fearful: "무서움",
  disgusted: "싫음",
  surprised: "놀람",
}

export function expressionForChoice(choice: string): FaceExpression | null {
  return CHOICE_TO_EXPRESSION[choice] ?? null
}

export type ExpressionReading = { at: number; scores: ExpressionScores }

/** 최근 측정만 남긴다(가장 최근 MAX_READINGS 개, READING_WINDOW_MS 이내). */
export function recentReadings(readings: ExpressionReading[], now: number): ExpressionReading[] {
  return readings.filter((reading) => now - reading.at <= READING_WINDOW_MS).slice(-MAX_READINGS)
}

/** 최근 측정의 표정별 평균 확률. 측정이 없으면(얼굴을 못 찾음) null. */
export function averageScores(readings: ExpressionReading[]): ExpressionScores | null {
  if (readings.length === 0) return null
  const sum = Object.fromEntries(FACE_EXPRESSIONS.map((name) => [name, 0])) as ExpressionScores
  for (const { scores } of readings) {
    for (const name of FACE_EXPRESSIONS) sum[name] += scores[name] ?? 0
  }
  for (const name of FACE_EXPRESSIONS) sum[name] /= readings.length
  return sum
}

export function topExpression(scores: ExpressionScores): { expression: FaceExpression; score: number } {
  let best: FaceExpression = "neutral"
  for (const name of FACE_EXPRESSIONS) {
    if ((scores[name] ?? 0) > (scores[best] ?? 0)) best = name
  }
  return { expression: best, score: scores[best] ?? 0 }
}

export type ExpressionVerdict =
  /** 고른 표정과 같다 */
  | { kind: "match" }
  /** 확실히(50% 이상) 다른 표정이다. 무표정도 여기에 든다. */
  | { kind: "mismatch"; detected: FaceExpression }
  /** 얼굴을 못 찾았다 */
  | { kind: "no-face" }
  /** 얼굴은 찾았지만 어느 표정도 50%를 넘지 않는다 */
  | { kind: "unsure" }
  /** 고른 보기가 모델에 없는 감정이라 판정할 수 없다 */
  | { kind: "unsupported" }

export function judgeExpression(choice: string, scores: ExpressionScores | null): ExpressionVerdict {
  const target = expressionForChoice(choice)
  if (!target) return { kind: "unsupported" }
  if (!scores) return { kind: "no-face" }
  const top = topExpression(scores)
  if (top.score < MIN_EXPRESSION_CONFIDENCE) return { kind: "unsure" }
  // 속상함처럼 두 보기가 같은 모델 표정을 가리켜도 모델 표정끼리 비교하므로 문제없다.
  if (top.expression === target) return { kind: "match" }
  return { kind: "mismatch", detected: top.expression }
}

/** 더 다시 찍을 수 없을 때 서버로 보낼 값. 확실히 다른 표정이면 MISMATCH, 판정 자체가 안 되면 NOT_ANALYZABLE. */
export function finalMatchResult(verdict: ExpressionVerdict): "MATCH" | "MISMATCH" | "NOT_ANALYZABLE" {
  if (verdict.kind === "match") return "MATCH"
  if (verdict.kind === "mismatch") return "MISMATCH"
  return "NOT_ANALYZABLE"
}
