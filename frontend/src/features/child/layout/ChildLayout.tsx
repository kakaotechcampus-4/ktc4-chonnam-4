import * as React from "react"
import { useNavigate } from "react-router-dom"
import { LogOut } from "lucide-react"
import { cn } from "@/lib/utils"
import { Character } from "../components/Character"
import { useChildSessionStore } from "../store/childSessionStore"
import { useWarnBeforeUnload } from "../hooks/useWarnBeforeUnload"

/**
 * 아동 코드·퀴즈·역할극 화면이 공유하는 레이아웃 셸.
 * Figma에는 "Child Header" 컴포넌트 시안이 아직 없어(로우파이 단계),
 * wireframe 화면들이 쓰던 로고 배지 + 현재 활동/나가기 배치를 임시로 구현했다.
 * 최종 시안이 나오면 이 파일만 교체하면 되도록 children 쪽에는 레이아웃 가정을 두지 않는다.
 */
function ChildLayout({
  activityTitle,
  stepLabel,
  headerCenter,
  backgroundImage,
  dimBackground = false,
  wide = false,
  children,
}: {
  activityTitle?: string
  stepLabel?: string
  /** 헤더 가운데에 activityTitle 대신 넣을 요소 (예: 내 활동의 "서연이의 활동" 제목) */
  headerCenter?: React.ReactNode
  /** 화면 전체 배경 이미지 URL (예: 내 활동 열기구 배경) */
  backgroundImage?: string
  /** 배경 그림 위에 흰 막(40%)을 덮어 흐리게 한다. 놀이공원 배경 시안(Figma 460:191) 값이다. */
  dimBackground?: boolean
  /** 본문 폭 제한(768px)을 풀어 화면 전체를 쓴다 (예: 열기구를 화면 끝까지 펼치는 내 활동) */
  wide?: boolean
  children: React.ReactNode
}) {
  const navigate = useNavigate()
  const endSession = useChildSessionStore((state) => state.endSession)
  useWarnBeforeUnload()

  const handleExit = () => {
    endSession()
    navigate("/child", { replace: true })
  }

  return (
    <div
      className="child-scope flex min-h-svh flex-col"
      // .child-scope의 background가 Tailwind 클래스보다 우선하므로 인라인 스타일로 덮는다.
      style={
        backgroundImage
          ? {
              // Vite가 작은 SVG를 data URI로 인라인하므로 따옴표로 감싸야 url()이 깨지지 않는다.
              backgroundImage: dimBackground
                ? `linear-gradient(rgb(255 255 255 / 0.4), rgb(255 255 255 / 0.4)), url("${backgroundImage}")`
                : `url("${backgroundImage}")`,
              backgroundSize: "cover",
              backgroundPosition: "center bottom",
            }
          : undefined
      }
    >
      <header className="flex flex-wrap items-center justify-between gap-4 px-6 py-4 sm:flex-nowrap sm:px-10">
        <div
          className={cn(
            "flex items-center gap-2 rounded-[var(--child-radius-pill)] bg-[var(--child-surface)] px-4 py-2 shadow-sm"
          )}
        >
          <Character name="turtle" size="xs" className="-my-1" />
          <span className="text-sm font-extrabold text-[var(--child-primary)]">
            느링고
          </span>
        </div>

        {headerCenter ? (
          // 좁은 화면에서는 로고·사용 종료 아래 줄로 내려 제목이 잘리지 않게 한다.
          <div className="order-last flex basis-full justify-center sm:order-none sm:min-w-0 sm:flex-1 sm:basis-auto">
            {headerCenter}
          </div>
        ) : activityTitle ? (
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

      <main
        className={cn(
          "mx-auto flex w-full flex-1 flex-col px-6 pb-10 sm:px-10",
          !wide && "max-w-3xl"
        )}
      >
        {children}
      </main>
    </div>
  )
}

export { ChildLayout }
