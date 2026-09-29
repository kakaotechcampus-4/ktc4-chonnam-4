import { cn } from "@/lib/utils"
import turtleUrl from "@/assets/child/turtle.png"
import rabbitUrl from "@/assets/child/rabbit.png"

/**
 * 느링고 캐릭터 이미지.
 * 역할을 고정해 아동이 헷갈리지 않게 한다: 거북이는 질문·안내, 토끼 깡총이는 힌트·도움.
 */
const CHARACTERS = {
  turtle: { src: turtleUrl, alt: "거북이 안내자" },
  rabbit: { src: rabbitUrl, alt: "토끼 깡총이" },
} as const

// 토끼가 세로로 길어서 너비 대신 높이로 크기를 맞춘다.
const CHARACTER_SIZES = {
  xs: "h-7",
  sm: "h-14",
  md: "h-24",
  lg: "h-40",
} as const

type CharacterName = keyof typeof CHARACTERS
type CharacterSize = keyof typeof CHARACTER_SIZES

function Character({
  name,
  size = "md",
  className,
}: {
  name: CharacterName
  size?: CharacterSize
  className?: string
}) {
  const { src, alt } = CHARACTERS[name]

  return (
    <img
      src={src}
      alt={alt}
      draggable={false}
      className={cn("w-auto shrink-0 select-none", CHARACTER_SIZES[size], className)}
    />
  )
}

export { Character }
export type { CharacterName }
