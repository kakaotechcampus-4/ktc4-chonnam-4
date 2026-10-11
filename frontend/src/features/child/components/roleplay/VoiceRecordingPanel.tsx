import { useCallback } from "react"
import { Mic } from "lucide-react"
import { ChildButton } from "../ChildButton"
import { useCountdown } from "../../hooks/useCountdown"
import { FuseTimer } from "./FuseTimer"

/** 한 번에 말할 수 있는 최대 시간. 명세에는 "시간 초과" 상태만 있고 값은 없어 30초로 정했다. */
const RECORDING_LIMIT_MS = 30_000

type RecordingEndReason = "manual" | "timeout"

/**
 * 역할극 "듣고 있어요" 화면 (C-RP-02 녹음 진행).
 * 화면에 올라온 순간부터 30초를 세고, "다 말했어요"를 누르거나 시간이 다 되면 onFinish로 알린다.
 * 실제 녹음·STT는 S6에서 붙이고, 지금은 화면과 시간 제한만 있다.
 */
function VoiceRecordingPanel({ onFinish }: { onFinish: (reason: RecordingEndReason) => void }) {
  const handleTimeout = useCallback(() => onFinish("timeout"), [onFinish])
  const remainingMs = useCountdown(RECORDING_LIMIT_MS, handleTimeout)

  return (
    <div className="flex flex-col gap-4 rounded-[var(--child-radius-card)] bg-[var(--child-surface)]/85 p-4 shadow-sm backdrop-blur-sm">
      <p className="flex items-center gap-2 font-child-display text-lg font-bold text-[var(--child-text)]">
        <Mic className="size-5 text-[var(--child-primary)]" />
        듣고 있어요! 천천히 말해봐
      </p>
      <FuseTimer remainingMs={remainingMs} durationMs={RECORDING_LIMIT_MS} />
      <div className="flex items-center gap-3">
        {/* 듣는 중임을 알리는 표시. 누르는 버튼이 아니다. */}
        <span
          aria-hidden="true"
          className="relative flex size-16 shrink-0 items-center justify-center rounded-full bg-[var(--child-primary)] text-white shadow-md"
        >
          <span className="absolute inset-0 rounded-full bg-[var(--child-primary)]/40 motion-safe:animate-ping" />
          <Mic className="relative size-7" />
        </span>
        <ChildButton className="h-16 flex-1 text-xl" onClick={() => onFinish("manual")}>
          다 말했어요
        </ChildButton>
      </div>
    </div>
  )
}

export { VoiceRecordingPanel }
export type { RecordingEndReason }
