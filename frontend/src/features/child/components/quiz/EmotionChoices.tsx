import { Check } from "lucide-react"
import { cn } from "@/lib/utils"
import type { QuizChoice } from "../../api"
import { EmotionFace } from "../EmotionFace"

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
    <div role="radiogroup" aria-label="보기" className="grid grid-cols-2 gap-3">
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
              "relative flex min-h-20 items-center gap-3 break-keep rounded-[var(--child-radius-card)] border-2 bg-[var(--child-surface)] px-4 py-3 text-left shadow-sm transition-transform outline-none select-none active:scale-[0.98] focus-visible:ring-3 focus-visible:ring-[var(--child-primary)]/50 disabled:opacity-60",
              isSelected
                ? "border-[var(--child-primary)] bg-[var(--child-surface-muted)]"
                : "border-transparent"
            )}
          >
            <EmotionFace emotion={choice.emotion} />
            <span className="flex flex-col">
              <span className="text-lg font-bold text-[var(--child-text)]">{choice.label}</span>
              {choice.description ? (
                <span className="text-sm text-[var(--child-text-muted)]">{choice.description}</span>
              ) : null}
            </span>
            {isSelected ? (
              <span className="absolute top-2 right-2 flex size-6 items-center justify-center rounded-full bg-[var(--child-primary)] text-white">
                <Check className="size-4" />
              </span>
            ) : null}
          </button>
        )
      })}
    </div>
  )
}

export { EmotionChoices }
