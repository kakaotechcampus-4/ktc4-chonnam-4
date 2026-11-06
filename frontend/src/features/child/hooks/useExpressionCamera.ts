import { useCallback, useEffect, useRef, useState } from "react"
import {
  averageScores,
  recentReadings,
  type ExpressionReading,
  type ExpressionScores,
} from "../camera/expressionJudge"
import { loadExpressionDetector } from "../camera/faceExpressionModel"

/**
 * - starting: 카메라 권한을 묻거나 모델을 불러오는 중
 * - ready: 영상이 나오고 표정을 읽는 중
 * - denied: 아이·기기가 카메라 권한을 거부함 → 서버에 CAMERA_PERMISSION_DENIED
 * - unavailable: 카메라가 없거나 모델을 못 불러옴 → 서버에 NOT_ANALYZABLE
 */
export type CameraStatus = "off" | "starting" | "ready" | "denied" | "unavailable"

/** 표정을 읽는 간격. 태블릿에서 화면이 버벅이지 않을 정도로 둔다. */
const DETECT_INTERVAL_MS = 300

function isPermissionError(error: unknown) {
  return error instanceof DOMException && (error.name === "NotAllowedError" || error.name === "SecurityError")
}

function stopStream(stream: MediaStream | null) {
  stream?.getTracks().forEach((track) => track.stop())
}

function showStream(video: HTMLVideoElement, stream: MediaStream | null) {
  video.srcObject = stream
  if (!stream) return
  // autoPlay 로도 재생되지만, 이미 붙은 video 에 새 영상을 넣을 때를 위해 한 번 더 부른다.
  try {
    void video.play()?.catch(() => {})
  } catch {
    // 재생을 지원하지 않는 환경(테스트 등). 영상이 안 나와도 판정 흐름은 그대로다.
  }
}

/**
 * 내 감정 문항의 셀프 카메라. enabled 동안만 카메라를 켜고, 끄거나 화면을 떠나면 바로 끈다.
 * 영상은 기기 밖으로 보내지 않고, 최근 표정 확률만 기억해 두었다가 readScores()로 꺼내 쓴다.
 */
export function useExpressionCamera(enabled: boolean) {
  const [status, setStatus] = useState<Exclude<CameraStatus, "off">>("starting")
  const [faceFound, setFaceFound] = useState(false)
  const [startCount, setStartCount] = useState(0)
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const readingsRef = useRef<ExpressionReading[]>([])

  /** <video ref={attachVideo}> 로 붙인다. 카메라보다 화면이 늦게 그려져도 영상을 이어 붙인다. */
  const attachVideo = useCallback((node: HTMLVideoElement | null) => {
    videoRef.current = node
    if (node && streamRef.current) showStream(node, streamRef.current)
  }, [])

  useEffect(() => {
    if (!enabled) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    let stream: MediaStream | null = null

    const start = async () => {
      if (!navigator.mediaDevices?.getUserMedia) {
        setStatus("unavailable")
        return
      }
      // 권한을 묻는 동안 모델도 같이 받는다. 실패는 아래에서 기다릴 때 처리한다.
      const detectorPromise = loadExpressionDetector()
      detectorPromise.catch(() => {})
      try {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "user" }, audio: false })
      } catch (error) {
        if (!cancelled) setStatus(isPermissionError(error) ? "denied" : "unavailable")
        return
      }
      if (cancelled) {
        stopStream(stream)
        return
      }
      streamRef.current = stream
      if (videoRef.current) showStream(videoRef.current, stream)

      let detector
      try {
        detector = await detectorPromise
      } catch {
        if (!cancelled) setStatus("unavailable")
        return
      }
      if (cancelled) return
      setStatus("ready")

      const tick = async () => {
        if (cancelled) return
        const video = videoRef.current
        if (video) {
          try {
            const scores = await detector.detect(video)
            if (cancelled) return
            const now = Date.now()
            // 얼굴을 놓치면 지난 표정으로 판정하지 않도록 비운다.
            readingsRef.current = scores ? recentReadings([...readingsRef.current, { at: now, scores }], now) : []
            setFaceFound(scores !== null)
          } catch {
            // 한 장면 분석 실패는 넘기고 다음 장면을 본다.
          }
        }
        if (!cancelled) timer = setTimeout(tick, DETECT_INTERVAL_MS)
      }
      void tick()
    }
    void start()

    return () => {
      cancelled = true
      clearTimeout(timer)
      stopStream(stream)
      streamRef.current = null
      readingsRef.current = []
      if (videoRef.current) videoRef.current.srcObject = null
    }
  }, [enabled, startCount])

  /** 지금 표정(최근 측정 평균). 얼굴을 못 찾았으면 null. */
  const readScores = useCallback((): ExpressionScores | null => {
    return averageScores(recentReadings(readingsRef.current, Date.now()))
  }, [])

  /** 권한을 허용한 뒤 "다시 확인하기"로 카메라를 다시 켠다. 새로 고침하면 아동 세션이 사라지므로 새로 고치지 않는다. */
  const restart = useCallback(() => {
    setStatus("starting")
    setFaceFound(false)
    setStartCount((count) => count + 1)
  }, [])

  return {
    status: enabled ? status : ("off" as const),
    faceFound: enabled && status === "ready" && faceFound,
    attachVideo,
    readScores,
    restart,
  }
}
