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

  it("성공 실행과 실패 실행을 나눠 세고, 각각의 세부 흐름 건수를 보인다", async () => {
    renderDashboard()

    const success = await screen.findByRole("region", { name: "성공 실행" })
    expect(success).toHaveTextContent("2")
    expect(success).toHaveTextContent("50%")
    expect(success).toHaveTextContent("한 번에 통과 1")
    expect(success).toHaveTextContent("재시도 후 정상 1")

    const failure = screen.getByRole("region", { name: "실패 실행" })
    expect(failure).toHaveTextContent("2")
    expect(failure).toHaveTextContent("안내로 종료 1")
    expect(failure).toHaveTextContent("오류 1")

    // 성공·실패 구역이 따로 있고, 머리에 건수와 비율이 나온다.
    expect(screen.getByRole("region", { name: "성공 흐름" })).toHaveTextContent("2건 · 50%")
    expect(screen.getByRole("region", { name: "실패 흐름" })).toHaveTextContent("2건 · 50%")
  })

  it("성공 구역과 실패 구역에 각각의 흐름 패턴을 단계 순서대로 보이고 최근 Trace ID 를 붙인다", async () => {
    const { user } = renderDashboard()

    const success = await screen.findByRole("region", { name: "성공 흐름" })
    const successPatterns = within(success).getByRole("region", { name: "흐름 패턴" })
    const retried = within(successPatterns).getByText("재시도 후 정상").closest("li")!
    const chain = within(retried).getByRole("list", { name: "단계 흐름" })
    expect(within(chain).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      "통과입력 판정",
      "통과원인 분석",
      "통과응답 생성",
      "거절 → 다시 시도응답 검수· 응답 다시 만들기",
      "통과응답 생성 2",
      "통과응답 검수 2",
      "통과턴 결과 판정",
      "응답 전달",
    ])
    expect(within(retried).getByRole("button", { name: `Trace ID ${ID.regenerate} 상세 열기` })).toBeInTheDocument()
    // 실패한 흐름은 성공 구역에 나오지 않는다.
    expect(within(successPatterns).queryByText("오류")).not.toBeInTheDocument()

    const failure = screen.getByRole("region", { name: "실패 흐름" })
    const failurePatterns = within(failure).getByRole("region", { name: "흐름 패턴" })
    // 칩에는 판정의 뜻을 쓰고, 코드와 그 단계가 한 역할(다시 시도·멈춤·실패)은 마우스를 올리면 보인다.
    expect(within(failurePatterns).getAllByTitle(/· TIMEOUT$/)).toHaveLength(3)
    expect(within(failurePatterns).getByTitle("실패 → 오류로 종료 · LIMIT_REACHED")).toHaveTextContent("재시도 한도 도달")
    expect(within(failurePatterns).getByTitle("멈춤 → 안내로 종료 · INPUT_REJECTED")).toHaveTextContent("입력 거절")
    expect(within(failurePatterns).queryByTitle(/REGENERATE$/)).not.toBeInTheDocument()

    await user.click(within(failurePatterns).getByRole("button", { name: `Trace ID ${ID.timeout} 상세 열기` }))
    const detail = await screen.findByRole("dialog", { name: "실행 상세" })
    expect(await within(detail).findByText(ID.timeout)).toBeInTheDocument()
  })

  it("실행 목록은 구역마다 따로 보이고 Trace ID 를 복사할 수 있다", async () => {
    const { user } = renderDashboard()

    const success = await screen.findByRole("region", { name: "성공 흐름" })
    const successList = within(success).getByRole("region", { name: "실행 목록" })
    await within(successList).findByRole("button", { name: `Trace ID ${ID.normal} 상세 열기` })
    expect(within(successList).getByRole("button", { name: `Trace ID ${ID.regenerate} 상세 열기` })).toBeInTheDocument()
    expect(within(successList).queryByRole("button", { name: `Trace ID ${ID.timeout} 상세 열기` })).not.toBeInTheDocument()

    const failureList = within(screen.getByRole("region", { name: "실패 흐름" })).getByRole("region", {
      name: "실행 목록",
    })
    // 실패 목록은 넓은 칸이라 Trace ID 를 줄이지 않고 다 보인다.
    expect(await within(failureList).findByText(ID.lowConfidence)).toBeInTheDocument()
    expect(within(failureList).getByText(ID.timeout)).toBeInTheDocument()

    await user.click(within(failureList).getByRole("button", { name: `Trace ID ${ID.timeout} 복사` }))
    expect(await navigator.clipboard.readText()).toBe(ID.timeout)
    expect(within(failureList).getByRole("button", { name: "Trace ID 복사함" })).toBeInTheDocument()

    // 세부 흐름으로 좁히면 그 흐름만 남는다.
    await user.click(within(failureList).getByRole("button", { name: "오류" }))
    await waitFor(() => expect(within(failureList).queryByText(ID.lowConfidence)).not.toBeInTheDocument())
    expect(within(failureList).getByText(ID.timeout)).toBeInTheDocument()
  })

  it("자동 진단은 실행이 적으면 판단하지 않았다고 알린다", async () => {
    renderDashboard()

    const diagnosis = await screen.findByRole("region", { name: "자동 진단" })
    expect(diagnosis).toHaveTextContent("실행이 20건 미만이라 판단하지 않았습니다(이번 기간 4건).")
  })

  it("자동 진단 항목을 심각한 순서로 문장·볼 곳·예시 Trace ID 와 함께 보인다", async () => {
    const { user } = renderDashboard("", {
      runs: runs(),
      diagnosis: {
        previousRuns: 40,
        failureRate: 0.42,
        previousFailureRate: 0.1,
        findings: [
          {
            kind: "FAILURE_RATE",
            severity: "CRITICAL",
            workflow: null,
            step: null,
            code: null,
            current: 0.42,
            previous: 0.1,
            threshold: 0.3,
            unit: "RATE",
            sampleRunIds: [ID.timeout],
          },
          {
            kind: "STEP_BLOCKED",
            severity: "WARNING",
            workflow: "ROLEPLAY_TURN",
            step: "RESPONSE_EVALUATION",
            code: "REGENERATE",
            current: 0.24,
            previous: null,
            threshold: 0.2,
            unit: "RATE",
            sampleRunIds: [ID.regenerate],
          },
          {
            kind: "REASON_SURGE",
            severity: "WARNING",
            workflow: "ROLEPLAY_TURN",
            step: "SPEECH_TRANSCRIPTION",
            code: "LOW_CONFIDENCE",
            current: 8,
            previous: 2,
            threshold: 2,
            unit: "COUNT",
            sampleRunIds: [ID.lowConfidence],
          },
        ],
      },
    })

    const diagnosis = await screen.findByRole("region", { name: "자동 진단" })
    expect(diagnosis).toHaveTextContent("실패 비율 42% · 앞 기간 10%(40건)")
    const items = within(diagnosis).getAllByRole("listitem")
    expect(items.map((item) => item.querySelector("p")?.textContent)).toEqual([
      "실패 비율이 42%입니다 (앞 기간 10%)",
      "역할극 턴 응답 검수 단계의 거절·실패가 24%입니다. 가장 많은 판정은 「응답 다시 만들기」(REGENERATE)",
      "「음성 인식 신뢰도 낮음」(LOW_CONFIDENCE) 사유로 끝난 실행이 8건으로 늘었습니다 (앞 기간 2건) · 역할극 턴 음성 인식",
    ])
    expect(items[0]).toHaveTextContent("심각")
    expect(items[0]).toHaveTextContent("실패 흐름 구역의 흐름 패턴과 실패 원인")
    expect(items[1]).toHaveTextContent("주의")
    expect(items[1]).toHaveTextContent("생성 프롬프트와 검수 기준")
    expect(items[2]).toHaveTextContent("STT confidence threshold")

    await user.click(within(items[2]).getByRole("button", { name: `Trace ID ${ID.lowConfidence} 상세 열기` }))
    const detail = await screen.findByRole("dialog", { name: "실행 상세" })
    expect(await within(detail).findByText(ID.lowConfidence)).toBeInTheDocument()
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

  it("실패 원인은 실패 구역에만, 원인 단계와 사유로 묶어 보인다", async () => {
    renderDashboard()

    const failure = await screen.findByRole("region", { name: "실패 흐름" })
    const causes = within(failure).getByRole("region", { name: "실패 원인" })
    const rows = within(causes).getAllByRole("row").slice(1)
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringMatching(/안내로 종료음성 인식음성 인식 신뢰도 낮음LOW_CONFIDENCE다시 입력 요청1/),
      expect.stringMatching(/오류응답 검수재시도 한도 도달LIMIT_REACHED다시 시도 요청1/),
    ])
    // 원인마다 그 원인으로 끝난 최근 실행의 Trace ID 를 붙인다.
    expect(within(rows[0]).getByRole("button", { name: `Trace ID ${ID.lowConfidence} 상세 열기` })).toBeInTheDocument()
    expect(within(rows[1]).getByRole("button", { name: `Trace ID ${ID.timeout} 상세 열기` })).toBeInTheDocument()
  })

  it("실행을 누르면 단계 흐름 상세가 열리고 주소에 남으며 Esc 로 닫힌다", async () => {
    const { user, router } = renderDashboard()

    const success = await screen.findByRole("region", { name: "성공 흐름" })
    const list = within(success).getByRole("region", { name: "실행 목록" })
    await user.click(await within(list).findByRole("button", { name: `Trace ID ${ID.regenerate} 상세 열기` }))

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
    expect(within(rejected).getByText("거절 → 다시 시도")).toBeInTheDocument()
    expect(within(rejected).getByText("답을 바로 알려 줌")).toBeInTheDocument()
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
    // 안내 문구 코드는 뜻으로 읽는다. 판정 코드는 뜻과 코드를 함께 둔다.
    expect(resolution).toHaveTextContent("고른 안내: 안전 확인 오류 안내")
    expect(within(resolution).getByText("LIMIT_REACHED")).toBeInTheDocument()
    expect(within(resolution).getByText("재시도 한도 도달")).toBeInTheDocument()
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

    await screen.findByRole("region", { name: "성공 실행" })
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
    const success = screen.getByRole("region", { name: "성공 흐름" })
    expect(within(success).getByText("정상 결과로 끝난 실행이 없습니다.")).toBeInTheDocument()
    const failure = screen.getByRole("region", { name: "실패 흐름" })
    expect(within(failure).getAllByText("정상 결과 없이 끝난 실행이 없습니다.")).toHaveLength(2)
    for (const section of [success, failure]) {
      const list = within(section).getByRole("region", { name: "실행 목록" })
      expect(await within(list).findByText("조건에 맞는 실행이 없습니다.")).toBeInTheDocument()
    }
  })
})
