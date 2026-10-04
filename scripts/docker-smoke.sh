#!/usr/bin/env bash
# 백엔드 이미지 검사. 빌드 → 실행 사용자가 root 가 아닌지 → deploy/compose.dev.yml 로 PostgreSQL 과 함께 띄워 요청을 받는지.
# 준비 여부는 로그인 없이 여는 GET /api/v1/csrf 200 으로 본다. /actuator/health 는 지금 로그인해야 본다(공개 여부는 팀 결정 전).
#
#   bash scripts/docker-smoke.sh   (verify.sh docker 와 docker-build.yml 이 부른다)
#
# 앱은 127.0.0.1:18080 에 띄운다(SMOKE_PORT 로 바꿀 수 있다). 로컬 백엔드(8080)와 겹치지 않는다.
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

IMAGE=neuringo-backend:smoke
PROJECT=neuringo-smoke
PORT=${SMOKE_PORT:-18080}
READY_URL="http://127.0.0.1:$PORT/api/v1/csrf"

random_hex() { od -An -tx1 -N"$1" /dev/urandom | tr -d ' \n'; }
compose() { docker compose -f deploy/compose.dev.yml -p "$PROJECT" "$@"; }
cleanup() { compose down --volumes --remove-orphans >/dev/null 2>&1 || true; }

# compose.dev.yml 은 .env 대신 이 환경변수를 읽는다. DB 계정은 실행마다 새로 만든다.
DB_USERNAME="smoke_$(random_hex 4)"
DB_PASSWORD=$(random_hex 16)
export APP_IMAGE=$IMAGE SPRING_PROFILES_ACTIVE=local APP_BIND=127.0.0.1 APP_PORT=$PORT
export DB_NAME=neuringo_smoke DB_USERNAME DB_PASSWORD

echo "▶ 이미지 빌드 ($IMAGE)"
docker build -t "$IMAGE" backend

echo "▶ 실행 사용자"
uid=$(docker run --rm --entrypoint id "$IMAGE" -u)
if [ "$uid" = 0 ]; then
  echo "이미지가 root 로 돈다. backend/Dockerfile 의 USER 를 확인하세요." >&2
  exit 1
fi
echo "  uid $uid"

echo "▶ compose.dev.yml 로 기동 (local 프로필, 127.0.0.1:$PORT)"
trap cleanup EXIT
compose up -d
deadline=$((SECONDS + 120))
until curl -fs -o /dev/null "$READY_URL"; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "앱이 120초 안에 요청을 받지 못했다($READY_URL 200). backend 로그 끝부분:" >&2
    compose logs --tail 40 backend >&2 || true
    exit 1
  fi
  sleep 2
done
echo "  $READY_URL → 200"
