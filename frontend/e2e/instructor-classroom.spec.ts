import { expect, test } from "@playwright/test"
import { marker, signUp } from "./support/instructor.js"

// VS-002 종단 흐름. 실제 백엔드(local)·PostgreSQL·프론트 빌드로 가입 → 학급 생성 → 동명이인 등록 → 새로고침까지 간다.
// 이름에는 이번 실행의 표시(marker)를 붙인다. scripts/e2e.sh scan 이 끝난 뒤 백엔드·DB 로그에서 이 문자열을 찾는다.
// 백엔드 TestFixtures·프론트 MSW 픽스처와 같은 이름을 쓴다. 실제 아동 이름은 쓰지 않는다.
const classroomName = `햇살반 ${marker}`
const namesake = `김하늘 ${marker}`

test("강사가 가입해 학급을 만들고 동명이인 두 명을 등록하면 한 번씩만 저장되고 새로고침 뒤에도 그대로 보인다", async ({ page }) => {
  // 생성 요청(POST)을 센다. 버튼은 전부 dblclick 으로 누르는데, 요청은 누를 때마다 한 번만 나가야 한다
  // (중복 제출 방지 — PR #16 멘토 리뷰, #17). 두 번째 요청이 나가면 같은 학급·아동이 두 번 저장된다.
  const posts = { classrooms: 0, children: 0 }
  page.on("request", (request) => {
    if (request.method() !== "POST") return
    const { pathname } = new URL(request.url())
    if (pathname === "/api/v1/classrooms") posts.classrooms += 1
    if (pathname.endsWith("/children")) posts.children += 1
  })

  await signUp(page)
  await page.getByRole("link", { name: "학급 생성" }).click()
  // 목록 화면에도 "학급명 검색" 칸이 있다. 생성 화면으로 바뀐 뒤에 입력해야 검색칸에 들어가지 않는다.
  await expect(page).toHaveURL(/\/classrooms\/new$/)
  await page.getByLabel(/학급명/).fill(classroomName)
  await page.getByRole("button", { name: "저장" }).dblclick()

  const heading = page.getByRole("heading", { name: `학급 상세 · ${classroomName}` })
  await expect(heading).toBeVisible()
  await page.getByRole("tab", { name: /아동 목록/ }).click()

  const nameInput = page.getByLabel("아동 이름")
  const register = page.getByRole("button", { name: "아동 등록" })
  const namesakes = page.getByRole("cell", { name: namesake, exact: true })

  await nameInput.fill(namesake)
  await register.dblclick()
  // 등록이 성공하면(onSuccess) 입력칸을 비운다. 첫 항목이 목록에 뜬 뒤에 다음 이름을 입력해야
  // 뒤늦게 비워지는 입력칸 때문에 두 번째 이름이 사라지지 않는다.
  await expect(namesakes).toHaveCount(1)

  await nameInput.fill(namesake)
  await register.dblclick()
  await expect(namesakes).toHaveCount(2)

  await page.reload()
  await expect(heading).toBeVisible()
  await expect(namesakes).toHaveCount(2)

  await page.getByRole("link", { name: "← 학급 목록" }).click()
  await expect(page.getByRole("cell", { name: classroomName, exact: true })).toHaveCount(1)
  expect(posts).toEqual({ classrooms: 1, children: 2 })
})
