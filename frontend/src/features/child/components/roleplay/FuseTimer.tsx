import { cn } from "@/lib/utils"

const WARNING_SECONDS = 5

/**
 * 심지가 타들어가는 카운트다운. 끈이 오른쪽 끝부터 줄어들고, 타는 끝에 불꽃이 붙는다.
 * 남은 양만으로는 시간을 가늠하기 어려워 남은 초도 함께 보여 준다. 마지막 5초는 주황색으로 알린다.
 * Figma에 시안이 없어 기존 아동 화면 색에 맞춰 그렸다.
 */
function FuseTimer({ remainingMs, durationMs }: { remainingMs: number; durationMs: number }) {
  const seconds = Math.ceil(remainingMs / 1000)
  const percent = (remainingMs / durationMs) * 100
  const isWarning = seconds <= WARNING_SECONDS

  return (
    <div
      role="timer"
      aria-label={`남은 시간 ${seconds}초`}
      data-warning={isWarning || undefined}
      className="flex items-center gap-4"
    >
      <div className="relative h-3 flex-1">
        {/* 타고 남은 자리: 옅은 점선으로 남겨 처음 길이를 알 수 있게 한다. */}
        <div className="absolute inset-x-0 top-1/2 -translate-y-1/2 border-t-2 border-dashed border-[var(--child-border)]" />
        <div
          className="absolute inset-y-0 left-0 rounded-full transition-[width] duration-100 ease-linear"
          style={{
            width: `${percent}%`,
            // 꼬인 끈처럼 보이도록 사선 줄무늬를 깐다.
            backgroundImage: "repeating-linear-gradient(-45deg, #E2B47C 0 6px, #C98C4E 6px 12px)",
          }}
        />
        <span
          aria-hidden="true"
          className={cn(
            "absolute top-1/2 -translate-x-1/2 -translate-y-1/2 rounded-full transition-[left,width,height] duration-100 ease-linear motion-safe:animate-pulse",
            isWarning
              ? "size-7 bg-[radial-gradient(circle,#FFF6C8_0%,#FFB347_45%,#F0782A_75%,transparent_76%)]"
              : "size-5 bg-[radial-gradient(circle,#FFF6C8_0%,#FFD166_50%,#F6A13A_75%,transparent_76%)]"
          )}
          style={{ left: `${percent}%` }}
        />
      </div>
      <span
        aria-hidden="true"
        className={cn(
          "w-14 text-right font-child-display text-2xl font-extrabold tabular-nums",
          isWarning ? "text-[#D9611C]" : "text-[var(--child-text)]"
        )}
      >
        {seconds}초
      </span>
    </div>
  )
}

export { FuseTimer }
