import * as React from "react"
import { cn } from "@/lib/utils"
import { Character, type CharacterName } from "./Character"

const SPEAKER_NAMES: Record<CharacterName, string> = {
  turtle: "거북이 안내자",
  rabbit: "깡총이",
}

/** 캐릭터 대사 말풍선. 퀴즈 상황 설명·역할극 질문(거북이)과 힌트(깡총이)에 함께 쓴다. */
function SpeechBubble({
  character,
  children,
  className,
}: {
  character: CharacterName
  children: React.ReactNode
  className?: string
}) {
  return (
    <div className={cn("flex items-end gap-3", className)}>
      <Character name={character} size="sm" />
      <div className="flex flex-col gap-1 rounded-[var(--child-radius-card)] rounded-bl-md bg-[var(--child-surface)] px-5 py-3 text-left shadow-sm">
        <span className="text-sm font-semibold text-[var(--child-text-muted)]">
          {SPEAKER_NAMES[character]}
        </span>
        <p className="text-lg font-medium text-[var(--child-text)]">{children}</p>
      </div>
    </div>
  )
}

export { SpeechBubble }
