// Playwright JSON 리포트에서 실패한 테스트와 재시도 끝에 통과한(flaky) 테스트를 뽑아 Issue 댓글 본문(마크다운)을 만든다.
// 기록할 게 없으면 아무것도 출력하지 않는다. 호출은 scripts/e2e-record.sh 가 한다.
//
//   node scripts/e2e-record-body.mjs <playwright-results.json>
//
// 환경 변수: E2E_OUTCOME(E2E 스텝 결과), E2E_WHERE(예: "PR #25"), E2E_SHA, E2E_RUN_URL
import { existsSync, readFileSync } from "node:fs"

const reportPath = process.argv[2]
const env = process.env

// ANSI 색 코드를 지우고 첫 줄만, 너무 길면 자른다.
function firstLine(message) {
  const line = (message ?? "")
    .replace(/\u001b\[[0-9;]*m/g, "")
    .split("\n")
    .map((part) => part.trim())
    .find((part) => part !== "")
  if (!line) return ""
  return line.length > 200 ? `${line.slice(0, 200)}…` : line
}

// 마크다운 표 칸에 넣을 글자. 역슬래시를 먼저 이스케이프해야 뒤에 오는 | 이스케이프가 깨지지 않는다.
// 줄바꿈이 있으면 표가 끊기므로 공백으로 바꾼다.
function cell(text) {
  return text
    .replace(/\\/g, "\\\\")
    .replace(/\|/g, "\\|")
    .replace(/[\r\n]+/g, " ")
}

function collect(suite, rows) {
  for (const spec of suite.specs ?? []) {
    for (const test of spec.tests ?? []) {
      if (test.status !== "unexpected" && test.status !== "flaky") continue
      const failed = (test.results ?? []).find((result) => result.error || (result.errors ?? []).length > 0)
      rows.push({
        kind: test.status === "unexpected" ? "실패" : "재시도 후 통과",
        name: `${spec.file} › ${spec.title}`,
        project: test.projectName ?? "",
        error: firstLine(failed?.error?.message ?? failed?.errors?.[0]?.message),
      })
    }
  }
  for (const child of suite.suites ?? []) collect(child, rows)
}

const rows = []
let report = null
if (reportPath && existsSync(reportPath)) {
  report = JSON.parse(readFileSync(reportPath, "utf8"))
  for (const suite of report.suites ?? []) collect(suite, rows)
}

const failedBeforeTests = report === null && env.E2E_OUTCOME === "failure"
if (rows.length === 0 && !failedBeforeTests && !(report?.errors ?? []).length) process.exit(0)

// 러너 시간대와 상관없이 한국 시간으로 적는다.
const date = new Intl.DateTimeFormat("sv-SE", {
  timeZone: "Asia/Seoul",
  dateStyle: "short",
  timeStyle: "short",
}).format(new Date())

const lines = [`### ${date} KST · ${env.E2E_WHERE ?? ""} · \`${(env.E2E_SHA ?? "").slice(0, 7)}\``, ""]

if (failedBeforeTests) {
  lines.push("테스트가 시작되기 전에 실패했습니다(DB·백엔드·프론트 빌드 중 하나). 실행 로그에서 E2E 스텝을 확인해 주세요.", "")
}
for (const error of report?.errors ?? []) {
  lines.push(`테스트 밖에서 난 오류: \`${cell(firstLine(error.message))}\``, "")
}
if (rows.length > 0) {
  lines.push("| 결과 | 테스트 | 브라우저 |", "| --- | --- | --- |")
  for (const row of rows) lines.push(`| ${row.kind} | ${cell(row.name)} | ${cell(row.project)} |`)
  lines.push("")
  const errors = rows.filter((row) => row.error)
  if (errors.length > 0) {
    lines.push("첫 번째 오류 메시지", "", "```text")
    for (const row of errors) lines.push(`[${row.project}] ${row.name}`, `  ${row.error}`)
    lines.push("```", "")
  }
}
lines.push(
  `실행 로그: ${env.E2E_RUN_URL ?? ""} (trace 가 든 Artifacts \`e2e-report\` 는 7일 뒤 지워집니다)`,
  "",
  "**원인:** 확인한 분이 이 댓글을 인용(Quote reply)해서 적어 주세요. 예: CI 서버 지연 / 실제 버그 / 테스트 코드 문제",
)
console.log(lines.join("\n"))
