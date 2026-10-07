#!/usr/bin/env bash
# deploy/host/deploy.sh · status.sh · backup.sh · restore-check.sh · release-bundle.sh 자체 검사.
# 가짜 docker·aws·curl·iptables 로 서버 배포를 흉내 낸다(실제 서버·AWS 필요 없음).
# - develop: 성공하면 기록하고 옛 이미지를 정리한다 / 같은 커밋이면 건너뛴다 / 실패하면 직전 상태로 되돌린다
# - preview: 미리보기 DB·계정을 새로 만들고 자리를 잡는다 / 다른 PR 은 거절한다 / 실패·끝내기는 미리보기 전 develop 으로
# - 출력(SSM → Actions 로그, 공개)에 비밀값·계정 ID·앱 로그·버킷 이름·테스트 주소가 나오지 않는다
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
file_lacks() { [ ! -f "$1" ] || ! grep -qF -- "$2" "$1"; }
same() { [ "$1" = "$2" ]; }
value() { sed -n "s/^$1=//p" <<<"$out" | tail -n 1; }

REGISTRY=123456789012.dkr.ecr.ap-northeast-2.amazonaws.com
REPO=$REGISTRY/neuringo/backend
PASSWORD='pw-s3cr3t-value'
HMAC=hmac-0123456789abcdef0123456789abcdef
BUCKET=neuringo-dev-backups-fake
DOMAIN=d1abcdefghijkl.cloudfront.net

mkdir -p "$tmp/bin"

# 가짜 docker. 떠 있는 backend·web 이미지를 $FAKE/running·running_web 에 적어 두고, 호출은 $FAKE/docker.calls 에 남긴다.
# compose 파일에 web 서비스가 없으면(옛 묶음) web 을 내리고 backend 가 직접 80 을 받는 것으로 본다($FAKE/noweb).
cat >"$tmp/bin/docker" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$FAKE/docker.calls"
if [ "$1" = compose ]; then
  shift
  env_file=""
  compose_file=""
  while [ "$#" -gt 0 ]; do
    case $1 in
      --env-file) env_file=$2; shift 2 ;;
      -f) compose_file=$2; shift 2 ;;
      -p) shift 2 ;;
      *) break ;;
    esac
  done
  sub=$1
  shift
  last=${*: -1}
  env_get() { sed -n "s/^$1='\(.*\)'$/\1/p" "$env_file"; }
  case $sub in
    ps)
      case $last in
        backend) [ -f "$FAKE/running" ] && echo backend-container-id ;;
        web) [ -f "$FAKE/running_web" ] && echo web-container-id ;;
        *) [ -f "$FAKE/pg_running" ] && echo pg-container-id ;;
      esac
      exit 0 ;;
    pull) [ "${FAKE_PULL_FAIL:-0}" = 1 ] && exit 1; echo "Pulled $(env_get APP_IMAGE)"; exit 0 ;;
    up)
      # DB 는 처음 up 할 때 볼륨과 함께 생긴다. "up … postgres" 는 DB 만 띄운다.
      touch "$FAKE/pg_running" "$FAKE/pg_volume"
      if [ "$last" = postgres ]; then echo "Container neuringo-dev-postgres-1 Started"; exit 0; fi
      if [ "${FAKE_UP_FAIL:-0}" = 1 ]; then echo "Error response from daemon"; exit 1; fi
      env_get APP_IMAGE >"$FAKE/running"
      cp "$env_file" "$FAKE/running.env"
      if grep -q '^  web:' "$compose_file"; then
        env_get WEB_IMAGE >"$FAKE/running_web"
        rm -f "$FAKE/noweb"
      else
        rm -f "$FAKE/running_web"
        touch "$FAKE/noweb"
      fi
      echo "Container neuringo-dev-backend-1 Started"
      exit 0 ;;
    stop) rm -f "$FAKE/running" "$FAKE/running_web"; echo stopped >>"$FAKE/stopped"; exit 0 ;;
    logs) echo "2026-10-02 ERROR 아동 김하늘 의 요청 처리 실패"; exit 0 ;;
    exec)
      case "$*" in
        *flyway_schema_history*) echo 5; exit 0 ;;
        *ON_ERROR_STOP*)
          cat >>"$FAKE/sql"
          [ "${FAKE_PSQL_FAIL:-0}" = 1 ] && exit 1
          exit 0 ;;
        *" pg_restore "*)
          head -c 5 | grep -q PGDMP
          exit $? ;;
      esac
      [ "${FAKE_DUMP_FAIL:-0}" = 1 ] && exit 1
      echo "PGDMP-fake-dump"
      exit 0 ;;
  esac
  exit 0
fi
case "$1 $2" in
  "volume inspect") [ -f "$FAKE/pg_volume" ]; exit $? ;;
  "inspect --format")
    case ${*: -1} in
      web-container-id) cat "$FAKE/running_web" 2>/dev/null ;;
      *) cat "$FAKE/running" 2>/dev/null ;;
    esac ;;
  "login --username") cat >/dev/null; echo "Login Succeeded" ;;
  "logout "*) echo "Removing login credentials for $2" ;;
  "image ls") cat "$FAKE/images" 2>/dev/null || true ;;
  "image rm") echo "$3" >>"$FAKE/removed" ;;
  "ps --filter")
    [ -f "$FAKE/running" ] && echo "backend:running"
    [ -f "$FAKE/running_web" ] && echo "web:running"
    [ -f "$FAKE/pg_running" ] && echo "postgres:running"
    exit 0 ;;
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

# 가짜 curl. API 는 backend 태그가 FAKE_GOOD_TAGS 에 있고 web(또는 옛 묶음이라 web 없음)이 떠 있으면 200.
# 화면은 web 이 떠 있으면 200 이고 헤더에 web 이미지의 커밋을 싣는다(FAKE_WEB_STALE=1 이면 엉뚱한 커밋).
# FAKE_BAD_ENV 가 떠 있는 컨테이너의 설정(.env)에 들어 있으면 API 가 죽는다(설정 탓에 죽는 배포).
cat >"$tmp/bin/curl" <<'EOF'
#!/usr/bin/env bash
url=${*: -1}
fail=0 headers=0 code_out=0
for a in "$@"; do
  case $a in
    -D) headers=1 ;;
    -w) code_out=1 ;;
    --*) ;;
    -*f*) fail=1 ;;
  esac
done
backend=$(cat "$FAKE/running" 2>/dev/null || true)
web=$(cat "$FAKE/running_web" 2>/dev/null || true)
tag=${backend##*:}
api_ok=0
if [ -n "$tag" ] && [[ " ${FAKE_GOOD_TAGS:-} " == *" $tag "* ]] && { [ -n "$web" ] || [ -f "$FAKE/noweb" ]; }; then api_ok=1; fi
if [ -n "${FAKE_BAD_ENV:-}" ] && grep -qF "$FAKE_BAD_ENV" "$FAKE/running.env" 2>/dev/null; then api_ok=0; fi
release=${web##*:web-}
[ "${FAKE_WEB_STALE:-0}" = 1 ] && release=0000000
case $url in
  */api/v1/csrf) if [ "$api_ok" = 1 ]; then code=200; else code=502; fi ;;
  *) if [ -n "$web" ]; then code=200; else code=000; fi ;;
esac
[ "$code" = 000 ] && { [ "$code_out" = 1 ] && printf 000; exit 7; }
if [ "$headers" = 1 ]; then
  printf 'HTTP/1.1 %s\r\n' "$code"
  [ -n "$web" ] && printf 'X-Neuringo-Release: %s\r\n' "$release"
  printf '\r\n'
fi
[ "$code_out" = 1 ] && printf '%s' "$code"
[ "$fail" = 1 ] && [ "$code" != 200 ] && exit 22
exit 0
EOF

# 가짜 iptables. FAKE_IMDS=1 이면 컨테이너 IMDS 차단 규칙이 없는 서버다.
# shellcheck disable=SC2016 # 가짜 명령 안에서 풀린다
printf '#!/usr/bin/env bash\nexit "${FAKE_IMDS:-0}"\n' >"$tmp/bin/iptables"
chmod +x "$tmp/bin/docker" "$tmp/bin/aws" "$tmp/bin/curl" "$tmp/bin/iptables"

new_case() { # 이전 상태를 지우고 Parameter Store 값을 채운다
  rm -rf "${tmp:?}/home" "${tmp:?}/fake"
  mkdir -p "$tmp/home" "$tmp/fake"
  printf '/neuringo/dev/db/name\tneuringo_dev\n/neuringo/dev/db/username\tneuringo\n/neuringo/dev/db/password\t%s\n/neuringo/dev/ai/model\tclaude-haiku\n/neuringo/dev/child-access/hmac-secret\t%s\n' \
    "$PASSWORD" "$HMAC" >"$tmp/fake/params"
}

# 커밋(7자리 hex) — 백엔드 $REPO:<커밋>, 화면 $REPO:web-<커밋>. 결과 코드는 $code, 출력은 $out
deploy() {
  code=0
  out=$(PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" NEURINGO_HOME="$tmp/home" IMAGE="$REPO:$1" WEB_IMAGE="$REPO:web-$1" \
    RELEASE="$1" HEALTH_TIMEOUT=0 HEALTH_INTERVAL=0 BACKUP_BUCKET="$BUCKET" bash deploy/host/deploy.sh 2>&1) || code=$?
}
preview() { # 커밋 PR 아이디
  code=0
  out=$(PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" NEURINGO_HOME="$tmp/home" IMAGE="$REPO:$1" WEB_IMAGE="$REPO:web-$1" \
    RELEASE="$1" MODE=preview PREVIEW_PR="$2" PREVIEW_BY="$3" HEALTH_TIMEOUT=0 HEALTH_INTERVAL=0 BACKUP_BUCKET="$BUCKET" \
    bash deploy/host/deploy.sh 2>&1) || code=$?
}
status() { # [EDGE_DOMAIN]
  code=0
  out=$(PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" NEURINGO_HOME="$tmp/home" EDGE_DOMAIN="${1:-}" \
    bash deploy/host/status.sh 2>&1) || code=$?
}

no_leak() {
  lacks "$out" "$PASSWORD" && lacks "$out" "$HMAC" && lacks "$out" "123456789012" && lacks "$out" "김하늘" &&
    lacks "$out" "Login Succeeded" && lacks "$out" "$BUCKET" && lacks "$out" "is not authorized" && lacks "$out" "$DOMAIN"
}
preview_password() { sed -n "s/^APP_DB_PASSWORD='\(.*\)'$/\1/p" "$tmp/home/state/dev.env"; }

echo "deploy.sh · status.sh · backup.sh · restore-check.sh · release-bundle.sh 자체 검사"

# ── develop ────────────────────────────────────────────
new_case
export FAKE_GOOD_TAGS="aaaaaaa bbbbbbb"
deploy aaaaaaa
check "첫 배포가 성공한다" same "$code" 0
check "결과 줄을 남긴다" has "$out" "DEPLOY_RESULT=ok"
check "지금 커밋을 기록한다(레지스트리 주소 없이)" file_has "$tmp/home/state/dev.release" "RELEASE=aaaaaaa"
check "develop 으로 기록한다" file_has "$tmp/home/state/dev.release" "MODE=develop"
check "기록에 레지스트리 주소가 없다" file_lacks "$tmp/home/state/dev.release" "$REGISTRY"
check "web·backend 가 같은 커밋으로 뜬다" file_has "$tmp/fake/running_web" "$REPO:web-aaaaaaa"
check ".env 에 화면 이미지·커밋을 넣는다" file_has "$tmp/home/state/dev.env" "WEB_IMAGE='$REPO:web-aaaaaaa'"
check ".env 에 필수 설정값을 넣는다" file_has "$tmp/home/state/dev.env" "DB_PASSWORD='$PASSWORD'"
check ".env 에 아동 입장 코드 비밀키를 넣는다" file_has "$tmp/home/state/dev.env" "CHILD_ACCESS_HMAC_SECRET='$HMAC'"
check ".env 에 있는 선택 설정값만 넣는다" file_has "$tmp/home/state/dev.env" "AI_MODEL='claude-haiku'"
check ".env 에 없는 선택 설정값은 넣지 않는다" bash -c "! grep -q '^AI_API_KEY=' '$tmp/home/state/dev.env'"
check "develop 에는 미리보기 DB 설정이 없다" file_lacks "$tmp/home/state/dev.env" "APP_DB_"
check "Flyway 버전을 알려 준다" has "$out" "FLYWAY_VERSION=V5"
check "출력에 비밀값·계정 ID·로그인 결과가 없다" no_leak
if [ "$(uname -s)" = Linux ]; then
  check ".env 권한은 600" same "$(stat -c %a "$tmp/home/state/dev.env")" 600
fi

touch "$tmp/fake/pg_running"
printf '%s\n' "$REPO:eee" "$REPO:web-eee" "$REPO:ddd" "$REPO:web-ddd" "$REPO:ccc" "$REPO:web-ccc" \
  "$REPO:bbbbbbb" "$REPO:web-bbbbbbb" "$REPO:aaaaaaa" "$REPO:web-aaaaaaa" "$REPO:old1" >"$tmp/fake/images"
deploy bbbbbbb
check "두 번째 배포가 성공한다" same "$code" 0
check "배포 전에 백업해 S3 에 올린다" file_has "$tmp/fake/s3" "s3://$BUCKET/dev/pre-deploy/"
check "옛 이미지를 정리한다(지금·직전 두 쌍 + 최근 2개만 남긴다)" \
  same "$(sort "$tmp/fake/removed" | tr '\n' ' ')" "$REPO:ccc $REPO:ddd $REPO:old1 $REPO:web-ccc $REPO:web-ddd "
check "출력에 버킷 이름이 없다" no_leak

rm -f "$tmp/fake/docker.calls"
deploy bbbbbbb
check "같은 커밋이 이미 떠 있으면 아무것도 바꾸지 않는다" has "$out" "DEPLOY_RESULT=unchanged"
check "그때 컨테이너를 다시 띄우지 않는다" bash -c "[ ! -f '$tmp/fake/docker.calls' ] || ! grep -q ' up ' '$tmp/fake/docker.calls'"
code=0
out=$(PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" NEURINGO_HOME="$tmp/home" IMAGE="$REPO:bbbbbbb" WEB_IMAGE="$REPO:web-bbbbbbb" \
  RELEASE=bbbbbbb FORCE=1 HEALTH_TIMEOUT=0 HEALTH_INTERVAL=0 bash deploy/host/deploy.sh 2>&1) || code=$?
check "FORCE=1 이면 같은 커밋도 다시 띄운다(설정값만 바꿀 때)" has "$out" "DEPLOY_RESULT=ok"

deploy ccccccc
check "상태 확인이 실패하면 2(되돌림)로 끝난다" same "$code" 2
check "직전 이미지로 되돌린다" file_has "$tmp/fake/running" "$REPO:bbbbbbb"
check "화면도 직전 것으로 되돌린다" file_has "$tmp/fake/running_web" "$REPO:web-bbbbbbb"
check "지금 커밋 기록은 그대로다" file_has "$tmp/home/state/dev.release" "RELEASE=bbbbbbb"
check ".env 의 이미지도 직전 것으로 고친다" file_has "$tmp/home/state/dev.env" "APP_IMAGE='$REPO:bbbbbbb'"
check "앱 로그는 서버 파일에만 남긴다" bash -c "grep -rqF '김하늘' '$tmp/home/logs'"
check "앱 로그를 출력에 싣지 않는다" no_leak

export FAKE_GOOD_TAGS="aaaaaaa bbbbbbb ddddddd"
FAKE_WEB_STALE=1 deploy ddddddd
check "화면 헤더의 커밋이 다르면(옛 화면이 응답) 성공으로 치지 않는다" bash -c "[ '$code' != 0 ]"

export FAKE_GOOD_TAGS=""
deploy fffffff
check "되돌리기도 실패하면 3 으로 끝난다" same "$code" 3
check "그때 web·backend 를 멈춘다(계속 재시작하며 CPU 크레딧을 태우지 않게)" file_has "$tmp/fake/stopped" stopped

new_case
export FAKE_GOOD_TAGS=""
deploy aaaaaaa
check "첫 배포가 실패하면 되돌릴 것이 없어 1 로 끝난다" same "$code" 1
check "실패 결과 줄" has "$out" "DEPLOY_RESULT=failed"
check "첫 배포가 실패하면 멈춘다" file_has "$tmp/fake/stopped" stopped
check "첫 배포가 실패해도 .env 는 남긴다(DB 가 이미 이 설정으로 만들어졌다)" test -f "$tmp/home/state/dev.env"
export FAKE_GOOD_TAGS="bbbbbbb"
deploy bbbbbbb
check "첫 배포가 실패한 뒤에도 다음 배포는 된다" same "$code" 0

# 같은 이미지라도 새 설정값 탓에 죽으면, 옛 .env(옛 설정)로 되돌린다.
new_case
export FAKE_GOOD_TAGS="aaaaaaa"
deploy aaaaaaa
printf '/neuringo/dev/ai/base-url\thttps://broken.invalid\n' >>"$tmp/fake/params"
FAKE_BAD_ENV=broken.invalid FORCE=1 deploy aaaaaaa
check "새 설정 탓에 실패하면 옛 설정으로 되돌리고 2 로 끝난다" same "$code" 2
check "지금 .env 는 옛 설정 그대로다" bash -c "! grep -q broken.invalid '$tmp/home/state/dev.env'"
check "되돌린 컨테이너는 옛 설정으로 떠 있다" bash -c "! grep -q broken.invalid '$tmp/fake/running.env'"

# 화면이 없던 옛 배포(backend 가 80 을 직접 받던 때)에서 처음 올린 화면 배포가 실패해도 옛 묶음으로 돌아간다.
new_case
export FAKE_GOOD_TAGS="0ld0000"
mkdir -p "$tmp/home/state" "$tmp/home/current/deploy"
sed '/^  web:/,$d' deploy/compose.dev.yml >"$tmp/home/current/deploy/compose.dev.yml"
printf "APP_IMAGE='%s'\nDB_NAME='neuringo_dev'\nDB_USERNAME='neuringo'\nDB_PASSWORD='%s'\n" "$REPO:0ld0000" "$PASSWORD" >"$tmp/home/state/dev.env"
echo "$REPO:0ld0000" >"$tmp/fake/running"
touch "$tmp/fake/noweb" "$tmp/fake/pg_running" "$tmp/fake/pg_volume"
deploy aaaaaaa
check "첫 화면 배포가 실패하면 2 로 끝난다" same "$code" 2
check "옛 묶음(web 없음)의 backend 로 돌아간다" file_has "$tmp/fake/running" "$REPO:0ld0000"
check "그때 web 은 내린다(옛 backend 가 80 을 받게)" test ! -f "$tmp/fake/running_web"

# DB 가 멈춰 있어도(볼륨은 있다) 배포 전 백업을 건너뛰지 않는다.
new_case
export FAKE_GOOD_TAGS="aaaaaaa bbbbbbb"
deploy aaaaaaa
rm -f "$tmp/fake/pg_running"
deploy bbbbbbb
check "DB 가 멈춰 있으면 DB 만 먼저 띄운다" file_has "$tmp/fake/docker.calls" "up -d --wait postgres"
check "그다음 배포 전 백업을 한다" file_has "$tmp/fake/s3" "/dev/pre-deploy/"
check "그리고 배포가 성공한다" same "$code" 0

new_case
deploy aaaaaaa
rm -f "$tmp/home/state/dev.env"
deploy bbbbbbb
check "DB 볼륨은 있는데 .env 가 없으면 1 로 멈춘다(사람이 확인)" same "$code" 1
check "멈춘 이유를 말한다" has "$out" ".env 가 없다"

# compose up 이 실패하면(옛 컨테이너가 계속 200 을 돌려줘도) 성공으로 치지 않는다.
new_case
export FAKE_GOOD_TAGS="aaaaaaa bbbbbbb"
deploy aaaaaaa
FAKE_UP_FAIL=1 deploy bbbbbbb
check "compose up 이 실패하면 성공으로 치지 않는다" bash -c "[ '$code' != 0 ]"
check "up 이 실패했다고 말한다" has "$out" "compose up 실패"
check "그때 지금 커밋 기록은 그대로다(up 실패)" file_has "$tmp/home/state/dev.release" "RELEASE=aaaaaaa"

new_case
sed -i '/db\/password/d' "$tmp/fake/params"
deploy aaaaaaa
check "필수 설정값이 없으면 1 로 끝난다" same "$code" 1
check "없는 설정값의 이름을 알려 준다" has "$out" "/neuringo/dev/db/password"
check "그때는 컨테이너를 건드리지 않는다" bash -c "[ ! -f '$tmp/fake/docker.calls' ] || ! grep -q ' up ' '$tmp/fake/docker.calls'"

new_case
printf "/neuringo/dev/ai/api-key\tit's-bad\n" >>"$tmp/fake/params"
deploy aaaaaaa
check "작은따옴표가 든 값은 거절한다" same "$code" 1
check "거절 이유를 말한다" has "$out" "작은따옴표"

new_case
FAKE_SSM_FAIL=1 deploy aaaaaaa
check "Parameter Store 를 못 읽으면 1 로 끝난다" same "$code" 1
check "권한 오류 본문(계정 ID)을 찍지 않는다" no_leak

new_case
export FAKE_GOOD_TAGS="aaaaaaa"
deploy aaaaaaa
touch "$tmp/fake/pg_running"
FAKE_DUMP_FAIL=1 deploy bbbbbbb
check "배포 전 백업이 실패하면 배포하지 않는다" same "$code" 1
check "그때 지금 이미지는 그대로다" file_has "$tmp/fake/running" "$REPO:aaaaaaa"

code=0
out=$(PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" NEURINGO_HOME="$tmp/home" IMAGE="$REPO:x" WEB_IMAGE="$REPO:web-x" \
  RELEASE="main" bash deploy/host/deploy.sh 2>&1) || code=$?
check "RELEASE 가 커밋 SHA 가 아니면 1 로 끝난다" same "$code" 1

# ── preview ────────────────────────────────────────────
new_case
export FAKE_GOOD_TAGS="aaaaaaa 4141414 4141415 4545454 4141416 fffffff"
deploy aaaaaaa
s3_count() { if [ -f "$tmp/fake/s3" ]; then wc -l <"$tmp/fake/s3" | tr -d ' '; else echo 0; fi; }
s3_before=$(s3_count)
preview 4141414 41 alice-kim
check "미리보기가 성공한다" same "$code" 0
check "미리보기로 기록한다" file_has "$tmp/home/state/dev.release" "MODE=preview"
check "PR 번호를 기록한다" file_has "$tmp/home/state/dev.release" "PR=41"
check "자리를 잡는다(PR·누가·끝나는 시각)" bash -c "grep -q '^PR=41$' '$tmp/home/state/preview.lease' && grep -q '^BY=alice-kim$' '$tmp/home/state/preview.lease' && grep -q '^UNTIL=[0-9]' '$tmp/home/state/preview.lease'"
check "끝나는 시각을 알려 준다" has "$out" "PREVIEW_UNTIL=20"
check "미리보기 DB 를 새로 만들었다고 알려 준다" has "$out" "PREVIEW_DB=fresh"
check "develop 상태를 되돌림 기준점으로 남긴다" file_has "$tmp/home/state/dev.base.env" "APP_IMAGE='$REPO:aaaaaaa'"
check "미리보기는 기본 DB 를 백업하지 않는다" same "$(s3_count)" "$s3_before"
check "지난 미리보기 DB 를 지우고 새로 만든다" file_has "$tmp/fake/sql" "DROP DATABASE IF EXISTS neuringo_preview WITH (FORCE);"
check "미리보기 DB 의 주인은 미리보기 계정이다" file_has "$tmp/fake/sql" "CREATE DATABASE neuringo_preview OWNER neuringo_preview;"
check "기본 DB 에 PUBLIC 접속을 막는다(미리보기 계정이 못 붙게)" file_has "$tmp/fake/sql" 'REVOKE CONNECT ON DATABASE "neuringo_dev" FROM PUBLIC;'
check "postgres·template1 도 막는다" file_has "$tmp/fake/sql" "REVOKE CONNECT ON DATABASE template1 FROM PUBLIC;"
check "앱은 미리보기 계정으로 붙는다" file_has "$tmp/home/state/dev.env" "APP_DB_USERNAME='neuringo_preview'"
check "미리보기 DB 주소" file_has "$tmp/home/state/dev.env" "APP_DB_URL='jdbc:postgresql://postgres:5432/neuringo_preview'"
pw=$(preview_password)
check "미리보기 계정 비밀번호는 무작위 hex 다" bash -c "[[ '$pw' =~ ^[0-9a-f]{48}$ ]]"
check "SQL 의 비밀번호와 .env 의 비밀번호가 같다" file_has "$tmp/fake/sql" "PASSWORD '$pw'"
check "미리보기에는 아동 입장 코드 비밀키를 따로 쓴다" file_lacks "$tmp/home/state/dev.env" "$HMAC"
check "출력에 미리보기 비밀번호가 없다" lacks "$out" "$pw"
check "미리보기 출력에도 비밀값·계정 ID 가 없다" no_leak

deploy bbbbbbb
check "미리보기 중에는 develop 자동 배포를 75 로 건너뛴다" same "$code" 75
check "누가 자리를 잡고 있는지 알려 준다" has "$out" "PREVIEW_HOLDER_PR=41"
check "그때 미리보기는 그대로 떠 있다" file_has "$tmp/fake/running" "$REPO:4141414"

preview 4545454 45 bob
check "다른 PR 이 자리를 잡고 있으면 76 으로 멈춘다" same "$code" 76
check "자리 주인·끝나는 시각을 알려 준다" bash -c "grep -q 'PREVIEW_HOLDER_BY=alice-kim' <<<\"\$0\" && grep -q 'PREVIEW_HOLDER_UNTIL=20' <<<\"\$0\"" "$out"
check "그때 아무것도 바꾸지 않는다" file_has "$tmp/fake/running" "$REPO:4141414"

status "$DOMAIN"
check "상태: 미리보기 중이라고 알려 준다" has "$out" "STATUS_MODE=preview"
check "상태: 자리 주인" has "$out" "LEASE_PR=41"
check "상태: 컨테이너" has "$out" "web:running"
check "상태: 서버에서 CloudFront 로 API 를 불러 본다" has "$out" "EDGE_API=200"
check "상태: 화면 헤더의 커밋이 지금 것과 같다" has "$out" "EDGE_RELEASE_MATCH=yes"
check "상태: 출력에 테스트 주소가 없다" no_leak
status "evil.example.com"
check "상태: CloudFront 주소 모양이 아니면 부르지 않는다" has "$out" "EDGE_CHECK=주소 모양이 틀렸다"

preview 4141415 41 alice-kim
check "같은 PR 은 다시 올릴 수 있다(새 커밋·자리 연장)" same "$code" 0
check "기준점은 처음 develop 그대로다" file_has "$tmp/home/state/dev.base.env" "APP_IMAGE='$REPO:aaaaaaa'"

preview 9999999 41 alice-kim
check "미리보기가 실패하면 2 로 끝난다" same "$code" 2
check "다른 PR 이 아니라 미리보기 전 develop 으로 돌아간다" file_has "$tmp/fake/running" "$REPO:aaaaaaa"
check "develop 으로 기록한다(되돌림)" file_has "$tmp/home/state/dev.release" "MODE=develop"
check "자리를 비운다" test ! -f "$tmp/home/state/preview.lease"
check "기준점을 지운다" test ! -f "$tmp/home/state/dev.base.env"
check "미리보기 DB 를 지운다" file_has "$tmp/fake/sql" "DROP ROLE IF EXISTS neuringo_preview;"
check "되돌린 .env 에는 미리보기 DB 설정이 없다" file_lacks "$tmp/home/state/dev.env" "APP_DB_"

preview 4141416 41 alice-kim
rm -f "$tmp/fake/sql"
code=0
out=$(PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" NEURINGO_HOME="$tmp/home" IMAGE="$REPO:fffffff" WEB_IMAGE="$REPO:web-fffffff" \
  RELEASE=fffffff END_PREVIEW=1 HEALTH_TIMEOUT=0 HEALTH_INTERVAL=0 BACKUP_BUCKET="$BUCKET" bash deploy/host/deploy.sh 2>&1) || code=$?
check "끝내기: develop 최신으로 돌아간다" same "$code" 0
check "끝내기: 미리보기가 끝났다고 알려 준다" has "$out" "PREVIEW_ENDED=1"
check "끝내기: develop 으로 기록한다" file_has "$tmp/home/state/dev.release" "RELEASE=fffffff"
check "끝내기: 자리·기준점을 지운다" bash -c "[ ! -f '$tmp/home/state/preview.lease' ] && [ ! -f '$tmp/home/state/dev.base.env' ]"
check "끝내기: 미리보기 DB 를 지운다" file_has "$tmp/fake/sql" "DROP DATABASE IF EXISTS neuringo_preview WITH (FORCE);"
check "끝내기: develop 으로 돌아갈 때는 배포 전 백업을 한다" file_has "$tmp/fake/s3" "/dev/pre-deploy/"

preview 4141414 41 alice-kim
export FAKE_GOOD_TAGS="aaaaaaa 4141414 fffffff"
code=0
out=$(PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" NEURINGO_HOME="$tmp/home" IMAGE="$REPO:8888888" WEB_IMAGE="$REPO:web-8888888" \
  RELEASE=8888888 END_PREVIEW=1 HEALTH_TIMEOUT=0 HEALTH_INTERVAL=0 BACKUP_BUCKET="$BUCKET" bash deploy/host/deploy.sh 2>&1) || code=$?
check "끝내기가 실패하면 2 로 끝난다" same "$code" 2
check "끝내기가 실패해도 PR 이 아니라 미리보기 전 develop 으로 돌아간다" file_has "$tmp/fake/running" "$REPO:fffffff"
check "그때도 자리를 비운다" test ! -f "$tmp/home/state/preview.lease"

# PR 라벨을 떼거나 PR 을 닫을 때(END_ONLY_PR): 그 PR 의 미리보기일 때만 끝낸다.
export FAKE_GOOD_TAGS="aaaaaaa 4141414 4545454 fffffff"
preview 4141414 41 alice-kim
end_only() { # PR번호
  code=0
  out=$(PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" NEURINGO_HOME="$tmp/home" IMAGE="$REPO:fffffff" WEB_IMAGE="$REPO:web-fffffff"     RELEASE=fffffff END_PREVIEW=1 END_ONLY_PR="$1" HEALTH_TIMEOUT=0 HEALTH_INTERVAL=0 bash deploy/host/deploy.sh 2>&1) || code=$?
}
end_only 45
check "다른 PR(#45)의 라벨을 떼도 #41 미리보기는 내리지 않는다(75)" same "$code" 75
check "끝낼 것이 없다고 알려 준다" has "$out" "DEPLOY_RESULT=not_this_pr"
check "그때 #41 미리보기는 그대로다" file_has "$tmp/fake/running" "$REPO:4141414"
end_only 41
check "#41 의 라벨을 떼면 #41 미리보기를 끝낸다" same "$code" 0
check "그때 develop 으로 돌아간다" file_has "$tmp/home/state/dev.release" "MODE=develop"
end_only 41
check "이미 develop 이면 끝낼 것이 없다(75)" same "$code" 75

preview 4141414 41 alice-kim
sed -i 's/^UNTIL=.*/UNTIL=1000/' "$tmp/home/state/preview.lease"
deploy aaaaaaa
check "자리 시간이 지났으면 develop 배포가 미리보기를 끝낸다" same "$code" 0
check "그때도 미리보기 DB·자리를 정리한다" bash -c "grep -q PREVIEW_ENDED=1 <<<\"\$0\" && [ ! -f '$tmp/home/state/preview.lease' ]" "$out"
preview 4545454 45 bob
check "자리 시간이 지났으면 다른 PR 이 자리를 잡을 수 있다" same "$code" 0

new_case
export FAKE_GOOD_TAGS="aaaaaaa 4141414"
deploy aaaaaaa
FAKE_IMDS=1 preview 4141414 41 alice-kim
check "컨테이너 IMDS 차단이 없으면 미리보기를 띄우지 않는다" same "$code" 1
check "Host setup 을 먼저 돌리라고 알려 준다" has "$out" "DEPLOY_RESULT=needs_host_setup"
check "그때 develop 은 그대로다" file_has "$tmp/fake/running" "$REPO:aaaaaaa"

FAKE_PSQL_FAIL=1 preview 4141414 41 alice-kim
check "미리보기 DB 를 못 만들면 1 로 멈춘다" same "$code" 1
check "그때 develop 은 그대로다(DB)" file_has "$tmp/fake/running" "$REPO:aaaaaaa"

code=0
out=$(PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" NEURINGO_HOME="$tmp/home" IMAGE="$REPO:x" WEB_IMAGE="$REPO:web-x" \
  RELEASE=4141414 MODE=preview PREVIEW_PR="41; rm -rf /" PREVIEW_BY=a bash deploy/host/deploy.sh 2>&1) || code=$?
check "PR 번호가 숫자가 아니면 1 로 끝난다" same "$code" 1

# ── backup.sh 단독: 최근 KEEP_LOCAL 개만 남긴다 ─────────────
new_case
export FAKE_GOOD_TAGS="aaaaaaa"
deploy aaaaaaa
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

# ── restore-check.sh: 최신 백업을 서버의 임시 postgres 에 복구해 보고 숫자만 남긴다 ──
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

# ── release-bundle.sh: 묶음 → 서버 스크립트 → deploy.sh 를 한 번에 돌린다(release.yml 이 SSM 으로 보내는 것과 같다) ──
new_case
export FAKE_GOOD_TAGS="fffffff 4141414"
SHA=fffffff89abcdef0123456789abcdef01234567
export FAKE_GOOD_TAGS="$SHA 4141414"
remote="$tmp/remote.sh"
IMAGE="$REPO:$SHA" WEB_IMAGE="$REPO:web-$SHA" RELEASE=$SHA BACKUP_BUCKET="$BUCKET" bash scripts/release-bundle.sh >"$remote"
remote_run() {
  code=0
  out=$(PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" NEURINGO_HOME="$tmp/home" HEALTH_TIMEOUT=0 HEALTH_INTERVAL=0 \
    bash "$remote" 2>&1) || code=$?
}
remote_run
check "배포 묶음 스크립트로 배포가 성공한다" same "$code" 0
check "묶음을 releases/<SHA> 에 푼다" test -f "$tmp/home/releases/$SHA/deploy/host/deploy.sh"
check "current 가 새 묶음을 가리킨다" test -f "$tmp/home/current/deploy/host/status.sh"
check "묶음 스크립트 출력에도 비밀값·계정 ID 가 없다" no_leak

IMAGE="$REPO:4141414" WEB_IMAGE="$REPO:web-4141414" RELEASE=4141414 MODE=preview PREVIEW_PR=41 PREVIEW_BY=alice-kim \
  bash scripts/release-bundle.sh >"$remote"
remote_run
check "묶음 스크립트로 미리보기도 된다(MODE·PR·아이디를 넘긴다)" file_has "$tmp/home/state/preview.lease" "PR=41"

# 같은 SHA 를 다시 배포해도(폴더 시각이 옛날 그대로) 정리할 때 그 묶음을 지우지 않는다.
# Git Bash(Windows)는 심볼릭 링크를 폴더 복사로 흉내 내서 current 를 다시 바꿀 수 없다. 리눅스(CI·서버)에서만 본다.
if [ "$(uname -s)" = Linux ]; then
  new_case
  IMAGE="$REPO:$SHA" WEB_IMAGE="$REPO:web-$SHA" RELEASE=$SHA BACKUP_BUCKET="$BUCKET" bash scripts/release-bundle.sh >"$remote"
  remote_run
  for n in 1 2 3 4 5 6; do mkdir -p "$tmp/home/releases/other$n"; done
  touch -d '2020-01-01' "$tmp/home/releases/$SHA"
  FORCE=1 remote_run
  check "같은 SHA 를 다시 배포해도 성공한다" same "$code" 0
  check "그 묶음을 정리하며 지우지 않는다" test -f "$tmp/home/releases/$SHA/deploy/host/deploy.sh"
  check "current 가 가리키는 묶음이 남아 있다(매일 백업이 쓴다)" test -f "$tmp/home/current/deploy/host/backup.sh"
fi

new_case
IMAGE="$REPO:$SHA" WEB_IMAGE="$REPO:web-$SHA" RELEASE=$SHA bash scripts/release-bundle.sh >"$remote"
sed -i 's/^if ! echo "[0-9a-f]\{64\}/if ! echo "0000000000000000000000000000000000000000000000000000000000000000/' "$remote"
remote_run
check "SHA256 이 다르면 풀지 않고 1 로 끝난다" same "$code" 1
check "묶음이 깨졌다고 말한다" has "$out" "깨졌다"
check "그때는 컨테이너를 건드리지 않는다(깨진 묶음)" bash -c "[ ! -f '$tmp/fake/docker.calls' ]"

bundle_rejects() { # 설명 환경...
  local what=$1
  shift
  code=0
  env IMAGE="$REPO:fff" WEB_IMAGE="$REPO:web-fff" RELEASE=abc1234 "$@" bash scripts/release-bundle.sh >/dev/null 2>&1 || code=$?
  check "$what" same "$code" 1
}
bundle_rejects "이미지 주소에 따옴표가 있으면 서버 스크립트를 만들지 않는다" IMAGE="$REPO:fff'; rm -rf /"
bundle_rejects "화면 이미지 주소에 따옴표가 있으면 만들지 않는다" WEB_IMAGE="$REPO:web'; rm -rf /"
bundle_rejects "RELEASE 가 커밋 SHA 가 아니면 만들지 않는다" RELEASE=main
bundle_rejects "MODE 가 develop·preview 가 아니면 만들지 않는다" MODE=prod
bundle_rejects "PR 번호가 숫자가 아니면 만들지 않는다" PREVIEW_PR="41'"
bundle_rejects "아이디 모양이 틀리면 만들지 않는다" PREVIEW_BY="a b"
bundle_rejects "END_ONLY_PR 이 숫자가 아니면 만들지 않는다" END_ONLY_PR="41'"

if [ "$failures" -gt 0 ]; then
  echo "서버 배포 자체 검사 실패: $failures 건" >&2
  exit 1
fi
echo "서버 배포 자체 검사 통과"
