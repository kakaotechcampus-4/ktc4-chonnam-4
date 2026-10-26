import { describe, expect, it } from "vitest"
import { contradictingForbidden } from "./goalConditions"

// 서버 GoalConditionsTest 의 모순 규칙과 같은 예시. 한쪽만 바뀌면 화면은 통과시키고 서버는 거절한다.
describe("contradictingForbidden", () => {
  it("필수 요소 안에 들어 있는 피할 표현의 위치를 띄어쓰기·대소문자 무시하고 돌려준다", () => {
    expect(contradictingForbidden(["위로 표현 1회", "Hi 라고 인사하기"], ["화내지 마", "위 로", "hi"])).toEqual([1, 2])
  })

  it("겹치지 않으면 빈 목록이다", () => {
    expect(contradictingForbidden(["감정을 확인하는 질문 1회", "위로 표현 1회"], ["괜찮아", "울지 마"])).toEqual([])
  })

  it("필수 요소가 없으면 아무것도 걸리지 않는다", () => {
    expect(contradictingForbidden([], ["위로"])).toEqual([])
  })
})
