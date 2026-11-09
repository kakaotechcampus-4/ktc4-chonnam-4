import { act, screen, waitFor, within } from "@testing-library/react"
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest"
import { http, HttpResponse } from "msw"
import { router } from "@/router"
import { envelope, ROLEPLAY_REINPUT_NOTICE, ROLEPLAY_SCRIPT } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { CHILD_ACTIVITY_ID, enterAsChild, renderRoutes, seedChildActivity } from "@/test/render"

// 역할극 말로 답하기(API-RPL-04) 검사. 브라우저 마이크(getUserMedia·MediaRecorder)는 jsdom 에 없어 가짜로 바꾼다.
// 가짜 서버의 STT 는 음성 파일 바이트를 글자로 읽으므로, 아이가 "말한" 내용은 nextSpeech 로 정한다.
// jsdom 의 Blob·File·FormData 는 Node fetch(undici)가 읽지 못해 음성 바이트가 "undefined" 로 간다(브라우저에서는 문제없다).
// 그래서 이 파일에서는 셋 다 Node 의 것으로 바꿔 실제 브라우저처럼 음성 바이트가 그대로 가게 한다.
// Blob·File 은 node:buffer 에서 가져온다. Node 24(CI, .nvmrc)의 undici 는 Response.blob() 에서 꺼낸 생성자를 Blob 으로
// 인정하지 않고(FormData.set 이 TypeError), 가짜 서버의 request.formData() 가 전역(jsdom) File 로 파트를 만들다 실패한다.
// FormData 는 전역이 jsdom 것으로 바뀌어 있으므로, jsdom 이 바꾸지 않는 Node 의 Response 에서 꺼낸다.
// 앱 tsconfig 에는 Node 타입이 없어서 모듈 이름을 변수로 넘기고 필요한 모양만 적는다.
const NODE_BUFFER = "node:buffer"
const { Blob: NodeBlob, File: NodeFile } = (await import(/* @vite-ignore */ NODE_BUFFER)) as {
  Blob: typeof Blob
  File: typeof File
}
const NodeFormData = (await new Response(new URLSearchParams()).formData()).constructor

const ACTIVITY = CHILD_ACTIVITY_ID
const VOICE_INPUT_PATH = /\/api\/v1\/roleplay-sessions\/[^/]+\/voice-inputs$/

let nextSpeech = ""
const trackStop = vi.fn()
const getUserMedia = vi.fn<(constraints: MediaStreamConstraints) => Promise<MediaStream>>()

class FakeMediaRecorder extends EventTarget {
  static isTypeSupported = (type: string) => type.startsWith("audio/webm")
  state: RecordingState = "inactive"
  readonly mimeType: string

  constructor(_stream: MediaStream, options?: MediaRecorderOptions) {
    super()
    this.mimeType = options?.mimeType ?? "audio/webm"
  }

  start() {
    this.state = "recording"
  }

  stop() {
    if (this.state === "inactive") return
    this.state = "inactive"
    const data = new Event("dataavailable")
    Object.assign(data, { data: new Blob([nextSpeech], { type: this.mimeType }) })
    this.dispatchEvent(data)
    this.dispatchEvent(new Event("stop"))
  }
}

function fakeStream() {
  return { getTracks: () => [{ stop: trackStop }] } as unknown as MediaStream
}

function voiceRequests() {
  const urls: string[] = []
  server.events.on("request:start", ({ request }) => {
    if (VOICE_INPUT_PATH.test(new URL(request.url).pathname)) urls.push(request.url)
  })
  return urls
}

async function openRoleplay() {
  enterAsChild()
  seedChildActivity({ quizFinished: true })
  const { user } = renderRoutes(router.routes, `/child/roleplay/${ACTIVITY}`)
  await screen.findByRole("button", { name: "글자로 답할래요" })
  return { user }
}

async function speak(user: Awaited<ReturnType<typeof openRoleplay>>["user"], speech: string) {
  nextSpeech = speech
  // 앞 답을 보내는 동안에는 마이크가 잠긴다. 대답이 온 뒤에 누른다.
  const mic = screen.getByRole("button", { name: "말로 답하기" })
  await waitFor(() => expect(mic).toBeEnabled())
  await user.click(mic)
  await user.click(await screen.findByRole("button", { name: "다 말했어요" }))
}

beforeAll(() => {
  // jsdom 에는 scrollIntoView 가 없다. 역할극은 새 말풍선이 생길 때마다 맨 아래로 내린다.
  Element.prototype.scrollIntoView = vi.fn()
})

beforeEach(() => {
  nextSpeech = ""
  trackStop.mockReset()
  getUserMedia.mockReset().mockImplementation(async () => fakeStream())
  vi.stubGlobal("Blob", NodeBlob)
  vi.stubGlobal("File", NodeFile)
  vi.stubGlobal("FormData", NodeFormData)
  vi.stubGlobal("MediaRecorder", FakeMediaRecorder)
  Object.defineProperty(navigator, "mediaDevices", { configurable: true, value: { getUserMedia } })
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  Reflect.deleteProperty(navigator, "mediaDevices")
  server.events.removeAllListeners()
})

describe("역할극 말로 답하기", () => {
  it("녹음한 음성을 보내면 알아들은 말이 아이 말풍선으로, 대답이 거북이 말풍선으로 나온다", async () => {
    const requests = voiceRequests()
    const { user } = await openRoleplay()

    await speak(user, "다리가 아플 것 같아")

    expect(await screen.findByText(ROLEPLAY_SCRIPT[0])).toBeInTheDocument()
    expect(screen.getByText("“다리가 아플 것 같아”")).toBeInTheDocument()
    expect(screen.getByRole("img", { name: "말로 답함" })).toBeInTheDocument()
    expect(requests).toHaveLength(1)
    // 녹음이 끝나면 마이크를 닫는다.
    expect(trackStop).toHaveBeenCalled()
    expect(getUserMedia).toHaveBeenCalledWith(expect.objectContaining({ audio: expect.anything() }))
  })

  it("잘 안 들리면 깡총이가 다시 말해 달라고 하고, 3번째부터는 글자 입력을 권하되 마이크는 남긴다", async () => {
    const { user } = await openRoleplay()

    await speak(user, "   ")
    expect(await screen.findByText(ROLEPLAY_REINPUT_NOTICE)).toBeInTheDocument()
    expect(screen.queryByRole("textbox", { name: "답 적기" })).not.toBeInTheDocument()

    await speak(user, "")
    await speak(user, " ")

    const input = await screen.findByRole("textbox", { name: "답 적기" })
    expect(input).toHaveFocus()
    expect(screen.getByRole("button", { name: "말로 답하기" })).toBeEnabled()

    // 글자를 권한 뒤에도 다시 말로 답할 수 있다.
    await speak(user, "속상할 것 같아")
    expect(await screen.findByText(ROLEPLAY_SCRIPT[0])).toBeInTheDocument()
  })

  it("보내다 연결이 끊기면 다시 하기로 같은 키·같은 음성을 다시 보낸다", async () => {
    const sent: { key: string | null; audio: string }[] = []
    server.events.on("request:start", async ({ request }) => {
      if (!VOICE_INPUT_PATH.test(new URL(request.url).pathname)) return
      const audio = (await request.clone().formData()).get("audio") as unknown as Blob
      sent.push({ key: request.headers.get("Idempotency-Key"), audio: await audio.text() })
    })
    server.use(http.post("*/api/v1/roleplay-sessions/:sessionId/voice-inputs", () => HttpResponse.error(), { once: true }))
    const { user } = await openRoleplay()

    await speak(user, "친구가 아플 것 같아")
    await user.click(await screen.findByRole("button", { name: "다시 하기" }))

    expect(await screen.findByText(ROLEPLAY_SCRIPT[0])).toBeInTheDocument()
    expect(screen.getByText("“친구가 아플 것 같아”")).toBeInTheDocument()
    await waitFor(() => expect(sent).toHaveLength(2))
    expect(sent[1]).toEqual(sent[0])
    expect(sent[0].audio).toBe("친구가 아플 것 같아")
  })

  it("30초가 다 되면 녹음을 버리고 보내지 않는다", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval", "Date"] })
    const requests = voiceRequests()
    const { user } = await openRoleplay()
    nextSpeech = "끝까지 말하는 중"

    await user.click(screen.getByRole("button", { name: "말로 답하기" }))
    await screen.findByRole("button", { name: "다 말했어요" })
    act(() => vi.advanceTimersByTime(30_100))

    expect(await screen.findByText("시간이 다 됐어. 조금 짧게 다시 말해줄래?")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "다 말했어요" })).not.toBeInTheDocument()
    expect(trackStop).toHaveBeenCalled()
    expect(requests).toHaveLength(0)
  })

  it("마이크 권한을 막으면 깡총이가 알려 주고 글자 입력으로 바꾼다", async () => {
    getUserMedia.mockRejectedValue(new DOMException("denied", "NotAllowedError"))
    const { user } = await openRoleplay()

    await user.click(screen.getByRole("button", { name: "말로 답하기" }))

    expect(await screen.findByText(/마이크를 쓸 수 없어/)).toBeInTheDocument()
    expect(await screen.findByRole("textbox", { name: "답 적기" })).toBeInTheDocument()
    // 권한 거부는 다시 묻지 않는다.
    expect(getUserMedia).toHaveBeenCalledTimes(1)
  })

  it("블루투스 이어폰 전환처럼 마이크가 처음 한 번 안 열리면 잠깐 뒤 다시 열어 녹음을 이어 간다", async () => {
    vi.spyOn(console, "warn").mockImplementation(() => {})
    getUserMedia.mockRejectedValueOnce(new DOMException("Could not start audio source", "NotReadableError"))
    const { user } = await openRoleplay()

    await speak(user, "다리가 아플 것 같아")

    expect(await screen.findByText(ROLEPLAY_SCRIPT[0])).toBeInTheDocument()
    expect(getUserMedia).toHaveBeenCalledTimes(2)
    expect(screen.queryByText(/마이크를 찾지 못했어/)).not.toBeInTheDocument()
  })

  it("다시 열어도 안 되면 처리 옵션 없이 한 번 더 열어 보고, 그래도 안 되면 깡총이가 알려 준다", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {})
    getUserMedia.mockRejectedValue(new DOMException("Could not start audio source", "NotReadableError"))
    const { user } = await openRoleplay()

    await user.click(screen.getByRole("button", { name: "말로 답하기" }))

    expect(await screen.findByText(/마이크를 찾지 못했어/, {}, { timeout: 3000 })).toBeInTheDocument()
    expect(getUserMedia).toHaveBeenCalledTimes(3)
    expect(getUserMedia.mock.calls[2][0]).toEqual({ audio: true })
    expect(warn).toHaveBeenCalledWith(expect.stringContaining("NotReadableError"))
  })

  it("녹음을 지원하지 않는 브라우저에서는 마이크 버튼을 누를 수 없다", async () => {
    vi.stubGlobal("MediaRecorder", undefined)
    await openRoleplay()

    expect(screen.getByRole("button", { name: "말로 답하기" })).toBeDisabled()
  })

  it("음성 파일은 multipart audio 파트 하나로, 형식과 함께 보낸다", async () => {
    let part: FormDataEntryValue | null = null
    let contentType: string | null = null
    server.events.on("request:start", async ({ request }) => {
      if (!VOICE_INPUT_PATH.test(new URL(request.url).pathname)) return
      contentType = request.headers.get("Content-Type")
      part = (await request.clone().formData()).get("audio")
    })
    const { user } = await openRoleplay()

    await speak(user, "친구가 아파")
    await screen.findByText(ROLEPLAY_SCRIPT[0])

    expect(contentType).toMatch(/^multipart\/form-data; boundary=/)
    expect(part).toBeInstanceOf(Blob)
    const audio = part as unknown as File
    expect(audio.type).toMatch(/^audio\/webm/)
    expect(audio.name).toBe("voice.webm")
  })
})

describe("역할극 화면 안의 아이 말풍선", () => {
  it("글자 답과 말 답이 각자 다른 아이콘으로 보인다", async () => {
    const { user } = await openRoleplay()

    await speak(user, "아플 것 같아")
    await screen.findByText(ROLEPLAY_SCRIPT[0])
    await user.click(screen.getByRole("button", { name: "글자로 답할래요" }))
    await user.type(screen.getByRole("textbox", { name: "답 적기" }), "속상해{Enter}")
    await screen.findByText(ROLEPLAY_SCRIPT[1])

    const voiceBubble = screen.getByText("“아플 것 같아”").parentElement as HTMLElement
    const textBubble = screen.getByText("“속상해”").parentElement as HTMLElement
    expect(within(voiceBubble).getByRole("img", { name: "말로 답함" })).toBeInTheDocument()
    expect(within(textBubble).getByRole("img", { name: "글자로 답함" })).toBeInTheDocument()
  })
})

describe("거북이 대사 음성(TTS)", () => {
  const SPOKEN_REPLY = "와, 회전목마를 타고 싶구나!"
  const AUDIO_URL = "data:audio/mpeg;base64,AAAA"
  const played: string[] = []
  const paused: string[] = []

  class FakeAudio {
    readonly src: string
    constructor(src: string) {
      this.src = src
    }
    play() {
      played.push(this.src)
      return Promise.resolve()
    }
    pause() {
      paused.push(this.src)
    }
  }

  beforeEach(() => {
    played.length = 0
    paused.length = 0
    vi.stubGlobal("Audio", FakeAudio)
    server.use(
      http.post(
        "*/api/v1/roleplay-sessions/:sessionId/voice-inputs",
        () =>
          HttpResponse.json(
            envelope({
              status: "DELIVERED",
              action: "NONE",
              messages: [{ speakerRole: "DIALOGUE", text: SPOKEN_REPLY, speech: { available: true, url: AUDIO_URL } }],
              acceptedInput: "회전목마 탈래",
              checkpointVersion: 1,
              replayed: false,
            })
          ),
        { once: true }
      )
    )
  })

  it("서버가 음성을 보내면 대답이 오자마자 재생하고, 다시 듣기로 또 들을 수 있다", async () => {
    const { user } = await openRoleplay()

    await speak(user, "회전목마 탈래")

    expect(await screen.findByText(SPOKEN_REPLY)).toBeInTheDocument()
    await waitFor(() => expect(played).toEqual([AUDIO_URL]))

    await user.click(screen.getByRole("button", { name: "다시 듣기" }))
    expect(played).toEqual([AUDIO_URL, AUDIO_URL])
  })

  it("말하기를 시작하면 울리던 대사를 멈춰 녹음에 섞이지 않게 한다", async () => {
    const { user } = await openRoleplay()
    await speak(user, "회전목마 탈래")
    await waitFor(() => expect(played).toHaveLength(1))

    await user.click(screen.getByRole("button", { name: "말로 답하기" }))

    expect(paused).toEqual([AUDIO_URL])
  })

  it("음성이 없는 대사에는 다시 듣기 버튼이 없다", async () => {
    await openRoleplay()

    expect(screen.queryByRole("button", { name: "다시 듣기" })).not.toBeInTheDocument()
  })
})
