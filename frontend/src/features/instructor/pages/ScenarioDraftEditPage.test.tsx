import { screen } from "@testing-library/react"
import { http, HttpResponse } from "msw"
import { beforeEach, describe, expect, it } from "vitest"
import { envelope } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { GOAL, goalHandler, scenario } from "@/test/msw/scenarios"
import { renderRoutes, signIn } from "@/test/render"
import type { Scenario } from "../api"
import { ScenarioDraftEditPage } from "./ScenarioDraftEditPage"

// 초안 수정. 제목·상황·작은 목표만 고치고, 확인 단계를 거쳐 승인하거나 반려·폐기한다. 다시 만들기는 아직 꺼져 있다.

const DRAFT_ID = "80000000-0000-4000-8000-000000000009"
const MICRO_GOAL_ID = "81000000-0000-4000-8000-000000000009"

function draft(overrides: Partial<Scenario> = {}) {
  return scenario({
    scenarioId: DRAFT_ID,
    version: 2,
    status: "DRAFT",
    scenarioSource: "NEWLY_GENERATED",
    microGoals: [{ microGoalId: MICRO_GOAL_ID, description: "친구의 감정을 말한다.", requiredEvidence: ["속상하다고 말함"] }],
    ...overrides,
  })
}

function serveDraft() {
  let current = draft()
  const patches: Record<string, unknown>[] = []
  const deletes: string[] = []
  server.use(
    goalHandler(),
    http.get("*/api/v1/scenarios/:scenarioId", () => HttpResponse.json(envelope(current))),
    http.patch("*/api/v1/scenarios/:scenarioId", async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>
      patches.push(body)
      current = {
        ...current,
        ...(typeof body.title === "string" ? { title: body.title } : {}),
        ...(typeof body.summary === "string" ? { summary: body.summary } : {}),
        ...(body.status === "APPROVED" ? { status: "APPROVED" as const } : {}),
        ...(body.status === "REJECTED" ? { status: "REJECTED" as const } : {}),
      }
      return HttpResponse.json(envelope(current))
    }),
    http.delete("*/api/v1/scenarios/:scenarioId", ({ params }) => {
      deletes.push(String(params.scenarioId))
      return new HttpResponse(null, { status: 204 })
    }),
  )
  return { patches, deletes }
}

function renderEdit() {
  return renderRoutes(
    [
      { path: "/scenarios/:scenarioId/edit", element: <ScenarioDraftEditPage /> },
      { path: "/content", element: <p>승인 대기함 화면</p> },
    ],
    `/scenarios/${DRAFT_ID}/edit`,
  )
}

describe("초안 수정", () => {
  beforeEach(() => signIn())

  it("고친 칸만 저장하고, 금지 추론·시작 질문은 고칠 수 없고 다시 만들기는 꺼져 있다", async () => {
    const { patches } = serveDraft()
    const { user } = renderEdit()

    const summary = await screen.findByRole("textbox", { name: "상황" })
    expect(screen.getByText("v1 → v2 작성 중")).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "재생성 (v3 생성)" })).toBeDisabled()
    expect(screen.getByRole("textbox", { name: "수정 지시" })).toBeDisabled()
    expect(screen.queryByRole("textbox", { name: "금지 추론" })).not.toBeInTheDocument()

    await user.clear(summary)
    await user.type(summary, "친구가 넘어져 울고 있다.")
    const evidence = screen.getByRole("textbox", { name: /필요 증거/ })
    await user.clear(evidence)
    await user.type(evidence, "괜찮냐고 물음{Enter}속상하겠다고 말함")
    await user.click(screen.getByRole("button", { name: "저장" }))

    expect(await screen.findByText("저장했습니다.")).toBeInTheDocument()
    expect(patches).toEqual([
      {
        version: 2,
        summary: "친구가 넘어져 울고 있다.",
        microGoals: [
          { microGoalId: MICRO_GOAL_ID, description: "친구의 감정을 말한다.", requiredEvidence: ["괜찮냐고 물음", "속상하겠다고 말함"] },
        ],
      },
    ])
  })

  it("승인 확인을 거쳐 고친 내용과 함께 승인한다", async () => {
    const { patches } = serveDraft()
    const { user } = renderEdit()

    const title = await screen.findByRole("textbox", { name: "제목" })
    await user.clear(title)
    await user.type(title, "고친 제목")
    await user.click(screen.getByRole("button", { name: "승인으로 이동" }))
    expect(screen.getByRole("region", { name: "시나리오 승인 확인" })).toHaveTextContent(GOAL.title)
    expect(screen.getByRole("heading", { name: "고친 제목" })).toBeInTheDocument()

    await user.click(screen.getByRole("checkbox", { name: "아동에게 노출되는 내용을 검토했습니다." }))
    await user.click(screen.getByRole("button", { name: "승인" }))
    expect(await screen.findByText(/승인했습니다/)).toBeInTheDocument()
    expect(patches).toEqual([{ version: 2, title: "고친 제목", status: "APPROVED" }])
  })

  it("반려하면 기록으로 남아 더 고칠 수 없다", async () => {
    const { patches } = serveDraft()
    const { user } = renderEdit()

    await user.click(await screen.findByRole("button", { name: "반려" }))
    await user.click(await screen.findByRole("button", { name: "반려" }))
    expect(await screen.findByText("판정이 끝난 시나리오라 고칠 수 없습니다.")).toBeInTheDocument()
    expect(patches).toEqual([{ version: 2, status: "REJECTED" }])
    expect(screen.queryByRole("button", { name: "저장" })).not.toBeInTheDocument()
  })

  it("폐기하면 지우고 승인 대기함으로 돌아간다", async () => {
    const { deletes } = serveDraft()
    const { user } = renderEdit()

    await user.click(await screen.findByRole("button", { name: "폐기" }))
    await user.click(await screen.findByRole("button", { name: "폐기" }))
    expect(await screen.findByText("승인 대기함 화면")).toBeInTheDocument()
    expect(deletes).toEqual([DRAFT_ID])
  })
})
