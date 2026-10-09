import * as React from "react"
import { Dialog as DialogPrimitive } from "radix-ui"
import { cn } from "@/lib/utils"
import { StateDialogContext } from "./stateDialogContext"

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
  const inDialog = React.useContext(StateDialogContext)

  const titleNode = (
    <p className="font-child-display font-extrabold text-xl text-[var(--child-text)]">
      {title}
    </p>
  )
  const descriptionNode = description ? (
    <p className="text-lg text-[var(--child-text-muted)]">{description}</p>
  ) : null

  return (
    <div
      // 모달 안에서는 dialog 역할이 이미 있으므로 status 역할을 겹쳐 두지 않는다.
      role={inDialog ? undefined : "status"}
      className={cn(
        // break-keep: 한글이 단어 중간("있/어요")에서 줄바꿈되지 않게 한다.
        "flex flex-col items-center gap-4 rounded-[var(--child-radius-card)] bg-[var(--child-surface)] px-8 py-12 text-center break-keep",
        inDialog && "shadow-xl",
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
        {inDialog ? <DialogPrimitive.Title asChild>{titleNode}</DialogPrimitive.Title> : titleNode}
        {inDialog && descriptionNode ? (
          <DialogPrimitive.Description asChild>{descriptionNode}</DialogPrimitive.Description>
        ) : (
          descriptionNode
        )}
      </div>
      {action}
    </div>
  )
}

export { StateScreen }
export type { StateTone }
