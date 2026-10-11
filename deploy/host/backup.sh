#!/usr/bin/env bash
# DB 백업: postgres 컨테이너에서 pg_dump(커스텀 형식, 압축) → 서버에 저장 → S3 에 올린다.
# deploy.sh 가 배포 전에(LABEL=pre-deploy), ops-backup.yml 이 매일(LABEL=daily) SSM 으로 부른다.
#   NEURINGO_ENV=dev BACKUP_BUCKET=<버킷 이름> bash deploy/host/backup.sh
#
# - 덤프가 비었거나 목록을 읽지 못하면(pg_restore --list) 실패한다. 복구할 수 없는 백업은 백업이 아니다.
# - 서버에는 환경별로 최근 KEEP_LOCAL(기본 3)개만 남긴다(S3 에 못 올려도 정리한다). S3 쪽은 버킷 수명 주기(14일).
# - DB 계정은 컨테이너 안의 POSTGRES_* 값을 쓴다. 이 스크립트는 비밀값을 읽지도 찍지도 않는다.
# - 출력은 SSM 을 거쳐 Actions 로그(공개)에 남는다. 버킷 이름·오류 본문은 서버의 로그 파일에만 남긴다.
set -euo pipefail

ENV_NAME=${NEURINGO_ENV:-dev}
HOME_DIR=${NEURINGO_HOME:-/opt/neuringo}
REGION=${AWS_REGION:-ap-northeast-2}
HERE=$(cd "$(dirname "$0")" && pwd)
COMPOSE_FILE=${COMPOSE_FILE:-$HERE/../compose.dev.yml}
LABEL=${LABEL:-daily}
KEEP_LOCAL=${KEEP_LOCAL:-3}

env_file="$HOME_DIR/state/$ENV_NAME.env"
log_file="$HOME_DIR/logs/backup-$ENV_NAME.log"

say() { echo "[backup] $*"; }
compose() { APP_ENV_FILE="$env_file" docker compose --env-file "$env_file" -f "$COMPOSE_FILE" -p "neuringo-$ENV_NAME" "$@"; }

umask 077
mkdir -p "$HOME_DIR/backups/$LABEL" "$HOME_DIR/logs"
if [ ! -f "$env_file" ]; then
  say "$env_file 이 없다. 첫 배포(deploy.sh) 뒤에 백업할 수 있다"
  exit 1
fi

file="$HOME_DIR/backups/$LABEL/$ENV_NAME-$(date -u +%Y%m%dT%H%M%SZ).dump"
# 아래 $POSTGRES_* 는 이 셸이 아니라 컨테이너 안의 셸이 풀어야 하므로 작은따옴표가 맞다.
# shellcheck disable=SC2016
if ! compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' >"$file" 2>>"$log_file"; then
  rm -f "$file"
  say "pg_dump 가 실패했다. 서버의 $log_file 를 본다"
  exit 1
fi
if [ ! -s "$file" ] || ! compose exec -T postgres pg_restore --list <"$file" >/dev/null 2>>"$log_file"; then
  rm -f "$file"
  say "덤프를 읽을 수 없다(비었거나 깨졌다)"
  exit 1
fi
bytes=$(wc -c <"$file" | tr -d ' ')

# 서버에는 이 환경의 최근 것만 남긴다. S3 업로드보다 먼저 한다(업로드가 실패해도 덤프가 쌓이지 않게).
# 파일 이름이 UTC 시각이라 이름 순서가 곧 시간 순서다. 같은 서버의 다른 환경(prod) 덤프는 건드리지 않는다.
shopt -s nullglob
dumps=("$HOME_DIR/backups/$LABEL/$ENV_NAME"-*.dump)
shopt -u nullglob
if [ "${#dumps[@]}" -gt "$KEEP_LOCAL" ]; then
  mapfile -t dumps < <(printf '%s\n' "${dumps[@]}" | sort -r)
  for old in "${dumps[@]:KEEP_LOCAL}"; do
    rm -f "$old"
  done
fi

if [ -n "${BACKUP_BUCKET:-}" ]; then
  if ! aws s3 cp "$file" "s3://$BACKUP_BUCKET/$ENV_NAME/$LABEL/${file##*/}" --region "$REGION" --only-show-errors >>"$log_file" 2>&1; then
    say "S3 에 올리지 못했다. 서버 역할 권한(s3:PutObject)을 확인한다"
    exit 1
  fi
  say "S3 에 올렸다 ($LABEL)"
else
  say "BACKUP_BUCKET 이 없어 서버에만 남겼다"
fi

say "백업 완료 ${bytes}바이트"
echo "BACKUP_RESULT=ok"
echo "BACKUP_BYTES=$bytes"
