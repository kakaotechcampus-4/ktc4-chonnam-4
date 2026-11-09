import { describe, expect, it } from "vitest"
import {
  averageScores,
  expressionForChoice,
  finalMatchResult,
  judgeExpression,
  MAX_READINGS,
  MIN_EXPRESSION_CONFIDENCE,
  READING_WINDOW_MS,
  recentReadings,
  type ExpressionScores,
} from "./expressionJudge"
import { CAMERA_MODEL_VERSION, FACE_API_VERSION } from "./faceExpressionModel"
import packageJson from "../../../../package.json?raw"

function scores(values: Partial<ExpressionScores>): ExpressionScores {
  return { neutral: 0, happy: 0, sad: 0, angry: 0, fearful: 0, disgusted: 0, surprised: 0, ...values }
}

describe("표정 판정 기준", () => {
  it("최소 확률은 50%다", () => {
    expect(MIN_EXPRESSION_CONFIDENCE).toBe(0.5)
  })

  it("고른 표정이 50% 이상으로 가장 높으면 MATCH", () => {
    expect(judgeExpression("기쁨", scores({ happy: 0.5, neutral: 0.5 - 1e-9 }))).toEqual({ kind: "match" })
    expect(judgeExpression("화남", scores({ angry: 0.92, sad: 0.08 }))).toEqual({ kind: "match" })
  })

  it("가장 높은 표정도 50% 미만이면 판정하지 않는다", () => {
    expect(judgeExpression("기쁨", scores({ happy: 0.49, neutral: 0.3, sad: 0.21 }))).toEqual({ kind: "unsure" })
  })

  it("다른 표정이 50% 이상이면 MISMATCH 이고, 무표정도 다른 표정으로 본다", () => {
    expect(judgeExpression("기쁨", scores({ sad: 0.7, happy: 0.3 }))).toEqual({ kind: "mismatch", detected: "sad" })
    expect(judgeExpression("놀람", scores({ neutral: 0.9 }))).toEqual({ kind: "mismatch", detected: "neutral" })
  })

  it("얼굴이 없으면 no-face, 모델에 없는 감정 보기는 판정할 수 없다", () => {
    expect(judgeExpression("기쁨", null)).toEqual({ kind: "no-face" })
    expect(judgeExpression("평온함", scores({ neutral: 1 }))).toEqual({ kind: "unsupported" })
  })

  it("서버 보기 글자를 모델 표정으로 바꾼다(속상함은 슬픔으로 본다)", () => {
    expect(expressionForChoice("기쁨")).toBe("happy")
    expect(expressionForChoice("슬픔")).toBe("sad")
    expect(expressionForChoice("속상함")).toBe("sad")
    expect(expressionForChoice("화남")).toBe("angry")
    expect(expressionForChoice("놀람")).toBe("surprised")
  })

  it("더 다시 찍을 수 없을 때는 확실히 다른 표정만 MISMATCH, 나머지는 NOT_ANALYZABLE 로 보낸다", () => {
    expect(finalMatchResult({ kind: "match" })).toBe("MATCH")
    expect(finalMatchResult({ kind: "mismatch", detected: "neutral" })).toBe("MISMATCH")
    expect(finalMatchResult({ kind: "unsure" })).toBe("NOT_ANALYZABLE")
    expect(finalMatchResult({ kind: "no-face" })).toBe("NOT_ANALYZABLE")
    expect(finalMatchResult({ kind: "unsupported" })).toBe("NOT_ANALYZABLE")
  })
})

describe("최근 측정 평균", () => {
  it("오래된 측정은 버리고 가장 최근 몇 개만 남긴다", () => {
    const now = 10_000
    const readings = [
      { at: now - READING_WINDOW_MS - 1, scores: scores({ sad: 1 }) },
      { at: now - 900, scores: scores({ happy: 1 }) },
      { at: now - 600, scores: scores({ happy: 1 }) },
      { at: now - 300, scores: scores({ happy: 1 }) },
      { at: now, scores: scores({ neutral: 1 }) },
    ]
    const kept = recentReadings(readings, now)
    expect(kept).toHaveLength(MAX_READINGS)
    expect(kept.at(-1)?.scores.neutral).toBe(1)
    expect(kept.some((reading) => reading.scores.sad === 1)).toBe(false)
  })

  it("표정별 확률을 평균 내고, 측정이 없으면 null", () => {
    const average = averageScores([
      { at: 0, scores: scores({ happy: 0.8, neutral: 0.2 }) },
      { at: 1, scores: scores({ happy: 0.4, neutral: 0.6 }) },
    ])
    expect(average?.happy).toBeCloseTo(0.6)
    expect(average?.neutral).toBeCloseTo(0.4)
    expect(averageScores([])).toBeNull()
  })
})

describe("모델 버전", () => {
  it("설치한 face-api 버전과 같고, 서버 컬럼 길이(100자) 안이다", () => {
    const pkg = JSON.parse(packageJson)
    expect(pkg.dependencies["@vladmandic/face-api"]).toBe(FACE_API_VERSION)
    expect(CAMERA_MODEL_VERSION).toContain(FACE_API_VERSION)
    expect(CAMERA_MODEL_VERSION.length).toBeLessThanOrEqual(100)
  })
})
