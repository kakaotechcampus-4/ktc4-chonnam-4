import { render, screen } from "@testing-library/react"
import { describe, expect, it } from "vitest"
import { Character } from "./Character"

// 캐릭터 표정 검사. 정답·완료는 신남(happy), 오답·재시도는 시무룩(sad) 그림을 쓴다.

describe("캐릭터 표정", () => {
  it("표정을 주지 않으면 기본 그림을 쓴다", () => {
    render(<Character name="turtle" />)

    expect(screen.getByRole("img", { name: "거북이 안내자" })).toHaveAttribute(
      "src",
      expect.stringMatching(/turtle\.png/)
    )
  })

  it("표정에 맞는 그림을 쓰고, 이름(대체 텍스트)은 그대로 둔다", () => {
    render(
      <>
        <Character name="turtle" mood="happy" />
        <Character name="rabbit" mood="sad" />
      </>
    )

    expect(screen.getByRole("img", { name: "거북이 안내자" })).toHaveAttribute(
      "src",
      expect.stringMatching(/turtle-happy\.png/)
    )
    expect(screen.getByRole("img", { name: "토끼 깡총이" })).toHaveAttribute(
      "src",
      expect.stringMatching(/rabbit-sad\.png/)
    )
  })

  it("그 캐릭터에 없는 표정이면 기본 그림을 쓴다", () => {
    render(<Character name="turtle" mood="sad" />)

    expect(screen.getByRole("img", { name: "거북이 안내자" })).toHaveAttribute(
      "src",
      expect.stringMatching(/turtle\.png/)
    )
  })
})
