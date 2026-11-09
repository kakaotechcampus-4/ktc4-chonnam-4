import { screen } from "@testing-library/react"
import { http, HttpResponse } from "msw"
import { beforeAll, describe, expect, it, vi } from "vitest"
import { router } from "@/router"
import { envelope } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { CHILD_ACTIVITY_ID, enterAsChild, renderRoutes, seedChildActivity } from "@/test/render"

// 역할극 첫 화면. 서버가 첫 대사 앞에 깡총이 장면 소개(HINT, purpose=INTRO)를 붙이면 깡총이 말풍선으로 보여 주고,
// 캐릭터 대사의 괄호 지문은 괄호 없이 대사 위 작은 글씨로 나눠 보여 준다.

const speech = { available: false, url: null }

const startBody = (messages: unknown[], characterName: string | null = "민수") =>
  envelope({
    sessionId: "82000000-0000-4000-8000-000000000001",
    activityId: CHILD_ACTIVITY_ID,
    scenarioId: "80000000-0000-4000-8000-000000000001",
    scenarioVersion: 1,
    status: "IN_PROGRESS",
    activityStatus: "IN_PROGRESS",
    lastDeliveredTurnId: null,
    checkpointVersion: 0,
    characterName,
    delivery: { messages },
  })

beforeAll(() => {
  Element.prototype.scrollIntoView = vi.fn()
})

describe("역할극 장면 소개와 지문", () => {
  it("깡총이 소개 다음에 캐릭터 첫 대사를 지문과 나눠 보여 준다", async () => {
    server.use(
      http.post("*/api/v1/activities/:activityId/roleplay-sessions", () =>
        HttpResponse.json(
          startBody([
            {
              messageId: "83000000-0000-4000-8000-000000000009",
              speakerRole: "HINT",
              purpose: "INTRO",
              text: "저기 민수 보이지? 민수 말을 잘 듣고 이야기를 나눠 보자.",
              speech,
            },
            {
              messageId: "83000000-0000-4000-8000-000000000001",
              speakerRole: "DIALOGUE",
              purpose: "QUESTION",
              text: "(종이를 들고) 나 그림 그리고 있었어.",
              speech,
            },
          ]),
          { status: 201 }
        )
      )
    )
    enterAsChild()
    seedChildActivity({ quizFinished: true })
    renderRoutes(router.routes, `/child/roleplay/${CHILD_ACTIVITY_ID}`)

    const intro = await screen.findByText("저기 민수 보이지? 민수 말을 잘 듣고 이야기를 나눠 보자.")
    const line = screen.getByText("나 그림 그리고 있었어.")
    expect(screen.getByText("종이를 들고")).toBeInTheDocument()
    expect(screen.queryByText(/\(종이를 들고\)/)).not.toBeInTheDocument()
    // 소개가 첫 대사보다 먼저(위에) 나온다.
    expect(intro.compareDocumentPosition(line) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it("캐릭터 말풍선 이름표는 상대 이름이고, 이름이 없으면 거북이 안내자다", async () => {
    const question = {
      messageId: "83000000-0000-4000-8000-000000000001",
      speakerRole: "DIALOGUE",
      purpose: "QUESTION",
      text: "나 그림 그리고 있었어.",
      speech,
    }
    server.use(
      http.post("*/api/v1/activities/:activityId/roleplay-sessions", () =>
        HttpResponse.json(startBody([question]), { status: 201 })
      )
    )
    enterAsChild()
    seedChildActivity({ quizFinished: true })
    const first = renderRoutes(router.routes, `/child/roleplay/${CHILD_ACTIVITY_ID}`)

    await screen.findByText("나 그림 그리고 있었어.")
    expect(screen.getByText("민수")).toBeInTheDocument()
    expect(screen.queryByText("거북이 안내자")).not.toBeInTheDocument()
    first.unmount()

    server.use(
      http.post("*/api/v1/activities/:activityId/roleplay-sessions", () =>
        HttpResponse.json(startBody([question], null), { status: 201 })
      )
    )
    renderRoutes(router.routes, `/child/roleplay/${CHILD_ACTIVITY_ID}`)

    await screen.findByText("나 그림 그리고 있었어.")
    expect(screen.getByText("거북이 안내자")).toBeInTheDocument()
  })
})
