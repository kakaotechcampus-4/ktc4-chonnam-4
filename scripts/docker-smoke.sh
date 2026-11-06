#!/usr/bin/env bash
# 서버 이미지 검사. 백엔드·화면 이미지 빌드 → 둘 다 root 가 아닌지 → deploy/compose.dev.yml 로 PostgreSQL 과 함께 띄워
# 서버와 똑같이 화면(nginx)을 거쳐 요청을 받는지 본다.
#   - API: GET /api/v1/csrf 200(로그인 없이 연다. /actuator/health 는 지금 로그인해야 본다)
#   - 화면: / 200 + 헤더 X-Neuringo-Release 가 빌드한 값, 화면 주소(/classrooms) 200(SPA), 없는 /assets/ 파일은 404
#   - CloudFront 를 거친 https → CSRF 쿠키 Secure(브라우저가 넣은 X-Forwarded-Proto 는 무시)
#   - 아동 입장 요청 수 제한(nginx): 한 IP 에서 넘치면 429(JSON TOO_MANY_REQUESTS, Retry-After), 다른 IP·다른 API 는 그대로
#   - 아동 입장 틀린 코드 횟수 제한(앱): nginx 가 넘긴 접속 IP 별로 세어, 한도를 넘은 IP 만 429(ACCESS_CODE_ATTEMPTS_EXCEEDED)
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
APP_ENV_FILE=$(mktemp)
cleanup() { compose down --volumes --remove-orphans >/dev/null 2>&1 || true; rm -f "$APP_ENV_FILE"; }

# compose.dev.yml 은 .env 대신 이 환경변수를 읽는다. DB 계정은 실행마다 새로 만든다.
DB_USERNAME="smoke_$(random_hex 4)"
DB_PASSWORD=$(random_hex 16)
export APP_IMAGE=$BACKEND_IMAGE WEB_IMAGE SPRING_PROFILES_ACTIVE=local APP_BIND=127.0.0.1 APP_PORT=$PORT
export DB_NAME=neuringo_smoke DB_USERNAME DB_PASSWORD
# 아동 입장 코드 HMAC 비밀키(32바이트 이상). 서버에서는 Parameter Store 값이 env_file(neuringo.env)로 들어온다.
# 없으면 입장 코드를 확인할 때 백엔드가 500 을 낸다. 실행마다 새로 만든다(scripts/e2e.sh 와 같은 방식).
printf 'CHILD_ACCESS_HMAC_SECRET=%s\n' "$(random_hex 32)" >"$APP_ENV_FILE"
export APP_ENV_FILE

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

# CloudFront 를 거친 https 요청(CloudFront 는 서버에 http 로 오고 CloudFront-Forwarded-Proto: https 를 붙인다).
# 백엔드가 https 인 줄 알아야 쿠키에 Secure 가 붙는다(nginx 가 X-Forwarded-Proto 로 넘기고 Spring 이 읽는다).
cookie=$(curl -fsS -o /dev/null -D - -H 'CloudFront-Forwarded-Proto: https' "$BASE/api/v1/csrf" | tr -d '\r' |
  grep -i '^set-cookie: XSRF-TOKEN=') || fail "CSRF 쿠키가 오지 않는다"
grep -qiE ';[[:space:]]*secure(;|$)' <<<"$cookie" ||
  fail "CloudFront 를 거친 https 요청인데 CSRF 쿠키에 Secure 가 없다 — nginx 의 X-Forwarded-Proto, 백엔드의 server.forward-headers-strategy 를 본다"
echo "  CloudFront 를 거친 https 요청 → CSRF 쿠키에 Secure"
# 브라우저가 X-Forwarded-Proto 를 직접 넣어도(CloudFront 헤더 없이 http) Secure 로 속지 않는다 — nginx 가 덮어쓴다.
cookie=$(curl -fsS -o /dev/null -D - -H 'X-Forwarded-Proto: https' "$BASE/api/v1/csrf" | tr -d '\r' |
  grep -i '^set-cookie: XSRF-TOKEN=') || fail "CSRF 쿠키가 오지 않는다"
! grep -qiE ';[[:space:]]*secure(;|$)' <<<"$cookie" || fail "브라우저가 넣은 X-Forwarded-Proto 가 백엔드에 닿았다"
echo "  브라우저가 넣은 X-Forwarded-Proto 는 쓰지 않는다"

# 아동 입장 요청 수 제한(nginx limit_req — 접속 IP 하나당 분당 60번, 한꺼번에 60번까지). 한 IP 로 한꺼번에 100번 보내면
# 넘친 것은 429 이고 몸통은 API 오류 모양(JSON)이다. 그동안 다른 IP 의 입장·같은 IP 의 다른 API 는 그대로 받는다.
# IP 는 CloudFront 가 붙이는 CloudFront-Viewer-Address 로 흉내 낸다(문서용 대역 203.0.113.0/24).
# CSRF 토큰 없이 보내므로 nginx 를 지난 요청은 백엔드가 403 으로 돌려보낸다(틀린 코드 횟수에는 들어가지 않는다).
entry="$BASE/api/v1/child-access-sessions"
burst=$(mktemp -d)
codes=$(curl -s --parallel --parallel-max 25 -X POST -H 'Content-Type: application/json' -d '{}' \
  -H 'CloudFront-Viewer-Address: 203.0.113.10:40001' -o "$burst/r#1.json" -w '%{http_code}\n' "$entry?n=[1-100]") || true
limited=$(grep -c '^429$' <<<"$codes" || true)
passed=$(grep -vc '^429$' <<<"$codes" || true)
[ "$limited" -ge 10 ] || fail "한 IP 로 입장을 100번 보냈는데 429 가 ${limited}번뿐이다(제한이 걸리지 않는다)"
[ "$passed" -ge 55 ] || fail "한꺼번에 60번까지는 받아야 하는데 ${passed}번만 통과했다(한 반이 함께 들어오지 못한다)"
echo "  아동 입장을 한 IP 로 100번 → 통과 $passed · 429 $limited"
headers=$(curl -s -D - -o "$burst/last.json" -X POST -H 'Content-Type: application/json' -d '{}' \
  -H 'CloudFront-Viewer-Address: 203.0.113.10:40002' "$entry" | tr -d '\r')
grep -q '^HTTP/[0-9.]* 429' <<<"$headers" || fail "제한에 걸린 IP 의 다음 입장이 429 가 아니다"
grep -qi '^content-type: application/json' <<<"$headers" || fail "429 응답이 JSON 이 아니다"
grep -qi "^x-neuringo-release: $RELEASE" <<<"$headers" || fail "429 응답에 X-Neuringo-Release 가 없다"
grep -qi '^retry-after: 60$' <<<"$headers" || fail "nginx 의 429 응답에 Retry-After: 60 이 없다(화면이 기다릴 시간을 모른다)"
grep -q '"code":"TOO_MANY_REQUESTS"' "$burst/last.json" || fail "429 몸통이 API 오류 모양(code TOO_MANY_REQUESTS)이 아니다"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{}' \
  -H 'CloudFront-Viewer-Address: 203.0.113.20:40003' "$entry")
[ "$code" != 429 ] || fail "다른 IP 의 입장까지 막혔다(IP 별로 세야 한다)"
code=$(curl -s -o /dev/null -w '%{http_code}' -H 'CloudFront-Viewer-Address: 203.0.113.10:40004' "$BASE/api/v1/csrf")
[ "$code" = 200 ] || fail "입장이 막힌 IP 의 다른 API 까지 막혔다($code)"
echo "  429 는 JSON(TOO_MANY_REQUESTS) · Retry-After 60 · 다른 IP 의 입장과 같은 IP 의 다른 API 는 그대로"

# 아동 입장 틀린 코드 횟수 제한(앱 — application.yml 의 neuringo.child-access.attempt-limit, 기본 10분 안에 30번).
# 백엔드는 nginx 가 X-Forwarded-For 로 넘긴 접속 IP(Tomcat RemoteIpValve 가 getRemoteAddr 로 바꾼다)로 센다. 이 IP 가
# nginx 컨테이너 주소로 남으면 모든 사람이 한 칸을 같이 쓰게 되므로, 다른 IP 가 막히지 않는 것까지 본다.
max_failures=30
jar="$burst/cookies.txt"
csrf=$(curl -fsS -c "$jar" -H 'CloudFront-Viewer-Address: 203.0.113.30:40010' "$BASE/api/v1/csrf") ||
  fail "CSRF 토큰을 받지 못했다"
csrf_header=$(sed -E 's/.*"headerName":"([^"]+)".*/\1/' <<<"$csrf")
csrf_token=$(sed -E 's/.*"token":"([^"]+)".*/\1/' <<<"$csrf")
wrong_entry() { # $1 = 접속 IP. 발급한 적 없는 코드라 늘 틀린 코드다. 응답 상태를 찍고 헤더·몸통은 $burst/wrong.* 에 남긴다
  curl -s -b "$jar" -D "$burst/wrong.headers" -o "$burst/wrong.json" -w '%{http_code}' -X POST \
    -H 'Content-Type: application/json' -H "$csrf_header: $csrf_token" -H "CloudFront-Viewer-Address: $1:40011" \
    -d '{"accessCode":"0000"}' "$entry"
}
for i in $(seq 1 "$max_failures"); do
  code=$(wrong_entry 203.0.113.30)
  [ "$code" = 401 ] || fail "틀린 코드 ${i}번째가 401 이 아니라 $code 다(한도 $max_failures 번 전에 막혔거나 CSRF 를 못 넘었다)"
done
code=$(wrong_entry 203.0.113.30)
[ "$code" = 429 ] || fail "틀린 코드를 ${max_failures}번 넘게 보냈는데 앱이 막지 않는다($code)"
grep -q '"code":"ACCESS_CODE_ATTEMPTS_EXCEEDED"' "$burst/wrong.json" || fail "앱의 429 몸통 code 가 ACCESS_CODE_ATTEMPTS_EXCEEDED 가 아니다"
retry=$(tr -d '\r' <"$burst/wrong.headers" | sed -n 's/^[Rr]etry-[Aa]fter: \([0-9]*\)$/\1/p')
if [ -z "$retry" ] || [ "$retry" -lt 1 ] || [ "$retry" -gt 600 ]; then
  fail "앱의 429 응답 Retry-After 가 1~600초가 아니다('$retry')"
fi
code=$(wrong_entry 203.0.113.31)
[ "$code" = 401 ] || fail "다른 IP 의 입장까지 앱이 막았다($code) — 백엔드가 접속 IP 대신 nginx 주소로 세는지 본다"
rm -rf "$burst"
echo "  틀린 코드 ${max_failures}번 → 그 IP 만 429(ACCESS_CODE_ATTEMPTS_EXCEEDED, Retry-After ${retry}초) · 다른 IP 는 그대로 401"
