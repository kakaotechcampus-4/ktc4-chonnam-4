import { screen, waitFor, within } from "@testing-library/react"
import { beforeEach, describe, expect, it } from "vitest"
import { AGENT_RUN_TEMPLATES, agentRunHandlers, buildAgentRun } from "@/test/msw/agentRuns"
import { server } from "@/test/msw/server"
import { renderRoutes, signIn } from "@/test/render"
import { AgentRunsPage } from "./AgentRunsPage"

// 운영 대시보드. 정상 흐름(한 번에 통과·재시도 후 정상)과 오류 흐름(안내로 종료·오류)이 같은 화면에 함께 보이고,
// 단계가 낸 판정이 실제로 어느 단계로 이어졌는지를 표와 실행 상세에서 읽을 수 있어야 한다.

const ID = {
  normal: "7c1f3a52-0d6b-4f0e-9a51-0000000000a1",
  regenerate: "7c1f3a52-0d6b-4f0e-9a51-0000000000a2",
  lowConfidence: "7c1f3a52-0d6b-4f0e-9a51-0000000000a3",
  timeout: "7c1f3a52-0d6b-4f0e-9a51-0000000000a4",
}

function template(key: string) {
  return AGENT_RUN_TEMPLATES.find((candidate) => candidate.key === key)!
}

function minutesAgo(minutes: number) {
  return new Date(Date.now() - minutes * 60_000)
}

const runs = () => [
  buildAgentRun(template("roleplay-text-normal"), ID.normal, minutesAgo(5)),
  buildAgentRun(template("roleplay-regenerate"), ID.regenerate, minutesAgo(12)),
  buildAgentRun(template("roleplay-low-confidence"), ID.lowConfidence, minutesAgo(20)),
  buildAgentRun(template("roleplay-evaluation-timeout"), ID.timeout, minutesAgo(31)),
]

function renderDashboard(search = "", options: Parameters<typeof agentRunHandlers>[0] = { runs: runs() }) {
  server.use(...agentRunHandlers(options))
  return renderRoutes([{ path: "/ops/agent-runs", element: <AgentRunsPage /> }], `/ops/agent-runs${search}`)
}

describe("에이전트 워크플로우 실행 기록 대시보드", () => {
  beforeEach(() => signIn())

  it("정상 흐름과 오류 흐름의 건수를 함께 보인다", async () => {
    renderDashboard()

    const paths = await screen.findByRole("region", { name: "흐름 종류" })
    for (const [label, count] of [
      ["한 번에 통과", "1"],
      ["재시도 후 정상", "1"],
      ["안내로 종료", "1"],
      ["오류", "1"],
    ]) {
      const row = within(paths).getByText(label).closest("li")!
      expect(within(row).getByText(count)).toBeInTheDocument()
      expect(within(row).getByText("25%")).toBeInTheDocument()
    }
    expect(within(screen.getByRole("region", { name: "실행 수" })).getByText("4")).toBeInTheDocument()
  })

  it("단계가 낸 판정과 그 때문에 이어진 다음 단계를 단계별로 보인다", async () => {
    renderDashboard()

    const transitions = await screen.findByRole("region", { name: "판정이 이어진 곳" })
    const regenerate = within(transitions).getByText("REGENERATE").closest("tr")!
    expect(within(regenerate).getByText("응답 생성")).toBeInTheDocument()

    const lowConfidence = within(transitions).getByText("INPUT_REJECTED").closest("tr")!
    expect(within(lowConfidence).getByText("다시 입력 요청")).toBeInTheDocument()

    // 검수 호출이 시간 초과로 세 번 실패한 흐름: 같은 단계가 다시 돌고, 마지막에는 턴 결과 판정으로 넘어간다.
    const timeouts = within(transitions).getAllByText("TIMEOUT").map((code) => code.closest("tr")!)
    // 셀: 판정 · 다음 단계 · 건수 · 비율(단계 이름 칸은 묶음 머리라 셀이 아니다)
    expect(timeouts.map((row) => within(row).getAllByRole("cell")[2].textContent)).toEqual(["2", "1"])
    expect(timeouts.map((row) => within(row).getAllByRole("cell")[3].textContent)).toEqual(["33%", "17%"])
    expect(within(timeouts[0]).getByText("응답 검수")).toBeInTheDocument()
    expect(within(timeouts[1]).getByText("턴 결과 판정")).toBeInTheDocument()
  })

  it("거절·실패만 고르면 통과 판정 줄이 빠지고 거절·실패 줄만 남는다", async () => {
    const { user } = renderDashboard()

    const transitions = await screen.findByRole("region", { name: "판정이 이어진 곳" })
    expect(within(transitions).getAllByText("VALID").length).toBeGreaterThan(0)

    await user.click(within(transitions).getByRole("button", { name: "거절·실패만" }))

    expect(within(transitions).queryByText("VALID")).not.toBeInTheDocument()
    expect(within(transitions).getByText("REGENERATE")).toBeInTheDocument()
    expect(within(transitions).getAllByText("TIMEOUT").length).toBeGreaterThan(0)
  })

  it("한 번에 통과하지 못한 실행을 원인 단계와 사유로 묶어 보인다", async () => {
    renderDashboard()

    const endings = await screen.findByRole("region", { name: "한 번에 통과하지 못한 실행" })
    const rows = within(endings).getAllByRole("row").slice(1)
    expect(rows.map((row) => row.textContent)).toEqual(
      expect.arrayContaining([
        expect.stringMatching(/재시도 후 정상응답 검수REGENERATE응답 전달1/),
        expect.stringMatching(/안내로 종료음성 인식LOW_CONFIDENCE다시 입력 요청1/),
        expect.stringMatching(/오류응답 검수LIMIT_REACHED다시 시도 요청1/),
      ]),
    )
  })

  it("실행을 누르면 단계 흐름 상세가 열리고 주소에 남으며 Esc 로 닫힌다", async () => {
    const { user, router } = renderDashboard()

    const list = await screen.findByRole("region", { name: "최근 실행" })
    const row = (await within(list).findByText("REGENERATE")).closest("tr")!
    await user.click(within(row).getByRole("button", { name: /실행 상세 열기/ }))

    const detail = await screen.findByRole("dialog", { name: "실행 상세" })
    expect(new URLSearchParams(router.state.location.search).get("run")).toBe(ID.regenerate)
    const flow = await within(detail).findByRole("region", { name: "단계 흐름" })
    const steps = within(flow).getAllByRole("listitem")
    expect(steps.map((step) => step.querySelector("p")?.textContent)).toEqual([
      "입력 판정AI 호출",
      "원인 분석AI 호출",
      "응답 생성AI 호출",
      "응답 검수AI 호출",
      "응답 생성 2번째AI 호출",
      "응답 검수 2번째AI 호출",
      "턴 결과 판정코드 판정",
      "최종 결과최종 결과",
    ])
    const rejected = steps[3]
    expect(within(rejected).getByText("거절")).toBeInTheDocument()
    expect(within(rejected).getByText("ANSWER_TOO_DIRECT")).toBeInTheDocument()
    expect(rejected).toHaveTextContent("다음: 응답 생성")
    // 승인된 턴은 판정이 지목한 곳(RESPONSE_DELIVERY)과 실제 결과(DELIVERED)가 같은 뜻이라 덧붙이지 않는다.
    expect(steps[6]).toHaveTextContent("다음: 응답 전달")
    expect(steps[6]).not.toHaveTextContent("판정이 지목한 곳")

    await user.keyboard("{Escape}")
    expect(screen.queryByRole("dialog", { name: "실행 상세" })).not.toBeInTheDocument()
    expect(new URLSearchParams(router.state.location.search).get("run")).toBeNull()
  })

  it("판정이 지목한 단계와 실제로 이어진 단계가 다르면 둘 다 보인다", async () => {
    renderDashboard(`?run=${ID.timeout}`)

    const detail = await screen.findByRole("dialog", { name: "실행 상세" })
    const resolution = (await within(detail).findAllByRole("listitem")).find((item) =>
      item.textContent?.startsWith("턴 결과 판정"),
    )!
    expect(resolution).toHaveTextContent("다음: 다시 시도 요청")
    expect(within(resolution).getByText("NOTICE_SAFETY_CHECK_ERROR")).toBeInTheDocument()
    expect(within(resolution).getByText("LIMIT_REACHED")).toBeInTheDocument()
  })

  it("요청 번호로 실행을 찾고, 형식이 틀리면 안내한다", async () => {
    const { user, router } = renderDashboard()

    const input = await screen.findByPlaceholderText("요청 번호(X-Trace-Id)로 실행 찾기")
    await user.type(input, "not-a-trace-id")
    await user.click(screen.getByRole("button", { name: "열기" }))
    expect(screen.getByRole("alert")).toHaveTextContent("UUID 형식")

    await user.clear(input)
    await user.type(input, ` ${ID.lowConfidence.toUpperCase()} `)
    await user.click(screen.getByRole("button", { name: "열기" }))

    const detail = await screen.findByRole("dialog", { name: "실행 상세" })
    // 원인 사유와 턴 결과 판정 단계 두 곳에 나온다.
    expect(await within(detail).findAllByText("LOW_CONFIDENCE")).toHaveLength(2)
    expect(new URLSearchParams(router.state.location.search).get("run")).toBe(ID.lowConfidence)
  })

  it("없는 요청 번호면 기록이 없다고 알린다", async () => {
    renderDashboard("?run=7c1f3a52-0d6b-4f0e-9a51-0000000000ff")

    const detail = await screen.findByRole("dialog", { name: "실행 상세" })
    expect(await within(detail).findByText(/이 번호의 실행 기록이 없습니다/)).toBeInTheDocument()
  })

  it("기간과 워크플로우를 바꾸면 주소에 남기고 그 조건으로 다시 조회한다", async () => {
    const requested: string[] = []
    server.events.on("request:start", ({ request }) => {
      if (request.url.includes("/ops/agent-runs/overview")) requested.push(new URL(request.url).search)
    })
    const { user, router } = renderDashboard()

    await screen.findByRole("region", { name: "흐름 종류" })
    await user.selectOptions(screen.getByRole("combobox", { name: "기간" }), "168")
    await user.selectOptions(screen.getByRole("combobox", { name: "워크플로우" }), "ROLEPLAY_TURN")

    const search = new URLSearchParams(router.state.location.search)
    expect(search.get("hours")).toBe("168")
    expect(search.get("workflow")).toBe("ROLEPLAY_TURN")
    await waitFor(() => expect(requested).toContain("?hours=168&workflow=ROLEPLAY_TURN"))
    expect(screen.getAllByText("최근 7일").length).toBeGreaterThan(1)
  })

  it("볼 수 있는 계정 목록에 없으면 설정을 확인하라고 안내한다", async () => {
    renderDashboard("", { denied: true })

    expect(await screen.findByRole("region", { name: "이 계정으로는 볼 수 없습니다" })).toHaveTextContent(
      "NEURINGO_AGENT_RUNS_OPERATORS",
    )
  })

  it("기록이 없으면 빈 상태를 보인다", async () => {
    renderDashboard("", { runs: [] })

    const chart = await screen.findByRole("region", { name: "시간대별 실행" })
    expect(within(chart).getByText("이 기간에 기록된 실행이 없습니다.")).toBeInTheDocument()
    // 실행이 0건이면 "모두 한 번에 통과했다"고 말하지 않는다.
    const endings = screen.getByRole("region", { name: "한 번에 통과하지 못한 실행" })
    expect(within(endings).getByText("이 기간에 기록된 실행이 없습니다.")).toBeInTheDocument()
    expect(screen.queryByText("모든 실행이 한 번에 통과했습니다.")).not.toBeInTheDocument()
    expect(within(screen.getByRole("region", { name: "최근 실행" })).getByText("조건에 맞는 실행이 없습니다.")).toBeInTheDocument()
  })
})
