import { act, screen, within } from "@testing-library/react"
import { http } from "msw"
import { afterEach, describe, expect, it, beforeEach } from "vitest"
import { apiError, fixtures, seedActivity } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { getChild, listChildren } from "../api"
import { renderRoutes, signIn } from "@/test/render"
import { ChildDetailPage } from "./ChildDetailPage"

// 아동 상세의 활동 이력 탭(ADR 2026-10-03 D5). 배정한 활동이 목표 제목·상태와 함께 보이고, 누르면 활동 리포트로 간다.
// 다른 강사의 아동은 없는 아동처럼 보인다(VS-001 "권한 밖 … 존재 여부가 드러나지 않도록").

const A1 = fixtures.classroomA1.classId
const CHILD = fixtures.childA1_1

function renderChildDetail(classId = A1, childId = CHILD.childId) {
  return renderRoutes(
    [
      { path: "/classrooms/:classId/children/:childId", element: <ChildDetailPage /> },
      { path: "/activities/:activityId/report", element: <p>활동 리포트 화면</p> },
      { path: "/activities/new", element: <p>활동 만들기 화면</p> },
      { path: "/classrooms/:classId", element: <p>학급 상세 화면</p> },
    ],
    `/classrooms/${classId}/children/${childId}`,
  )
}

describe("아동 상세 화면", () => {
  beforeEach(() => signIn())

  it("배정한 활동을 목표 제목·상태와 함께 최신순으로 보여 준다", async () => {
    seedActivity({ childId: CHILD.childId, goalTitle: "표정에서 기쁨·슬픔·화남을 구분한다", status: "COMPLETED" })
    seedActivity({ childId: CHILD.childId, goalTitle: "친구가 속상할 때 위로하는 말을 한다" })

    renderChildDetail()

    expect(await screen.findByRole("heading", { name: "아동 상세 · 김하늘 (햇살반)" })).toBeInTheDocument()
    const table = await screen.findByRole("table")
    const [, body] = within(table).getAllByRole("rowgroup")
    const rows = within(body).getAllByRole("row")
    expect(rows.map((row) => within(row).getAllByRole("cell")[0].textContent)).toEqual([
      "친구가 속상할 때 위로하는 말을 한다",
      "표정에서 기쁨·슬픔·화남을 구분한다",
    ])
    expect(within(rows[0]).getByText("시작 전")).toBeInTheDocument()
    expect(within(rows[1]).getByText("완료")).toBeInTheDocument()
  })

  it("처음에는 활동 이력 탭이 열리고, 개요 탭은 준비 중으로 보인다", async () => {
    renderChildDetail()

    const tabs = await screen.findByRole("tablist", { name: "아동 상세" })
    expect(within(tabs).getByRole("tab", { name: "활동 이력" })).toHaveAttribute("aria-selected", "true")
    expect(within(tabs).getByRole("tab", { name: "접근 코드" })).toHaveAttribute("aria-selected", "false")
    expect(within(tabs).getByRole("tab", { name: "개요 · 학습 상태" })).toHaveAttribute("aria-disabled", "true")
  })

  it("활동을 누르면 그 활동의 리포트로 간다", async () => {
    const activity = seedActivity({ childId: CHILD.childId, goalTitle: "도움이 필요할 때 말로 요청한다" })
    const { user, router } = renderChildDetail()

    await user.click(await screen.findByRole("link", { name: "도움이 필요할 때 말로 요청한다" }))

    expect(router.state.location.pathname).toBe(`/activities/${activity.activityId}/report`)
  })

  it("활동이 없으면 안내하고, '활동 만들기' 는 이 아동을 골라 둔 채 연다", async () => {
    const { user, router } = renderChildDetail()

    expect(await screen.findByText("아직 배정된 활동이 없습니다.")).toBeInTheDocument()
    // 사이드바에도 같은 이름의 메뉴가 있다. 헤더의 버튼을 누른다.
    await user.click(within(screen.getByRole("banner")).getByRole("link", { name: "활동 만들기" }))
    expect(router.state.location.pathname).toBe("/activities/new")
    expect(router.state.location.search).toBe(`?classId=${A1}&childId=${CHILD.childId}`)
  })

  it("다른 강사의 아동은 없는 아동처럼 보이고 이름이 드러나지 않는다", async () => {
    const other = fixtures.childC1_1
    renderChildDetail(fixtures.classroomC1.classId, other.childId)

    expect(await screen.findByText("아동을 찾을 수 없습니다.")).toBeInTheDocument()
    expect(screen.queryByText(other.displayName, { exact: false })).not.toBeInTheDocument()
    expect(within(screen.getByRole("banner")).queryByRole("link", { name: "활동 만들기" })).not.toBeInTheDocument()
  })

  it("주소의 학급과 아동의 학급이 다르면 잘못된 주소로 본다", async () => {
    renderChildDetail(fixtures.classroomB1.classId, CHILD.childId)

    expect(await screen.findByText("아동을 찾을 수 없습니다.")).toBeInTheDocument()
    expect(screen.queryByRole("table")).not.toBeInTheDocument()
  })

  // 아동 삭제(ADR 2026-10-04). 기록이 있어도 지우고, 확인 창에서 함께 지워지는 활동 수를 알린다.
  it("아동을 삭제하면 확인 창에서 활동 수를 알리고, 삭제 뒤 아동 목록으로 돌아간다", async () => {
    seedActivity({ childId: CHILD.childId, goalTitle: "표정에서 기쁨·슬픔·화남을 구분한다", status: "COMPLETED" })
    seedActivity({ childId: CHILD.childId, goalTitle: "친구가 속상할 때 위로하는 말을 한다" })
    const { user, router } = renderChildDetail()

    await user.click(await screen.findByRole("button", { name: "아동 삭제" }))
    const dialog = await screen.findByRole("alertdialog", { name: "김하늘 아동을 삭제할까요?" })
    expect(within(dialog).getByText(/활동 2개\(완료 1, 진행 중 0\)/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole("button", { name: "영구 삭제" }))

    await screen.findByText("학급 상세 화면")
    expect(router.state.location.pathname).toBe(`/classrooms/${A1}`)
    expect(router.state.location.search).toBe("?tab=children")
    expect((await listChildren(A1)).map((child) => child.childId)).not.toContain(CHILD.childId)
  })

  // 활동 목록은 한 페이지에 최대 100개다. 확인 창이 첫 페이지만 세면 실제로 지워지는 수보다 적게 알린다.
  it("활동이 100개를 넘어도 확인 창이 전체 활동 수와 완료·진행 중 수를 알린다", async () => {
    for (let i = 0; i < 3; i++) seedActivity({ childId: CHILD.childId, goalTitle: `완료 목표 ${i}`, status: "COMPLETED" })
    for (let i = 0; i < 2; i++) seedActivity({ childId: CHILD.childId, goalTitle: `진행 목표 ${i}`, status: "IN_PROGRESS" })
    for (let i = 0; i < 100; i++) seedActivity({ childId: CHILD.childId, goalTitle: `시작 전 목표 ${i}` })
    const { user } = renderChildDetail()

    await user.click(await screen.findByRole("button", { name: "아동 삭제" }))
    const dialog = await screen.findByRole("alertdialog", { name: "김하늘 아동을 삭제할까요?" })
    expect(within(dialog).getByText(/활동 105개\(완료 3, 진행 중 2\)/)).toBeInTheDocument()
  })

  it("취소하면 아동이 그대로 남는다", async () => {
    const { user } = renderChildDetail()

    await user.click(await screen.findByRole("button", { name: "아동 삭제" }))
    const dialog = await screen.findByRole("alertdialog")
    await user.click(within(dialog).getByRole("button", { name: "취소" }))

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument()
    expect((await getChild(CHILD.childId)).childId).toBe(CHILD.childId)
  })

  // 입장 코드 받기(멘토 리뷰: 강사가 아이에게 줄 코드를 화면에서 받을 방법이 없다).
  // 서버가 원문을 저장하지 않아, 받은 직후에만 코드가 보인다.
  describe("접근 코드 탭", () => {
    afterEach(() => server.events.removeAllListeners())

    function recordIssueKeys() {
      const keys: string[] = []
      server.events.on("request:start", ({ request }) => {
        if (request.method === "POST" && request.url.endsWith("/access-codes")) {
          keys.push(request.headers.get("Idempotency-Key") ?? "")
        }
      })
      return keys
    }

    it("코드 받기를 누르면 숫자 4자리와 만료 시각이 보이고, 탭을 오가도 코드가 남는다", async () => {
      const { user, router } = renderChildDetail()

      await user.click(await screen.findByRole("tab", { name: "접근 코드" }))
      expect(router.state.location.search).toBe("?tab=access-code")
      const panel = screen.getByRole("tabpanel", { name: "접근 코드" })
      expect(within(panel).getByRole("button", { name: "복사" })).toBeDisabled()

      await user.click(within(panel).getByRole("button", { name: "코드 받기" }))

      const code = await within(panel).findByText(/^\d{4}$/)
      expect(within(panel).getByText(/까지 쓸 수 있어요$/)).toBeInTheDocument()
      await user.click(screen.getByRole("tab", { name: "활동 이력" }))
      await user.click(screen.getByRole("tab", { name: "접근 코드" }))
      expect(within(panel).getByText(code.textContent!)).toBeInTheDocument()
    })

    it("실패해서 다시 누르면 같은 요청 키로, 받은 뒤 다시 받으면 새 요청 키로 보낸다", async () => {
      server.use(
        http.post(
          "*/api/v1/children/:childId/access-codes",
          ({ request }) => apiError(500, "INTERNAL_ERROR", "서버 오류", new URL(request.url).pathname),
          { once: true },
        ),
      )
      const keys = recordIssueKeys()
      const { user } = renderChildDetail()

      await user.click(await screen.findByRole("tab", { name: "접근 코드" }))
      const panel = screen.getByRole("tabpanel", { name: "접근 코드" })
      await user.click(within(panel).getByRole("button", { name: "코드 받기" }))
      expect(await within(panel).findByRole("alert")).toBeInTheDocument()

      await user.click(within(panel).getByRole("button", { name: "코드 받기" }))
      await within(panel).findByText(/^\d{4}$/)
      await user.click(within(panel).getByRole("button", { name: "코드 다시 받기" }))
      await within(panel).findByRole("button", { name: "코드 다시 받기" })

      expect(keys).toHaveLength(3)
      expect(keys[1]).toBe(keys[0])
      expect(keys[2]).not.toBe(keys[1])
    })

    it("코드를 받으면 복사할 수 있다", async () => {
      const { user } = renderChildDetail()

      await user.click(await screen.findByRole("tab", { name: "접근 코드" }))
      const panel = screen.getByRole("tabpanel", { name: "접근 코드" })
      await user.click(within(panel).getByRole("button", { name: "코드 받기" }))
      const code = await within(panel).findByText(/^\d{4}$/)
      await user.click(within(panel).getByRole("button", { name: "복사" }))

      expect(await within(panel).findByRole("button", { name: "복사했어요" })).toBeInTheDocument()
      expect(await navigator.clipboard.readText()).toBe(code.textContent)
    })

    // 목록을 거치지 않고 다른 아동 상세로 바로 넘어가면(그 아동을 이미 불러온 적이 있으면 로딩 화면도 없이) 같은 화면이 남는다.
    // 이때 앞 아동의 코드가 보이면 강사가 그 코드를 다른 아이에게 줄 수 있다.
    async function switchToChild(
      router: ReturnType<typeof renderChildDetail>["router"],
      queryClient: ReturnType<typeof renderChildDetail>["queryClient"],
      childId: string,
    ) {
      queryClient.setQueryData(["children", childId], await getChild(childId))
      await act(() => router.navigate(`/classrooms/${A1}/children/${childId}?tab=access-code`))
    }

    it("다른 아동으로 바로 넘어가면 앞 아동의 코드와 복사 상태가 남지 않는다", async () => {
      const { user, router, queryClient } = renderChildDetail()

      await user.click(await screen.findByRole("tab", { name: "접근 코드" }))
      const panel = screen.getByRole("tabpanel", { name: "접근 코드" })
      await user.click(within(panel).getByRole("button", { name: "코드 받기" }))
      const code = (await within(panel).findByText(/^\d{4}$/)).textContent!
      await user.click(within(panel).getByRole("button", { name: "복사" }))
      await within(panel).findByRole("button", { name: "복사했어요" })

      await switchToChild(router, queryClient, fixtures.childA1_2.childId)

      const next = screen.getByRole("tabpanel", { name: "접근 코드" })
      expect(within(next).queryByText(code)).not.toBeInTheDocument()
      expect(within(next).getByRole("button", { name: "코드 받기" })).toBeInTheDocument()
      expect(within(next).getByRole("button", { name: "복사" })).toBeDisabled()
    })

    it("받기에 실패한 뒤 다른 아동으로 넘어가면 앞 아동의 요청 키를 다시 쓰지 않는다", async () => {
      server.use(
        http.post(
          "*/api/v1/children/:childId/access-codes",
          ({ request }) => apiError(500, "INTERNAL_ERROR", "서버 오류", new URL(request.url).pathname),
          { once: true },
        ),
      )
      const keys = recordIssueKeys()
      const { user, router, queryClient } = renderChildDetail()

      await user.click(await screen.findByRole("tab", { name: "접근 코드" }))
      const panel = screen.getByRole("tabpanel", { name: "접근 코드" })
      await user.click(within(panel).getByRole("button", { name: "코드 받기" }))
      expect(await within(panel).findByRole("alert")).toBeInTheDocument()

      await switchToChild(router, queryClient, fixtures.childA1_2.childId)
      const next = screen.getByRole("tabpanel", { name: "접근 코드" })
      expect(within(next).queryByRole("alert")).not.toBeInTheDocument()
      await user.click(within(next).getByRole("button", { name: "코드 받기" }))
      await within(next).findByText(/^\d{4}$/)

      expect(keys).toHaveLength(2)
      expect(keys[1]).not.toBe(keys[0])
    })
  })
})
