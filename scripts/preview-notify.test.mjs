// preview-notify.mjs 자체 검사(node:test). bash scripts/verify.sh workflows 에 포함.
import assert from "node:assert/strict"
import { test } from "node:test"
import { compose, kst, parseState, payload, plain, send } from "./preview-notify.mjs"

const DOMAIN = "d1abcdefghijkl.cloudfront.net"
const SHA = "c3d12f3313a95f25162a1503be624f3b0617f8c0"
const RUN = "https://github.com/o/r/actions/runs/1"
const HOOK = "https://discord.com/api/webhooks/123/abc_DEF-9"

const STATE_PR65 = parseState(
  [
    "SERVER_STATE=running",
    "STATUS_MODE=preview",
    "STATUS_PR=65",
    `STATUS_RELEASE=${SHA}`,
    "LEASE_ACTIVE=1",
    "LEASE_PR=65",
    "LEASE_BY=bji0319",
    "LEASE_UNTIL=2026-10-13T17:05:00Z",
    "EDGE_API=200",
    "EDGE_PAGE=200",
    "EDGE_RELEASE_MATCH=yes",
  ].join("\n"),
)

const preview = (over = {}) => ({
  task: "PR 미리보기 올리기",
  pr: "65",
  title: "feat: 가입 동의 저장",
  author: "bji0319",
  sha: SHA,
  domain: DOMAIN,
  powerOnJob: "success",
  releaseJob: "success",
  releaseResult: "ok",
  runUrl: RUN,
  state: STATE_PR65,
  ...over,
})

test("KEY=값 줄을 읽고 같은 키는 마지막 값", () => {
  assert.deepEqual(parseState("A=1\r\nB=x=y\n\nA=2"), { A: "2", B: "x=y" })
})

test("UTC 시각을 KST 로", () => {
  assert.equal(kst("2026-10-13T17:05:00Z"), "10/14 02:05")
  assert.equal(kst(""), "")
})

test("남이 쓴 제목은 서식·멘션·링크로 풀리지 않는다", () => {
  assert.equal(plain("@everyone [x](http://e) `a`"), "\\@everyone \\[x\\](http\\://e) \\`a\\`")
  assert.equal(plain("a\nb"), "a b")
  assert.equal(plain("가".repeat(90)).length, 81)
})

test("미리보기가 뜨면 주소·자리·확인 결과를 알린다", () => {
  const text = compose(preview())
  assert.match(text, /^🟢 \*\*PR #65 미리보기가 떴습니다\*\*/)
  assert.match(text, /^> feat\\: 가입 동의 저장 · bji0319$/m)
  assert.ok(text.split("\n").includes(`주소: <https://${DOMAIN}>`))
  assert.match(text, /커밋 `c3d12f3` · 자리 10\/14 02:05\(KST\)까지 · 화면 ✅ · API ✅ · 이 커밋 ✅/)
  assert.match(text, /\[실행 보고서\]\(<https:\/\/github\.com\/o\/r\/actions\/runs\/1>\)/)
})

test("push 로 다시 올리면 다시 올렸다고 알린다", () => {
  assert.match(compose(preview({ task: "PR 미리보기 다시 올리기" })), /^🔁 \*\*PR #65 미리보기를 새 커밋으로 다시 올렸습니다\*\*/)
})

test("다른 PR 이 자리를 쓰면 누가 언제까지인지와 다시 띄우는 법", () => {
  const text = compose(preview({ pr: "62", releaseResult: "" }))
  assert.match(text, /^⏸️ \*\*PR #62 미리보기를 못 띄웠습니다\*\* — 서버를 PR #65\(bji0319\)가 10\/14 02:05\(KST\)까지 쓰고 있습니다/)
  assert.match(text, /PR #62 에서 `preview` 라벨을 뗐다가 다시 붙이면/)
  assert.doesNotMatch(text, /주소:/)
})

test("서버가 꺼져 있어 push 를 건너뛰면 그렇게 알린다", () => {
  const text = compose(preview({ task: "PR 미리보기 다시 올리기", pr: "62", releaseResult: "", state: { SERVER_STATE: "stopped" } }))
  assert.match(text, /^⏸️ \*\*PR #62 새 커밋은 올리지 않았습니다\*\* — 서버가 꺼져 있습니다/)
})

test("테스트 주소를 쓰는 중이라 미루면 실패가 아니라 미뤘다고 알린다", () => {
  const text = compose(preview({ task: "PR 미리보기 다시 올리기", releaseResult: "busy" }))
  assert.match(text, /^⏸️ \*\*PR #65 미리보기를 미뤘습니다\*\* — 테스트 주소를 누가 쓰는 중/)
  assert.match(text, /`preview` 라벨을 뗐다가 다시 붙이면/)
  assert.doesNotMatch(text, /주소:/)
})

test("배포가 되돌려지면 실패와 이유", () => {
  const text = compose(preview({ releaseResult: "rolled_back", releaseJob: "failure", state: { SERVER_STATE: "running", LEASE_ACTIVE: "0" } }))
  assert.match(text, /^❌ \*\*PR #65 미리보기 실패\*\* — 새 버전이 상태 확인/)
  assert.match(text, /실행 보고서/)
})

test("서버를 켜지 못하면 실패", () => {
  assert.match(compose(preview({ powerOnJob: "failure", releaseJob: "skipped", releaseResult: "", state: {} })), /서버를 켜지 못했습니다/)
})

test("주소 모양이 틀리면 주소를 넣지 않는다", () => {
  const text = compose(preview({ domain: "evil.example.com" }))
  assert.doesNotMatch(text, /evil/)
  assert.match(text, /주소: 읽지 못했습니다/)
})

test("미리보기를 끝내면 알리고, 끝낼 것이 없었으면 보내지 않는다", () => {
  const ended = compose({ task: "미리보기 끝내기", pr: "65", sha: SHA, releaseResult: "ok", by: "bji0319", state: {} })
  assert.match(ended, /^⚪ \*\*PR #65 미리보기를 끝냈습니다\*\* — 주소에는 이제 develop 최신 `c3d12f3` 이 떠 있습니다/)
  assert.equal(compose({ task: "미리보기 끝내기", pr: "65", releaseResult: "not_this_pr", releaseJob: "success" }), null)
  assert.equal(compose({ task: "미리보기 끝내기", pr: "65", releaseResult: "", releaseJob: "success" }), null)
})

test("PR 을 닫았는데 테스트 주소를 쓰는 중이라 끝내기를 미루면 그렇게 알린다", () => {
  const text = compose({ task: "미리보기 끝내기", pr: "65", releaseResult: "busy", releaseJob: "success", runUrl: RUN })
  assert.match(text, /^⏸️ \*\*PR #65 미리보기 끝내기를 미뤘습니다\*\* — 테스트 주소를 누가 쓰는 중이라 주소에는 아직 PR #65 미리보기가 떠 있습니다/)
  assert.match(text, /Run workflow → `미리보기 끝내기`/)
})

test("켜기·끄기는 누른 사람과 함께, 상태 보기는 보내지 않는다", () => {
  const on = compose({
    task: "켜기",
    by: "LearnToThink777",
    domain: DOMAIN,
    powerOnJob: "success",
    releaseJob: "success",
    releaseResult: "ok",
    state: { SERVER_STATE: "running", STATUS_MODE: "develop", STATUS_RELEASE: SHA, EDGE_API: "200", EDGE_PAGE: "200", EDGE_RELEASE_MATCH: "yes" },
  })
  assert.match(on, /^🟢 \*\*서버를 켰습니다\*\* — develop 최신 `c3d12f3` · 누른 사람 LearnToThink777/)
  assert.match(on, /주소: </)
  assert.equal(compose({ task: "끄기", by: "x", powerOffJob: "success", state: { SERVER_STATE: "stopped" } }), "⚫ **서버를 껐습니다** · 누른 사람 x")
  assert.match(compose({ task: "끄기", powerOffJob: "failure", state: STATE_PR65 }), /끄지 못했습니다\*\* — PR #65\(bji0319\)가 10\/14 02:05\(KST\)까지 미리보기 중/)
  assert.equal(compose({ task: "상태 보기", state: STATE_PR65 }), null)
})

test("본문은 멘션을 풀지 않는다", () => {
  assert.deepEqual(payload("x").allowed_mentions, { parse: [] })
})

test("보내기: 429 는 기다렸다 다시, 실패해도 웹후크 주소를 메시지에 넣지 않는다", async () => {
  const calls = []
  const replies = [
    { ok: false, status: 429, json: async () => ({ retry_after: 0.1 }) },
    { ok: true, status: 200 },
  ]
  await send(HOOK, payload("hi"), async (url, init) => {
    calls.push({ url, body: JSON.parse(init.body) })
    return replies.shift()
  }, async () => {})
  assert.equal(calls.length, 2)
  assert.equal(calls[0].url, `${HOOK}?wait=true`)
  assert.equal(calls[0].body.content, "hi")

  await assert.rejects(
    send(HOOK, payload("hi"), async () => ({ ok: false, status: 404 })),
    (error) => /HTTP 404/.test(error.message) && !error.message.includes("abc_DEF"),
  )
  await assert.rejects(send("https://example.com/hook", payload("hi"), async () => ({ ok: true })), /모양이 틀렸다/)
})
