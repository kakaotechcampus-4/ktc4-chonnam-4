/**
 * 역할극 말로 답하기의 브라우저 녹음 (C-RP-02, API-RPL-04).
 * getUserMedia 로 마이크를 열고 MediaRecorder 로 한 번 녹음한다. stop() 은 녹음한 음성(Blob)을, cancel() 은 버린다.
 * 어느 쪽이든 끝나면 마이크를 바로 닫아 브라우저의 녹음 표시가 남지 않게 한다.
 * 서버(AudioFormat)는 WebM·OGG(Opus)·WAV·MP3·MP4(AAC) 를 받는다. Chrome·Edge·Firefox 는 webm/opus, Safari 는 mp4 로 녹음된다.
 */

/** 마이크를 쓸 수 없는 이유. unsupported: 브라우저 미지원·http 주소, denied: 아이·보호자가 권한을 막음, failed: 그 밖(장치 없음 등). */
export type VoiceRecorderError = "unsupported" | "denied" | "failed"

export class VoiceRecorderUnavailable extends Error {
  readonly reason: VoiceRecorderError

  constructor(reason: VoiceRecorderError) {
    super(`voice recorder unavailable: ${reason}`)
    this.name = "VoiceRecorderUnavailable"
    this.reason = reason
  }
}

export type VoiceRecording = {
  /** 녹음을 끝내고 녹음한 음성을 돌려준다. */
  stop: () => Promise<Blob>
  /** 녹음을 버린다(시간 초과·화면 떠남). 여러 번 불러도 된다. */
  cancel: () => void
}

// 앞에 있는 것부터 쓴다. 서버가 받는 형식만 둔다.
const PREFERRED_MIME_TYPES = ["audio/webm;codecs=opus", "audio/webm", "audio/ogg;codecs=opus", "audio/mp4"]

// 음성 인식에는 충분하고, 30초를 녹음해도 서버 업로드 상한(2MiB)보다 훨씬 작다.
const AUDIO_BITS_PER_SECOND = 64_000

/** 이 브라우저에서 녹음할 수 있는지. 마이크는 https(또는 localhost)에서만 열린다. */
export function canRecordVoice(): boolean {
  return (
    typeof window !== "undefined" &&
    typeof window.MediaRecorder !== "undefined" &&
    typeof navigator !== "undefined" &&
    typeof navigator.mediaDevices?.getUserMedia === "function"
  )
}

function pickMimeType(): string | undefined {
  if (typeof MediaRecorder.isTypeSupported !== "function") return undefined
  return PREFERRED_MIME_TYPES.find((type) => MediaRecorder.isTypeSupported(type))
}

// 마이크를 처음 열 때 블루투스 이어폰이 음악용(A2DP)에서 통화용(HFP, "머리에 거는 수화기")으로 바뀌는 사이에
// NotReadableError·AbortError 가 한 번 나고, 다시 열면 되는 일이 흔하다. 이만큼 기다렸다 한 번 더 연다.
const MIC_RETRY_DELAY_MS = 500

const PREFERRED_AUDIO: MediaTrackConstraints = { echoCancellation: true, noiseSuppression: true, channelCount: 1 }

function isDenied(error: unknown) {
  return error instanceof DOMException && (error.name === "NotAllowedError" || error.name === "SecurityError")
}

// DOMException 은 환경에 따라 Error 를 상속하지 않으므로 name 만 본다.
function errorName(error: unknown) {
  if (error && typeof error === "object" && "name" in error && typeof error.name === "string") return error.name
  return typeof error
}

function toUnavailable(error: unknown): VoiceRecorderUnavailable {
  return new VoiceRecorderUnavailable(isDenied(error) ? "denied" : "failed")
}

function wait(ms: number) {
  return new Promise<void>((resolve) => setTimeout(resolve, ms))
}

/**
 * 마이크를 연다. 권한 거부는 바로 알린다. 그 밖의 실패(장치 전환 중·다른 앱이 사용 중 등)는
 * 잠깐 뒤 같은 설정으로 한 번, 그래도 안 되면 처리 옵션 없이(audio: true) 한 번 더 연다.
 * 원인을 현장에서 알 수 있게 실패한 오류 이름만 콘솔에 남긴다(음성·장치 이름은 남기지 않는다).
 */
async function openMicrophone(): Promise<MediaStream> {
  const attempts: (MediaTrackConstraints | true)[] = [PREFERRED_AUDIO, PREFERRED_AUDIO, true]
  let lastError: unknown
  for (let i = 0; i < attempts.length; i++) {
    if (i > 0) await wait(MIC_RETRY_DELAY_MS)
    try {
      return await navigator.mediaDevices.getUserMedia({ audio: attempts[i] })
    } catch (error) {
      lastError = error
      console.warn(`[voice] 마이크 열기 실패 (${i + 1}/${attempts.length}): ${errorName(error)}`)
      if (isDenied(error)) break
    }
  }
  throw toUnavailable(lastError)
}

/** 녹음기를 만들고 시작한다. 지정한 형식·비트레이트로 실패하면 브라우저 기본값으로 한 번 더 시도한다. */
function startRecorder(stream: MediaStream, mimeType: string | undefined): MediaRecorder {
  const options: MediaRecorderOptions[] = [
    { ...(mimeType ? { mimeType } : {}), audioBitsPerSecond: AUDIO_BITS_PER_SECOND },
    {},
  ]
  let lastError: unknown
  for (const option of options) {
    try {
      const recorder = new MediaRecorder(stream, option)
      recorder.start()
      return recorder
    } catch (error) {
      lastError = error
      console.warn(`[voice] 녹음 시작 실패: ${errorName(error)}`)
    }
  }
  throw toUnavailable(lastError)
}

/** 마이크를 열고 녹음을 시작한다. 처음이면 브라우저가 마이크 권한을 묻는다. 쓸 수 없으면 VoiceRecorderUnavailable 을 던진다. */
export async function startVoiceRecording(): Promise<VoiceRecording> {
  if (!canRecordVoice()) throw new VoiceRecorderUnavailable("unsupported")

  const stream = await openMicrophone()

  const releaseStream = () => stream.getTracks().forEach((track) => track.stop())

  let recorder: MediaRecorder
  const mimeType = pickMimeType()
  try {
    recorder = startRecorder(stream, mimeType)
  } catch (error) {
    releaseStream()
    throw error
  }

  const chunks: Blob[] = []
  recorder.addEventListener("dataavailable", (event) => {
    if (event.data.size > 0) chunks.push(event.data)
  })

  let finished = false
  const finish = () => {
    finished = true
    releaseStream()
  }

  return {
    stop: () =>
      new Promise<Blob>((resolve) => {
        if (finished || recorder.state === "inactive") {
          finish()
          resolve(new Blob([], { type: recorder.mimeType || mimeType || "audio/webm" }))
          return
        }
        // 마지막 조각(dataavailable)은 stop 이벤트보다 먼저 온다.
        recorder.addEventListener(
          "stop",
          () => {
            finish()
            // 서버는 multipart 파트의 MIME 으로 형식을 고르므로 Blob 에 형식을 꼭 붙인다.
            const type = recorder.mimeType || mimeType || chunks[0]?.type || "audio/webm"
            resolve(new Blob(chunks, { type }))
          },
          { once: true }
        )
        recorder.stop()
      }),
    cancel: () => {
      if (finished) return
      chunks.length = 0
      if (recorder.state !== "inactive") recorder.stop()
      finish()
    },
  }
}
