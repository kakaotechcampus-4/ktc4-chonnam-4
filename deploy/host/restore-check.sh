#!/usr/bin/env bash
# 최신 백업이 실제로 복구되는지 서버에서 확인한다. ops-backup.yml 이 매일 백업 뒤 SSM 으로 부른다.
#   NEURINGO_ENV=dev bash deploy/host/restore-check.sh
#
# 데이터가 서버(AWS) 밖으로 나가지 않게, 같은 서버의 임시 postgres 컨테이너(메모리 512 MiB, 네트워크 없음)에 복구해 본다.
# 끝나면(실패해도) 컨테이너와 그 데이터 볼륨까지 지운다(-v). 복구한 DB 사본(개인정보)이 서버에 남지 않게 한다.
# 지난번에 시간 초과 등으로 못 지운 임시 컨테이너도 시작할 때 지운다.
# 출력은 숫자만 남긴다: 테이블 수·적용된 마이그레이션 버전·전체 행 수.
set -euo pipefail

ENV_NAME=${NEURINGO_ENV:-dev}
HOME_DIR=${NEURINGO_HOME:-/opt/neuringo}
LABEL=${LABEL:-daily}
IMAGE=${RESTORE_IMAGE:-postgres:18.6}
WAIT=${RESTORE_WAIT:-60}
name="neuringo-restore-check-$$"
log_file="$HOME_DIR/logs/restore-check-$ENV_NAME.log"

say() { echo "[restore-check] $*"; }

mkdir -p "$HOME_DIR/logs"
shopt -s nullglob
dumps=("$HOME_DIR/backups/$LABEL/$ENV_NAME"-*.dump)
shopt -u nullglob
if [ "${#dumps[@]}" = 0 ]; then
  say "복구해 볼 백업이 없다($LABEL)"
  exit 1
fi
latest=$(printf '%s\n' "${dumps[@]}" | sort -r | head -n 1)

remove() { docker rm -f -v "$1" >>"$log_file" 2>&1 || true; }
docker ps -aq --filter "name=neuringo-restore-check-" 2>>"$log_file" | while IFS= read -r stale; do
  [ -n "$stale" ] && remove "$stale"
done

# 네트워크가 없는 임시 컨테이너라(서버의 다른 프로세스도 못 붙는다) 비밀번호 대신 trust 로 붙는다.
if ! docker run -d --rm --name "$name" --network none --memory 512m -e POSTGRES_HOST_AUTH_METHOD=trust "$IMAGE" \
  >>"$log_file" 2>&1; then
  say "임시 postgres 를 띄우지 못했다"
  exit 1
fi
trap 'remove "$name"' EXIT

# 처음 뜰 때 이미지가 소켓에만 붙는 임시 서버를 잠깐 띄웠다 다시 시작한다. TCP(127.0.0.1)로 물어 진짜 서버를 기다린다.
deadline=$((SECONDS + WAIT))
until docker exec "$name" pg_isready -h 127.0.0.1 -U postgres >/dev/null 2>&1; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    say "임시 postgres 가 ${WAIT}초 안에 준비되지 않았다"
    exit 1
  fi
  sleep 1
done

sql() { docker exec "$name" psql -U postgres -d restore_check -tAc "$1" 2>>"$log_file"; }

if ! docker exec "$name" createdb -U postgres restore_check >>"$log_file" 2>&1 ||
  ! docker exec -i "$name" pg_restore -U postgres -d restore_check --no-owner --no-privileges <"$latest" >>"$log_file" 2>&1; then
  say "복구가 실패했다. 서버의 $log_file 를 본다"
  exit 1
fi

tables=$(sql "select count(*) from information_schema.tables where table_schema = 'public'")
version=$(sql "select coalesce(max(version::int), 0) from flyway_schema_history where success")
rows=$(sql "select coalesce(sum((xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', schemaname, tablename), false, true, '')))[1]::text::bigint), 0) from pg_tables where schemaname = 'public'")

say "복구 성공: 테이블 ${tables}개, 마이그레이션 V${version}, 행 ${rows}개"
echo "RESTORE_RESULT=ok"
echo "RESTORE_TABLES=$tables"
echo "RESTORE_MIGRATION=V$version"
echo "RESTORE_ROWS=$rows"
