import { useCallback, useEffect, useRef, useState, type FormEvent } from "react"
import { Keyboard, Mic, Pencil } from "lucide-react"
import { cn } from "@/lib/utils"
import { ChildButton } from "../ChildButton"
import { VoiceRecordingPanel, type RecordingEndReason } from "./VoiceRecordingPanel"

const MAX_REPLY_LENGTH = 200

// 녹음·STT(S6) 전이라 마이크는 개발 서버에서만 눌러 "듣고 있어요" 화면을 확인할 수 있다.
// 사용자 테스트 주소(운영 빌드)에서는 말해도 아무 일이 없으므로 계속 막아 둔다.
const CAN_PREVIEW_VOICE = import.meta.env.DEV

export type RoleplayInputMode = "voice" | "text"

/**
 * 역할극 답 입력 (C-RP-02 음성 모드 · C-RP-04 글자 모드, VS-010).
 * 음성이 기본이고, "글자로 답할래요"로 글자 입력으로 바꾼다. 글자 모드에서도 마이크 버튼은 유지한다.
 * 녹음·STT가 없어 운영 빌드에서는 마이크 버튼이 비활성이다. 개발 서버에서는 마이크를 누르면 30초 "듣고 있어요" 화면이 뜨고,
 * 끝나면 보낼 음성이 없으므로 글자 모드로 바꾼다. 저신뢰 3회 누적 시 글자 모드 자동 전환도 S6에서 붙인다.
 */
function RoleplayComposer({
  onSend,
  onVoiceFinish,
  disabled = false,
}: {
  onSend: (text: string, mode: RoleplayInputMode) => void
  /** "듣고 있어요" 화면이 끝났을 때. S6에서는 여기서 녹음한 음성을 보낸다. */
  onVoiceFinish?: (reason: RecordingEndReason) => void
  disabled?: boolean
}) {
  const [mode, setMode] = useState<RoleplayInputMode>("voice")
  const [isRecording, setIsRecording] = useState(false)
  const [text, setText] = useState("")
  const inputRef = useRef<HTMLInputElement>(null)
  const trimmed = text.trim()

  // 글자 모드로 바꾸면 바로 입력창에 포커스해 태블릿 키보드가 뜨게 한다.
  useEffect(() => {
    if (mode === "text" && !isRecording) inputRef.current?.focus()
  }, [mode, isRecording])

  const finishRecording = useCallback(
    (reason: RecordingEndReason) => {
      setIsRecording(false)
      setMode("text")
      onVoiceFinish?.(reason)
    },
    [onVoiceFinish]
  )

  const micButtonProps = {
    disabled: !CAN_PREVIEW_VOICE || disabled,
    onClick: () => setIsRecording(true),
  }

  if (isRecording) return <VoiceRecordingPanel onFinish={finishRecording} />

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (!trimmed || disabled) return
    onSend(trimmed, "text")
    setText("")
  }

  if (mode === "voice") {
    return (
      <div className="grid grid-cols-[1fr_auto_1fr] items-center gap-4">
        <button
          type="button"
          onClick={() => setMode("text")}
          disabled={disabled}
          className="flex items-center gap-2 justify-self-end rounded-[var(--child-radius-pill)] bg-[var(--child-surface)] px-5 py-3 text-base font-semibold text-[var(--child-text)] shadow-sm outline-none focus-visible:ring-3 focus-visible:ring-[var(--child-primary)]/50 disabled:opacity-60"
        >
          <Keyboard className="size-5" />
          글자로 답할래요
        </button>
        <MicButton size="lg" {...micButtonProps} />
        <span />
      </div>
    )
  }

  return (
    <form
      onSubmit={handleSubmit}
      // 놀이공원 배경 위에서도 안내 문구가 읽히도록 반투명 흰 카드에 담는다.
      className="flex flex-col gap-3 rounded-[var(--child-radius-card)] bg-[var(--child-surface)]/85 p-4 shadow-sm backdrop-blur-sm"
    >
      <p className="flex items-center gap-2 text-base font-semibold text-[var(--child-text-muted)]">
        <Keyboard className="size-5 text-[var(--child-primary)]" />
        이번엔 글씨로 답해볼게
      </p>
      <input
        ref={inputRef}
        value={text}
        onChange={(event) => setText(event.target.value)}
        maxLength={MAX_REPLY_LENGTH}
        disabled={disabled}
        aria-label="답 적기"
        placeholder="답을 적어줘"
        className="h-16 w-full rounded-[var(--child-radius-card)] border-2 border-[var(--child-border)] bg-[var(--child-surface)] px-5 text-lg text-[var(--child-text)] outline-none placeholder:text-[var(--child-text-muted)] focus-visible:border-[var(--child-primary)] disabled:opacity-60"
      />
      <p className="flex items-center gap-1.5 text-sm text-[var(--child-text-muted)]">
        <Pencil className="size-4" />
        입력창을 누르면 태블릿 키보드가 자동으로 나타나요
      </p>
      <div className="flex items-center gap-3">
        <MicButton size="sm" {...micButtonProps} />
        <ChildButton type="submit" className="h-16 flex-1 text-xl" disabled={!trimmed || disabled}>
          보낼래요
        </ChildButton>
      </div>
    </form>
  )
}

/** 음성 입력 버튼. 녹음·STT(S6) 전까지는 개발 서버에서만 누를 수 있다. */
function MicButton({ size, disabled, onClick }: { size: "sm" | "lg"; disabled: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      aria-label="말로 답하기"
      className={cn(
        "flex shrink-0 items-center justify-center rounded-full disabled:opacity-60",
        size === "lg"
          ? "size-20 bg-[var(--child-primary)] text-white shadow-md [&_svg]:size-9"
          : "size-16 bg-[var(--child-surface)] text-[var(--child-text)] shadow-sm [&_svg]:size-7"
      )}
    >
      <Mic />
    </button>
  )
}

export { RoleplayComposer }
