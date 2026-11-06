import { screen } from "@testing-library/react"
import { http, HttpResponse } from "msw"
import { beforeAll, describe, expect, it, vi } from "vitest"
import { router } from "@/router"
import { envelope } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { CHILD_ACTIVITY_ID, enterAsChild, renderRoutes, seedChildActivity } from "@/test/render"

// 역할극 깡총이 힌트 표시 검사. 서버는 마스코트 힌트를 speakerRole=HINT 로 보낸다(API-RPL-04·05).
// 힌트는 다음 답을 위한 참고라 대화 기록에 쌓지 않고 마지막 힌트 1개만 남긴다.
// 아이가 그 힌트를 보고 답했다는 흐름이 보이도록 마지막 힌트는 나온 자리에 그대로 둔다 (PR #45 리뷰 반영).

const ACTIVITY = CHILD_ACTIVITY_ID
const TEXT_INPUT_URL = "*/api/v1/roleplay-sessions/:sessionId/text-inputs"

const speech = { available: false, url: null }

/** 아이가 막혀 마스코트 힌트가 붙은 DELIVERED 응답. [HINT, DIALOGUE] 순서다. */
const deliveredWithHint = (hint: string, dialogue: string) =>
  envelope({
    status: "DELIVERED",
    action: "NONE",
    messages: [
      { speakerRole: "HINT", text: hint, speech },
      { speakerRole: "DIALOGUE", text: dialogue, speech },
    ],
    acceptedInput: "몰라",
    checkpointVersion: 1,
    replayed: false,
  })

async function openTextComposer() {
  enterAsChild()
  seedChildActivity({ quizFinished: true })
  const { user } = renderRoutes(router.routes, `/child/roleplay/${ACTIVITY}`)
  await user.click(await screen.findByRole("button", { name: "글자로 답할래요" }))
  return { user, input: screen.getByRole("textbox", { name: "답 적기" }) }
}

beforeAll(() => {
  // jsdom 에는 scrollIntoView 가 없다. 역할극은 새 말풍선이 생길 때마다 맨 아래로 내린다.
  Element.prototype.scrollIntoView = vi.fn()
})

describe("역할극 깡총이 힌트", () => {
  it("HINT 는 깡총이, DIALOGUE 는 거북이 말풍선으로 보여 준다", async () => {
    server.use(http.post(TEXT_INPUT_URL, () => HttpResponse.json(deliveredWithHint("첫 번째 힌트", "어떤 표정일까?"))))
    const { user, input } = await openTextComposer()

    await user.type(input, "몰라{Enter}")

    expect(await screen.findByText("첫 번째 힌트")).toBeInTheDocument()
    expect(screen.getByText("깡총이:")).toBeInTheDocument()
    expect(screen.getByText("어떤 표정일까?")).toBeInTheDocument()
  })

  it("힌트가 여러 번 와도 말풍선은 1개이고 마지막 힌트만 보인다", async () => {
    server.use(
      http.post(TEXT_INPUT_URL, () => HttpResponse.json(deliveredWithHint("첫 번째 힌트", "어떤 표정일까?")), {
        once: true,
      }),
      http.post(TEXT_INPUT_URL, () => HttpResponse.json(deliveredWithHint("두 번째 힌트", "다시 볼까?")), {
        once: true,
      }),
    )
    const { user, input } = await openTextComposer()

    await user.type(input, "몰라{Enter}")
    expect(await screen.findByText("첫 번째 힌트")).toBeInTheDocument()
    await user.type(input, "모르겠어{Enter}")

    expect(await screen.findByText("두 번째 힌트")).toBeInTheDocument()
    expect(screen.queryByText("첫 번째 힌트")).not.toBeInTheDocument()
    expect(screen.getAllByText("깡총이:")).toHaveLength(1)
  })

  it("아이가 답을 보내도 마지막 힌트는 대화 속 제자리에 남는다", async () => {
    server.use(
      http.post(TEXT_INPUT_URL, () => HttpResponse.json(deliveredWithHint("첫 번째 힌트", "어떤 표정일까?")), {
        once: true,
      }),
      // 두 번째 답은 응답을 붙잡아 둔다.
      http.post(TEXT_INPUT_URL, () => new Promise<Response>(() => {}), { once: true }),
    )
    const { user, input } = await openTextComposer()

    await user.type(input, "몰라{Enter}")
    const hint = await screen.findByText("첫 번째 힌트")
    await user.type(input, "속상할 것 같아{Enter}")

    const nextAnswer = screen.getByText("“속상할 것 같아”")
    expect(hint).toBeInTheDocument()
    // 힌트가 아이의 다음 답보다 앞(위)에 있다.
    expect(hint.compareDocumentPosition(nextAnswer) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it("다시 입력해야 하면 깡총이가 서버 고정 안내를 보여 주고 계속 답할 수 있다", async () => {
    server.use(
      http.post(TEXT_INPUT_URL, () =>
        HttpResponse.json(
          envelope({
            status: "REINPUT_REQUIRED",
            action: "REQUEST_NEW_INPUT",
            messages: [{ speakerRole: "HINT", text: "다시 한 번만 말해줄래?", speech }],
            acceptedInput: null,
            checkpointVersion: null,
            replayed: false,
          }),
        ),
      ),
    )
    const { user, input } = await openTextComposer()

    await user.type(input, "음{Enter}")

    expect(await screen.findByText("다시 한 번만 말해줄래?")).toBeInTheDocument()
    expect(screen.getByRole("textbox", { name: "답 적기" })).toBeEnabled()
  })

  it("안전 중단(STOPPED)이면 입력을 닫고 내 활동으로 돌아가게 한다", async () => {
    server.use(
      http.post(TEXT_INPUT_URL, () =>
        HttpResponse.json(
          envelope({
            status: "STOPPED",
            action: "STOP_DIALOGUE",
            messages: [{ speakerRole: "HINT", text: "선생님과 이야기해 보자.", speech }],
            acceptedInput: null,
            checkpointVersion: null,
            replayed: false,
          }),
        ),
      ),
    )
    const { user, input } = await openTextComposer()

    await user.type(input, "싫어{Enter}")

    expect(await screen.findByText("선생님과 이야기해 보자.")).toBeInTheDocument()
    expect(screen.queryByRole("textbox", { name: "답 적기" })).not.toBeInTheDocument()
    expect(screen.getByRole("button", { name: "내 활동으로 돌아가기" })).toBeInTheDocument()
  })
})
