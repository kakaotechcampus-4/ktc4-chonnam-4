import { screen } from "@testing-library/react"
import { http, HttpResponse } from "msw"
import { beforeEach, describe, expect, it } from "vitest"
import { apiError, envelope } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { GOAL, goalHandler, scenario } from "@/test/msw/scenarios"
import { renderRoutes, signIn } from "@/test/render"
import { ScenarioCandidatePage } from "./ScenarioCandidatePage"

// 재사용 후보 검토. 후보를 하나씩 넘기고, 확인 단계를 거쳐 승인하거나, 이 목표용 새 판을 만들어 고치러 간다.

const ACTIVITY_ID = "40000000-0000-4000-8000-000000000001"
const FIRST = scenario({ scenarioId: "80000000-0000-4000-8000-000000000001", title: "첫 후보" })
const SECOND = scenario({ scenarioId: "80000000-0000-4000-8000-000000000002", title: "둘째 후보" })

function serveCandidates() {
  const afters: (string | null)[] = []
  server.use(
    goalHandler(),
    http.get("*/api/v1/activities/:activityId/scenario-candidate", ({ request }) => {
      const after = new URL(request.url).searchParams.get("after")
      afters.push(after)
      return HttpResponse.json(
        envelope(after === FIRST.scenarioId ? { scenario: SECOND, hasNext: false } : { scenario: FIRST, hasNext: true }),
      )
    }),
  )
  return afters
}

function renderCandidate() {
  return renderRoutes(
    [
      { path: "/activities/:activityId/scenario", element: <ScenarioCandidatePage /> },
      { path: "/scenarios/:scenarioId/edit", element: <p>초안 수정 화면</p> },
    ],
    `/activities/${ACTIVITY_ID}/scenario`,
  )
}

describe("재사용 후보 검토", () => {
  beforeEach(() => signIn())

  it("후보 내용을 보여 주고 [다음 후보]로 넘기면 마지막에서 버튼이 꺼진다", async () => {
    const afters = serveCandidates()
    const { user } = renderCandidate()

    expect(await screen.findByRole("heading", { name: "첫 후보" })).toBeInTheDocument()
    expect(screen.getByText("쉬는 시간에 친구의 그림에 물이 쏟아졌다.")).toBeInTheDocument()
    expect(screen.getByText("친구의 감정을 말한다.", { exact: false })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "새로 만들기" })).toBeDisabled()

    await user.click(screen.getByRole("button", { name: "다음 후보" }))
    expect(await screen.findByRole("heading", { name: "둘째 후보" })).toBeInTheDocument()
    expect(afters).toEqual([null, FIRST.scenarioId])
    expect(screen.getByRole("button", { name: "다음 후보" })).toBeDisabled()
    expect(screen.getByText(/마지막 후보입니다/)).toBeInTheDocument()
  })

  it("확인을 체크해야 승인되고, 승인하면 그 후보를 활동에 붙인다", async () => {
    serveCandidates()
    const attached: unknown[] = []
    server.use(
      http.put("*/api/v1/activities/:activityId/scenario", async ({ request }) => {
        attached.push(await request.json())
        // 붙인 뒤에는 서버처럼 후보 조회가 409 다. 화면이 이것으로 성공 안내를 덮지 않아야 한다.
        server.use(
          http.get("*/api/v1/activities/:activityId/scenario-candidate", ({ request: candidate }) =>
            apiError(409, "SCENARIO_ALREADY_ATTACHED", "이미 정해짐", new URL(candidate.url).pathname),
          ),
        )
        return HttpResponse.json(envelope(FIRST))
      }),
    )
    const { user } = renderCandidate()

    await user.click(await screen.findByRole("button", { name: "승인으로 이동" }))
    const panel = screen.getByRole("region", { name: "시나리오 승인 확인" })
    expect(panel).toHaveTextContent(GOAL.title)
    expect(panel).toHaveTextContent("바보")
    const approveButton = screen.getByRole("button", { name: "승인" })
    expect(approveButton).toBeDisabled()

    await user.click(screen.getByRole("checkbox", { name: "아동에게 노출되는 내용을 검토했습니다." }))
    await user.click(approveButton)
    expect(await screen.findByText(/승인했습니다/)).toBeInTheDocument()
    expect(attached).toEqual([{ scenarioId: FIRST.scenarioId }])
  })

  it("[수정하기]는 이 목표용 새 판을 만들고 수정 화면으로 간다", async () => {
    serveCandidates()
    const bodies: unknown[] = []
    server.use(
      http.post("*/api/v1/scenarios/:scenarioId/versions", async ({ request }) => {
        bodies.push(await request.json())
        return HttpResponse.json(envelope(scenario({ scenarioId: "80000000-0000-4000-8000-000000000009", status: "DRAFT", version: 2 })), {
          status: 201,
        })
      }),
    )
    const { user } = renderCandidate()

    await user.click(await screen.findByRole("button", { name: "수정하기" }))
    expect(await screen.findByText("초안 수정 화면")).toBeInTheDocument()
    expect(bodies).toEqual([{ version: 1, goalId: GOAL.goalId }])
  })

  it("고치는 중인 초안이 있으면 승인 대기함으로 안내한다", async () => {
    serveCandidates()
    server.use(
      http.post("*/api/v1/scenarios/:scenarioId/versions", ({ request }) =>
        apiError(409, "SCENARIO_DRAFT_EXISTS", "이미 있습니다.", new URL(request.url).pathname),
      ),
    )
    const { user } = renderCandidate()

    await user.click(await screen.findByRole("button", { name: "수정하기" }))
    expect(await screen.findByRole("alert")).toHaveTextContent("고치는 중인 시나리오가 이미 있습니다")
  })

  it("후보가 없으면 새로 만들기가 필요하다고 안내한다", async () => {
    server.use(
      goalHandler(),
      http.get("*/api/v1/activities/:activityId/scenario-candidate", () =>
        HttpResponse.json(envelope({ scenario: null, hasNext: false })),
      ),
    )
    renderCandidate()
    expect(await screen.findByText(/재사용할 수 있는 시나리오가 없습니다/)).toBeInTheDocument()
  })

  it("이미 시나리오가 정해진 활동이면 그렇다고 알린다", async () => {
    server.use(
      http.get("*/api/v1/activities/:activityId/scenario-candidate", ({ request }) =>
        apiError(409, "SCENARIO_ALREADY_ATTACHED", "이미 정해짐", new URL(request.url).pathname),
      ),
    )
    renderCandidate()
    expect(await screen.findByText(/이미 시나리오가 정해진 활동입니다/)).toBeInTheDocument()
  })
})
