import * as React from "react"
import { cn } from "@/lib/utils"
import { Character, type CharacterName } from "./Character"

const SPEAKER_NAMES: Record<CharacterName, string> = {
  turtle: "거북이 안내자",
  rabbit: "깡총이",
}

// 깡총이 말풍선 색은 Figma C-RP-02 힌트 말풍선(node 286:1473) 값이다. 거북이는 기본 흰 말풍선.
const BUBBLE_STYLES: Record<CharacterName, string> = {
  turtle: "bg-[var(--child-surface)] text-[var(--child-text)]",
  rabbit: "bg-[#FBEADC] text-[#3A2E2A]",
}

/** 캐릭터 대사 말풍선. 퀴즈 상황 설명·역할극 질문(거북이)과 힌트(깡총이)에 함께 쓴다. 캐릭터는 항상 왼쪽에 선다. */
function SpeechBubble({
  character,
  characterAlign = "bottom",
  children,
  className,
}: {
  character: CharacterName
  /** 캐릭터를 말풍선 아래(기본) 또는 위(이름 옆)에 맞춘다. 말풍선 꼬리 모서리도 같은 쪽으로 둔다. */
  characterAlign?: "top" | "bottom"
  children: React.ReactNode
  className?: string
}) {
  const isTop = characterAlign === "top"

  return (
    <div className={cn("flex gap-3", isTop ? "items-start" : "items-end", className)}>
      <Character name={character} size="sm" />
      <div
        className={cn(
          "flex flex-col gap-1 rounded-[var(--child-radius-card)] px-5 py-3 text-left shadow-sm",
          // 말풍선 꼬리(작은 모서리)는 캐릭터가 있는 쪽을 가리킨다.
          isTop ? "rounded-tl-md" : "rounded-bl-md",
          BUBBLE_STYLES[character]
        )}
      >
        <span className="text-sm font-semibold text-[var(--child-text-muted)]">
          {SPEAKER_NAMES[character]}
        </span>
        <p className="font-child-display text-lg font-bold">{children}</p>
      </div>
    </div>
  )
}

export { SpeechBubble }
