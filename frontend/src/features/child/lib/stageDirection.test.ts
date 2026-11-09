import { describe, expect, it } from "vitest"
import { splitStageDirection } from "./stageDirection"

describe("splitStageDirection", () => {
  it("괄호 지문을 대사와 나눈다", () => {
    expect(splitStageDirection("(종이를 들고) 나 그림 그리고 있었어.")).toEqual({
      action: "종이를 들고",
      speech: "나 그림 그리고 있었어.",
    })
  })

  it("지문이 없으면 대사만 돌려준다", () => {
    expect(splitStageDirection("응… 고마워")).toEqual({ action: null, speech: "응… 고마워" })
  })

  it("대사 중간의 지문도 빼고 여러 지문은 순서대로 잇는다", () => {
    expect(splitStageDirection("(훌쩍이며) 흐윽… (고개를 숙이며) 미안해")).toEqual({
      action: "훌쩍이며, 고개를 숙이며",
      speech: "흐윽… 미안해",
    })
  })

  it("지문만 있으면 대사는 비어 있다", () => {
    expect(splitStageDirection("(고개를 숙인다)")).toEqual({ action: "고개를 숙인다", speech: "" })
  })

  it("빈 괄호는 지문으로 보지 않는다", () => {
    expect(splitStageDirection("() 안녕")).toEqual({ action: null, speech: "안녕" })
  })
})
