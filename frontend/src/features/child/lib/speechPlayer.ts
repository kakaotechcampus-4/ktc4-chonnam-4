/**
 * 역할극 캐릭터 대사(TTS) 재생. 화면에 하나의 오디오만 울리도록 마지막 것만 재생한다.
 * 서버가 보낸 음성 주소(data URL 등)를 그대로 재생하며 저장하지 않는다.
 * 브라우저 자동 재생 정책 때문에 재생이 막히면 false 를 돌려준다(아이가 "다시 듣기"를 누르면 된다).
 */
let current: HTMLAudioElement | null = null

function canPlayAudio() {
  return typeof window !== "undefined" && typeof window.Audio === "function"
}

/** 지금 울리는 대사를 멈춘다. 녹음을 시작할 때 캐릭터 목소리가 마이크에 섞이지 않게 부른다. */
export function stopSpeech() {
  if (!current) return
  try {
    current.pause()
  } catch {
    // jsdom 등 재생을 지원하지 않는 환경
  }
  current = null
}

/** 대사 음성을 처음부터 재생한다. 이전 대사는 멈춘다. */
export async function playSpeech(url: string): Promise<boolean> {
  if (!canPlayAudio()) return false
  stopSpeech()
  const audio = new window.Audio(url)
  current = audio
  try {
    await audio.play()
    return true
  } catch {
    if (current === audio) current = null
    return false
  }
}
