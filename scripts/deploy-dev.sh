#!/usr/bin/env bash
# 개발 서버 배포(.github/workflows/deploy-dev.yml 의 SSH 단계). 서버에 compose 파일과 .env 를 올리고 새 이미지로 띄운다.
#
# 받는 환경변수(워크플로가 secret 에서 넘긴다):
#   DEV_HOST DEV_SSH_USER DEV_SSH_KEY DEV_SSH_KNOWN_HOSTS   서버 접속
#   DEV_DB_NAME DEV_DB_USERNAME DEV_DB_PASSWORD             서버 DB — 서버의 .env 로만 들어간다
#   APP_IMAGE                                              배포할 이미지(ghcr.io/...:<커밋 SHA>)
#   REGISTRY_USER REGISTRY_TOKEN                           서버가 GHCR 에서 이미지를 받을 때 쓴다(실행 중에만 유효한 토큰)
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

for name in DEV_HOST DEV_SSH_USER DEV_SSH_KEY DEV_SSH_KNOWN_HOSTS DEV_DB_NAME DEV_DB_USERNAME DEV_DB_PASSWORD \
  APP_IMAGE REGISTRY_USER REGISTRY_TOKEN; do
  if [ -z "${!name:-}" ]; then
    echo "$name 이(가) 비어 있다." >&2
    exit 1
  fi
done

ssh_dir=$(mktemp -d)
trap 'rm -rf "$ssh_dir"' EXIT
printf '%s\n' "$DEV_SSH_KEY" >"$ssh_dir/key"
printf '%s\n' "$DEV_SSH_KNOWN_HOSTS" >"$ssh_dir/known_hosts"
chmod 600 "$ssh_dir/key"

# 호스트 키는 secret 으로 고정한다. 처음 보는 키는 받아들이지 않는다.
ssh_opts=(-i "$ssh_dir/key" -o UserKnownHostsFile="$ssh_dir/known_hosts" -o StrictHostKeyChecking=yes -o BatchMode=yes)
target="$DEV_SSH_USER@$DEV_HOST"

# 원격 명령은 변수 없는 고정 문자열로 두고, 값은 전부 표준입력으로 넘긴다. 명령줄·로그에 값이 남지 않는다.
# 서버에서는 SSH 사용자 홈의 neuringo/ 에 compose.yml 과 .env 를 둔다.
ssh "${ssh_opts[@]}" "$target" 'mkdir -p neuringo'
scp "${ssh_opts[@]}" deploy/compose.dev.yml "$target:neuringo/compose.yml"

# .env 는 서버에서만 만든다(권한 600).
printf 'APP_IMAGE=%s\nDB_NAME=%s\nDB_USERNAME=%s\nDB_PASSWORD=%s\n' \
  "$APP_IMAGE" "$DEV_DB_NAME" "$DEV_DB_USERNAME" "$DEV_DB_PASSWORD" |
  ssh "${ssh_opts[@]}" "$target" 'umask 077 && cat > neuringo/.env'

# 비공개 GHCR 이미지를 받으려면 로그인해야 한다. 첫 줄은 사용자 이름, 나머지는 토큰이다.
# 끝나면 성공·실패와 상관없이 로그아웃한다. $user·$? 는 서버에서 풀려야 해서 작은따옴표로 둔다.
# shellcheck disable=SC2016
printf '%s\n%s' "$REGISTRY_USER" "$REGISTRY_TOKEN" |
  ssh "${ssh_opts[@]}" "$target" \
    'read -r user && docker login ghcr.io -u "$user" --password-stdin >/dev/null && cd neuringo && docker compose pull && docker compose up -d --wait; status=$?; docker logout ghcr.io >/dev/null; exit $status'

echo "배포 완료: $APP_IMAGE → $DEV_HOST"
