import { cn } from "@/lib/utils"
import type { Emotion } from "../api"

/**
 * 감정 얼굴 일러스트. 퀴즈 보기와 이미지 제시 문항의 친구 얼굴(S1 임시)에 쓴다.
 * 색은 놀이공원 배경 팔레트에서 골랐다.
 */
const FACE_COLORS: Record<Emotion, string> = {
  HAPPY: "#F8D46F",
  SAD: "#9BE0F0",
  ANGRY: "#F8AFC7",
  SURPRISED: "#CFC4F2",
  UPSET: "#B7E58C",
}

const FEATURE_COLOR = "#34313B"

function FaceFeatures({ emotion }: { emotion: Emotion }) {
  const stroke = { stroke: FEATURE_COLOR, strokeWidth: 4, strokeLinecap: "round" as const, fill: "none" }

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
          <circle cx="26" cy="29" r="3.5" fill={FEATURE_COLOR} />
          <circle cx="46" cy="29" r="3.5" fill={FEATURE_COLOR} />
          <path d="M25 50 Q36 40 47 50" {...stroke} />
          <path d="M26 35 Q23 41 26 43 Q29 41 26 35Z" fill="#4AA8D8" />
        </>
      )
    case "ANGRY":
      return (
        <>
          <path d="M19 21 L31 26" {...stroke} />
          <path d="M53 21 L41 26" {...stroke} />
          <circle cx="26" cy="31" r="3.5" fill={FEATURE_COLOR} />
          <circle cx="46" cy="31" r="3.5" fill={FEATURE_COLOR} />
          <path d="M26 49 Q36 42 46 49" {...stroke} />
        </>
      )
    case "SURPRISED":
      return (
        <>
          <path d="M20 19 Q26 15 32 19" {...stroke} />
          <path d="M40 19 Q46 15 52 19" {...stroke} />
          <circle cx="26" cy="29" r="5" fill={FEATURE_COLOR} />
          <circle cx="46" cy="29" r="5" fill={FEATURE_COLOR} />
          <ellipse cx="36" cy="47" rx="6" ry="7" fill={FEATURE_COLOR} />
        </>
      )
    case "UPSET":
      return (
        <>
          <path d="M20 25 L31 21" {...stroke} />
          <path d="M52 25 L41 21" {...stroke} />
          <circle cx="26" cy="30" r="3.5" fill={FEATURE_COLOR} />
          <circle cx="46" cy="30" r="3.5" fill={FEATURE_COLOR} />
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
      <circle cx="36" cy="36" r="34" fill={FACE_COLORS[emotion]} />
      <circle cx="17" cy="40" r="4" fill="#FFFFFF" fillOpacity="0.45" />
      <circle cx="55" cy="40" r="4" fill="#FFFFFF" fillOpacity="0.45" />
      <FaceFeatures emotion={emotion} />
    </svg>
  )
}

export { EmotionFace }
