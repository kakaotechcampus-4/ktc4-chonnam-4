import { useState } from "react"
import { CameraOff, CircleCheck, LoaderCircle, ScanFace } from "lucide-react"
import { PhotoGate } from "./PhotoGate"
import { CameraPermissionGuide, StateDialog } from "../state"
import type { CameraStatus } from "../../hooks/useExpressionCamera"

type SelfCameraGateProps = {
  status: CameraStatus
  faceFound: boolean
  /** <video> 에 붙일 ref 콜백 */
  attachVideo: (node: HTMLVideoElement | null) => void
  /** 권한 안내에서 "다시 확인하기"를 누르면 카메라를 다시 켠다 */
  onRestart: () => void
}

function Caption({ status, faceFound, onShowGuide }: { status: CameraStatus; faceFound: boolean; onShowGuide: () => void }) {
  const title = (text: string) => (
    <span className="font-child-display text-base font-extrabold text-[#252331]">{text}</span>
  )
  const note = "flex items-center gap-1 text-xs font-bold"

  if (status === "denied") {
    return (
      <>
        {title("카메라 없이 골라도 괜찮아!")}
        <button type="button" onClick={onShowGuide} className={`${note} text-[#6654D9] underline underline-offset-2`}>
          <CameraOff className="size-4" aria-hidden="true" />
          카메라 켜는 방법 보기
        </button>
      </>
    )
  }
  if (status === "unavailable") {
    return (
      <>
        {title("카메라 없이 골라도 괜찮아!")}
        <span className={`${note} text-[#6B6776]`}>
          <CameraOff className="size-4" aria-hidden="true" />
          카메라를 쓸 수 없어서 표정은 확인하지 않아요
        </span>
      </>
    )
  }
  if (status === "off") {
    return title("표정을 잘 보여줬어!")
  }
  return (
    <>
      {title("얼굴을 맞추고 표정을 지어봐!")}
      {status === "starting" ? (
        <span className={`${note} text-[#6B6776]`}>
          <LoaderCircle className="size-4 animate-spin" aria-hidden="true" />
          카메라를 준비하고 있어요
        </span>
      ) : faceFound ? (
        <span className={`${note} text-[#6654D9]`}>
          <CircleCheck className="size-4 fill-[#8BCB4A] text-white" aria-hidden="true" />
          카메라 준비 완료 · 얼굴을 찾았어요
        </span>
      ) : (
        <span className={`${note} text-[#6B6776]`}>
          <ScanFace className="size-4" aria-hidden="true" />
          얼굴을 동그라미 안에 맞춰 줘
        </span>
      )}
    </>
  )
}

/**
 * 내 감정 문항(C-QZ-01)의 포토 게이트 안 셀프 카메라.
 * 영상은 거울처럼 좌우를 뒤집어 보여 주고, 기기 밖으로 보내지 않는다.
 * 카메라를 못 써도 보기를 골라 계속 풀 수 있다(VS-006). 그 문항은 서버가 점수에서 뺀다.
 */
function SelfCameraGate({ status, faceFound, attachVideo, onRestart }: SelfCameraGateProps) {
  const [guideOpen, setGuideOpen] = useState(false)
  const showVideo = status === "starting" || status === "ready"

  return (
    <>
      <PhotoGate
        caption={
          <div aria-live="polite" className="flex flex-col items-center gap-0.5">
            <Caption status={status} faceFound={faceFound} onShowGuide={() => setGuideOpen(true)} />
          </div>
        }
      >
        <div className="relative flex h-[76%] w-[57%] items-center justify-center overflow-hidden rounded-[50%] bg-white/70">
          {showVideo ? (
            <video
              ref={attachVideo}
              autoPlay
              muted
              playsInline
              aria-label="내 얼굴 카메라"
              className="absolute inset-0 size-full -scale-x-100 object-cover"
            />
          ) : null}
          <div
            className={`relative flex h-[88%] w-[84%] items-center justify-center rounded-[50%] border-3 border-dashed ${
              faceFound ? "border-[#8BCB4A]" : "border-[#A99BF5]"
            } text-[#A99BF5]`}
          >
            {status === "ready" ? null : status === "denied" || status === "unavailable" ? (
              <CameraOff className="size-12" aria-hidden="true" />
            ) : (
              <ScanFace className="size-12" aria-hidden="true" />
            )}
          </div>
        </div>
      </PhotoGate>

      <StateDialog open={guideOpen} size="lg">
        <CameraPermissionGuide
          onRetry={() => {
            setGuideOpen(false)
            onRestart()
          }}
        />
      </StateDialog>
    </>
  )
}

export { SelfCameraGate }
