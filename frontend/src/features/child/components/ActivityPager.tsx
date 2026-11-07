import { ChevronLeft, ChevronRight } from "lucide-react"
import { cn } from "@/lib/utils"

// Figma "pagination-container"(669:397) 값. 아동 토큰에 같은 색이 없어 시안 값을 그대로 쓴다.
const PAGES_PER_GROUP = 3

const ARROW_BUTTON =
  "flex size-10 shrink-0 items-center justify-center rounded-[20px] border border-[#e8e8e8] bg-[var(--child-surface)] text-[#333333] outline-none transition-transform select-none enabled:active:scale-95 focus-visible:ring-3 focus-visible:ring-[var(--child-primary)]/50 disabled:opacity-40"

/**
 * 내 활동(C-HOME-01) 쪽 넘김. 열기구가 한 쪽에 3개라 활동이 더 많으면 쪽을 나눠 보여 준다.
 * 화살표는 lucide 의 chevron 이 시안(20×20, 선 2px)과 같은 모양이라 그대로 쓴다.
 */
function ActivityPager({
  page,
  pageCount,
  onChange,
  className,
}: {
  /** 1부터 센다 */
  page: number
  pageCount: number
  onChange: (page: number) => void
  className?: string
}) {
  // 쪽 번호는 3개씩 묶어 보여 준다(1~3, 4~6 …). 화살표는 한 쪽씩 넘기고, 묶음을 넘어가면 번호가 다음 묶음으로 바뀐다.
  const groupStart = Math.floor((page - 1) / PAGES_PER_GROUP) * PAGES_PER_GROUP + 1
  const pages = Array.from(
    { length: Math.min(PAGES_PER_GROUP, pageCount - groupStart + 1) },
    (_, index) => groupStart + index
  )
  // 쪽이 3개보다 많으면 마지막 묶음이 덜 차도 빈자리를 둬서, 쪽을 넘길 때 버튼 폭과 화살표 위치가 그대로이게 한다.
  const emptySlots = pageCount > PAGES_PER_GROUP ? PAGES_PER_GROUP - pages.length : 0

  return (
    <nav
      aria-label="활동 쪽 넘기기"
      className={cn(
        "flex items-center justify-center gap-4 rounded-[var(--child-radius-pill)] bg-[var(--child-surface)] px-5 py-3 drop-shadow-[0_4px_5px_rgba(0,0,0,0.08)]",
        className
      )}
    >
      <button
        type="button"
        aria-label="이전 쪽"
        disabled={page <= 1}
        onClick={() => onChange(page - 1)}
        className={ARROW_BUTTON}
      >
        <ChevronLeft className="size-5" strokeWidth={2} absoluteStrokeWidth />
      </button>

      <ol className="flex items-center gap-2">
        {pages.map((number) => {
          const isCurrent = number === page
          return (
            <li key={number}>
              <button
                type="button"
                aria-label={`${number}쪽`}
                aria-current={isCurrent ? "page" : undefined}
                onClick={() => onChange(number)}
                className={cn(
                  "flex size-8 items-center justify-center rounded-[var(--child-radius-pill)] text-sm outline-none select-none focus-visible:ring-3 focus-visible:ring-[var(--child-primary)]/50",
                  isCurrent ? "bg-[#333333] font-bold text-white" : "bg-[#f3f4f6] font-semibold text-[#9ca3af]"
                )}
              >
                {number}
              </button>
            </li>
          )
        })}
        {Array.from({ length: emptySlots }, (_, index) => (
          <li key={`empty-${index}`} aria-hidden className="size-8" />
        ))}
      </ol>

      <button
        type="button"
        aria-label="다음 쪽"
        disabled={page >= pageCount}
        onClick={() => onChange(page + 1)}
        className={ARROW_BUTTON}
      >
        <ChevronRight className="size-5" strokeWidth={2} absoluteStrokeWidth />
      </button>
    </nav>
  )
}

export { ActivityPager }
