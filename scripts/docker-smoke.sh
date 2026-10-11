#!/usr/bin/env bash
# 서버 이미지 검사. 백엔드·화면 이미지 빌드 → 둘 다 root 가 아닌지 → deploy/compose.dev.yml 로 PostgreSQL 과 함께 띄워
# 서버와 똑같이 화면(nginx)을 거쳐 요청을 받는지 본다.
#   - API: GET /api/v1/csrf 200(로그인 없이 연다. /actuator/health 는 지금 로그인해야 본다)
#   - 화면: / 200 + 헤더 X-Neuringo-Release 가 빌드한 값, 화면 주소(/classrooms) 200(SPA), 없는 /assets/ 파일은 404
#
#   bash scripts/docker-smoke.sh   (verify.sh docker 와 docker-build.yml 이 부른다)
#
# 화면은 127.0.0.1:18080 에 띄운다(SMOKE_PORT 로 바꿀 수 있다). 로컬 백엔드(8080)와 겹치지 않는다.
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

BACKEND_IMAGE=neuringo-backend:smoke
WEB_IMAGE=neuringo-web:smoke
RELEASE=smoke-$(date +%s)
PROJECT=neuringo-smoke
PORT=${SMOKE_PORT:-18080}
BASE="http://127.0.0.1:$PORT"

random_hex() { od -An -tx1 -N"$1" /dev/urandom | tr -d ' \n'; }
compose() { docker compose -f deploy/compose.dev.yml -p "$PROJECT" "$@"; }
cleanup() { compose down --volumes --remove-orphans >/dev/null 2>&1 || true; }

# compose.dev.yml 은 .env 대신 이 환경변수를 읽는다. DB 계정은 실행마다 새로 만든다.
DB_USERNAME="smoke_$(random_hex 4)"
DB_PASSWORD=$(random_hex 16)
export APP_IMAGE=$BACKEND_IMAGE WEB_IMAGE SPRING_PROFILES_ACTIVE=local APP_BIND=127.0.0.1 APP_PORT=$PORT
export DB_NAME=neuringo_smoke DB_USERNAME DB_PASSWORD

echo "▶ 이미지 빌드 ($BACKEND_IMAGE, $WEB_IMAGE)"
docker build -t "$BACKEND_IMAGE" backend
docker build --build-arg "RELEASE=$RELEASE" -t "$WEB_IMAGE" frontend

echo "▶ 실행 사용자"
for image in "$BACKEND_IMAGE" "$WEB_IMAGE"; do
  uid=$(docker run --rm --entrypoint id "$image" -u)
  if [ "$uid" = 0 ]; then
    echo "$image 가 root 로 돈다. Dockerfile 의 USER 를 확인하세요." >&2
    exit 1
  fi
  echo "  $image uid $uid"
done

echo "▶ compose.dev.yml 로 기동 (local 프로필, 화면 $BASE)"
trap cleanup EXIT
compose up -d
deadline=$((SECONDS + 120))
until curl -fs -o /dev/null "$BASE/api/v1/csrf"; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "앱이 120초 안에 화면을 거친 요청을 받지 못했다($BASE/api/v1/csrf 200). 로그 끝부분:" >&2
    compose logs --tail 40 backend web >&2 || true
    exit 1
  fi
  sleep 2
done
echo "  /api/v1/csrf → 200 (web → backend)"

fail() { echo "$1" >&2; compose logs --tail 20 web >&2 || true; exit 1; }
headers=$(curl -fsS -o /dev/null -D - "$BASE/") || fail "화면 / 가 200 이 아니다"
grep -qi "^x-neuringo-release: $RELEASE" <<<"$headers" || fail "화면 헤더 X-Neuringo-Release 가 빌드한 값($RELEASE)이 아니다"
echo "  / → 200, X-Neuringo-Release: $RELEASE"
page=$(curl -fsS "$BASE/classrooms") || fail "화면 주소 /classrooms 가 200 이 아니다(SPA 는 index.html 을 돌려줘야 한다)"
grep -q '<div id="root">' <<<"$page" || fail "화면 주소 /classrooms 가 index.html 이 아니다"
echo "  /classrooms → 200 (index.html)"
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/assets/does-not-exist.js")
[ "$code" = 404 ] || fail "없는 /assets/ 파일이 404 가 아니다($code). index.html 을 돌려주면 그게 1년 캐시된다"
echo "  /assets/does-not-exist.js → 404"
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/does-not-exist")
[ "$code" != 200 ] || fail "없는 API 가 200 이다(index.html 로 빠지면 안 된다)"
echo "  /api/v1/does-not-exist → $code (화면으로 빠지지 않는다)"
