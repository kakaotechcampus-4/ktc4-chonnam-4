#!/usr/bin/env bash
# 안 쓰면 끄기(Lambda, infra/modules/idle-off)의 마지막 판단을 CloudWatch 로그에서 읽는다. dev-server.yml 의 상태 보고서가 부른다.
#   bash scripts/idle-last.sh
# 출력(KEY=값): IDLE_LOG=ok|none|unreadable, IDLE_AT(UTC ISO), IDLE_RESULT(keep·stopped·skip·closing·abort·error), IDLE_REASON
# 로그 줄은 idle_off.py 가 찍는 JSON 한 줄({"result":…,"reason":…})이다. 서버 ID·주소·배포 ID 는 들어 있지 않다.
# 읽지 못해도 실패로 끝나지 않는다(상태 보고서의 한 줄일 뿐이다).
set -euo pipefail

GROUP=${IDLE_LOG_GROUP:-/aws/lambda/neuringo-dev-idle-off}
HOURS=${IDLE_LOG_HOURS:-6}
REGION=${AWS_REGION:-ap-northeast-2}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

start=$((($(date +%s) - HOURS * 3600) * 1000))
if ! aws logs filter-log-events --region "$REGION" --log-group-name "$GROUP" --start-time "$start" \
  --filter-pattern result --query 'events[*].[timestamp,message]' --output json >"$work/events.json" 2>"$work/err"; then
  # 로그 그룹이 없으면 아직 apply 전이다. 그 밖(권한 등)은 읽지 못함.
  if grep -q ResourceNotFoundException "$work/err"; then echo "IDLE_LOG=none"; else echo "IDLE_LOG=unreadable"; fi
  exit 0
fi

# 가장 늦은 판단 한 줄. 표 칸에 들어가므로 | 와 줄바꿈을 지우고 길이를 줄인다.
node -e '
const events = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8") || "[]") || []
let last = null
for (const [at, message] of events) {
  let line
  try { line = JSON.parse(message) } catch { continue }
  if (line && typeof line.result === "string" && (!last || at >= last.at)) last = { at, line }
}
if (!last) { console.log("IDLE_LOG=none"); process.exit(0) }
console.log("IDLE_LOG=ok")
console.log("IDLE_AT=" + new Date(last.at).toISOString().replace(/\.\d+Z$/, "Z"))
console.log("IDLE_RESULT=" + last.line.result.replace(/[^a-z]/g, ""))
console.log("IDLE_REASON=" + String(last.line.reason ?? "").replace(/[|\r\n]+/g, " ").replace(/\s+/g, " ").trim().slice(0, 160))
' "$work/events.json"
