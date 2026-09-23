import * as React from "react"
import { cn } from "@/lib/utils"

const STATE_TONE_STYLES = {
  neutral: "bg-[var(--child-surface-muted)] text-[var(--child-text-muted)]",
  success: "bg-[var(--child-success-bg)] text-[var(--child-success-fg)]",
  warning: "bg-[var(--child-warning-bg)] text-[var(--child-warning-fg)]",
  danger: "bg-[var(--child-danger-bg)] text-[var(--child-danger-fg)]",
} as const

type StateTone = keyof typeof STATE_TONE_STYLES

/**
 * 아동 화면 상태 오버레이(로딩/오류/권한/만료/네트워크)가 공유하는 뼈대.
 * wireframe README의 "상태 화면·오버레이" 목록에 대응한다.
 */
function StateScreen({
  icon,
  tone = "neutral",
  title,
  description,
  action,
  className,
}: {
  icon: React.ReactNode
  tone?: StateTone
  title: string
  description?: string
  action?: React.ReactNode
  className?: string
}) {
  return (
    <div
      role="status"
      className={cn(
        "flex flex-col items-center gap-4 rounded-[var(--child-radius-card)] bg-[var(--child-surface)] px-8 py-12 text-center",
        className
      )}
    >
      <div
        className={cn(
          "flex size-16 items-center justify-center rounded-full [&_svg]:size-8",
          STATE_TONE_STYLES[tone]
        )}
      >
        {icon}
      </div>
      <div className="flex flex-col gap-2">
        <p className="font-[var(--child-font-display)] text-xl font-semibold text-[var(--child-text)]">
          {title}
        </p>
        {description ? (
          <p className="text-lg text-[var(--child-text-muted)]">{description}</p>
        ) : null}
      </div>
      {action}
    </div>
  )
}

export { StateScreen }
export type { StateTone }
