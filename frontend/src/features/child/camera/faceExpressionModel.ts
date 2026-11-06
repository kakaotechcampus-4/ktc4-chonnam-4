import type { ExpressionScores } from "./expressionJudge"

/**
 * 브라우저 안에서 도는 표정 인식 모델 (vladmandic/face-api, TensorFlow.js).
 * 카메라 영상은 기기 밖으로 나가지 않는다. 서버에는 판정 결과와 아래 모델 버전만 보낸다(API-QUIZ-04).
 *
 * 라이브러리(약 1.3MB)는 내 감정 문항에서 카메라를 켤 때만 내려받도록 동적으로 불러온다.
 * 모델 가중치는 public/models/face-api 에 두었다(npm 패키지 model/ 폴더에서 복사, 약 0.5MB).
 */

/** package.json 의 @vladmandic/face-api 버전과 같아야 한다(테스트가 확인한다). */
export const FACE_API_VERSION = "1.7.15"

/**
 * 서버에 함께 저장하는 모델 버전(최대 100자). 같은 버전이면 같은 기준으로 판정한 결과라는 뜻이다.
 * 라이브러리·모델·판정 기준(최소 확률 50%)이 바뀌면 이 값도 바꾼다.
 */
export const CAMERA_MODEL_VERSION = `face-api-${FACE_API_VERSION}/tiny-face-expression/min50`

const MODEL_URL = `${import.meta.env.BASE_URL}models/face-api`

export type ExpressionDetector = {
  /** 영상에서 얼굴 하나를 찾아 표정 확률을 준다. 얼굴이 없거나 영상이 아직 준비되지 않았으면 null. */
  detect(video: HTMLVideoElement): Promise<ExpressionScores | null>
}

type TfBackend = {
  setBackend(name: string): Promise<boolean>
  ready(): Promise<void>
}

async function createDetector(): Promise<ExpressionDetector> {
  const faceapi = await import("@vladmandic/face-api")
  // 번들에는 webgl·wasm·cpu 백엔드가 함께 들어 있다. 고르지 않으면 wasm 파일까지 찾다가(배포하지 않음) 실패할 수 있어
  // webgl 을 먼저 쓰고, 안 되는 기기면 느리지만 cpu 로 돈다.
  const tf = faceapi.tf as unknown as TfBackend
  const webgl = await tf.setBackend("webgl").catch(() => false)
  if (!webgl) await tf.setBackend("cpu")
  await tf.ready()
  await Promise.all([
    faceapi.nets.tinyFaceDetector.loadFromUri(MODEL_URL),
    faceapi.nets.faceExpressionNet.loadFromUri(MODEL_URL),
  ])
  const options = new faceapi.TinyFaceDetectorOptions({ inputSize: 320, scoreThreshold: 0.3 })

  return {
    async detect(video) {
      if (video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA || video.videoWidth === 0) return null
      const result = await faceapi.detectSingleFace(video, options).withFaceExpressions()
      if (!result) return null
      const e = result.expressions
      return {
        neutral: e.neutral,
        happy: e.happy,
        sad: e.sad,
        angry: e.angry,
        fearful: e.fearful,
        disgusted: e.disgusted,
        surprised: e.surprised,
      }
    },
  }
}

let loading: Promise<ExpressionDetector> | null = null

/** 모델은 한 번만 불러와 문항끼리 함께 쓴다. 실패하면 다음에 다시 시도할 수 있게 비운다. */
export function loadExpressionDetector(): Promise<ExpressionDetector> {
  if (!loading) {
    loading = createDetector().catch((error: unknown) => {
      loading = null
      throw error
    })
  }
  return loading
}
