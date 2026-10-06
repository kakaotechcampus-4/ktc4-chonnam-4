import * as React from "react"
import photoGateUrl from "@/assets/child/photo-gate.svg"
import { Character } from "../Character"

// 아래 위치 값은 photo-gate.svg(360×420, viewBox 36 194) 안의 좌표를 비율로 옮긴 것이다.
// 프레임 안쪽 화면: x 96~336, y 278~500
const SCREEN_STYLE: React.CSSProperties = { left: "16.7%", top: "20%", width: "66.7%", height: "52.9%" }
// 안내 알약: x 102~330, y 528~590 (Figma 캡션 위치). 좁은 화면에서 줄이 늘면 아래로 자라도록 높이는 최소값만 둔다.
const CAPTION_STYLE: React.CSSProperties = { left: "18.3%", top: "79.5%", width: "63.3%", minHeight: "14.8%" }

/**
 * 표정 퀴즈 "느링고 파크" 입구 포토 게이트 (Figma 284:696).
 * 프레임 안에는 문항마다 다른 그림(얼굴 프레임·친구 얼굴·물음표)을 넣고,
 * 양쪽 받침대에는 거북이와 깡총이를 세운다. 셔터 버튼은 그림이라 누를 수 없다.
 */
function PhotoGate({ caption, children }: { caption: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="relative mx-auto aspect-[360/420] w-full max-w-[22.5rem] select-none">
      <img src={photoGateUrl} alt="" aria-hidden="true" draggable={false} className="absolute inset-0 size-full" />

      <div className="absolute flex items-center justify-center pb-[8%]" style={SCREEN_STYLE}>
        {children}
      </div>

      {/* 받침대 가운데(x 80, 350)에 캐릭터 발을 맞춘다. */}
      <Character
        name="turtle"
        size="sm"
        className="absolute bottom-[20.7%] left-[12.2%] h-[15%] -translate-x-1/2"
      />
      <Character
        name="rabbit"
        size="sm"
        className="absolute right-[12.8%] bottom-[20.7%] h-[16%] translate-x-1/2"
      />

      <div
        className="absolute flex flex-col items-center justify-center gap-0.5 rounded-[1.5rem] bg-white px-3 py-1.5 text-center break-keep shadow-sm"
        style={CAPTION_STYLE}
      >
        {caption}
      </div>
    </div>
  )
}

export { PhotoGate }
