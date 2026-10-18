import { screen } from "@testing-library/react"
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest"
import { router } from "@/router"
import { enterAsChild, renderRoutes } from "@/test/render"
import * as api from "./api"

// 역할극 깡총이 힌트 표시 검사. 힌트는 다음 답을 위한 참고라 대화 기록에 쌓지 않고 마지막 힌트 1개만 남긴다.
// 아이가 그 힌트를 보고 답했다는 흐름이 보이도록 마지막 힌트는 나온 자리에 그대로 둔다 (PR #45 리뷰 반영).

vi.mock("./api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api")>()
  return { ...actual, sendRoleplayTurn: vi.fn(actual.sendRoleplayTurn) }
})

const ACTIVITY = "mock-activity-1"

const rabbitHint = (text: string): api.RoleplayTurnResult => ({
  status: "DELIVERED",
  reply: { speaker: "rabbit", text },
  isFinished: false,
})

async function openTextComposer() {
  enterAsChild()
  const { user } = renderRoutes(router.routes, `/child/roleplay/${ACTIVITY}`)
  await user.click(await screen.findByRole("button", { name: "글자로 답할래요" }))
  return { user, input: screen.getByRole("textbox", { name: "답 적기" }) }
}

beforeAll(() => {
  // jsdom 에는 scrollIntoView 가 없다. 역할극은 새 말풍선이 생길 때마다 맨 아래로 내린다.
  Element.prototype.scrollIntoView = vi.fn()
})

beforeEach(() => {
  vi.mocked(api.sendRoleplayTurn).mockReset()
})

describe("역할극 깡총이 힌트", () => {
  it("힌트가 여러 번 와도 말풍선은 1개이고 마지막 힌트만 보인다", async () => {
    vi.mocked(api.sendRoleplayTurn)
      .mockResolvedValueOnce(rabbitHint("첫 번째 힌트"))
      .mockResolvedValueOnce(rabbitHint("두 번째 힌트"))
    const { user, input } = await openTextComposer()

    await user.type(input, "몰라{Enter}")
    expect(await screen.findByText("첫 번째 힌트")).toBeInTheDocument()
    await user.type(input, "모르겠어{Enter}")

    expect(await screen.findByText("두 번째 힌트")).toBeInTheDocument()
    expect(screen.queryByText("첫 번째 힌트")).not.toBeInTheDocument()
    expect(screen.getAllByText("깡총이:")).toHaveLength(1)
  })

  it("아이가 답을 보내도 마지막 힌트는 대화 속 제자리에 남는다", async () => {
    vi.mocked(api.sendRoleplayTurn)
      .mockResolvedValueOnce(rabbitHint("첫 번째 힌트"))
      .mockReturnValueOnce(new Promise(() => {}))
    const { user, input } = await openTextComposer()

    await user.type(input, "몰라{Enter}")
    const hint = await screen.findByText("첫 번째 힌트")
    await user.type(input, "속상할 것 같아{Enter}")

    const nextAnswer = screen.getByText("“속상할 것 같아”")
    expect(hint).toBeInTheDocument()
    // 힌트가 아이의 다음 답보다 앞(위)에 있다.
    expect(hint.compareDocumentPosition(nextAnswer) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})
