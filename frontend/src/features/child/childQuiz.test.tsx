import { screen, waitFor } from "@testing-library/react"
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest"
import { router } from "@/router"
import {
  confirmScenario,
  ROLEPLAY_OPENING,
  storedActivity,
  storedQuizAttempt,
  storedQuizItems,
} from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { CHILD_ACTIVITY_ID, enterAsChild, renderRoutes, seedChildActivity } from "@/test/render"
import type { ExpressionScores } from "./camera/expressionJudge"
import { CAMERA_MODEL_VERSION } from "./camera/faceExpressionModel"

// 브라우저 표정 모델은 테스트에서 돌릴 수 없어, 테스트가 정한 표정 확률을 돌려주는 가짜로 바꾼다.
// 카메라(navigator.mediaDevices)가 없는 jsdom 기본 상태에서는 모델을 부르지 않는다.
const camera = vi.hoisted(() => ({ scores: null as ExpressionScores | null, detectCalls: 0 }))
vi.mock("./camera/faceExpressionModel", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./camera/faceExpressionModel")>()),
  loadExpressionDetector: async () => ({
    detect: async () => {
      camera.detectCalls++
      return camera.scores
    },
  }),
}))

// 표정 퀴즈 실제 API 연동 검사 (API-QUIZ-03·04·05·06, API-ACT-04). MSW 가짜 서버가 서버 판정 규칙을 흉내 낸다.
// 최초 결합 3문항: 1) 내 감정(정답 기쁨) 2) 친구 감정 상황(정답 슬픔, 힌트 2개) 3) 친구 감정 이미지(정답 화남).
// - 서버는 첫 답 → (틀리면) 힌트 최대 2개 → 최종 답 1번 순서로 받는다. 정답은 화면에 오지 않는다.
// - 내 감정 문항은 셀프 카메라로 고른 표정을 따라 지었는지 브라우저에서 보고 판정(MATCH·MISMATCH)을 함께 보낸다.
//   카메라를 못 쓰면 "표정 분석 불가"·"권한 거부"로 보내고, 서버가 점수에서 빼고 바로 확정한다.

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

  it("카메라가 없는 기기에서 내 감정 문항은 표정 분석 불가를 보내고, 서버가 점수에서 빼고 확정한다", async () => {
    const { user } = openQuiz()

    expect(await screen.findByText("카메라를 쓸 수 없어서 표정은 확인하지 않아요")).toBeInTheDocument()
    await user.click(await screen.findByRole("radio", { name: /슬픔/ }))
    await user.click(screen.getByRole("button", { name: "이 표정으로 확인할래요" }))

    expect(await screen.findByText("네 마음을 알려줘서 고마워!")).toBeInTheDocument()
    const [put] = sent.filter((request) => request.method === "PUT")
    expect(JSON.parse(put.body)).toEqual({
      firstResponse: "슬픔",
      expressionMatchResult: "NOT_ANALYZABLE",
      cameraModelVersion: CAMERA_MODEL_VERSION,
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

  it("최초 결합 퀴즈를 다 풀어도 선생님이 시나리오를 확정하기 전이면 기다림을 안내한다", async () => {
    const { user, router: memoryRouter } = openQuiz(2)

    await user.click(await screen.findByRole("radio", { name: /화남/ }))
    await user.click(screen.getByRole("button", { name: "이 표정으로 확인할래요" }))
    await user.click(await screen.findByRole("button", { name: "다 풀었어요" }))
    await user.click(await screen.findByRole("button", { name: "역할극 하러 가기" }))

    expect(await screen.findByText("선생님이 역할극을 준비하고 있어")).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe(`/child/quiz/${ACTIVITY}`)
    // 퀴즈를 마쳐도 시나리오가 자동으로 붙지 않는다(S4-JIN-01). 최초 결합은 퀴즈만으로 완료되지도 않는다.
    expect(storedActivity(ACTIVITY)).toMatchObject({ status: "IN_PROGRESS", scenarioId: null })
  })

  it("선생님이 시나리오를 확정했으면 퀴즈 뒤 역할극으로 이어 가고, 서버가 첫 질문을 준다", async () => {
    const { user, router: memoryRouter } = openQuiz(2)
    confirmScenario(ACTIVITY)

    await user.click(await screen.findByRole("radio", { name: /화남/ }))
    await user.click(screen.getByRole("button", { name: "이 표정으로 확인할래요" }))
    await user.click(await screen.findByRole("button", { name: "다 풀었어요" }))
    await user.click(await screen.findByRole("button", { name: "역할극 하러 가기" }))

    expect(await screen.findByText(ROLEPLAY_OPENING)).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe(`/child/roleplay/${ACTIVITY}`)
  })

  it("퀴즈를 다 풀기 전에 역할극 주소로 오면 퀴즈로 돌려보낸다", async () => {
    enterAsChild()
    seedChildActivity()
    const { router: memoryRouter } = renderRoutes(router.routes, `/child/roleplay/${ACTIVITY}`)

    expect(await screen.findByText("1/3 문항")).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe(`/child/quiz/${ACTIVITY}`)
  })
})

// 셀프 카메라 (C-QZ-01, API-QUIZ-04). 카메라·모델은 가짜이고, 판정 규칙(최소 50%·다시 찍기 2번)은 실제 코드다.
describe("내 감정 문항은 셀프 카메라로 고른 표정을 따라 지었는지 본다", () => {
  const stop = vi.fn()
  const getUserMedia = vi.fn()

  function scores(values: Partial<ExpressionScores>): ExpressionScores {
    return { neutral: 0, happy: 0, sad: 0, angry: 0, fearful: 0, disgusted: 0, surprised: 0, ...values }
  }

  /** 표정을 바꾼 뒤 지난 측정이 평균에서 빠지도록 새 측정을 3번 기다린다. */
  async function waitForFreshReadings() {
    const before = camera.detectCalls
    await waitFor(() => expect(camera.detectCalls).toBeGreaterThanOrEqual(before + 3))
  }

  const puts = () => sent.filter((request) => request.method === "PUT").map((request) => JSON.parse(request.body))

  beforeEach(() => {
    camera.scores = null
    camera.detectCalls = 0
    getUserMedia.mockResolvedValue({ getTracks: () => [{ stop }] })
    Object.defineProperty(navigator, "mediaDevices", { configurable: true, value: { getUserMedia } })
    vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue(undefined)
  })

  afterEach(() => {
    Reflect.deleteProperty(navigator, "mediaDevices")
  })

  it("고른 표정을 50% 이상으로 지으면 MATCH 와 모델 버전을 보내고, 답을 내면 카메라를 끈다", async () => {
    camera.scores = scores({ happy: 0.9, neutral: 0.1 })
    const { user } = openQuiz()

    expect(await screen.findByText("카메라 준비 완료 · 얼굴을 찾았어요")).toBeInTheDocument()
    expect(getUserMedia).toHaveBeenCalledWith({ video: { facingMode: "user" }, audio: false })
    await user.click(screen.getByRole("radio", { name: /기쁨/ }))
    await user.click(screen.getByRole("button", { name: "이 표정으로 확인할래요" }))

    expect(await screen.findByText("맞아! 잘 찾았어")).toBeInTheDocument()
    expect(puts()).toEqual([
      { firstResponse: "기쁨", expressionMatchResult: "MATCH", cameraModelVersion: CAMERA_MODEL_VERSION },
    ])
    expect(stop).toHaveBeenCalled()
  })

  it("어느 표정도 50%를 넘지 않으면 보내지 않고 다시 찍게 한다", async () => {
    camera.scores = scores({ happy: 0.45, neutral: 0.35, sad: 0.2 })
    const { user } = openQuiz()

    await screen.findByText("카메라 준비 완료 · 얼굴을 찾았어요")
    await user.click(screen.getByRole("radio", { name: /기쁨/ }))
    await user.click(screen.getByRole("button", { name: "이 표정으로 확인할래요" }))

    expect(await screen.findByText("'기쁨' 표정을 조금 더 크게 지어 볼까?")).toBeInTheDocument()
    expect(screen.getByText("다시 찍을 수 있는 기회: 2번 남았어요")).toBeInTheDocument()
    expect(puts()).toEqual([])

    camera.scores = scores({ happy: 0.8, neutral: 0.2 })
    await waitForFreshReadings()
    await user.click(screen.getByRole("button", { name: "이 표정으로 확인할래요" }))

    expect(await screen.findByText("맞아! 잘 찾았어")).toBeInTheDocument()
    expect(puts()).toEqual([
      { firstResponse: "기쁨", expressionMatchResult: "MATCH", cameraModelVersion: CAMERA_MODEL_VERSION },
    ])
  })

  it("얼굴을 못 찾으면 얼굴을 맞추라고 하고 보내지 않는다", async () => {
    const { user } = openQuiz()

    expect(await screen.findByText("얼굴을 동그라미 안에 맞춰 줘")).toBeInTheDocument()
    await user.click(screen.getByRole("radio", { name: /기쁨/ }))
    await user.click(screen.getByRole("button", { name: "이 표정으로 확인할래요" }))

    expect(await screen.findByText("얼굴이 잘 안 보여. 동그라미 안에 얼굴을 맞춰 볼까?")).toBeInTheDocument()
    expect(puts()).toEqual([])
  })

  it("다시 2번 찍어도 다른 표정이면 MISMATCH 로 보내고, 다시 들어와 낸 최종 답에도 같은 카메라 판정을 보낸다", async () => {
    camera.scores = scores({ sad: 0.85, neutral: 0.15 })
    const view = openQuiz()
    const { user } = view

    await screen.findByText("카메라 준비 완료 · 얼굴을 찾았어요")
    await user.click(screen.getByRole("radio", { name: /기쁨/ }))
    const check = () => user.click(screen.getByRole("button", { name: "이 표정으로 확인할래요" }))
    await check()
    expect(await screen.findByText("지금은 '슬픔' 표정처럼 보여. '기쁨' 표정을 다시 지어 볼까?")).toBeInTheDocument()
    expect(screen.getByText("다시 찍을 수 있는 기회: 2번 남았어요")).toBeInTheDocument()
    await check()
    expect(await screen.findByText("다시 찍을 수 있는 기회: 1번 남았어요")).toBeInTheDocument()
    expect(puts()).toEqual([])
    await check()

    // 세 번째에는 보낸다. 서버는 표정이 다르면 오답으로 보고 힌트로 이어 간다.
    expect(await screen.findByText(/힌트 1: 선물을 받으면 웃음이 나요\./)).toBeInTheDocument()
    const camerafields = { expressionMatchResult: "MISMATCH", cameraModelVersion: CAMERA_MODEL_VERSION }
    expect(puts()).toEqual([{ firstResponse: "기쁨", ...camerafields }])
    expect(stop).toHaveBeenCalled()

    // 화면을 떠났다가 다시 들어와도 서버에 저장된 카메라 판정을 그대로 보내야 409(FIRST_RESPONSE_IMMUTABLE)가 나지 않는다.
    view.unmount()
    const again = renderRoutes(router.routes, `/child/quiz/${ACTIVITY}`)
    expect(await screen.findByText("괜찮아, 다시 한번 살펴볼까?")).toBeInTheDocument()
    await again.user.click(screen.getByRole("radio", { name: /기쁨/ }))
    await again.user.click(screen.getByRole("button", { name: "다시 골라볼래요" }))

    expect(await screen.findByText("괜찮아! 다음 문제로 가볼까?")).toBeInTheDocument()
    expect(puts()[1]).toEqual({ firstResponse: "기쁨", ...camerafields, finalResponse: "기쁨" })
    const [first] = storedQuizItems(ACTIVITY)
    expect(storedQuizAttempt(first.activityQuizId)).toMatchObject({ status: "FINALIZED", excludedFromScoring: false })
  })

  it("카메라 권한을 거부하면 카메라 없이 고르게 하고, 권한 거부로 보내 점수에서 뺀다", async () => {
    getUserMedia.mockRejectedValue(new DOMException("denied", "NotAllowedError"))
    const { user } = openQuiz()

    expect(await screen.findByRole("button", { name: "카메라 켜는 방법 보기" })).toBeInTheDocument()
    await user.click(screen.getByRole("radio", { name: /슬픔/ }))
    await user.click(screen.getByRole("button", { name: "이 표정으로 확인할래요" }))

    expect(await screen.findByText("네 마음을 알려줘서 고마워!")).toBeInTheDocument()
    expect(puts()).toEqual([{ firstResponse: "슬픔", technicalFailureReason: "CAMERA_PERMISSION_DENIED" }])
    const [first] = storedQuizItems(ACTIVITY)
    expect(storedQuizAttempt(first.activityQuizId)).toMatchObject({ status: "FINALIZED", excludedFromScoring: true })
  })

  it("권한 안내에서 다시 확인하면 새로 고치지 않고 카메라를 다시 켠다", async () => {
    getUserMedia.mockRejectedValueOnce(new DOMException("denied", "NotAllowedError"))
    camera.scores = scores({ happy: 0.9 })
    const { user } = openQuiz()

    await user.click(await screen.findByRole("button", { name: "카메라 켜는 방법 보기" }))
    expect(await screen.findByRole("dialog", { name: "카메라를 쓸 수 없어요" })).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "다시 확인하기" }))

    expect(await screen.findByText("카메라 준비 완료 · 얼굴을 찾았어요")).toBeInTheDocument()
    expect(getUserMedia).toHaveBeenCalledTimes(2)
  })

  it("카메라가 켜지는 동안에는 확인 버튼을 막는다", async () => {
    getUserMedia.mockReturnValue(new Promise(() => {}))
    const { user } = openQuiz()

    await user.click(await screen.findByRole("radio", { name: /기쁨/ }))
    expect(screen.getByRole("button", { name: "카메라를 준비하고 있어요…" })).toBeDisabled()
  })
})
