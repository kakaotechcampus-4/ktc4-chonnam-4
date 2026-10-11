import { cn } from "@/lib/utils"
import type { Emotion } from "../api"

/**
 * 감정 얼굴 일러스트. 퀴즈 보기와 이미지 제시 문항의 친구 얼굴(S1 임시)에 쓴다.
 * 색은 Figma 표정 퀴즈 신버전(284:696) 값이다: 옅은 원 위에 같은 계열의 진한 이목구비.
 * 속상해요(UPSET)는 시안에 없어 게이트 초록색(#8BCB4A) 계열로 맞췄다.
 */
const FACE_THEMES: Record<Emotion, { face: string; faceOpacity: number; feature: string }> = {
  HAPPY: { face: "#F6C84C", faceOpacity: 0.24, feature: "#8B6500" },
  SAD: { face: "#79D7EB", faceOpacity: 0.4, feature: "#20627D" },
  ANGRY: { face: "#F48FB1", faceOpacity: 0.26, feature: "#A42C58" },
  SURPRISED: { face: "#A99BF5", faceOpacity: 0.3, feature: "#5945BB" },
  UPSET: { face: "#8BCB4A", faceOpacity: 0.3, feature: "#4C7A1F" },
}

function FaceFeatures({ emotion }: { emotion: Emotion }) {
  const featureColor = FACE_THEMES[emotion].feature
  const stroke = { stroke: featureColor, strokeWidth: 4, strokeLinecap: "round" as const, fill: "none" }

  switch (emotion) {
    case "HAPPY":
      return (
        <>
          <path d="M22 28 Q26 23 30 28" {...stroke} />
          <path d="M42 28 Q46 23 50 28" {...stroke} />
          <path d="M22 42 Q36 56 50 42" {...stroke} />
        </>
      )
    case "SAD":
      return (
        <>
          <circle cx="26" cy="29" r="3.5" fill={featureColor} />
          <circle cx="46" cy="29" r="3.5" fill={featureColor} />
          <path d="M25 50 Q36 40 47 50" {...stroke} />
          <path d="M26 35 Q23 41 26 43 Q29 41 26 35Z" fill="#79D7EB" />
        </>
      )
    case "ANGRY":
      return (
        <>
          <path d="M19 21 L31 26" {...stroke} />
          <path d="M53 21 L41 26" {...stroke} />
          <circle cx="26" cy="31" r="3.5" fill={featureColor} />
          <circle cx="46" cy="31" r="3.5" fill={featureColor} />
          <path d="M26 49 Q36 42 46 49" {...stroke} />
        </>
      )
    case "SURPRISED":
      return (
        <>
          <path d="M20 19 Q26 15 32 19" {...stroke} />
          <path d="M40 19 Q46 15 52 19" {...stroke} />
          <circle cx="26" cy="29" r="5" fill={featureColor} />
          <circle cx="46" cy="29" r="5" fill={featureColor} />
          <ellipse cx="36" cy="47" rx="6" ry="7" fill={featureColor} />
        </>
      )
    case "UPSET":
      return (
        <>
          <path d="M20 25 L31 21" {...stroke} />
          <path d="M52 25 L41 21" {...stroke} />
          <circle cx="26" cy="30" r="3.5" fill={featureColor} />
          <circle cx="46" cy="30" r="3.5" fill={featureColor} />
          <path d="M27 48 Q31 45 36 48 Q41 51 45 48" {...stroke} />
        </>
      )
  }
}

function EmotionFace({
  emotion,
  label,
  className,
}: {
  emotion: Emotion
  /** 스크린리더용 이름. 없으면 장식으로 숨긴다. 문항 이미지에는 정답이 드러나지 않는 이름을 준다. */
  label?: string
  className?: string
}) {
  return (
    <svg
      viewBox="0 0 72 72"
      className={cn("size-12 shrink-0", className)}
      role={label ? "img" : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
    >
      <circle cx="36" cy="36" r="34" fill={FACE_THEMES[emotion].face} fillOpacity={FACE_THEMES[emotion].faceOpacity} />
      <FaceFeatures emotion={emotion} />
    </svg>
  )
}

export { EmotionFace }
