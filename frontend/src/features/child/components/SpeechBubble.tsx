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
  characterAlign = "bottom",
  side = "left",
  children,
  className,
}: {
  character: CharacterName
  /** 캐릭터를 말풍선 아래(기본) 또는 위(이름 옆)에 맞춘다. 말풍선 꼬리 모서리도 같은 쪽으로 둔다. */
  characterAlign?: "top" | "bottom"
  /** 캐릭터를 왼쪽(기본) 또는 오른쪽에 둔다. 역할극에서 깡총이는 Figma처럼 오른쪽에 선다. */
  side?: "left" | "right"
  children: React.ReactNode
  className?: string
}) {
  const isTop = characterAlign === "top"
  const isRight = side === "right"
  // 말풍선 꼬리(작은 모서리)는 캐릭터가 있는 쪽을 가리킨다.
  const tailCorner = isTop
    ? isRight ? "rounded-tr-md" : "rounded-tl-md"
    : isRight ? "rounded-br-md" : "rounded-bl-md"

  return (
    <div
      className={cn(
        "flex gap-3",
        isTop ? "items-start" : "items-end",
        isRight && "flex-row-reverse",
        className
      )}
    >
      <Character name={character} size="sm" />
      <div
        className={cn(
          "flex flex-col gap-1 rounded-[var(--child-radius-card)] bg-[var(--child-surface)] px-5 py-3 text-left shadow-sm",
          tailCorner
        )}
      >
        <span className="text-sm font-semibold text-[var(--child-text-muted)]">
          {SPEAKER_NAMES[character]}
        </span>
        <p className="text-lg font-medium text-[var(--child-text)]">{children}</p>
      </div>
    </div>
  )
}

export { SpeechBubble }
