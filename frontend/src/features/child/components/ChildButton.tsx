import * as React from "react"
import { cn } from "@/lib/utils"

const CHILD_BUTTON_VARIANTS = {
  primary:
    "bg-[var(--child-primary)] text-[var(--child-primary-foreground)] hover:brightness-105",
  outline:
    "bg-[var(--child-surface)] text-[var(--child-primary)] border-2 border-[var(--child-primary)] hover:bg-[var(--child-surface-muted)]",
} as const

type ChildButtonVariant = keyof typeof CHILD_BUTTON_VARIANTS

function ChildButton({
  className,
  variant = "primary",
  ...props
}: React.ComponentProps<"button"> & { variant?: ChildButtonVariant }) {
  return (
    <button
      data-slot="child-button"
      data-variant={variant}
      className={cn(
        "inline-flex h-14 min-w-40 shrink-0 items-center justify-center gap-2 rounded-[var(--child-radius-pill)] px-6 text-lg font-medium transition-colors outline-none select-none focus-visible:ring-3 focus-visible:ring-[var(--child-primary)]/50 disabled:pointer-events-none disabled:opacity-50",
        CHILD_BUTTON_VARIANTS[variant],
        className
      )}
      {...props}
    />
  )
}

export { ChildButton }
