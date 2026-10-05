#!/usr/bin/env bash
# deploy/host/deploy.sh · backup.sh 자체 검사. 가짜 docker·aws·curl 로 서버 배포를 흉내 낸다(실제 서버·AWS 필요 없음).
# - 성공하면 기록하고 옛 이미지를 정리한다 / 실패하면 직전 이미지로 되돌린다 / 바꾸기 전에 멈출 때는 아무것도 바꾸지 않는다
# - 출력(SSM → Actions 로그, 공개)에 비밀값·계정 ID·앱 로그·버킷 이름이 나오지 않는다
#   bash scripts/test-host-deploy.sh   (verify.sh workflows 에 포함)
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

failures=0

check() { # 설명 명령...
  local what=$1
  shift
  if "$@"; then
    echo "  ✓ $what"
  else
    echo "  ✗ $what"
    failures=$((failures + 1))
  fi
}

has() { grep -qF -- "$2" <<<"$1"; }
lacks() { ! has "$@"; }
file_has() { [ -f "$1" ] && grep -qF -- "$2" "$1"; }
same() { [ "$1" = "$2" ]; }

REGISTRY=123456789012.dkr.ecr.ap-northeast-2.amazonaws.com
REPO=$REGISTRY/neuringo/backend
PASSWORD=pw-s3cr3t-value
HMAC=hmac-0123456789abcdef0123456789abcdef
BUCKET=neuringo-dev-backups-fake

mkdir -p "$tmp/bin"

# 가짜 docker. 실행 중인 이미지를 $FAKE/running 에 적어 두고, 호출은 $FAKE/docker.calls 에 남긴다.
cat >"$tmp/bin/docker" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$FAKE/docker.calls"
if [ "$1" = compose ]; then
  shift
  env_file=""
  while [ "$#" -gt 0 ]; do
    case $1 in
      --env-file) env_file=$2; shift 2 ;;
      -f | -p) shift 2 ;;
      *) break ;;
    esac
  done
  sub=$1
  shift
  last=${*: -1}
  case $sub in
    ps)
      if [ "$last" = backend ]; then
        [ -f "$FAKE/running" ] && echo backend-container-id
      else
        [ -f "$FAKE/pg_running" ] && echo pg-container-id
      fi
      exit 0 ;;
    pull) [ "${FAKE_PULL_FAIL:-0}" = 1 ] && exit 1; echo "Pulled $(grep '^APP_IMAGE=' "$env_file")"; exit 0 ;;
    up)
      # DB 는 처음 up 할 때 볼륨과 함께 생긴다. "up … postgres" 는 DB 만 띄운다.
      touch "$FAKE/pg_running" "$FAKE/pg_volume"
      if [ "$last" = postgres ]; then echo "Container neuringo-dev-postgres-1 Started"; exit 0; fi
      if [ "${FAKE_UP_FAIL:-0}" = 1 ]; then echo "Error response from daemon"; exit 1; fi
      sed -n "s/^APP_IMAGE='\(.*\)'$/\1/p" "$env_file" >"$FAKE/running"
      cp "$env_file" "$FAKE/running.env"
      echo "Container neuringo-dev-backend-1 Started"
      exit 0 ;;
    stop) rm -f "$FAKE/running"; echo stopped >>"$FAKE/stopped"; exit 0 ;;
    logs) echo "2026-10-02 ERROR 아동 김하늘 의 요청 처리 실패"; exit 0 ;;
    exec)
      if [[ " $* " == *" pg_restore "* ]]; then
        head -c 5 | grep -q PGDMP
        exit $?
      fi
      [ "${FAKE_DUMP_FAIL:-0}" = 1 ] && exit 1
      echo "PGDMP-fake-dump"
      exit 0 ;;
  esac
  exit 0
fi
case "$1 $2" in
  "volume inspect") [ -f "$FAKE/pg_volume" ]; exit $? ;;
  "inspect --format") cat "$FAKE/running" 2>/dev/null ;;
  "login --username") cat >/dev/null; echo "Login Succeeded" ;;
  "logout "*) echo "Removing login credentials for $2" ;;
  "image ls") cat "$FAKE/images" 2>/dev/null || true ;;
  "image rm") echo "$3" >>"$FAKE/removed" ;;
  "run -d") echo restore-container-id ;;
  "ps -aq") [ "${FAKE_STALE:-0}" = 1 ] && echo stale-restore-id; exit 0 ;;
  "rm -f") echo "${*: -1}" >>"$FAKE/restore_removed" ;;
  "exec -i")
    head -c 9 >"$FAKE/restored"
    grep -q '^PGDMP' "$FAKE/restored" ;;
  "exec "*)
    case "$*" in
      *information_schema*) echo 12 ;;
      *flyway_schema_history*) echo 5 ;;
      *query_to_xml*) echo 42 ;;
    esac ;;
  *) : ;;
esac
EOF

# 가짜 aws. Parameter Store 는 $FAKE/params(이름<TAB>값)에서 읽는다.
cat >"$tmp/bin/aws" <<'EOF'
#!/usr/bin/env bash
case "$1 $2" in
  "ssm get-parameters")
    if [ "${FAKE_SSM_FAIL:-0}" = 1 ]; then
      echo "An error occurred (AccessDeniedException) when calling the GetParameters operation: User: arn:aws:sts::123456789012:assumed-role/ktc-ec2-ssm-role/i-0abc is not authorized" >&2
      exit 254
    fi
    shift 2
    [ "$1" = --names ] && shift
    while [ "$#" -gt 0 ] && [[ "$1" != --* ]]; do
      awk -F'\t' -v name="$1" '$1 == name' "$FAKE/params"
      shift
    done ;;
  "ecr get-login-password") echo fake-ecr-token ;;
  "s3 cp") [ "${FAKE_S3_FAIL:-0}" = 1 ] && exit 1; echo "$4" >>"$FAKE/s3" ;;
esac
EOF

# 가짜 curl. 실행 중인 이미지의 태그가 FAKE_GOOD_TAGS 에 있으면 200.
# FAKE_BAD_ENV 가 떠 있는 컨테이너의 설정(.env)에 들어 있으면 실패한다(설정 탓에 죽는 배포).
cat >"$tmp/bin/curl" <<'EOF'
#!/usr/bin/env bash
if [ -n "${FAKE_BAD_ENV:-}" ] && grep -qF "$FAKE_BAD_ENV" "$FAKE/running.env" 2>/dev/null; then exit 22; fi
running=$(cat "$FAKE/running" 2>/dev/null || true)
tag=${running##*:}
[ -n "$tag" ] && [[ " ${FAKE_GOOD_TAGS:-} " == *" $tag "* ]] && exit 0
exit 22
EOF
chmod +x "$tmp/bin/docker" "$tmp/bin/aws" "$tmp/bin/curl"

new_case() { # 이전 상태를 지우고 Parameter Store 값을 채운다
  rm -rf "${tmp:?}/home" "${tmp:?}/fake"
  mkdir -p "$tmp/home" "$tmp/fake"
  printf '/neuringo/dev/db/name\tneuringo_dev\n/neuringo/dev/db/username\tneuringo\n/neuringo/dev/db/password\t%s\n/neuringo/dev/ai/model\tclaude-haiku\n/neuringo/dev/child-access/hmac-secret\t%s\n' \
    "$PASSWORD" "$HMAC" >"$tmp/fake/params"
}

deploy() { # 태그 — 결과 코드는 $code, 출력은 $out
  code=0
  out=$(PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" NEURINGO_HOME="$tmp/home" IMAGE="$REPO:$1" \
    HEALTH_TIMEOUT=0 HEALTH_INTERVAL=0 BACKUP_BUCKET="$BUCKET" bash deploy/host/deploy.sh 2>&1) || code=$?
}

no_leak() {
  lacks "$out" "$PASSWORD" && lacks "$out" "$HMAC" && lacks "$out" "123456789012" && lacks "$out" "김하늘" &&
    lacks "$out" "Login Succeeded" && lacks "$out" "$BUCKET" && lacks "$out" "is not authorized"
}

echo "deploy.sh · backup.sh · release-bundle.sh 자체 검사"

new_case
export FAKE_GOOD_TAGS="aaa bbb"
deploy aaa
check "첫 배포가 성공한다" same "$code" 0
check "결과 줄을 남긴다" has "$out" "DEPLOY_RESULT=ok"
check "지금 이미지를 기록한다" file_has "$tmp/home/state/dev.current" "$REPO:aaa"
check ".env 에 필수 설정값을 넣는다" file_has "$tmp/home/state/dev.env" "DB_PASSWORD='$PASSWORD'"
check ".env 에 아동 입장 코드 비밀키를 넣는다" file_has "$tmp/home/state/dev.env" "CHILD_ACCESS_HMAC_SECRET='$HMAC'"
check ".env 에 있는 선택 설정값만 넣는다" file_has "$tmp/home/state/dev.env" "AI_MODEL='claude-haiku'"
check ".env 에 없는 선택 설정값은 넣지 않는다" bash -c "! grep -q '^AI_API_KEY=' '$tmp/home/state/dev.env'"
check "출력에 비밀값·계정 ID·로그인 결과가 없다" no_leak
if [ "$(uname -s)" = Linux ]; then
  check ".env 권한은 600" same "$(stat -c %a "$tmp/home/state/dev.env")" 600
fi

touch "$tmp/fake/pg_running"
printf '%s\n' "$REPO:eee" "$REPO:ddd" "$REPO:ccc" "$REPO:bbb" "$REPO:aaa" "$REPO:old1" "$REPO:old2" >"$tmp/fake/images"
deploy bbb
check "두 번째 배포가 성공한다" same "$code" 0
check "직전 이미지를 기록한다" file_has "$tmp/home/state/dev.previous" "$REPO:aaa"
check "배포 전에 백업해 S3 에 올린다" file_has "$tmp/fake/s3" "s3://$BUCKET/dev/pre-deploy/"
check "옛 이미지를 정리한다(지금·직전 + 최근 1개만 남긴다)" same "$(sort "$tmp/fake/removed" | tr '\n' ' ')" "$REPO:ccc $REPO:ddd $REPO:old1 $REPO:old2 "
check "출력에 버킷 이름이 없다" no_leak

export FAKE_GOOD_TAGS="aaa bbb"
deploy zzz
check "상태 확인이 실패하면 2(되돌림)로 끝난다" same "$code" 2
check "직전 이미지로 되돌린다" file_has "$tmp/fake/running" "$REPO:bbb"
check "지금 이미지 기록은 그대로다" file_has "$tmp/home/state/dev.current" "$REPO:bbb"
check ".env 의 이미지도 직전 것으로 고친다" file_has "$tmp/home/state/dev.env" "APP_IMAGE='$REPO:bbb'"
check "앱 로그는 서버 파일에만 남긴다" bash -c "grep -rqF '김하늘' '$tmp/home/logs'"
check "앱 로그를 출력에 싣지 않는다" no_leak

export FAKE_GOOD_TAGS=""
deploy yyy
check "되돌리기도 실패하면 3 으로 끝난다" same "$code" 3
check "그때 backend 를 멈춘다(계속 재시작하며 CPU 크레딧을 태우지 않게)" file_has "$tmp/fake/stopped" stopped

new_case
export FAKE_GOOD_TAGS=""
deploy aaa
check "첫 배포가 실패하면 되돌릴 것이 없어 1 로 끝난다" same "$code" 1
check "실패 결과 줄" has "$out" "DEPLOY_RESULT=failed"
check "첫 배포가 실패하면 backend 를 멈춘다" file_has "$tmp/fake/stopped" stopped
check "첫 배포가 실패해도 .env 는 남긴다(DB 가 이미 이 설정으로 만들어졌다)" test -f "$tmp/home/state/dev.env"
export FAKE_GOOD_TAGS="bbb"
deploy bbb
check "첫 배포가 실패한 뒤에도 다음 배포는 된다" same "$code" 0

# 같은 이미지라도 새 설정값 탓에 죽으면, 옛 .env(옛 설정)로 되돌린다.
new_case
export FAKE_GOOD_TAGS="aaa"
deploy aaa
printf '/neuringo/dev/ai/base-url\thttps://broken.invalid\n' >>"$tmp/fake/params"
FAKE_BAD_ENV=broken.invalid deploy aaa
check "새 설정 탓에 실패하면 옛 설정으로 되돌리고 2 로 끝난다" same "$code" 2
check "지금 .env 는 옛 설정 그대로다" bash -c "! grep -q broken.invalid '$tmp/home/state/dev.env'"
check "되돌린 컨테이너는 옛 설정으로 떠 있다" bash -c "! grep -q broken.invalid '$tmp/fake/running.env'"

# DB 가 멈춰 있어도(볼륨은 있다) 배포 전 백업을 건너뛰지 않는다.
new_case
export FAKE_GOOD_TAGS="aaa bbb"
deploy aaa
rm -f "$tmp/fake/pg_running"
deploy bbb
check "DB 가 멈춰 있으면 DB 만 먼저 띄운다" file_has "$tmp/fake/docker.calls" "up -d --wait postgres"
check "그다음 배포 전 백업을 한다" file_has "$tmp/fake/s3" "/dev/pre-deploy/"
check "그리고 배포가 성공한다" same "$code" 0

new_case
deploy aaa
rm -f "$tmp/home/state/dev.env"
deploy bbb
check "DB 볼륨은 있는데 .env 가 없으면 1 로 멈춘다(사람이 확인)" same "$code" 1
check "멈춘 이유를 말한다" has "$out" ".env 가 없다"

# compose up 이 실패하면(옛 컨테이너가 계속 200 을 돌려줘도) 성공으로 치지 않는다.
new_case
export FAKE_GOOD_TAGS="aaa bbb"
deploy aaa
FAKE_UP_FAIL=1 deploy bbb
check "compose up 이 실패하면 성공으로 치지 않는다" bash -c "[ '$code' != 0 ]"
check "up 이 실패했다고 말한다" has "$out" "compose up 실패"
check "그때 지금 이미지 기록은 그대로다(up 실패)" file_has "$tmp/home/state/dev.current" "$REPO:aaa"

new_case
sed -i '/db\/password/d' "$tmp/fake/params"
deploy aaa
check "필수 설정값이 없으면 1 로 끝난다" same "$code" 1
check "없는 설정값의 이름을 알려 준다" has "$out" "/neuringo/dev/db/password"
check "그때는 컨테이너를 건드리지 않는다" bash -c "[ ! -f '$tmp/fake/docker.calls' ] || ! grep -q ' up ' '$tmp/fake/docker.calls'"

new_case
printf "/neuringo/dev/ai/api-key\tit's-bad\n" >>"$tmp/fake/params"
deploy aaa
check "작은따옴표가 든 값은 거절한다" same "$code" 1
check "거절 이유를 말한다" has "$out" "작은따옴표"

new_case
FAKE_SSM_FAIL=1 deploy aaa
check "Parameter Store 를 못 읽으면 1 로 끝난다" same "$code" 1
check "권한 오류 본문(계정 ID)을 찍지 않는다" no_leak

new_case
export FAKE_GOOD_TAGS="aaa"
deploy aaa
touch "$tmp/fake/pg_running"
FAKE_DUMP_FAIL=1 deploy bbb
check "배포 전 백업이 실패하면 배포하지 않는다" same "$code" 1
check "그때 지금 이미지는 그대로다" file_has "$tmp/fake/running" "$REPO:aaa"

# backup.sh 단독: 최근 KEEP_LOCAL 개만 남긴다.
new_case
deploy aaa
mkdir -p "$tmp/home/backups/daily"
for stamp in 20261001T000000Z 20261002T000000Z 20261003T000000Z 20261004T000000Z; do
  echo PGDMP >"$tmp/home/backups/daily/dev-$stamp.dump"
done
code=0
out=$(PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" NEURINGO_HOME="$tmp/home" LABEL=daily KEEP_LOCAL=3 \
  BACKUP_BUCKET="$BUCKET" bash deploy/host/backup.sh 2>&1) || code=$?
check "매일 백업이 성공한다" same "$code" 0
check "S3 daily 경로에 올린다" file_has "$tmp/fake/s3" "s3://$BUCKET/dev/daily/"
check "서버에는 최근 3개만 남는다" same "$(find "$tmp/home/backups/daily" -name '*.dump' | wc -l | tr -d ' ')" 3
check "가장 오래된 것부터 지운다" bash -c "[ ! -f '$tmp/home/backups/daily/dev-20261001T000000Z.dump' ]"
check "백업 출력에 버킷 이름이 없다" no_leak

# S3 에 못 올려도 서버의 옛 덤프는 정리한다. 같은 서버의 다른 환경(prod) 덤프는 건드리지 않는다.
for stamp in 20261001T000000Z 20261002T000000Z 20261003T000000Z; do
  echo PGDMP >"$tmp/home/backups/daily/dev-$stamp.dump"
done
for stamp in 20261008T000000Z 20261009T000000Z; do
  echo PGDMP >"$tmp/home/backups/daily/prod-$stamp.dump"
done
code=0
out=$(PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" FAKE_S3_FAIL=1 NEURINGO_HOME="$tmp/home" LABEL=daily KEEP_LOCAL=3 \
  BACKUP_BUCKET="$BUCKET" bash deploy/host/backup.sh 2>&1) || code=$?
check "S3 에 못 올리면 1 로 끝난다" same "$code" 1
check "그래도 서버에는 최근 3개만 남긴다" same "$(find "$tmp/home/backups/daily" -name 'dev-*.dump' | wc -l | tr -d ' ')" 3
check "다른 환경(prod) 덤프는 지우지 않는다" same "$(find "$tmp/home/backups/daily" -name 'prod-*.dump' | wc -l | tr -d ' ')" 2
check "S3 실패 출력에도 버킷 이름이 없다" no_leak

# restore-check.sh: 최신 백업을 서버의 임시 postgres 에 복구해 보고 숫자만 남긴다.
restore() {
  code=0
  out=$(PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" NEURINGO_HOME="$tmp/home" RESTORE_WAIT=1 \
    bash deploy/host/restore-check.sh 2>&1) || code=$?
}
new_case
mkdir -p "$tmp/home/backups/daily"
echo PGDMP-old >"$tmp/home/backups/daily/dev-20261003T000000Z.dump"
echo PGDMP-new >"$tmp/home/backups/daily/dev-20261004T000000Z.dump"
restore
check "복구 확인이 성공한다" same "$code" 0
check "가장 최근 백업으로 복구한다" file_has "$tmp/fake/restored" "PGDMP-new"
check "테이블 수·마이그레이션 버전·행 수를 남긴다" has "$out" "복구 성공: 테이블 12개, 마이그레이션 V5, 행 42개"
check "결과 줄을 남긴다(워크플로가 읽는다)" has "$out" "RESTORE_ROWS=42"
check "끝나면 임시 컨테이너를 지운다" file_has "$tmp/fake/restore_removed" "neuringo-restore-check-"

check "임시 postgres 는 네트워크 없이 띄운다(서버의 다른 프로세스도 못 붙는다)" file_has "$tmp/fake/docker.calls" "--network none"
check "임시 컨테이너를 지울 때 데이터 볼륨까지 지운다(복구한 DB 사본이 남지 않게)" file_has "$tmp/fake/docker.calls" "rm -f -v"

echo PGDMP-prod >"$tmp/home/backups/daily/prod-20261009T000000Z.dump"
FAKE_STALE=1 restore
check "다른 환경(prod) 덤프가 더 새것이어도 이 환경의 최신 백업으로 복구한다" file_has "$tmp/fake/restored" "PGDMP-new"
check "지난번에 못 지운 임시 컨테이너를 먼저 지운다" file_has "$tmp/fake/restore_removed" "stale-restore-id"
rm -f "$tmp/home/backups/daily/prod-20261009T000000Z.dump"

echo garbage >"$tmp/home/backups/daily/dev-20261005T000000Z.dump"
rm -f "$tmp/fake/restore_removed"
restore
check "깨진 백업이면 1 로 끝난다" same "$code" 1
check "실패해도 임시 컨테이너를 지운다" file_has "$tmp/fake/restore_removed" "neuringo-restore-check-"

rm -f "$tmp/home/backups/daily"/*.dump
restore
check "백업이 없으면 1 로 끝난다" same "$code" 1

# release-bundle.sh: 묶음 → 서버 스크립트 → deploy.sh 를 한 번에 돌린다(deploy.yml 이 SSM 으로 보내는 것과 같다).
new_case
export FAKE_GOOD_TAGS="fff"
SHA=0123456789abcdef0123456789abcdef01234567
remote="$tmp/remote.sh"
IMAGE="$REPO:fff" RELEASE=$SHA BACKUP_BUCKET="$BUCKET" bash scripts/release-bundle.sh >"$remote"
remote_run() {
  code=0
  out=$(PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" NEURINGO_HOME="$tmp/home" HEALTH_TIMEOUT=0 HEALTH_INTERVAL=0 \
    bash "$remote" 2>&1) || code=$?
}
remote_run
check "배포 묶음 스크립트로 배포가 성공한다" same "$code" 0
check "묶음을 releases/<SHA> 에 푼다" test -f "$tmp/home/releases/$SHA/deploy/host/deploy.sh"
check "current 가 새 묶음을 가리킨다" test -f "$tmp/home/current/deploy/host/backup.sh"
check "묶음 스크립트 출력에도 비밀값·계정 ID 가 없다" no_leak

# 같은 SHA 를 다시 배포해도(폴더 시각이 옛날 그대로) 정리할 때 그 묶음을 지우지 않는다.
# Git Bash(Windows)는 심볼릭 링크를 폴더 복사로 흉내 내서 current 를 다시 바꿀 수 없다. 리눅스(CI·서버)에서만 본다.
if [ "$(uname -s)" = Linux ]; then
  for n in 1 2 3 4 5 6; do mkdir -p "$tmp/home/releases/other$n"; done
  touch -d '2020-01-01' "$tmp/home/releases/$SHA"
  remote_run
  check "같은 SHA 를 다시 배포해도 성공한다" same "$code" 0
  check "그 묶음을 정리하며 지우지 않는다" test -f "$tmp/home/releases/$SHA/deploy/host/deploy.sh"
  check "current 가 가리키는 묶음이 남아 있다(매일 백업이 쓴다)" test -f "$tmp/home/current/deploy/host/backup.sh"
fi

new_case
sed -i 's/^if ! echo "[0-9a-f]\{64\}/if ! echo "0000000000000000000000000000000000000000000000000000000000000000/' "$remote"
remote_run
check "SHA256 이 다르면 풀지 않고 1 로 끝난다" same "$code" 1
check "묶음이 깨졌다고 말한다" has "$out" "깨졌다"
check "그때는 컨테이너를 건드리지 않는다(깨진 묶음)" bash -c "[ ! -f '$tmp/fake/docker.calls' ]"

code=0
IMAGE="$REPO:fff'; rm -rf /" RELEASE=abc1234 bash scripts/release-bundle.sh >/dev/null 2>&1 || code=$?
check "이미지 주소에 따옴표가 있으면 서버 스크립트를 만들지 않는다" same "$code" 1
code=0
IMAGE="$REPO:fff" RELEASE="main" bash scripts/release-bundle.sh >/dev/null 2>&1 || code=$?
check "RELEASE 가 커밋 SHA 가 아니면 만들지 않는다" same "$code" 1

if [ "$failures" -gt 0 ]; then
  echo "deploy.sh · backup.sh · release-bundle.sh 자체 검사 실패: $failures 건" >&2
  exit 1
fi
echo "deploy.sh · backup.sh · release-bundle.sh 자체 검사 통과"
