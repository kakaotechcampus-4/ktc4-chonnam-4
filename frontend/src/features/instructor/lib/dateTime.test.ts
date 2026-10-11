import { describe, expect, it } from "vitest"
import { formatDateTime } from "./dateTime"

// 기준 시각: 한국 시간 2026-10-03 18:00
const NOW = new Date("2026-10-03T18:00:00+09:00")

describe("formatDateTime", () => {
  it("오늘이면 '오늘' 과 시각만 보인다", () => {
    expect(formatDateTime("2026-10-03T17:31:00+09:00", NOW)).toBe("오늘 17:31")
  })

  it("올해의 다른 날은 월/일과 시각을 보인다", () => {
    expect(formatDateTime("2026-09-05T09:05:00+09:00", NOW)).toBe("9/5 09:05")
  })

  it("작년 이전은 연도까지 보인다", () => {
    expect(formatDateTime("2025-12-30T23:10:00+09:00", NOW)).toBe("2025/12/30 23:10")
  })

  it("UTC 로 와도 한국 날짜로 판단한다(UTC 로는 어제지만 한국은 오늘)", () => {
    expect(formatDateTime("2026-10-02T15:30:00Z", NOW)).toBe("오늘 00:30")
  })

  it("값이 없으면 '-'", () => {
    expect(formatDateTime(null, NOW)).toBe("-")
  })
})
