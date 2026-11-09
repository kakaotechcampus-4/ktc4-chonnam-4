import { screen, waitFor, within } from "@testing-library/react"
import { http, HttpResponse } from "msw"
import { beforeEach, describe, expect, it } from "vitest"
import { apiError, envelope } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { renderRoutes, signIn } from "@/test/render"
import { ACTIVITY_ID, REVIEW_ID, reviewScenario, scenarioReview } from "@/test/msw/scenarios"
import type { ScenarioReview } from "../api"
import { ScenarioReviewPage } from "./ScenarioReviewPage"

// 시나리오 검토 화면. 검토 상태는 서버에 있고, 화면은 reviewId·revision 과 동작마다의 요청 키를 들고 다닌다.

type Sent = { method: string; key: string | null; body: unknown }

const PATH = `/activities/${ACTIVITY_ID}/scenario`

const SECOND = scenarioReview({
  revision: 2,
  scenario: reviewScenario({ scenarioId: "80000000-0000-4000-8000-000000000002", title: "넘어진 친구 돕기" }),
})

function closure(outcome: "CONFIRMED" | "CANCELLED") {
  return { reviewId: REVIEW_ID, activityId: ACTIVITY_ID, outcome, closedAt: "2026-11-05T00:00:00Z" }
}

/** 받은 요청을 모으고, 준비 요청에는 차례대로 응답한다. 응답 대신 함수를 넣으면 그 응답을 그대로 준다. */
function serve(prepares: (ScenarioReview | (() => Response))[]) {
  const sent: Sent[] = []
  let index = 0
  const record = async (request: Request) => {
    const text = await request.text()
    sent.push({ method: request.method, key: request.headers.get("Idempotency-Key"), body: text ? JSON.parse(text) : null })
  }
  server.use(
    http.post("*/api/v1/activities/:activityId/scenario-review", async ({ request }) => {
      await record(request)
      const next = prepares[Math.min(index++, prepares.length - 1)]
      return typeof next === "function" ? next() : HttpResponse.json(envelope(next))
    }),
    http.put("*/api/v1/activities/:activityId/scenario", async ({ request }) => {
      await record(request)
      return HttpResponse.json(envelope(closure("CONFIRMED")))
    }),
    http.delete("*/api/v1/activities/:activityId/scenario-review", async ({ request }) => {
      await record(request)
      return HttpResponse.json(envelope(closure("CANCELLED")))
    }),
  )
  return sent
}

function renderReview() {
  return renderRoutes(
    [
      { path: "/activities/:activityId/scenario", element: <ScenarioReviewPage /> },
      { path: "/content", element: <p>승인 대기함 화면</p> },
    ],
    PATH,
  )
}

describe("시나리오 검토", () => {
  beforeEach(() => signIn())

  it("열면 검토를 시작하거나 이어 보고, 후보와 이번 활동 조건을 함께 보인다", async () => {
    const sent = serve([scenarioReview()])
    renderReview()

    expect(await screen.findByRole("heading", { name: "그림을 망친 친구 위로하기" })).toBeInTheDocument()
    expect(screen.getByText("친구가 속상할 때 위로하는 말을 한다")).toBeInTheDocument()
    expect(screen.getByText("선택지 제시 · 작은 목표 2")).toBeInTheDocument()
    expect(sent).toHaveLength(1)
    expect(sent[0].body).toEqual({ action: "START_OR_RESUME" })
    expect(sent[0].key).toMatch(/^[0-9a-f-]{36}$/)
  })

  it("[다른 후보]는 지금 검토 번호로 NEXT 를 보내고 새 키를 쓴다", async () => {
    const sent = serve([scenarioReview(), SECOND])
    const { user } = renderReview()

    await user.click(await screen.findByRole("button", { name: "다른 후보" }))
    expect(await screen.findByRole("heading", { name: "넘어진 친구 돕기" })).toBeInTheDocument()
    expect(sent[1].body).toEqual({ action: "NEXT", reviewId: REVIEW_ID, revision: 1 })
    expect(sent[1].key).not.toBe(sent[0].key)
  })

  it("응답을 못 받은 NEXT 는 [다시 시도]가 같은 키·본문으로 다시 보낸다", async () => {
    const sent = serve([scenarioReview(), () => HttpResponse.error(), SECOND])
    const { user } = renderReview()

    await user.click(await screen.findByRole("button", { name: "다른 후보" }))
    await user.click(await screen.findByRole("button", { name: "다시 시도" }))
    expect(await screen.findByRole("heading", { name: "넘어진 친구 돕기" })).toBeInTheDocument()
    expect(sent[2].body).toEqual(sent[1].body)
    expect(sent[2].key).toBe(sent[1].key)
  })

  it("다른 후보를 만들지 못하면 위쪽 버튼을 숨기고, [이전 후보로 돌아가기]로 직전 후보와 버튼을 되찾는다", async () => {
    serve([scenarioReview(), () => apiError(422, "SCENARIO_GENERATION_FAILED", "만들지 못했습니다.", PATH)])
    const { user } = renderReview()

    await user.click(await screen.findByRole("button", { name: "다른 후보" }))
    expect(await screen.findByRole("button", { name: "이전 후보로 돌아가기" })).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "승인으로 이동" })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "다른 후보" })).not.toBeInTheDocument()

    await user.click(screen.getByRole("button", { name: "이전 후보로 돌아가기" }))
    expect(await screen.findByRole("heading", { name: "그림을 망친 친구 위로하기" })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "승인으로 이동" })).toBeInTheDocument()
  })

  it("처음 불러오기가 실패하면 돌아갈 후보가 없으니 [이전 후보로 돌아가기]를 보이지 않는다", async () => {
    serve([() => HttpResponse.error()])
    renderReview()

    expect(await screen.findByRole("button", { name: "다시 시도" })).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "이전 후보로 돌아가기" })).not.toBeInTheDocument()
  })

  it("고친 칸만 edits 로 실어 승인하고, 확인 화면에는 고친 내용이 보인다", async () => {
    const sent = serve([scenarioReview()])
    const { user } = renderReview()

    await user.click(await screen.findByRole("button", { name: "수정하기" }))
    const title = screen.getByRole("textbox", { name: "제목" })
    await user.clear(title)
    await user.type(title, "친구 마음 알아보기")
    const evidence = screen.getAllByRole("textbox", { name: /필요 증거/ })[1]
    await user.clear(evidence)
    await user.type(evidence, "괜찮냐고 물어봄{enter}토닥여 줌")

    await user.click(screen.getByRole("button", { name: "승인으로 이동" }))
    expect(screen.getByRole("heading", { name: "친구 마음 알아보기" })).toBeInTheDocument()
    expect(screen.getByText("v1 → v2 · L2")).toBeInTheDocument()
    expect(screen.getByText("제목·작은 목표 1개 수정 · 새 버전으로 승인")).toBeInTheDocument()

    await user.click(screen.getByRole("checkbox", { name: "아동에게 노출되는 내용을 검토했습니다." }))
    await user.click(screen.getByRole("button", { name: "승인" }))
    expect(await screen.findByText(/승인했습니다/)).toBeInTheDocument()

    const put = sent.find((request) => request.method === "PUT")!
    expect(put.body).toEqual({
      reviewId: REVIEW_ID,
      revision: 1,
      scenarioId: "80000000-0000-4000-8000-000000000001",
      scenarioVersion: 1,
      edits: {
        title: "친구 마음 알아보기",
        microGoals: [
          { microGoalId: "81000000-0000-4000-8000-000000000002", requiredEvidence: ["괜찮냐고 물어봄", "토닥여 줌"] },
        ],
      },
    })
  })

  it("고치지 않고 승인하면 edits 없이 보낸다", async () => {
    const sent = serve([scenarioReview()])
    const { user } = renderReview()

    await user.click(await screen.findByRole("button", { name: "승인으로 이동" }))
    expect(screen.getByText("내용 변경 없음")).toBeInTheDocument()
    await user.click(screen.getByRole("checkbox", { name: "아동에게 노출되는 내용을 검토했습니다." }))
    await user.click(screen.getByRole("button", { name: "승인" }))
    await screen.findByText(/승인했습니다/)
    expect(sent.find((request) => request.method === "PUT")!.body).not.toHaveProperty("edits")
  })

  it("필요 증거가 1~3개가 아니면 승인으로 넘어가지 못한다", async () => {
    serve([scenarioReview()])
    const { user } = renderReview()

    await user.click(await screen.findByRole("button", { name: "수정하기" }))
    const evidence = screen.getAllByRole("textbox", { name: /필요 증거/ })[0]
    await user.clear(evidence)
    expect(screen.getByRole("alert")).toHaveTextContent("작은 목표 1의 필요 증거는 1~3개여야 합니다.")
    expect(screen.getByRole("button", { name: "승인으로 이동" })).toBeDisabled()
  })

  it("검토가 다른 곳에서 바뀌었으면 [새로 불러오기]가 새 키로 지금 상태를 다시 불러온다", async () => {
    const sent = serve([scenarioReview(), SECOND])
    server.use(
      http.put("*/api/v1/activities/:activityId/scenario", () =>
        apiError(409, "SCENARIO_REVIEW_CHANGED", "검토가 바뀌었습니다.", PATH),
      ),
    )
    const { user } = renderReview()

    await user.click(await screen.findByRole("button", { name: "승인으로 이동" }))
    await user.click(screen.getByRole("checkbox", { name: "아동에게 노출되는 내용을 검토했습니다." }))
    await user.click(screen.getByRole("button", { name: "승인" }))
    await user.click(await screen.findByRole("button", { name: "새로 불러오기" }))

    expect(await screen.findByRole("heading", { name: "넘어진 친구 돕기" })).toBeInTheDocument()
    expect(sent[1].body).toEqual({ action: "START_OR_RESUME" })
    expect(sent[1].key).not.toBe(sent[0].key)
  })

  it("[검토 그만하기]는 검토 번호를 실어 취소하고 승인 대기함으로 돌아간다", async () => {
    const sent = serve([scenarioReview()])
    const { user, router } = renderReview()

    await user.click(await screen.findByRole("button", { name: "검토 그만하기" }))
    await user.click(await screen.findByRole("button", { name: "검토 그만하기", hidden: false }))
    await waitFor(() => expect(router.state.location.pathname).toBe("/content"))
    const deleted = sent.find((request) => request.method === "DELETE")!
    expect(deleted.body).toEqual({ reviewId: REVIEW_ID, revision: 1 })
  })

  it("고친 뒤 다른 후보로 넘기다 연결이 끊기면 확인창 안에서 알리고 같은 키로 다시 보낸다", async () => {
    const sent = serve([scenarioReview(), () => HttpResponse.error(), SECOND])
    const { user } = renderReview()

    await user.click(await screen.findByRole("button", { name: "수정하기" }))
    await user.type(screen.getByRole("textbox", { name: "제목" }), "!")
    await user.click(screen.getByRole("button", { name: "다른 후보" }))
    const dialog = await screen.findByRole("alertdialog")
    await user.click(within(dialog).getByRole("button", { name: "다른 후보 보기" }))
    expect(await within(dialog).findByText(/서버에 연결할 수 없습니다/)).toBeInTheDocument()

    await user.click(within(dialog).getByRole("button", { name: "다른 후보 보기" }))
    expect(await screen.findByRole("heading", { name: "넘어진 친구 돕기" })).toBeInTheDocument()
    expect(sent[2].key).toBe(sent[1].key)
  })

  it("검토 그만하기가 검토 변경으로 막히면 확인창을 닫고 [새로 불러오기]를 보인다", async () => {
    serve([scenarioReview()])
    server.use(
      http.delete("*/api/v1/activities/:activityId/scenario-review", () =>
        apiError(409, "SCENARIO_REVIEW_CHANGED", "검토가 바뀌었습니다.", PATH),
      ),
    )
    const { user } = renderReview()

    await user.click(await screen.findByRole("button", { name: "검토 그만하기" }))
    const dialog = await screen.findByRole("alertdialog")
    await user.click(within(dialog).getByRole("button", { name: "검토 그만하기" }))

    expect(await screen.findByRole("button", { name: "새로 불러오기" })).toBeInTheDocument()
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument()
  })

  it("고친 내용이 있는 채로 나가려 하면 사라진다고 묻는다", async () => {
    serve([scenarioReview()])
    const { user, router } = renderReview()

    await user.click(await screen.findByRole("button", { name: "수정하기" }))
    await user.type(screen.getByRole("textbox", { name: "제목" }), "!")
    await user.click(screen.getByRole("link", { name: "승인 대기함" }))

    expect(await screen.findByText("고친 내용을 버리고 나갈까요?")).toBeInTheDocument()
    expect(router.state.location.pathname).toBe(PATH)
    await user.click(screen.getByRole("button", { name: "나가기" }))
    await waitFor(() => expect(router.state.location.pathname).toBe("/content"))
  })

  it("이미 시나리오가 정해진 활동이면 안내한다", async () => {
    server.use(
      http.post("*/api/v1/activities/:activityId/scenario-review", () =>
        apiError(409, "SCENARIO_ALREADY_ASSIGNED", "이미 배정됨", PATH),
      ),
    )
    renderReview()
    expect(await screen.findByText(/이미 시나리오가 정해진 활동입니다/)).toBeInTheDocument()
  })
})
