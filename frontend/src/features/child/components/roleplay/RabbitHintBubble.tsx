import type { ReactNode } from "react"
import rabbitUrl from "@/assets/child/rabbit.png"

/**
 * 역할극 전용 깡총이 힌트 (Figma C-RP-02).
 * 깡총이가 화면 오른쪽 끝에서 얼굴만 내밀고, 그 왼쪽에 연한 주황 말풍선이 붙는다.
 * 퀴즈·코드 입력의 깡총이는 공용 SpeechBubble(왼쪽, 작은 캐릭터)을 그대로 쓴다.
 *
 * 화면 끝까지 닿아야 하므로 ChildLayout을 wide로 쓰는 화면(main 좌우 여백 1.5rem, sm 이상 2.5rem)에서만 쓴다.
 */
function RabbitHintBubble({ children }: { children: ReactNode }) {
  return (
    // 오른쪽 여백(pr)은 깡총이 자리다. 깡총이 틀 폭(68px) - 화면 쪽으로 뺀 만큼. 팔이 말풍선 끝에 살짝 닿게 둔다.
    <div className="relative flex justify-end pr-11 sm:pr-7">
      {/* 색·모서리는 Figma 깡총이 힌트 말풍선(node 286:1473) 값이다. 꼬리는 Figma대로 왼쪽 아래. */}
      <p className="max-w-[80%] rounded-[20px] rounded-bl-md bg-[#FBEADC] px-5 py-3 text-left font-child-display text-lg font-bold text-[#3A2E2A] shadow-sm">
        <span className="font-extrabold">깡총이: </span>
        {children}
      </p>

      {/* 크게 키운 깡총이를 작은 틀에 넣어 얼굴·팔만 보이고 몸통·오른쪽은 잘리게 해 "옆에서 쏙 내민" 모습을 만든다. */}
      <div
        aria-hidden="true"
        // 머리가 말풍선 위로 살짝 올라오고 팔이 말풍선 높이에 오도록 위로 올린다.
        // 모바일은 말풍선 사이가 좁아, 바로 위 아이 답의 아이콘을 가리지 않게 덜 올린다.
        className="pointer-events-none absolute top-0 -right-6 h-22 w-17 -translate-y-1/5 overflow-hidden sm:-right-10 sm:-translate-y-[45%]"
      >
        <img
          src={rabbitUrl}
          alt=""
          draggable={false}
          // 전신 높이 124px로 키워 틀(68×88px) 밖으로 다리·몸통·오른쪽이 나가게 한다. 얼굴과 들어 올린 팔까지만 보인다.
          className="absolute top-0 left-2 h-31 w-auto max-w-none -rotate-20 select-none"
        />
      </div>
    </div>
  )
}

export { RabbitHintBubble }
