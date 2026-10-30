#!/usr/bin/env bash
# 역할극 대화를 로컬에서 확인할 수 있게 백엔드를 띄운다(local 프로필 + 역할극 음성 API + 실제 LLM).
#
#   bash scripts/local-roleplay/run-backend.sh
#
# DB: backend/compose.yml 의 Postgres(5432)를 먼저 띄운다(cd backend && docker compose up -d). 접속 값은 DB_URL·DB_USERNAME·
#     DB_PASSWORD 환경변수나 backend/.env 에서 읽는다.
# LLM: AI_BASE_URL·AI_API_KEY·AI_MODEL 을 환경변수나 backend/.env.local(scenarioEval 과 같은 파일)에서 읽는다.
#      OpenAI 에 직접 붙으면 AI_BASE_URL 은 https://api.openai.com/v1 처럼 /v1 까지 쓴다.
#
# 운영에 아직 없는 세 자리(입력 안전 검사·STT·TTS)는 LocalRoleplayBeans.java 로 채운다. 배포 설정이 아니다.
#   - 아이 입력은 안전 검사 없이 통과한다.
#   - "음성" 파일 내용을 글자로 읽는다(roleplay-chat.sh 가 문장을 파일로 보낸다). 녹음·STT 키가 필요 없다.
#   - TTS 는 끈다. 대답은 글자로만 온다.
#   - LLM 요청·출력을 backend/build/local-roleplay/llm-trace.log 에 남긴다(운영은 남기지 않는다).
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
HERE=$ROOT/scripts/local-roleplay
BACKEND=$ROOT/backend
OUT=$BACKEND/build/local-roleplay
mkdir -p "$OUT/spool" "$OUT/addon"

# KEY=VALUE 파일을 읽는다. 셸에 이미 값이 있으면 그 값을 쓴다.
load_env() {
  [ -f "$1" ] || return 0
  while IFS='=' read -r key value; do
    key=${key#export }
    [[ $key =~ ^[A-Z_][A-Z0-9_]*$ ]] || continue
    value=${value%$'\r'}; value=${value#[\"\']}; value=${value%[\"\']}
    [ -n "$value" ] && [ -z "${!key:-}" ] && export "$key=$value"
  done < "$1"
}
load_env "$BACKEND/.env"
load_env "$BACKEND/.env.local"

: "${AI_API_KEY:?AI_API_KEY 가 없습니다. 환경변수나 backend/.env.local 에 넣어 주세요}"
: "${AI_MODEL:?AI_MODEL 이 없습니다(예: gpt-5.4-nano)}"

export SPRING_PROFILES_ACTIVE=local
export DB_URL=${DB_URL:-jdbc:postgresql://localhost:5432/${DB_NAME:-neuringo_local}}
export DB_USERNAME=${DB_USERNAME:?DB_USERNAME 이 없습니다(backend/.env)} DB_PASSWORD=${DB_PASSWORD:?DB_PASSWORD 가 없습니다(backend/.env)}
export CHILD_ACCESS_HMAC_SECRET=${CHILD_ACCESS_HMAC_SECRET:-local-only-hmac-secret-that-is-at-least-32-bytes}
export AI_CHAT_PROVIDER=openai AI_BASE_URL=${AI_BASE_URL:-https://api.openai.com/v1} AI_TIMEOUT=${AI_TIMEOUT:-10s} AI_MAX_RETRIES=0
export STT_PROVIDER=none TTS_PROVIDER=none
export ROLEPLAY_MAXIMUM_TURN_COUNT=${ROLEPLAY_MAXIMUM_TURN_COUNT:-8}
export ROLEPLAY_MAXIMUM_SUPPORT_TURNS_PER_MICRO_GOAL=${ROLEPLAY_MAXIMUM_SUPPORT_TURNS_PER_MICRO_GOAL:-3}
export LC_ALL=C.UTF-8 LANG=C.UTF-8
# LLM 요청 종류·입력·출력을 남길 파일. 끄려면 LOCAL_LLM_TRACE= 로 비운다.
LOCAL_LLM_TRACE=${LOCAL_LLM_TRACE-$OUT/llm-trace.log}

echo "== bootJar"
(cd "$BACKEND" && ./gradlew -q bootJar)
JAR=$(ls "$BACKEND"/build/libs/*-SNAPSHOT.jar | grep -v plain | head -1)

echo "== 로컬 전용 부품 컴파일"
rm -rf "$OUT/jar" "$OUT/addon"/*
mkdir -p "$OUT/jar"
# Windows(Git Bash)의 java·javac 는 Windows 경로와 ; 구분자를 쓴다.
if command -v cygpath >/dev/null; then
  win() { cygpath -w "$1"; }
  SEP=';'
else
  win() { printf '%s' "$1"; }
  SEP=':'
fi
# JDK 21 이상의 bin 폴더를 찾는다. Windows 는 PATH 에 java 만 있고 jar·javac 가 없는 경우가 많다(Oracle javapath).
jdk_ok() { [ -x "$1/javac" ] || [ -x "$1/javac.exe" ]; }
jdk_version() { "$1/javac" -version 2>&1 | tr -d '\r' | sed -nE 's/^javac ([0-9]+).*/\1/p' | head -1; }
find_jdk() {
  local candidates=()
  [ -n "${JAVA_HOME:-}" ] && candidates+=("$JAVA_HOME/bin")
  if command -v java >/dev/null; then
    local home
    home=$(java -XshowSettings:properties -version 2>&1 | tr -d '\r' | sed -n 's/^ *java.home = //p')
    [ -n "$home" ] && candidates+=("$(command -v cygpath >/dev/null && cygpath -u "$home" || echo "$home")/bin")
  fi
  local dir
  for dir in "$HOME"/.gradle/jdks/*/bin "$HOME"/.gradle/jdks/*/Contents/Home/bin \
    /c/Program\ Files/Java/*/bin /c/Program\ Files/Eclipse\ Adoptium/*/bin /c/Program\ Files/Microsoft/*/bin \
    /c/Program\ Files/Amazon\ Corretto/*/bin /usr/lib/jvm/*/bin; do
    candidates+=("$dir")
  done
  for dir in "${candidates[@]}"; do
    if jdk_ok "$dir" && [ "$(jdk_version "$dir")" -ge 21 ] 2>/dev/null; then
      printf '%s' "$dir"
      return
    fi
  done
  return 1
}
JDK_BIN=$(find_jdk) || { echo "JDK 21 이상(javac 포함)을 찾지 못했다. JAVA_HOME 을 JDK 21 폴더로 지정해 주세요." >&2; exit 1; }
echo "   JDK: $JDK_BIN"
(cd "$OUT/jar" && "$JDK_BIN/jar" xf "$(win "$JAR")")
"$JDK_BIN/javac" -proc:none -encoding UTF-8 -d "$(win "$OUT/addon")" \
  -cp "$(win "$OUT/jar/BOOT-INF/classes")$SEP$(win "$OUT/jar/BOOT-INF/lib")/*" "$(win "$HERE/LocalRoleplayBeans.java")"

args=(
  --roleplay.http.enabled=true
  --roleplay.http.spool-directory="$(win "$OUT/spool")"
  --roleplay.http.maximum-upload-bytes=2000000
  --roleplay.http.maximum-request-bytes=2500000
  --roleplay.http.allowed-formats=WEBM,WAV,MP3,OGG,MP4
  --roleplay.http.worker-count=2
  --roleplay.http.queue-capacity=4
  --roleplay.http.turn-budget=30s
)
# 서버가 승인 문구를 요구하는 안내(TIMEOUT 은 서버에 고정). 어느 단계에서 막혔는지 보이게 종류 이름을 넣은 로컬 문구다.
i=0
for kind in SAFETY_ESCALATION_CHILD SAFETY_ESCALATION_TEACHER SAFETY_CHECK_ERROR SAFE_FALLBACK INPUT_QUALITY INPUT_CONFIRMATION; do
  args+=(
    "--roleplay.http.notices[$i].kind=$kind"
    "--roleplay.http.notices[$i].text=(로컬 안내: $kind)"
    "--roleplay.http.notices[$i].version=local-v1"
    "--roleplay.http.notices[$i].approval-reference=local"
  )
  i=$((i + 1))
done

[ -n "$LOCAL_LLM_TRACE" ] && LOCAL_LLM_TRACE=$(win "$LOCAL_LLM_TRACE")
export LOCAL_LLM_TRACE

echo "== 백엔드 시작 (http://localhost:8080, 끝내려면 Ctrl+C)"
exec "$JDK_BIN/java" -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dloader.path="$(win "$OUT/addon")" \
  -cp "$(win "$JAR")" org.springframework.boot.loader.launch.PropertiesLauncher "${args[@]}"
