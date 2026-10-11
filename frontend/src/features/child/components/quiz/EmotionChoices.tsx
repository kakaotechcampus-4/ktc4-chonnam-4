import { Check } from "lucide-react"
import { cn } from "@/lib/utils"
import type { Emotion, QuizChoice } from "../../api"
import { EmotionFace } from "../EmotionFace"

// 카드 색은 Figma 표정 퀴즈 신버전(284:696) 보기 카드 값이다. 속상해요는 시안에 없어 초록 계열로 맞췄다.
const CARD_STYLES: Record<Emotion, string> = {
  HAPPY: "bg-[#FFF5D8] border-[#F6C84C]",
  SAD: "bg-[#E7F7FD] border-[#79D7EB]",
  ANGRY: "bg-[#FFF0F5] border-[#F48FB1]",
  SURPRISED: "bg-[#F0EBFF] border-[#A99BF5]",
  UPSET: "bg-[#F1FAE6] border-[#8BCB4A]",
}

/** 표정 퀴즈 보기. 힌트 뒤에는 남은 보기(2개)만 넘겨받아 보여준다. */
function EmotionChoices({
  choices,
  selectedChoiceId,
  onSelect,
  disabled = false,
}: {
  choices: QuizChoice[]
  selectedChoiceId: string | null
  onSelect: (choiceId: string) => void
  disabled?: boolean
}) {
  return (
    <div role="radiogroup" aria-label="보기" className="grid grid-cols-2 gap-4">
      {choices.map((choice) => {
        const isSelected = choice.choiceId === selectedChoiceId
        return (
          <button
            key={choice.choiceId}
            type="button"
            role="radio"
            aria-checked={isSelected}
            disabled={disabled}
            onClick={() => onSelect(choice.choiceId)}
            className={cn(
              "relative flex min-h-24 items-center gap-3 break-keep rounded-[20px] border-2 px-4 py-3 text-left transition-transform outline-none select-none active:scale-[0.98] focus-visible:ring-3 focus-visible:ring-[var(--child-primary)]/50 disabled:opacity-60",
              CARD_STYLES[choice.emotion],
              // 선택한 카드는 감정 색 대신 보라 테두리를 굵게 둘러 한눈에 구분되게 한다.
              isSelected && "border-[3px] border-[#6654D9]"
            )}
          >
            <EmotionFace emotion={choice.emotion} className="size-13" />
            <span className="flex flex-col gap-0.5">
              <span className="font-child-display text-xl font-extrabold text-[#252331]">{choice.label}</span>
              {isSelected ? (
                <span className="text-xs font-bold text-[#6654D9]">선택했어요</span>
              ) : choice.description ? (
                <span className="text-xs text-[#6B6776]">{choice.description}</span>
              ) : null}
            </span>
            {isSelected ? (
              <span className="absolute top-2 right-2 flex size-6 items-center justify-center rounded-full bg-[#6654D9] text-white">
                <Check className="size-4" strokeWidth={3} />
              </span>
            ) : null}
          </button>
        )
      })}
    </div>
  )
}

export { EmotionChoices }
