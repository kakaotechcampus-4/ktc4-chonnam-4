// Dev server 결과(PR 미리보기·켜기·끄기·끝내기)를 팀 디스코드 채널(#pr-주소)에 한 번 알린다. dev-server.yml 의 status 잡이 부른다.
//
//   node scripts/preview-notify.mjs <상태 파일>
//
// 상태 파일: 같은 잡의 "Report current state" 가 남긴 KEY=값 줄(server-power.sh state · 서버의 status.sh 출력).
// 나머지는 환경변수: WEBHOOK(secret DISCORD_PREVIEW_WEBHOOK)·DOMAIN·TASK·PR·TITLE·AUTHOR·BY·SHA·
//   POWER_ON_JOB·RELEASE_JOB·RELEASE_RESULT·POWER_OFF_JOB, 실행 주소는 GITHUB_SERVER_URL·GITHUB_REPOSITORY·GITHUB_RUN_ID.
// 테스트 주소는 디스코드 메시지에만 넣는다(팀만 보는 채널). 로그·Summary 에는 찍지 않는다(공개 레포).
// 알릴 것이 없으면(상태 보기, 끝낼 미리보기가 없었음) 보내지 않는다. 보내지 못해도 실행을 실패로 만들지 않는다(경고만).
import { readFileSync } from "node:fs"
import { pathToFileURL } from "node:url"

const WEBHOOK = /^https:\/\/(?:discord|discordapp)\.com\/api\/webhooks\/[0-9]+\/[A-Za-z0-9_-]+$/
const DOMAIN = /^[a-z0-9]+\.cloudfront\.net$/
const OK = new Set(["ok", "unchanged"])
const PREVIEW = new Set(["PR 미리보기 올리기", "PR 미리보기 다시 올리기"])
const FAILED = {
  rolled_back: "새 버전이 상태 확인(API·화면·커밋)을 통과하지 못해 직전 상태로 되돌렸습니다",
  rollback_failed: "되돌리기도 실패했습니다. 서버를 확인해야 합니다",
  failed: "배포가 실패했습니다",
  timeout: "시간이 초과됐습니다",
  needs_host_setup: "서버 기본 설정이 필요합니다(Dev server → 켜기 또는 Host setup)",
}

/** KEY=값 줄을 객체로(같은 키는 마지막 값). */
export function parseState(text) {
  const state = {}
  for (const line of String(text ?? "").split(/\r?\n/)) {
    const at = line.indexOf("=")
    if (at > 0) state[line.slice(0, at)] = line.slice(at + 1)
  }
  return state
}

/** ISO(UTC) → "10/14 02:05"(KST). 읽지 못하면 "". */
export function kst(iso) {
  const time = Date.parse(iso ?? "")
  if (Number.isNaN(time)) return ""
  const d = new Date(time + 9 * 3600_000)
  const two = (n) => String(n).padStart(2, "0")
  return `${two(d.getUTCMonth() + 1)}/${two(d.getUTCDate())} ${two(d.getUTCHours())}:${two(d.getUTCMinutes())}`
}

/** PR 제목·아이디처럼 남이 쓴 글을 한 줄로 자르고 디스코드 서식·멘션·링크로 풀리지 않게 한다. */
export function plain(text, max = 80) {
  const chars = [...String(text ?? "").replace(/\s+/g, " ").trim()]
  const cut = chars.slice(0, max).join("") + (chars.length > max ? "…" : "")
  return cut.replace(/[\\`*_~|<>[\]#@:]/g, "\\$&")
}

const mark = (value, good) => (value === good ? "✅" : "❌")
const short = (sha) => (/^[0-9a-f]{7,40}$/.test(sha ?? "") ? `\`${sha.slice(0, 7)}\`` : "")

function address(domain) {
  return DOMAIN.test(domain ?? "") ? `주소: <https://${domain}>` : "주소: 읽지 못했습니다(실행 보고서를 봅니다)"
}

function checks(state) {
  if (!state.EDGE_API) return ""
  return `화면 ${mark(state.EDGE_PAGE, "200")} · API ${mark(state.EDGE_API, "200")} · 이 커밋 ${mark(state.EDGE_RELEASE_MATCH, "yes")}`
}

function holder(state) {
  const until = kst(state.LEASE_UNTIL)
  return `PR #${plain(state.LEASE_PR, 10)}(${plain(state.LEASE_BY, 39)})${until ? `가 ${until}(KST)까지` : "가"}`
}

/**
 * 보낼 메시지(디스코드 마크다운)를 만든다. 알릴 것이 없으면 null.
 * @param {{task?: string, pr?: string, title?: string, author?: string, by?: string, sha?: string, domain?: string,
 *   powerOnJob?: string, releaseJob?: string, releaseResult?: string, powerOffJob?: string, runUrl?: string,
 *   state?: Record<string, string>}} input
 */
export function compose(input) {
  const { task = "", sha = "", domain = "", powerOnJob = "", releaseJob = "", powerOffJob = "", runUrl = "" } = input
  const result = input.releaseResult ?? ""
  const state = input.state ?? {}
  const pr = /^[0-9]{1,6}$/.test(input.pr ?? "") ? input.pr : ""
  const running = state.SERVER_STATE === "running"
  const report = /^https:\/\//.test(runUrl) ? `[실행 보고서](<${runUrl}>)` : ""
  const about = input.title || input.author ? `> ${plain(input.title)}${input.author ? ` · ${plain(input.author, 39)}` : ""}` : ""
  const by = input.by ? ` · 누른 사람 ${plain(input.by, 39)}` : ""
  const lines = (...items) => items.filter(Boolean).join("\n")
  const failed = (head) => lines(`❌ **${head}** — ${FAILED[result] ?? "실패했습니다"}`, about, report)

  if (PREVIEW.has(task)) {
    if (!pr) return null
    if (OK.has(result)) {
      const head =
        result === "unchanged"
          ? `PR #${pr} 미리보기가 이미 이 커밋으로 떠 있습니다`
          : task === "PR 미리보기 다시 올리기"
            ? `PR #${pr} 미리보기를 새 커밋으로 다시 올렸습니다`
            : `PR #${pr} 미리보기가 떴습니다`
      const until = state.LEASE_PR === pr ? kst(state.LEASE_UNTIL) : ""
      const facts = [short(sha) && `커밋 ${short(sha)}`, until && `자리 ${until}(KST)까지`, checks(state)].filter(Boolean)
      return lines(
        `${task === "PR 미리보기 다시 올리기" ? "🔁" : "🟢"} **${head}**`,
        about,
        address(domain),
        facts.join(" · "),
        "다 봤으면 PR 에서 `preview` 라벨을 떼 주세요(다음 PR 이 쓸 수 있게). 안 쓰면 서버가 저절로 꺼집니다.",
        report,
      )
    }
    if (powerOnJob === "failure") return lines(`❌ **PR #${pr} 미리보기 실패** — 서버를 켜지 못했습니다`, about, report)
    if (state.LEASE_ACTIVE === "1" && state.LEASE_PR && state.LEASE_PR !== pr) {
      return lines(
        `⏸️ **PR #${pr} 미리보기를 못 띄웠습니다** — 서버를 ${holder(state)} 쓰고 있습니다`,
        about,
        `그 뒤에 PR #${pr} 에서 \`preview\` 라벨을 뗐다가 다시 붙이면 뜹니다.`,
      )
    }
    if (result === "busy") {
      return lines(
        `⏸️ **PR #${pr} 미리보기를 미뤘습니다** — 테스트 주소를 누가 쓰는 중이라 기다렸지만 계속 쓰였습니다`,
        about,
        `다 쓴 뒤 PR #${pr} 에서 \`preview\` 라벨을 뗐다가 다시 붙이면 기다리지 않고 바로 올립니다.`,
        report,
      )
    }
    if (!running || result === "server_off") {
      return lines(
        `⏸️ **PR #${pr} 새 커밋은 올리지 않았습니다** — 서버가 꺼져 있습니다(push 로는 서버를 켜지 않습니다)`,
        about,
        `보려면 PR #${pr} 에서 \`preview\` 라벨을 뗐다가 다시 붙입니다(서버를 켜고 올립니다).`,
      )
    }
    if (result in FAILED || releaseJob === "failure") return failed(`PR #${pr} 미리보기 실패`)
    return lines(`⚠️ **PR #${pr} 미리보기 결과를 알 수 없습니다**`, about, report)
  }

  if (task === "미리보기 끝내기") {
    if (result === "ok") {
      const who = pr ? `PR #${pr} 미리보기를` : "미리보기를"
      return lines(`⚪ **${who} 끝냈습니다** — 주소에는 이제 develop 최신${short(sha) ? ` ${short(sha)}` : ""} 이 떠 있습니다${by}`, report)
    }
    if (result === "busy") {
      const who = pr ? `PR #${pr} 미리보기` : "미리보기"
      return lines(
        `⏸️ **${who} 끝내기를 미뤘습니다** — 테스트 주소를 누가 쓰는 중이라 주소에는 아직 ${who}가 떠 있습니다`,
        "다 쓴 뒤 Actions → Dev server → Run workflow → `미리보기 끝내기` 를 누르면 기다리지 않고 바로 끝냅니다.",
        report,
      )
    }
    if (result in FAILED || releaseJob === "failure") return failed(pr ? `PR #${pr} 미리보기 끝내기 실패` : "미리보기 끝내기 실패")
    return null // 끝낼 미리보기가 없었다(다른 PR·develop 이 떠 있음, 서버 꺼짐)
  }

  if (task === "켜기") {
    if (powerOnJob === "failure" || !running) return lines(`❌ **서버 켜기 실패**${by}`, report)
    if (result in FAILED || releaseJob === "failure") return failed(`서버는 켰지만 develop 배포 실패${by}`)
    const now =
      state.STATUS_MODE === "preview"
        ? `지금은 PR #${plain(state.STATUS_PR, 10)} 미리보기가 떠 있습니다`
        : `develop 최신${short(state.STATUS_RELEASE || sha) ? ` ${short(state.STATUS_RELEASE || sha)}` : ""}`
    return lines(`🟢 **서버를 켰습니다** — ${now}${by}`, address(domain), checks(state), "다 쓰면 Dev server → 끄기(안 쓰면 저절로 꺼집니다).", report)
  }

  if (task === "끄기") {
    if (powerOffJob === "success" && !running) return lines(`⚫ **서버를 껐습니다**${by}`)
    if (powerOffJob === "failure" || powerOffJob === "success") {
      const why = state.LEASE_ACTIVE === "1" ? ` — ${holder(state)} 미리보기 중입니다` : ""
      return lines(`❌ **서버를 끄지 못했습니다**${why}${by}`, report)
    }
    return null
  }

  return null // 상태 보기 · 안 쓰면 끄기
}

/** 디스코드 웹후크 본문. 멘션(@everyone·역할·사람)은 풀지 않는다. */
export function payload(content) {
  return { username: "Dev server", content, allowed_mentions: { parse: [] } }
}

/** 보낸다. 429(너무 잦음)는 기다렸다 두 번까지 다시. 오류 메시지에 웹후크 주소를 넣지 않는다. */
export async function send(webhook, body, fetchImpl = fetch, sleep = (ms) => new Promise((r) => setTimeout(r, ms))) {
  if (!WEBHOOK.test(webhook ?? "")) throw new Error("웹후크 주소 모양이 틀렸다(secret DISCORD_PREVIEW_WEBHOOK)")
  for (let attempt = 1; ; attempt++) {
    const response = await fetchImpl(`${webhook}?wait=true`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(10_000),
    })
    if (response.ok) return
    if (response.status === 429 && attempt < 3) {
      const wait = Number((await response.json().catch(() => ({}))).retry_after) || 1
      await sleep(Math.min(wait, 5) * 1000)
      continue
    }
    throw new Error(`디스코드가 HTTP ${response.status} 를 돌려줬다`)
  }
}

async function main([stateFile]) {
  const env = process.env
  if (!env.WEBHOOK) {
    console.log("::notice title=디스코드 알림::secret DISCORD_PREVIEW_WEBHOOK 이 없어 알리지 않는다")
    return
  }
  let text = ""
  try {
    text = readFileSync(stateFile, "utf8")
  } catch {
    // 상태를 읽지 못한 단계 뒤에도 결과(실패 등)는 알린다.
  }
  const content = compose({
    task: env.TASK,
    pr: env.PR,
    title: env.TITLE,
    author: env.AUTHOR,
    by: env.BY,
    sha: env.SHA,
    domain: env.DOMAIN,
    powerOnJob: env.POWER_ON_JOB,
    releaseJob: env.RELEASE_JOB,
    releaseResult: env.RELEASE_RESULT,
    powerOffJob: env.POWER_OFF_JOB,
    runUrl: `${env.GITHUB_SERVER_URL}/${env.GITHUB_REPOSITORY}/actions/runs/${env.GITHUB_RUN_ID}`,
    state: parseState(text),
  })
  if (!content) {
    console.log("디스코드: 알릴 것이 없다")
    return
  }
  await send(env.WEBHOOK, payload(content))
  console.log("디스코드 #pr-주소 에 알렸다")
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch((error) => {
    console.log(`::warning title=디스코드 알림::알리지 못했다 — ${error.message}`)
  })
}
