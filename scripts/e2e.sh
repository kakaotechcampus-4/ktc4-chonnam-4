#!/usr/bin/env bash
# 종단(E2E) 테스트. 실제 백엔드(local 프로필)·PostgreSQL·프론트 빌드를 띄우고 강사·아동 흐름을 브라우저로 확인한다.
# 강사 계정은 E2E 가 가입 화면으로 직접 만든다(이메일·비밀번호는 실행마다 새로 만든다, e2e/support/instructor.ts).
# 끝나면 백엔드·DB 로그에서 이번 실행의 마커를 전부 찾는다. 마커는 E2E 가 만드는 아동 이름과 강사 이메일에 들어 있어서,
# 한 줄이라도 나오면 개인정보가 로그로 새는 것이다 → 실패.
#
#   bash scripts/e2e.sh         # run + scan (verify.sh e2e 와 같다)
#   bash scripts/e2e.sh run     # DB → bootJar → Playwright → 정리 (CI 의 E2E 스텝)
#   bash scripts/e2e.sh scan    # 마커 전수 스캔만 (CI 의 별도 스텝. E2E 가 실패해도 돈다)
#
# 필요: Docker, JDK 21, Node 24 와 npm ci 가 끝난 frontend/, Playwright Chromium(npx playwright install chromium)
# 포트: 백엔드 8080·프론트 5173 은 고정이다. 프론트가 API 주소를 localhost:8080 으로 하드코딩했고 CORS 가 5173 만 연다.
#       DB 는 로컬 Postgres(5432)와 겹치지 않게 55432 를 쓴다(E2E_DB_PORT 로 바꿀 수 있다).
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

DB_IMAGE=postgres:18.6
DB_PORT=${E2E_DB_PORT:-55432}
DB_NAME=neuringo_e2e
DB_CONTAINER=neuringo-e2e-db
BACKEND_URL=http://127.0.0.1:8080
LOG_DIR=${E2E_LOG_DIR:-frontend/test-results/e2e-logs}
MARKER_FILE=$LOG_DIR/marker

backend_pid=""

random_hex() { od -An -tx1 -N"$1" /dev/urandom | tr -d ' \n'; }

# TCP 연결이 되면 이미 누가 쓰는 중이다. curl 의 "연결 거부" 코드로 판단하면 Windows 에서는 거부 대신
# 재시도 끝에 시간 초과가 나서 비어 있는 포트도 쓰는 중으로 잘못 본다.
port_in_use() { timeout 5 bash -c "exec 3<>/dev/tcp/127.0.0.1/$1" 2>/dev/null; }

wait_until() { # 이름 최대초 검사함수 — 횟수가 아니라 시간으로 센다(Windows 는 닫힌 포트 연결 시도 한 번에 2초쯤 걸린다)
  local name=$1 seconds=$2 check=$3
  local deadline=$((SECONDS + seconds))
  until "$check"; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "$name 이(가) ${seconds}초 안에 준비되지 않았다." >&2
      return 1
    fi
    sleep 1
  done
}

db_healthy() { [ "$(docker inspect -f '{{.State.Health.Status}}' "$DB_CONTAINER" 2>/dev/null)" = healthy ]; }

# /actuator/health 는 지금 로그인해야 본다(공개 여부는 팀 결정 전). 로그인 없이 여는 CSRF 토큰 경로가 200 이면
# 앱이 DB 마이그레이션까지 마치고 요청을 받는 것이다.
backend_ready() {
  if ! kill -0 "$backend_pid" 2>/dev/null; then
    echo "백엔드가 떠 있지 않다. $LOG_DIR/backend.log 끝부분:" >&2
    tail -n 40 "$LOG_DIR/backend.log" >&2
    exit 1
  fi
  curl -fs -o /dev/null "$BACKEND_URL/api/v1/csrf"
}

cleanup() {
  if [ -n "$backend_pid" ]; then
    kill "$backend_pid" 2>/dev/null || true
    wait "$backend_pid" 2>/dev/null || true
  fi
  if docker container inspect "$DB_CONTAINER" >/dev/null 2>&1; then
    docker logs "$DB_CONTAINER" >"$LOG_DIR/db.log" 2>&1 || true
    docker rm -f "$DB_CONTAINER" >/dev/null
  fi
}

run() {
  if port_in_use 8080; then
    echo "8080 포트를 이미 쓰고 있다. 로컬에서 띄운 백엔드를 끄고 다시 실행하세요(E2E 는 새 DB 로 백엔드를 직접 띄운다)." >&2
    exit 1
  fi

  mkdir -p "$LOG_DIR"
  rm -f "$LOG_DIR"/*.log
  local marker db_user db_password jar
  marker="e2e-$(random_hex 6)"
  echo "$marker" >"$MARKER_FILE"
  # 일회용 DB 계정. 레포에 자격증명을 남기지 않도록 실행할 때마다 새로 만든다.
  db_user="e2e_$(random_hex 4)"
  db_password=$(random_hex 16)

  trap cleanup EXIT

  echo "▶ DB ($DB_IMAGE, localhost:$DB_PORT)"
  docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true
  docker run -d --name "$DB_CONTAINER" \
    -e POSTGRES_DB="$DB_NAME" -e POSTGRES_USER="$db_user" -e POSTGRES_PASSWORD="$db_password" \
    -p "127.0.0.1:$DB_PORT:5432" \
    --health-cmd "pg_isready -U $db_user -d $DB_NAME" --health-interval 1s --health-retries 60 \
    "$DB_IMAGE" >/dev/null
  wait_until "DB" 60 db_healthy

  echo "▶ 백엔드 (bootJar, local 프로필)"
  (cd backend && ./gradlew bootJar --no-daemon -q)
  jar=$(find backend/build/libs -name '*.jar' ! -name '*-plain.jar' | head -n 1)
  SPRING_PROFILES_ACTIVE=local \
    DB_URL="jdbc:postgresql://localhost:$DB_PORT/$DB_NAME" DB_USERNAME="$db_user" DB_PASSWORD="$db_password" \
    java -jar "$jar" >"$LOG_DIR/backend.log" 2>&1 &
  backend_pid=$!
  wait_until "백엔드" 120 backend_ready

  echo "▶ Playwright (프론트 빌드 → 미리보기 5173)"
  (cd frontend && E2E_MARKER="$marker" npx playwright test)
}

scan() {
  if [ ! -f "$MARKER_FILE" ]; then
    echo "마커 파일($MARKER_FILE)이 없다. E2E 가 DB 를 띄우기 전에 멈춰서 검사할 로그가 없다."
    return 0
  fi
  local marker hits
  marker=$(cat "$MARKER_FILE")
  shopt -s nullglob
  local logs=("$LOG_DIR"/*.log)
  if [ "${#logs[@]}" -eq 0 ]; then
    echo "검사할 로그가 없다($LOG_DIR/*.log)."
    return 0
  fi
  if hits=$(grep -nF -- "$marker" "${logs[@]}"); then
    [ -n "${GITHUB_ACTIONS:-}" ] && echo "::error title=개인정보 마커 스캔::E2E 가 만든 아동 이름이나 강사 이메일이 로그에 남았다"
    echo "아동 이름이나 강사 이메일(E2E 마커 $marker)이 로그에 남았다. 로그에 쓰는 코드를 찾아 지우세요:" >&2
    echo "$hits" >&2
    return 1
  fi
  echo "마커 스캔: 로그 ${#logs[@]}개에서 0건 (마커 $marker)"
}

case "${1:-all}" in
  run) run ;;
  scan) scan ;;
  all)
    # run 을 하위 셸로 돌려야 run 안에서 set -e 가 그대로 동작한다(함수를 || 뒤에 두면 꺼진다).
    status=0
    bash "$0" run || status=$?
    bash "$0" scan || status=1
    exit "$status"
    ;;
  *)
    echo "사용법: bash scripts/e2e.sh [run|scan]" >&2
    exit 2
    ;;
esac
