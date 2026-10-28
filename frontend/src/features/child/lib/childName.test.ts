import { describe, expect, it } from "vitest"
import { givenName, withNameSuffix, withVocative } from "./childName"

// 아동 화면은 실명에서 성을 빼고 이름만 부른다(사용자 결정 2026-10-27). 의도적으로 규칙을 바꾸면 이 테스트도 같이 고치세요.
describe("아동 호칭", () => {
  it.each([
    ["김하늘", "하늘"],
    ["이서연", "서연"],
    ["남궁민수", "민수"],
    ["김 하늘", "하늘"],
    ["  박지우  ", "지우"],
    ["하늘", "하늘"],
    ["이안", "이안"],
    ["솔", "솔"],
  ])("%s 는 %s 로 부른다", (name, expected) => {
    expect(givenName(name)).toBe(expected)
  })

  it("받침에 따라 부르는 말과 조사를 맞춘다", () => {
    expect(withVocative("김하늘")).toBe("하늘아")
    expect(withVocative("박지우")).toBe("지우야")
    expect(withNameSuffix("김하늘")).toBe("하늘이")
    expect(withNameSuffix("박지우")).toBe("지우")
  })
})
