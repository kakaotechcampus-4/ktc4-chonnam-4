#!/usr/bin/env node
// 로컬 끝까지 돌려 보기용 아동 쪽 드라이버. 실제 백엔드 API 를 부른다(run-backend.ps1 로 띄운 서버, 대본 STT).
// 강사로 로그인해 입장 코드를 받고, 아동으로 입장해 퀴즈(최초 결합이면)를 풀고, 역할극을 시작해 답 대본을 한 줄씩 보낸다.
// 답마다 WAV 파일 안의 nrgo 조각에 글을 넣어 보내면 서버의 대본 STT 가 그 글을 아동 발화로 쓴다. 판단·응답은 실제 LLM 이 한다.
//
//   node scripts/local/roleplay-run.mjs --activity <활동 UUID> --child <아동 UUID> --answers scripts/local/answers/steady.json
//   (강사 계정은 환경변수 RR_EMAIL·RR_PASSWORD. 퀴즈는 정답을 모르므로 늘 첫 보기로 답하고, 틀리면 힌트 두 번 뒤 마지막 보기로 마친다.
//    그래서 첫 역할극 레벨은 문항 구성에 따라 정해진다. 시드가 L1~L3 를 모두 넣어 두어 어느 레벨이든 시작할 수 있다)
//
// 표준 출력에는 AI 응답 글과 보낸 대본만 찍는다. 토큰·쿠키·코드는 찍지 않는다. 대본은 합성 아동의 답이라 실제 아동 발화가 아니다.
import { readFile } from "node:fs/promises"
import { parseArgs } from "node:util"

const { values: args } = parseArgs({
  options: {
    api: { type: "string", default: "http://localhost:8080/api/v1" },
    activity: { type: "string" },
    child: { type: "string" },
    answers: { type: "string" },
    "start-wait": { type: "string", default: "30" },
  },
})
if (!args.activity || !args.child || !args.answers) {
  console.error("--activity, --child, --answers 가 필요하다")
  process.exit(2)
}
const email = process.env.RR_EMAIL
const password = process.env.RR_PASSWORD
if (!email || !password) {
  console.error("강사 계정 환경변수 RR_EMAIL·RR_PASSWORD 가 필요하다")
  process.exit(2)
}

/** 아주 작은 쿠키 보관소. 서버가 주는 JSESSIONID·XSRF-TOKEN 만 다룬다. */
class Client {
  constructor(label) {
    this.label = label
    this.cookies = new Map()
    this.bearer = null
  }

  async request(method, path, { body, form, key, expect = [200, 201], csrf = method !== "GET" } = {}) {
    const headers = { Accept: "application/json" }
    if (this.bearer) headers.Authorization = `Bearer ${this.bearer}`
    if (key) headers["Idempotency-Key"] = key
    if (csrf) {
      const token = await this.request("GET", "/csrf", { csrf: false })
      headers[token.headerName] = token.token
    }
    let payload
    if (form) payload = form
    else if (body !== undefined) {
      headers["Content-Type"] = "application/json"
      payload = JSON.stringify(body)
    }
    headers.Cookie = [...this.cookies].map(([k, v]) => `${k}=${v}`).join("; ")
    const res = await fetch(`${args.api}${path}`, { method, headers, body: payload })
    for (const cookie of res.headers.getSetCookie()) {
      const [pair] = cookie.split(";")
      const [name, value] = pair.split("=")
      this.cookies.set(name.trim(), value)
    }
    const text = await res.text()
    const json = text ? JSON.parse(text) : null
    if (!expect.includes(res.status)) {
      const code = json?.error?.code ?? "(본문 없음)"
      throw new Error(`${this.label} ${method} ${path} → ${res.status} ${code}`)
    }
    return json?.data ?? json
  }
}

/** 16kHz 모노 무음 0.1초 WAV 에 대본 조각(nrgo)을 붙인다. 서버 ScriptedSpeechToTextProvider 가 이 조각을 읽는다. */
function scriptedWav(text) {
  const chunk = (id, data) => {
    const pad = data.length % 2
    const out = Buffer.alloc(8 + data.length + pad)
    out.write(id, 0, "ascii")
    out.writeUInt32LE(data.length, 4)
    data.copy(out, 8)
    return out
  }
  const fmt = Buffer.alloc(16)
  fmt.writeUInt16LE(1, 0)
  fmt.writeUInt16LE(1, 2)
  fmt.writeUInt32LE(16000, 4)
  fmt.writeUInt32LE(32000, 8)
  fmt.writeUInt16LE(2, 12)
  fmt.writeUInt16LE(16, 14)
  const body = Buffer.concat([chunk("fmt ", fmt), chunk("data", Buffer.alloc(3200)), chunk("nrgo", Buffer.from(text, "utf8"))])
  const header = Buffer.alloc(12)
  header.write("RIFF", 0, "ascii")
  header.writeUInt32LE(4 + body.length, 4)
  header.write("WAVE", 8, "ascii")
  return Buffer.concat([header, body])
}

async function main() {
  // Windows 편집기가 붙이는 BOM 은 떼고 읽는다.
  const answers = JSON.parse((await readFile(args.answers, "utf8")).replace(/^﻿/, "")).answers
  const instructor = new Client("강사")
  const login = await instructor.request("POST", "/auth/sessions", { body: { email, password } })
  instructor.bearer = login.accessToken
  const code = await instructor.request("POST", `/children/${args.child}/access-codes`, { key: crypto.randomUUID() })

  const child = new Client("아동")
  await child.request("POST", "/child-access-sessions", { body: { accessCode: code.accessCode } })
  console.log("아동 입장")

  const detail = await instructor.request("GET", `/activities/${args.activity}`)
  if (detail.activity.activityType === "FIRST_COMBINED" && detail.activity.status !== "COMPLETED") {
    await solveQuiz(child)
  }

  // 최초 결합은 퀴즈가 끝난 뒤 서버가 커밋 뒤에 시나리오를 붙인다. 붙을 때까지 잠깐 기다린다.
  let started = null
  for (let i = 0; i < Number(args["start-wait"]); i++) {
    try {
      started = await child.request("POST", `/activities/${args.activity}/roleplay-sessions`, { key: crypto.randomUUID() })
      break
    } catch (error) {
      if (!String(error.message).includes("INITIAL_ROLEPLAY_NOT_READY")) throw error
      await new Promise((resolve) => setTimeout(resolve, 1000))
    }
  }
  if (!started) throw new Error("역할극을 시작하지 못했다(승인 시나리오가 없거나 퀴즈가 끝나지 않음)")
  console.log(`역할극 시작 session=${started.sessionId.slice(0, 8)} level 시나리오=${started.scenarioId.slice(0, 8)}`)
  for (const message of started.delivery.messages) console.log(`  AI: ${message.text}`)

  for (const [index, answer] of answers.entries()) {
    const status = await instructor.request("GET", `/activities/${args.activity}/status`)
    if (status.status === "COMPLETED") break
    const form = new FormData()
    form.set("audio", new Blob([scriptedWav(answer)], { type: "audio/wav" }), "answer.wav")
    const sentAt = Date.now()
    const reply = await child.request("POST", `/roleplay-sessions/${started.sessionId}/voice-inputs`, {
      form,
      key: crypto.randomUUID(),
      expect: [200, 202],
    })
    console.log(`${index + 1}. 아동: ${answer}`)
    for (const message of reply.messages ?? []) console.log(`   AI: ${message.text}`)
    console.log(`   (${reply.status}/${reply.action}, ${Math.round((Date.now() - sentAt) / 100) / 10}s)`)
  }
  const final = await instructor.request("GET", `/activities/${args.activity}/status`)
  console.log(`활동 상태: ${final.status}`)
}

/** 늘 첫 보기로 답한다. 틀리면 힌트 두 번 뒤 마지막 보기로 마친다(첫 응답은 바꿀 수 없다). */
async function solveQuiz(child) {
  const items = await child.request("GET", `/activities/${args.activity}/quiz-items`)
  for (const item of items) {
    // 내 감정 상황 문항은 화면이 카메라로 표정을 판정해 함께 보낸다. 드라이버는 표정이 맞았다고 보낸다.
    const camera =
      item.quizType === "SELF_EMOTION_SITUATION"
        ? { expressionMatchResult: "MATCH", cameraModelVersion: "local-driver/v1" }
        : {}
    let attempt = await child.request("PUT", `/activity-quiz-items/${item.activityQuizId}/attempt`, {
      body: { firstResponse: item.choices[0], ...camera },
    })
    if (attempt.status !== "FINALIZED") {
      for (let hint = 0; hint < 2; hint++) {
        await child.request("POST", `/activity-quiz-items/${item.activityQuizId}/hints`, { key: crypto.randomUUID() })
      }
      const last = item.choices[item.choices.length - 1]
      attempt = await child.request("PUT", `/activity-quiz-items/${item.activityQuizId}/attempt`, {
        body: { firstResponse: attempt.firstResponse, finalResponse: last, ...camera },
      })
    }
    console.log(`퀴즈 ${item.questionOrder}: 첫 응답 ${attempt.firstResponseCorrect ? "정답" : "오답"} (${attempt.status})`)
  }
  const result = await child.request("GET", `/activities/${args.activity}/status`)
  console.log(`퀴즈 뒤 활동 상태: ${result.status}`)
}

main().catch((error) => {
  console.error(String(error.message ?? error))
  process.exit(1)
})
