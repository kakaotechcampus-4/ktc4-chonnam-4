import * as React from "react"

/**
 * 학급·아동 관리 화면이 공유하는 레이아웃 셸.
 * features/child 의 ChildLayout 과 같은 규칙을 따른다 — 라우터는 평평하게 두고
 * 각 페이지가 이 셸을 직접 감싸므로, 라우트 구조와 레이아웃이 서로 얽히지 않는다.
 * 강사용 화면 시안이 나오면 이 파일만 교체한다.
 */
function InstructorLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-svh flex-col">
      <header className="border-border border-b px-6 py-4">
        <span className="text-sm font-semibold">느링고 강사용</span>
      </header>

      <main className="mx-auto w-full max-w-3xl flex-1 px-6 py-6">
        {children}
      </main>
    </div>
  )
}

export { InstructorLayout }
