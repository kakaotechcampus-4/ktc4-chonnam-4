import { cn } from "@/lib/utils"
import turtleUrl from "@/assets/child/turtle.png"
import rabbitUrl from "@/assets/child/rabbit.png"
import turtleHappyUrl from "@/assets/child/turtle-happy.png"
import rabbitHappyUrl from "@/assets/child/rabbit-happy.png"
import rabbitSadUrl from "@/assets/child/rabbit-sad.png"
import turtleWinkUrl from "@/assets/child/turtle-wink.png"
import turtleWaveUrl from "@/assets/child/turtle-wave.png"
import turtleWalkUrl from "@/assets/child/turtle-walk.png"

type CharacterMood = "default" | "happy" | "sad" | "wink" | "wave" | "walk"

/**
 * 느링고 캐릭터 이미지.
 * 역할을 고정해 아동이 헷갈리지 않게 한다: 거북이는 질문·안내, 토끼 깡총이는 힌트·도움.
 * 표정(mood)은 Figma 캐릭터 시안의 동작이다. 정답·완료는 신남(happy), 오답·재시도는 시무룩(sad),
 * 거북이의 격려는 윙크(wink), 인사는 손 흔들기(wave), 다음 활동 안내는 걷기(walk).
 * 시무룩한 거북이 시안은 없어서, 없는 표정은 기본 그림을 쓴다.
 */
const CHARACTERS: Record<
  "turtle" | "rabbit",
  { alt: string; src: Record<"default", string> & Partial<Record<CharacterMood, string>> }
> = {
  turtle: {
    alt: "거북이 안내자",
    src: { default: turtleUrl, happy: turtleHappyUrl, wink: turtleWinkUrl, wave: turtleWaveUrl, walk: turtleWalkUrl },
  },
  rabbit: { alt: "토끼 깡총이", src: { default: rabbitUrl, happy: rabbitHappyUrl, sad: rabbitSadUrl } },
}

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
  mood = "default",
  className,
}: {
  name: CharacterName
  size?: CharacterSize
  mood?: CharacterMood
  className?: string
}) {
  const { src: sources, alt } = CHARACTERS[name]
  const src = sources[mood] ?? sources.default

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
export type { CharacterMood, CharacterName }
