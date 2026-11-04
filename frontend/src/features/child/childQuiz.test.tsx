import { screen } from "@testing-library/react"
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest"
import { router } from "@/router"
import {
  ROLEPLAY_OPENING,
  storedActivity,
  storedQuizAttempt,
  storedQuizItems,
} from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { CHILD_ACTIVITY_ID, enterAsChild, renderRoutes, seedChildActivity } from "@/test/render"
import { CAMERA_NOT_CONNECTED } from "./api"

// 표정 퀴즈 실제 API 연동 검사 (API-QUIZ-03·04·05·06, API-ACT-04). MSW 가짜 서버가 서버 판정 규칙을 흉내 낸다.
// 최초 결합 3문항: 1) 내 감정(정답 기쁨) 2) 친구 감정 상황(정답 슬픔, 힌트 2개) 3) 친구 감정 이미지(정답 화남).
// - 서버는 첫 답 → (틀리면) 힌트 최대 2개 → 최종 답 1번 순서로 받는다. 정답은 화면에 오지 않는다.
// - 내 감정 문항은 카메라를 아직 켜지 않으므로 "표정 분석 불가"로 보내고, 서버가 점수에서 빼고 바로 확정한다.

const ACTIVITY = CHILD_ACTIVITY_ID
type Sent = { method: string; path: string; key: string | null; body: string }
let sent: Sent[] = []

function openQuiz(answered = 0) {
  enterAsChild()
  seedChildActivity({ quizFinished: answered })
  return renderRoutes(router.routes, `/child/quiz/${ACTIVITY}`)
}

beforeAll(() => {
  Element.prototype.scrollIntoView = vi.fn()
})

beforeEach(() => {
  sent = []
  server.events.on("request:start", async ({ request }) => {
    const path = new URL(request.url).pathname
    if (request.method === "GET") return
    sent.push({ method: request.method, path, key: request.headers.get("Idempotency-Key"), body: await request.clone().text() })
  })
})

afterEach(() => {
  server.events.removeAllListeners("request:start")
})

describe("표정 퀴즈는 서버가 고정한 문항을 보여 주고 서버가 판정한다", () => {
  it("서버 문항 글자와 보기 글자를 그대로 보여 준다", async () => {
    openQuiz()

    expect(await screen.findByText("생일에 친구가 선물을 줬어. 너는 어떤 기분이 들까?")).toBeInTheDocument()
    expect(screen.getAllByRole("radio").map((radio) => radio.textContent)).toEqual(["기쁨", "슬픔", "화남"])
    expect(screen.getByText("1/3 문항")).toBeInTheDocument()
  })

  it("내 감정 문항은 고른 보기와 함께 표정 분석 불가를 보내고, 서버가 점수에서 빼고 확정한다", async () => {
    const { user } = openQuiz()

    await user.click(await screen.findByRole("radio", { name: /슬픔/ }))
    await user.click(screen.getByRole("button", { name: "이 표정으로 확인할래요" }))

    expect(await screen.findByText("네 마음을 알려줘서 고마워!")).toBeInTheDocument()
    const [put] = sent.filter((request) => request.method === "PUT")
    expect(JSON.parse(put.body)).toEqual({
      firstResponse: "슬픔",
      expressionMatchResult: "NOT_ANALYZABLE",
      cameraModelVersion: CAMERA_NOT_CONNECTED,
    })
    const [first] = storedQuizItems(ACTIVITY)
    expect(storedQuizAttempt(first.activityQuizId)).toMatchObject({ status: "FINALIZED", excludedFromScoring: true })
  })

  it("첫 답이 맞으면 바로 맞았다고 하고 힌트를 요청하지 않는다", async () => {
    const { user } = openQuiz(1)

    expect(await screen.findByText("2/3 문항")).toBeInTheDocument()
    await user.click(screen.getByRole("radio", { name: /슬픔/ }))
    await user.click(screen.getByRole("button", { name: "이 표정으로 확인할래요" }))

    expect(await screen.findByText("맞아! 잘 찾았어")).toBeInTheDocument()
    expect(sent.filter((request) => request.path.endsWith("/hints"))).toEqual([])
  })

  it("첫 답이 틀리면 힌트를 받아 보여 주고, 하나 더 받은 뒤 최종 답 한 번으로 확정한다", async () => {
    const { user } = openQuiz(1)

    await user.click(await screen.findByRole("radio", { name: /기쁨/ }))
    await user.click(screen.getByRole("button", { name: "이 표정으로 확인할래요" }))

    expect(await screen.findByText(/힌트 1: 좋아하던 것을 잃으면 눈물이 날 수 있어요\./)).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "힌트 하나 더 볼래요" }))
    expect(await screen.findByText(/힌트 2: 기쁨과 슬픔 중에서 골라 봐요\./)).toBeInTheDocument()
    // 서버는 문항당 힌트를 2개까지만 준다.
    expect(screen.queryByRole("button", { name: "힌트 하나 더 볼래요" })).not.toBeInTheDocument()

    await user.click(screen.getByRole("radio", { name: /슬픔/ }))
    await user.click(screen.getByRole("button", { name: "다시 골라볼래요" }))

    expect(await screen.findByText("맞아! 힌트를 보고 잘 찾았어")).toBeInTheDocument()
    const puts = sent.filter((request) => request.method === "PUT").map((request) => JSON.parse(request.body))
    expect(puts).toEqual([{ firstResponse: "기쁨" }, { firstResponse: "기쁨", finalResponse: "슬픔" }])
    const hints = sent.filter((request) => request.path.endsWith("/hints"))
    expect(hints).toHaveLength(2)
    expect(hints[0].key).not.toBe(hints[1].key)
    const second = storedQuizItems(ACTIVITY)[1]
    expect(storedQuizAttempt(second.activityQuizId)).toMatchObject({
      status: "FINALIZED",
      itemCorrect: true,
      resolvedAfterHint: true,
    })
  })

  it("힌트 뒤 최종 답도 틀리면 정답을 알려 주지 않고 다음 문제로 넘어간다", async () => {
    const { user } = openQuiz(1)

    await user.click(await screen.findByRole("radio", { name: /기쁨/ }))
    await user.click(screen.getByRole("button", { name: "이 표정으로 확인할래요" }))
    await screen.findByText(/힌트 1:/)
    await user.click(screen.getByRole("radio", { name: /놀람/ }))
    await user.click(screen.getByRole("button", { name: "다시 골라볼래요" }))

    expect(await screen.findByText("괜찮아! 다음 문제로 가볼까?")).toBeInTheDocument()
    expect(screen.queryByText(/정답/)).not.toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "다음 문제" }))
    expect(await screen.findByText("3/3 문항")).toBeInTheDocument()
  })

  it("이미지 문항은 서버가 준 그림을 '친구 얼굴'로 보여 준다", async () => {
    openQuiz(2)

    const image = await screen.findByRole("img", { name: "친구 얼굴" })
    expect(image).toHaveAttribute("src", "/quiz-images/sample-anger.svg")
  })

  it("다시 들어오면 이미 확정한 문항은 건너뛰고 이어서 푼다", async () => {
    openQuiz(2)

    expect(await screen.findByText("3/3 문항")).toBeInTheDocument()
  })

  it("최초 결합 퀴즈를 다 풀면 역할극으로 이어 가고, 서버가 첫 질문을 준다", async () => {
    const { user, router: memoryRouter } = openQuiz(2)

    await user.click(await screen.findByRole("radio", { name: /화남/ }))
    await user.click(screen.getByRole("button", { name: "이 표정으로 확인할래요" }))
    await user.click(await screen.findByRole("button", { name: "다 풀었어요" }))
    await user.click(await screen.findByRole("button", { name: "역할극 하러 가기" }))

    expect(await screen.findByText(ROLEPLAY_OPENING)).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe(`/child/roleplay/${ACTIVITY}`)
    // 최초 결합은 퀴즈만으로 완료되지 않는다.
    expect(storedActivity(ACTIVITY)?.status).toBe("IN_PROGRESS")
  })

  it("퀴즈를 다 풀기 전에 역할극 주소로 오면 퀴즈로 돌려보낸다", async () => {
    enterAsChild()
    seedChildActivity()
    const { router: memoryRouter } = renderRoutes(router.routes, `/child/roleplay/${ACTIVITY}`)

    expect(await screen.findByText("1/3 문항")).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe(`/child/quiz/${ACTIVITY}`)
  })
})
