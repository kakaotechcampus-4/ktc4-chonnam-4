import { useCallback, useEffect, useRef, useState, type FormEvent } from "react"
import { Keyboard, Mic, Pencil } from "lucide-react"
import { cn } from "@/lib/utils"
import { ChildButton } from "../ChildButton"
import { VoiceRecordingPanel, type RecordingEndReason } from "./VoiceRecordingPanel"
import {
  canRecordVoice,
  startVoiceRecording,
  VoiceRecorderUnavailable,
  type VoiceRecorderError,
  type VoiceRecording,
} from "../../lib/voiceRecorder"

const MAX_REPLY_LENGTH = 200

export type RoleplayInputMode = "voice" | "text"

/**
 * 녹음이 끝난 결과.
 * audio: "다 말했어요"로 끝낸 녹음. 화면이 서버(API-RPL-04)로 보낸다.
 * timeout: 30초가 다 됨. 계약(API-RPL-04)에 따라 녹음을 버리고 자동으로 보내지 않는다.
 * unavailable: 마이크를 열 수 없다(권한 거부·장치 없음·미지원 브라우저).
 */
export type VoiceResult =
  | { type: "audio"; audio: Blob }
  | { type: "timeout" }
  | { type: "unavailable"; reason: VoiceRecorderError }

/**
 * 역할극 답 입력 (C-RP-02 음성 모드 · C-RP-04 글자 모드, VS-010).
 * 음성이 기본이고, "글자로 답할래요"로 글자 입력으로 바꾼다. 글자 모드에서도 마이크 버튼은 유지한다.
 * 마이크를 누르면 브라우저 마이크로 녹음하며 30초 "듣고 있어요" 화면이 뜬다. "다 말했어요"를 누르면 녹음한 음성을 onVoiceResult 로 넘긴다.
 * 입력 방식(mode)은 화면이 정한다. 음성 실패가 쌓여 서버가 글자를 권하면(SUGGEST_TEXT_KEEP_VOICE) 화면이 글자 모드로 바꾼다.
 */
function RoleplayComposer({
  mode,
  onModeChange,
  onSend,
  onVoiceResult,
  disabled = false,
}: {
  mode: RoleplayInputMode
  onModeChange: (mode: RoleplayInputMode) => void
  onSend: (text: string) => void
  onVoiceResult: (result: VoiceResult) => void
  disabled?: boolean
}) {
  const [recording, setRecording] = useState<VoiceRecording | null>(null)
  const [isPreparingMic, setIsPreparingMic] = useState(false)
  const [text, setText] = useState("")
  const inputRef = useRef<HTMLInputElement>(null)
  const recordingRef = useRef<VoiceRecording | null>(null)
  const mountedRef = useRef(true)
  const trimmed = text.trim()
  const canRecord = canRecordVoice()

  // 화면을 떠나면 녹음을 버리고 마이크를 닫는다.
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      recordingRef.current?.cancel()
      recordingRef.current = null
    }
  }, [])

  // 글자 모드로 바꾸면 바로 입력창에 포커스해 태블릿 키보드가 뜨게 한다.
  useEffect(() => {
    if (mode === "text" && !recording) inputRef.current?.focus()
  }, [mode, recording])

  const startRecording = async () => {
    if (isPreparingMic || recordingRef.current) return
    setIsPreparingMic(true)
    try {
      const started = await startVoiceRecording()
      if (!mountedRef.current) {
        started.cancel()
        return
      }
      recordingRef.current = started
      setRecording(started)
    } catch (error) {
      if (!mountedRef.current) return
      const reason = error instanceof VoiceRecorderUnavailable ? error.reason : "failed"
      onVoiceResult({ type: "unavailable", reason })
    } finally {
      if (mountedRef.current) setIsPreparingMic(false)
    }
  }

  const finishRecording = useCallback(
    async (reason: RecordingEndReason) => {
      const current = recordingRef.current
      recordingRef.current = null
      setRecording(null)
      if (!current) return
      if (reason === "timeout") {
        current.cancel()
        onVoiceResult({ type: "timeout" })
        return
      }
      // 소리가 거의 없는 녹음도 그대로 보낸다. 무음·빈 파일 판정과 실패 횟수(3번이면 글자 권유)는 서버가 정한다.
      onVoiceResult({ type: "audio", audio: await current.stop() })
    },
    [onVoiceResult]
  )

  const micButtonProps = {
    disabled: !canRecord || disabled || isPreparingMic,
    onClick: () => void startRecording(),
  }

  if (recording) return <VoiceRecordingPanel onFinish={(reason) => void finishRecording(reason)} />

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (!trimmed || disabled) return
    onSend(trimmed)
    setText("")
  }

  if (mode === "voice") {
    return (
      <div className="grid grid-cols-[1fr_auto_1fr] items-center gap-4">
        <button
          type="button"
          onClick={() => onModeChange("text")}
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

/** 음성 입력 버튼. 녹음을 지원하지 않는 브라우저(또는 http 주소)에서는 누를 수 없다. */
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
