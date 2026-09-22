import * as React from "react"
import { useNavigate } from "react-router-dom"
import { LogOut } from "lucide-react"
import { cn } from "@/lib/utils"
import { useChildSessionStore } from "../store/childSessionStore"

/**
 * 아동 코드·퀴즈·역할극 화면이 공유하는 레이아웃 셸.
 * Figma에는 "Child Header" 컴포넌트 시안이 아직 없어(로우파이 단계),
 * wireframe 화면들이 쓰던 로고 배지 + 현재 활동/나가기 배치를 임시로 구현했다.
 * 최종 시안이 나오면 이 파일만 교체하면 되도록 children 쪽에는 레이아웃 가정을 두지 않는다.
 */
function ChildLayout({
  activityTitle,
  stepLabel,
  children,
}: {
  activityTitle?: string
  stepLabel?: string
  children: React.ReactNode
}) {
  const navigate = useNavigate()
  const endSession = useChildSessionStore((state) => state.endSession)

  const handleExit = () => {
    endSession()
    navigate("/child", { replace: true })
  }

  return (
    <div className="child-scope flex min-h-svh flex-col">
      <header className="flex items-center justify-between gap-4 px-6 py-4 sm:px-10">
        <div
          className={cn(
            "flex items-center gap-2 rounded-[var(--child-radius-pill)] bg-[var(--child-surface)] px-4 py-2 shadow-sm"
          )}
        >
          <span className="text-sm font-extrabold text-[var(--child-primary)]">
            느링고
          </span>
        </div>

        {activityTitle ? (
          <div className="flex min-w-0 flex-1 flex-col items-center text-center">
            <p className="truncate text-lg font-semibold text-[var(--child-text)]">
              {activityTitle}
            </p>
            {stepLabel ? (
              <p className="text-sm text-[var(--child-text-muted)]">{stepLabel}</p>
            ) : null}
          </div>
        ) : (
          <div className="flex-1" />
        )}

        <button
          type="button"
          onClick={handleExit}
          className="flex items-center gap-1.5 rounded-[var(--child-radius-pill)] bg-[var(--child-surface)] px-4 py-2 text-sm font-medium text-[var(--child-text-muted)] shadow-sm hover:text-[var(--child-danger-fg)]"
        >
          <LogOut className="size-4" />
          사용 종료
        </button>
      </header>

      <main className="mx-auto flex w-full max-w-3xl flex-1 flex-col px-6 pb-10 sm:px-10">
        {children}
      </main>
    </div>
  )
}

export { ChildLayout }
