import { describe, expect, it } from "vitest"
import { supportChangeLabel } from "./reportHelpers"

// 추천 상세 예상 조건의 "S1 (한 단계 하향)" 괄호(시안 T-REC-02). 지원이 줄면 하향, 늘면 상향이다.
describe("supportChangeLabel", () => {
  it("이번 시작 지원과 다음 시작 지원의 단계 차이를 말로 쓴다", () => {
    expect(supportChangeLabel("S1", "S1")).toBe("유지")
    expect(supportChangeLabel("S2", "S1")).toBe("한 단계 하향")
    expect(supportChangeLabel("S1", "S3")).toBe("두 단계 상향")
  })
})
