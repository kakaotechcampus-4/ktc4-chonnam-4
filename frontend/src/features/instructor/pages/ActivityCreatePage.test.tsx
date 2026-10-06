import { screen, within } from "@testing-library/react"
import { http, HttpResponse } from "msw"
import { beforeEach, describe, expect, it } from "vitest"
import { emptyQuizPool, fixtures, seedActivity } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { renderRoutes, signIn } from "@/test/render"
import { ActivityCreatePage } from "./ActivityCreatePage"
import { ChildDetailPage } from "./ChildDetailPage"

// 활동 만들기 마법사(ADR 2026-10-03 D1~D4). 대상 아동 한 명 → 학습 목표 → 확인 후 배정. 강사는 퀴즈 문항을 고르지 않는다.
// 응답을 못 받아 다시 눌러도 활동·목표가 하나만 남는지(같은 요청 키·저장한 목표 재사용)도 본다.

const A1 = fixtures.classroomA1.classId
const CHILD = fixtures.childA1_1

function renderWizard(search = "") {
  return renderRoutes(
    [
      { path: "/activities/new", element: <ActivityCreatePage /> },
      { path: "/classrooms/:classId/children/:childId", element: <ChildDetailPage /> },
      { path: "/classrooms", element: <p>학급 목록 화면</p> },
    ],
    `/activities/new${search}`,
  )
}

describe("활동 만들기 화면", () => {
  beforeEach(() => signIn())

  it("아동과 목표를 고르고 배정하면, 그 아동의 활동 이력에 시작 전 활동으로 보인다", async () => {
    const { user, router } = renderWizard()

    expect(await screen.findByRole("heading", { name: "활동 만들기 — 대상 선택" })).toBeInTheDocument()
    await user.selectOptions(await screen.findByLabelText("학급"), A1)
    const targets = await screen.findByRole("group", { name: "아동" })
    // 동명이인은 내부 childId 로 구분한다. 첫 번째 김하늘을 고른다.
    await user.click(within(targets).getAllByRole("radio", { name: "김하늘" })[0])
    await user.click(screen.getByRole("button", { name: "다음 · 학습 목표 설정" }))

    expect(await screen.findByRole("heading", { name: "학습 목표 설정" })).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "공감 표현" }))
    await user.click(screen.getByRole("radio", { name: /친구가 속상할 때 위로하는 말을 한다/ }))
    await user.click(screen.getByRole("button", { name: "다음 · 확인" }))

    const summary = await screen.findByRole("region", { name: "배정 내용" })
    expect(within(summary).getByText("김하늘")).toBeInTheDocument()
    expect(within(summary).getByText("친구가 속상할 때 위로하는 말을 한다")).toBeInTheDocument()
    expect(within(summary).getByText(/최대 3문항이 자동으로 준비돼요/)).toBeInTheDocument()
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument()

    await user.click(screen.getByRole("button", { name: "배정하기" }))

    expect(await screen.findByRole("heading", { name: "아동 상세 · 김하늘 (햇살반)" })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe(`/classrooms/${A1}/children/${CHILD.childId}`)
    expect(await screen.findByRole("link", { name: "친구가 속상할 때 위로하는 말을 한다" })).toBeInTheDocument()
    expect(screen.getByText("시작 전")).toBeInTheDocument()
  })

  it("아동 상세에서 열면 그 아동이 골라져 있고, 목표를 직접 입력할 수 있다", async () => {
    const { user } = renderWizard(`?classId=${A1}&childId=${CHILD.childId}`)

    // 학급의 아동 목록을 받으면 주소의 아동이 골라져 있다.
    expect(await screen.findByRole("radio", { name: "김하늘", checked: true })).toBeInTheDocument()
    const next = screen.getByRole("button", { name: "다음 · 학습 목표 설정" })
    expect(next).toBeEnabled()
    await user.click(next)
    await user.click(await screen.findByRole("tab", { name: "직접 입력" }))
    const confirm = screen.getByRole("button", { name: "다음 · 확인" })
    expect(confirm).toBeDisabled()
    await user.type(screen.getByLabelText("학습 목표"), "  친구에게 먼저 인사한다  ")
    await user.selectOptions(screen.getByLabelText("분류 (선택)"), "SITUATION_COPING")
    await user.click(confirm)

    const summary = await screen.findByRole("region", { name: "배정 내용" })
    expect(within(summary).getByText("친구에게 먼저 인사한다")).toBeInTheDocument()
    expect(within(summary).getByText("· 상황 대처")).toBeInTheDocument()
  })

  it("새로고침으로 목표 입력이 사라지면 확인 단계 대신 목표 단계를 연다", async () => {
    renderWizard(`?step=confirm&classId=${A1}&childId=${CHILD.childId}`)

    expect(await screen.findByRole("heading", { name: "학습 목표 설정" })).toBeInTheDocument()
  })

  it("같은 목표로 아직 시작하지 않은 활동이 있으면 다시 배정하지 않는다", async () => {
    seedActivity({
      childId: CHILD.childId,
      goalTitle: "표정에서 기쁨·슬픔·화남을 구분한다",
      situationType: "EMOTION_RECOGNITION",
    })
    const { user } = renderWizard(`?classId=${A1}&childId=${CHILD.childId}`)

    await goToConfirm(user)
    await user.click(screen.getByRole("button", { name: "배정하기" }))

    expect(await screen.findByRole("alert")).toHaveTextContent("같은 목표로 아직 시작하지 않은 활동이 있어요")
  })

  it("승인 문항이 없으면 배정하지 않고 이유를 알려 준다", async () => {
    emptyQuizPool()
    const { user } = renderWizard(`?classId=${A1}&childId=${CHILD.childId}`)

    await goToConfirm(user)
    await user.click(screen.getByRole("button", { name: "배정하기" }))

    expect(await screen.findByRole("alert")).toHaveTextContent("배정할 수 있는 승인된 퀴즈 문항이 없어요")
  })

  it("배정 응답을 못 받아 다시 눌러도 목표와 활동은 하나만 생긴다", async () => {
    const keys: string[] = []
    server.events.on("request:start", ({ request }) => {
      if (request.method === "POST" && new URL(request.url).pathname === "/api/v1/activities") {
        keys.push(request.headers.get("Idempotency-Key") ?? "")
      }
    })
    // 첫 배정 요청은 서버에 닿지 못한 것처럼 실패시킨다.
    server.use(http.post("*/api/v1/activities", () => HttpResponse.error(), { once: true }))
    let goalPosts = 0
    server.events.on("request:start", ({ request }) => {
      if (request.method === "POST" && new URL(request.url).pathname.endsWith("/learning-goals")) goalPosts++
    })
    const { user, router } = renderWizard(`?classId=${A1}&childId=${CHILD.childId}`)

    await goToConfirm(user)
    await user.click(screen.getByRole("button", { name: "배정하기" }))
    expect(await screen.findByRole("alert")).toHaveTextContent("서버에 연결할 수 없습니다")
    await user.click(screen.getByRole("button", { name: "배정하기" }))

    await screen.findByRole("heading", { name: "아동 상세 · 김하늘 (햇살반)" })
    expect(router.state.location.pathname).toBe(`/classrooms/${A1}/children/${CHILD.childId}`)
    expect(goalPosts).toBe(1)
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBe(keys[1])
    server.events.removeAllListeners()
  })

  it("분류 칩을 바꾸면 가려진 목표 선택을 비운다", async () => {
    const { user } = renderWizard(`?classId=${A1}&childId=${CHILD.childId}`)

    await screen.findByRole("radio", { name: "김하늘", checked: true })
    await user.click(screen.getByRole("button", { name: "다음 · 학습 목표 설정" }))
    await user.click(await screen.findByRole("button", { name: "공감 표현" }))
    await user.click(screen.getByRole("radio", { name: /친구가 속상할 때 위로하는 말을 한다/ }))
    const confirm = screen.getByRole("button", { name: "다음 · 확인" })
    expect(confirm).toBeEnabled()

    await user.click(screen.getByRole("button", { name: "감정 인식" }))
    expect(confirm).toBeDisabled()
    // 원래 분류로 돌아가도 선택은 남아 있지 않다.
    await user.click(screen.getByRole("button", { name: "공감 표현" }))
    expect(screen.queryByRole("radio", { checked: true })).not.toBeInTheDocument()
    expect(confirm).toBeDisabled()
  })

  it("배정에 실패한 뒤 이전으로 갔다 돌아오면 예전 오류를 보이지 않는다", async () => {
    seedActivity({
      childId: CHILD.childId,
      goalTitle: "표정에서 기쁨·슬픔·화남을 구분한다",
      situationType: "EMOTION_RECOGNITION",
    })
    const { user } = renderWizard(`?classId=${A1}&childId=${CHILD.childId}`)

    await goToConfirm(user)
    await user.click(screen.getByRole("button", { name: "배정하기" }))
    expect(await screen.findByRole("alert")).toHaveTextContent("같은 목표로 아직 시작하지 않은 활동이 있어요")

    await user.click(screen.getByRole("button", { name: "이전" }))
    await user.click(await screen.findByRole("button", { name: "공감 표현" }))
    await user.click(screen.getByRole("radio", { name: /친구가 속상할 때 위로하는 말을 한다/ }))
    await user.click(screen.getByRole("button", { name: "다음 · 확인" }))

    await screen.findByRole("region", { name: "배정 내용" })
    expect(screen.queryByRole("alert")).not.toBeInTheDocument()
  })
})

async function goToConfirm(user: ReturnType<typeof renderWizard>["user"]) {
  await screen.findByRole("radio", { name: "김하늘", checked: true })
  await user.click(screen.getByRole("button", { name: "다음 · 학습 목표 설정" }))
  await user.click(await screen.findByRole("radio", { name: /표정에서 기쁨·슬픔·화남을 구분한다/ }))
  await user.click(screen.getByRole("button", { name: "다음 · 확인" }))
  await screen.findByRole("region", { name: "배정 내용" })
}
