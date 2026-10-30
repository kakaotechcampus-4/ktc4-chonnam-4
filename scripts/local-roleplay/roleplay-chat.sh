#!/usr/bin/env bash
# 로컬에서 아이와의 역할극 대화(LLM)를 확인한다. 시나리오 생성·승인 API 가 아직 없어서 승인 시나리오를 DB 에 직접 넣는다.
#
#   1) 백엔드를 역할극 설정으로 띄운다:  bash scripts/local-roleplay/run-backend.sh  (README.md 참고)
#   2) 다른 터미널에서:                   bash scripts/local-roleplay/roleplay-chat.sh
#      문장을 인자로 주면 차례로 보내고 끝난다:
#                                         bash scripts/local-roleplay/roleplay-chat.sh "친구가 넘어졌어" "아파서 울어"
#
# 하는 일: 강사 가입·로그인 → 학급·아동 → 첫 활동(퀴즈+역할극) → 승인 시나리오(L1~L3) DB 삽입 → 아동 입장 → 퀴즈 풀기
#          → 역할극 시작(첫 질문) → 아이 문장을 보내고 AI 대답을 받는다.
#
# 필요: curl, jq, psql(없으면 docker compose 의 Postgres 컨테이너를 쓴다). 아이 문장은 음성 대신 글자로 보낸다(run-backend.sh 의 로컬 STT).
set -euo pipefail

for tool in curl jq; do
  type -P "$tool" >/dev/null || {
    echo "$tool 이 없다. Windows 는 PowerShell 에서 'winget install jqlang.jq' 로 설치하고 Git Bash 를 새로 연다." >&2
    exit 1
  }
done

ROOT=$(git rev-parse --show-toplevel)
BASE=${BASE_URL:-http://127.0.0.1:8080}

# DB 접속 값은 backend/.env(docker compose 와 같은 파일)에서 읽는다.
if [ -f "$ROOT/backend/.env" ]; then
  while IFS='=' read -r key value; do
    [[ $key =~ ^DB_(NAME|USERNAME|PASSWORD)$ ]] && [ -z "${!key:-}" ] && export "$key=${value%$'\r'}"
  done < "$ROOT/backend/.env"
fi
# 시나리오를 넣을 psql 명령. 로컬에 psql 이 없으면 backend/compose.yml 의 Postgres 컨테이너 안의 psql 을 쓴다.
if [ -z "${DB_PSQL:-}" ]; then
  if command -v psql >/dev/null; then
    DB_PSQL="psql postgresql://${DB_USERNAME:?backend/.env 에 DB_USERNAME 이 필요합니다}:${DB_PASSWORD}@127.0.0.1:5432/${DB_NAME:-neuringo_local}"
  else
    DB_PSQL="docker compose -f $ROOT/backend/compose.yml exec -T postgres psql -U ${DB_USERNAME:?backend/.env 에 DB_USERNAME 이 필요합니다} -d ${DB_NAME:-neuringo_local}"
  fi
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
TEACHER_JAR=$WORK/teacher.cookies
CHILD_JAR=$WORK/child.cookies

# Windows(Git Bash): jq 는 줄 끝에 \r 을 붙이고, curl 은 Windows 경로를 받는다.
jq() { command jq "$@" | tr -d '\r'; }
if command -v cygpath >/dev/null; then win() { cygpath -w "$1"; }; else win() { printf '%s' "$1"; }; fi

# 무작위 UUID(v4). /proc 나 uuidgen 이 없는 Windows Git Bash 에서도 돈다.
uuid() {
  local h
  h=$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')
  printf '%s-%s-4%s-%x%s-%s\n' "${h:0:8}" "${h:8:4}" "${h:13:3}" $(( (0x${h:16:1} & 3) | 8 )) "${h:17:3}" "${h:20:12}"
}

# call 쿠키파일 메서드 경로 [JSON 본문] [추가 curl 인자...] — CSRF 를 붙이고, 실패하면 응답을 보여 주고 멈춘다.
call() {
  local jar=$1 method=$2 path=$3 body=${4:-}
  shift 4 || shift $#
  local token
  token=$(curl -sf -c "$jar" -b "$jar" "$BASE/api/v1/csrf" | jq -r .data.token)
  local args=(-s -o "$WORK/out" -w '%{http_code}' -c "$jar" -b "$jar" -X "$method" -H "X-XSRF-TOKEN: $token")
  [ -n "${BEARER:-}" ] && [ "$jar" = "$TEACHER_JAR" ] && args+=(-H "Authorization: Bearer $BEARER")
  [ "$method" != GET ] && args+=(-H "Idempotency-Key: $(uuid)")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  local status
  status=$(curl "${args[@]}" "$@" "$BASE$path")
  if [ "${status:0:1}" != 2 ]; then
    echo "실패: $method $path → $status" >&2
    jq . "$WORK/out" >&2 2>/dev/null || cat "$WORK/out" >&2
    exit 1
  fi
  cat "$WORK/out"
}

curl -sf -o /dev/null "$BASE/api/v1/csrf" || { echo "백엔드($BASE)가 떠 있지 않다. run-backend.sh 를 먼저 실행하세요." >&2; exit 1; }
$DB_PSQL -tAc 'select 1' >/dev/null || { echo "DB 에 접속할 수 없다. DB_PSQL 을 확인하세요." >&2; exit 1; }

echo "== 강사 가입·로그인"
email="local-$(uuid | cut -c1-8)@example.test"
call "$TEACHER_JAR" POST /api/v1/users \
  "{\"email\":\"$email\",\"password\":\"local-pass-1234\",\"name\":\"로컬 강사\",\"termsAgreed\":true,\"privacyAgreed\":true}" >/dev/null
BEARER=$(call "$TEACHER_JAR" POST /api/v1/auth/sessions "{\"email\":\"$email\",\"password\":\"local-pass-1234\"}" | jq -r .data.accessToken)

echo "== 학급·아동·첫 활동"
class_id=$(call "$TEACHER_JAR" POST /api/v1/classrooms '{"name":"로컬 학급"}' | jq -r .data.classId)
child_id=$(call "$TEACHER_JAR" POST "/api/v1/classrooms/$class_id/children" '{"displayName":"로컬 아동"}' | jq -r .data.childId)
activity=$(call "$TEACHER_JAR" POST /api/v1/activities \
  "{\"childId\":\"$child_id\",\"activityType\":\"FIRST_COMBINED\",\"goal\":{\"title\":\"친구의 감정을 이해하고 공감하기\",\"situationType\":\"놀이터\",\"category\":\"EMOTION_RECOGNITION\"}}")
activity_id=$(jq -r .data.activityId <<<"$activity")
goal_id=$(jq -r '.data.goalId // .data.goal.goalId' <<<"$activity")
goal_hash=$(call "$TEACHER_JAR" GET "/api/v1/learning-goals/$goal_id" | jq -r .data.contentHash)

echo "== 승인 시나리오 넣기 (L1·L2·L3, 퀴즈 결과 난이도에 맞는 것이 붙는다)"
$DB_PSQL -v ON_ERROR_STOP=1 -q -v class_id="$class_id" -v goal_hash="$goal_hash" <"$ROOT/scripts/local-roleplay/approved-scenario.sql" >/dev/null

echo "== 아동 입장"
code=$(call "$TEACHER_JAR" POST "/api/v1/children/$child_id/access-codes" | jq -r .data.accessCode)
call "$CHILD_JAR" POST /api/v1/child-access-sessions "{\"accessCode\":\"$code\"}" >/dev/null

echo "== 퀴즈 풀기 (모두 첫 답으로 제출)"
items=$(call "$CHILD_JAR" GET "/api/v1/activities/$activity_id/quiz-items")
# 보기 첫 번째로 답한다. 표정 따라 하기(SELF_*) 문항은 카메라 판정을 MATCH 로 보낸다.
while IFS=$'\t' read -r quiz_id quiz_type answer; do
  if [[ $quiz_type == SELF* ]]; then camera='"MATCH","cameraModelVersion":"local-test"'; else camera='null,"cameraModelVersion":null'; fi
  echo "   $quiz_type → $answer"
  call "$CHILD_JAR" PUT "/api/v1/activity-quiz-items/$quiz_id/attempt" \
    "{\"firstResponse\":\"$answer\",\"expressionMatchResult\":$camera,\"finalResponse\":null,\"technicalFailureReason\":null}" >"$WORK/attempt"
  # 틀리면 확정되지 않는다(DRAFT). 힌트를 받고 같은 보기로 최종 답을 내 확정한다.
  if [ "$(jq -r .data.status "$WORK/attempt")" != FINALIZED ]; then
    call "$CHILD_JAR" POST "/api/v1/activity-quiz-items/$quiz_id/hints" >/dev/null
    call "$CHILD_JAR" PUT "/api/v1/activity-quiz-items/$quiz_id/attempt" \
      "{\"firstResponse\":\"$answer\",\"expressionMatchResult\":$camera,\"finalResponse\":\"$answer\",\"technicalFailureReason\":null}" >"$WORK/attempt"
  fi
  jq -r '"     → " + .data.status' "$WORK/attempt"
done < <(jq -r '.data[] | [.activityQuizId, .quizType, .choices[0]] | @tsv' <<<"$items")
level=$(call "$CHILD_JAR" GET "/api/v1/activities/$activity_id/quiz-result" | jq -r '.data.initialDifficulty.scenarioLevel // .data.scenarioLevel // "?"')
echo "   최초 난이도: $level"

echo "== 역할극 시작"
start=$(call "$CHILD_JAR" POST "/api/v1/activities/$activity_id/roleplay-sessions" '')
session_id=$(jq -r .data.sessionId <<<"$start")
echo
jq -r '.data.delivery.messages[] | "AI: " + .text' <<<"$start"

# 아이 문장 하나를 "음성" 파일로 보낸다. 서버가 같은 말을 다시 보내 달라고 하면(RETRY_TURN) 한 번 더 보낸다.
send() {
  printf '%s' "$1" > "$WORK/utterance.wav"
  local res attempt
  for attempt in 1 2; do
    res=$(call "$CHILD_JAR" POST "/api/v1/roleplay-sessions/$session_id/voice-inputs" '' -F "audio=@$(win "$WORK/utterance.wav");type=audio/wav")
    jq -r '(.data.messages // [])[] | "AI: " + .text' <<<"$res"
    jq -r '"    (" + ([.data.status, .data.action] | map(select(. != null and . != "NONE")) | join(" · ")) + ")"' <<<"$res"
    [ "$(jq -r .data.action <<<"$res")" = RETRY_TURN ] && [ "$attempt" = 1 ] || break
    echo "    (같은 말을 다시 보낸다)"
  done
  if [ "$(jq -r .data.status <<<"$res")" = STOPPED ] || [ "$(curl -s -b "$CHILD_JAR" "$BASE/api/v1/activities/$activity_id/status" | jq -r .data.status)" = COMPLETED ]; then
    echo "== 역할극이 끝났다"
    exit 0
  fi
}

if [ $# -gt 0 ]; then
  for line in "$@"; do echo "아이: $line"; send "$line"; done
else
  echo "(아이 대답을 입력하세요. 끝내려면 빈 줄)"
  while IFS= read -r -p "아이: " line && [ -n "$line" ]; do send "$line"; done
fi
