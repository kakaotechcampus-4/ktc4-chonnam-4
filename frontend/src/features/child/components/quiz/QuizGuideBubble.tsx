import { Character } from "../Character"

/**
 * 표정 퀴즈 맨 위 거북이 안내 말풍선 (Figma 284:696 안내 말풍선).
 * 상황은 진한 글씨, 질문은 보라 글씨로 이어 써서 무엇을 물어보는지 눈에 띄게 한다.
 */
function QuizGuideBubble({ situation, question }: { situation?: string; question: string }) {
  return (
    <div className="relative flex items-center gap-4 rounded-3xl border-[1.5px] border-[#A99BF5] bg-white px-5 py-3 text-left shadow-sm sm:px-6">
      <Character name="turtle" size="sm" />
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="text-sm font-semibold text-[#6654D9]">거북이 안내자</span>
        <p className="font-child-display text-xl font-extrabold text-[#252331] sm:text-2xl">
          {situation ? `${situation} ` : ""}
          <span className="text-[#6654D9]">{question}</span>
        </p>
      </div>
      {/* 말풍선 꼬리: 아래 포토 게이트 쪽(왼쪽 아래)을 가리킨다. */}
      <span
        aria-hidden="true"
        className="absolute -bottom-[9px] left-20 size-4 rotate-45 border-r-[1.5px] border-b-[1.5px] border-[#A99BF5] bg-white"
      />
    </div>
  )
}

export { QuizGuideBubble }
