#!/usr/bin/env bash
# 서버(EC2)에서 화면(web)·백엔드를 같은 커밋의 새 이미지로 바꾸고 상태를 확인한다. 안 되면 되돌린다.
# deploy.yml·dev-server.yml 이 SSM(send-command)으로 배포 묶음을 풀고 이 스크립트를 부른다. 사람이 서버에서 직접 불러도 된다.
#   IMAGE=<레지스트리>/neuringo/backend:<SHA> WEB_IMAGE=<레지스트리>/neuringo/backend:web-<SHA> RELEASE=<SHA> \
#     NEURINGO_ENV=dev bash deploy/host/deploy.sh
#
# MODE
#   develop(기본) develop 의 커밋. 배포 전에 백업한다. PR 미리보기가 자리를 잡고 있으면(끝나는 시각 전) 75 로 건너뛴다.
#                 END_PREVIEW=1 이면 자리를 무시하고 develop 으로 돌아간다(미리보기 끝내기).
#                 미리보기에서 돌아오면 미리보기 DB·계정, 자리 기록, 되돌림 기준점을 지운다.
#   preview      PR 미리보기(PREVIEW_PR·PREVIEW_BY·PREVIEW_TTL). 미리보기 전용 DB 와 그 DB 의 주인 계정을 새로 만들어 붙인다.
#                 기본 DB 는 건드리지 않으므로 백업하지 않는다. 다른 PR 이 자리를 잡고 있으면 76 으로 멈춘다.
# 되돌릴 곳: 미리보기가 끼어 있으면 미리보기에 들어가기 전의 develop 상태(기준점, state/<환경>.base.*),
#            아니면 직전 상태(지금 .env + 직전 묶음의 compose). 미리보기가 실패해도 다른 PR 로 돌아가지 않는다.
#
# 순서: 자리 확인 → 설정값(Parameter Store) → 새 .env(권한 600) → develop: 배포 전 백업 / preview: 미리보기 DB
#       → 이미지 받기 → up → 상태 확인(API 200 + 화면 200 + 화면 헤더의 커밋 + 실제로 새 이미지가 떠 있는지)
#       → 성공하면 기록·정리 / 실패하면 되돌린다
# 종료 코드: 0 성공 · 1 실패(바꾸기 전에 멈췄거나 되돌릴 것이 없다) · 2 실패했지만 되돌렸다 · 3 되돌리기도 실패
#            75 건너뜀(미리보기 중) · 76 자리 있음(다른 PR 미리보기 중)
# 1(바꾼 뒤)·3 일 때는 web·backend 를 멈춘다(계속 재시작하며 공용 서버의 CPU 크레딧을 태우지 않게).
#
# 출력은 SSM 을 거쳐 Actions 로그(공개)에 남는다. 비밀값·레지스트리 주소(계정 ID 가 들어 있다)·앱 로그는 찍지 않는다.
# 앱 로그와 docker 출력은 서버의 $NEURINGO_HOME/logs 에 남기고 SSM 세션으로 본다(30일·크기 상한).
# 끝에 KEY=값 줄(DEPLOY_RESULT 등)을 남긴다. 워크플로가 읽어 결과 보고서를 만든다.
set -euo pipefail

: "${IMAGE:?IMAGE(백엔드 이미지 전체 주소)가 필요하다}"
: "${WEB_IMAGE:?WEB_IMAGE(화면 이미지 전체 주소)가 필요하다}"
: "${RELEASE:?RELEASE(커밋 SHA)가 필요하다}"
ENV_NAME=${NEURINGO_ENV:-dev}
MODE=${MODE:-develop}
HOME_DIR=${NEURINGO_HOME:-/opt/neuringo}
REGION=${AWS_REGION:-ap-northeast-2}
HERE=$(cd "$(dirname "$0")" && pwd)
COMPOSE_FILE=${COMPOSE_FILE:-$HERE/../compose.dev.yml}
# 직전에 성공한 묶음의 compose. 되돌릴 때 쓴다. 첫 배포에는 없으니 새 것을 쓴다.
PREV_COMPOSE_FILE=${PREV_COMPOSE_FILE:-$HOME_DIR/current/deploy/compose.dev.yml}
[ -f "$PREV_COMPOSE_FILE" ] || PREV_COMPOSE_FILE=$COMPOSE_FILE
MANIFEST=${PARAMS_MANIFEST:-$HERE/params.txt}
APP_BIND=${APP_BIND:-0.0.0.0}
APP_PORT=${APP_PORT:-80}
BASE_URL=${BASE_URL:-http://127.0.0.1:$APP_PORT}
HEALTH_TIMEOUT=${HEALTH_TIMEOUT:-120}
HEALTH_INTERVAL=${HEALTH_INTERVAL:-3}
KEEP_IMAGES=${KEEP_IMAGES:-6}
LOG_DAYS=${LOG_DAYS:-30}
PREVIEW_TTL=${PREVIEW_TTL:-3600}
PREVIEW_DB=${PREVIEW_DB:-neuringo_preview}
PROJECT="neuringo-$ENV_NAME"
DB_VOLUME="${PROJECT}_postgres-data"

state_dir="$HOME_DIR/state"
log_dir="$HOME_DIR/logs"
env_file="$state_dir/$ENV_NAME.env"
new_env="$env_file.new"
release_file="$state_dir/$ENV_NAME.release"
base_env="$state_dir/$ENV_NAME.base.env"
base_compose="$state_dir/$ENV_NAME.base.compose.yml"
base_release="$state_dir/$ENV_NAME.base.release"
lease_file="$state_dir/preview.lease"
docker_log="$log_dir/docker-$ENV_NAME.log"
started=$SECONDS

say() { echo "[deploy] $*"; }
tag_of() { echo "${1##*:}"; } # 태그만. 앞의 레지스트리 주소에는 계정 ID 가 있다
short() { echo "${1:0:7}"; }
env_value() { # 키 파일 — deploy.sh 가 쓴 .env 에서 값 하나
  [ -f "$2" ] || return 0
  sed -n "s/^$1='\(.*\)'$/\1/p" "$2" | tail -n 1
}
kv_value() { # 키 파일 — KEY=값 기록 파일에서 값 하나
  [ -f "$2" ] || return 0
  sed -n "s/^$1=//p" "$2" | tail -n 1
}
compose_at() { # .env compose파일 인자...
  local env=$1 file=$2
  shift 2
  APP_ENV_FILE="$env" docker compose --env-file "$env" -f "$file" -p "$PROJECT" "$@"
}
new_compose() { compose_at "$new_env" "$COMPOSE_FILE" "$@"; }
# 아래 두 함수는 stop_app·running_is·psql_admin 등에 이름으로 넘겨 부른다(shellcheck 은 그것을 보지 못한다).
# 되돌릴 곳(기준점 또는 직전 상태)은 아래에서 정한다.
# shellcheck disable=SC2329
now_compose() { compose_at "$env_file" "$COMPOSE_FILE" "$@"; }
# shellcheck disable=SC2329
back_compose() { compose_at "$back_env" "$back_file" "$@"; }

[[ "$RELEASE" =~ ^[0-9a-f]{7,40}$ ]] || { say "RELEASE 는 커밋 SHA 여야 한다"; exit 1; }
case $MODE in
  develop) ;;
  preview)
    [[ "${PREVIEW_PR:-}" =~ ^[0-9]{1,6}$ ]] || { say "PREVIEW_PR(PR 번호)이 필요하다"; exit 1; }
    [[ "${PREVIEW_BY:-}" =~ ^[A-Za-z0-9-]{1,39}$ ]] || { say "PREVIEW_BY(GitHub 아이디)가 필요하다"; exit 1; }
    [[ "$PREVIEW_TTL" =~ ^[0-9]{2,6}$ ]] || { say "PREVIEW_TTL 은 초"; exit 1; }
    ;;
  *) say "MODE 는 develop 또는 preview"; exit 1 ;;
esac
[[ "$PREVIEW_DB" =~ ^[a-z_][a-z0-9_]{0,40}$ ]] || { say "PREVIEW_DB 이름 모양이 틀렸다"; exit 1; }

umask 077
mkdir -p "$state_dir" "$log_dir"

# 배포는 서버에서도 한 번에 하나만. 매일 백업·서버 설정·서버 끄기도 같은 잠금을 잡는다.
# 자리(preview.lease)도 이 잠금 안에서 읽고 쓴다(두 사람이 동시에 눌러도 한 사람만 자리를 잡는다).
# flock 이 없는 곳(로컬 자체 검사)에서는 건너뛴다.
if command -v flock >/dev/null 2>&1; then
  exec 9>"$state_dir/deploy.lock"
  flock -w "${LOCK_WAIT:-600}" 9 || {
    say "다른 배포·백업이 ${LOCK_WAIT:-600}초 안에 끝나지 않았다"
    exit 1
  }
fi

now=$(date +%s)
iso() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -r "$1" +%Y-%m-%dT%H:%M:%SZ; }
lease_pr=$(kv_value PR "$lease_file")
lease_by=$(kv_value BY "$lease_file")
lease_until=$(kv_value UNTIL "$lease_file")
[[ "$lease_until" =~ ^[0-9]+$ ]] || lease_until=0
current_mode=$(kv_value MODE "$release_file")
current_release=$(kv_value RELEASE "$release_file")
lease_active=0
if [ "$current_mode" = preview ] && [ "$lease_until" -gt "$now" ]; then lease_active=1; fi

holder() {
  echo "PREVIEW_HOLDER_PR=$lease_pr"
  echo "PREVIEW_HOLDER_BY=$lease_by"
  echo "PREVIEW_HOLDER_UNTIL=$(iso "$lease_until")"
}

# 1. 자리 확인
if [ "$MODE" = develop ] && [ "$lease_active" = 1 ] && [ "${END_PREVIEW:-0}" != 1 ]; then
  say "PR #$lease_pr 미리보기가 $(iso "$lease_until") 까지 자리를 잡고 있어 develop 배포를 건너뛴다(끝내기·시간이 지나면 develop 최신으로 돌아간다)"
  holder
  echo "DEPLOY_RESULT=skipped_preview"
  exit 75
fi
if [ "$MODE" = preview ] && [ "$lease_active" = 1 ] && [ "$lease_pr" != "$PREVIEW_PR" ]; then
  say "PR #$lease_pr 미리보기($lease_by)가 $(iso "$lease_until") 까지 자리를 잡고 있다. 끝난 뒤 다시 누른다"
  holder
  echo "DEPLOY_RESULT=slot_taken"
  exit 76
fi

# PR 코드를 띄우기 전에 컨테이너에서 인스턴스 메타데이터(서버 역할 자격증명)가 막혀 있는지 본다(setup.sh 7번).
if [ "$MODE" = preview ] && ! iptables -C DOCKER-USER -d 169.254.169.254/32 -j REJECT >/dev/null 2>&1; then
  say "컨테이너의 인스턴스 메타데이터(IMDS) 차단이 없다. Actions → Host setup 을 먼저 돌린다(PR 코드가 서버 역할 권한을 쓰지 못하게)"
  echo "DEPLOY_RESULT=needs_host_setup"
  exit 1
fi

# 2. 설정값: params.txt 에 적힌 것만 Parameter Store 에서 읽어 새 .env 를 만든다(권한 600).
#    지금 .env 는 되돌릴 때 쓰므로 성공할 때까지 건드리지 않는다.
random_hex() { od -An -tx1 -N"$1" /dev/urandom | tr -d ' \n'; }
preview_password=""
render_env() { # 만들 파일
  local target=$1 prefix="/neuringo/$ENV_NAME/" line key path optional out name val i
  local keys=() names=() optionals=() missing=()
  declare -A value=()

  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%%#*}
    line=${line//[[:space:]]/}
    [ -n "$line" ] || continue
    key=${line%%=*}
    path=${line#*=}
    optional=0
    if [ "${key: -1}" = "?" ]; then
      key=${key%\?}
      optional=1
    fi
    if ! [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] || [ -z "$path" ]; then
      say "params.txt 형식이 틀렸다: $key"
      return 1
    fi
    keys+=("$key")
    names+=("$prefix$path")
    optionals+=("$optional")
  done <"$MANIFEST"

  # GetParameters 는 한 번에 10개까지다.
  for ((i = 0; i < ${#names[@]}; i += 10)); do
    if ! out=$(aws ssm get-parameters --names "${names[@]:i:10}" --with-decryption --region "$REGION" \
      --query 'Parameters[].[Name,Value]' --output text 2>>"$docker_log"); then
      say "Parameter Store 를 읽지 못했다. 서버 역할 권한(ssm:GetParameters·kms:Decrypt)을 확인한다"
      return 1
    fi
    while IFS=$'\t' read -r name val; do
      [ -n "$name" ] || continue
      # 값에 줄바꿈이 있으면 다음 줄이 이름 없이 나온다.
      if [[ "$name" != "$prefix"* ]]; then
        say "설정값에 줄바꿈이 있다. 줄바꿈 없는 값으로 바꾼다"
        return 1
      fi
      if [[ "$val" == *"'"* ]]; then
        say "설정값에 작은따옴표가 있다: ${name#"$prefix"}"
        return 1
      fi
      value["$name"]=$val
    done <<<"$out"
  done

  for i in "${!names[@]}"; do
    if [ -z "${value[${names[$i]}]+set}" ] && [ "${optionals[$i]}" = 0 ]; then
      missing+=("${names[$i]}")
    fi
  done
  if [ "${#missing[@]}" -gt 0 ]; then
    say "Parameter Store 에 없는 필수 설정값: ${missing[*]}"
    return 1
  fi

  # 미리보기에는 아동 입장 코드 비밀키도 따로 쓴다(PR 코드가 기본 DB 의 입장 코드를 풀 수 없게).
  if [ "$MODE" = preview ] && [ -n "${value[${prefix}child-access/hmac-secret]+set}" ]; then
    value["${prefix}child-access/hmac-secret"]=$(random_hex 32)
  fi

  # 중간에 쓰기가 실패하면(디스크 부족 등) 반쪽 파일을 남기지 않는다.
  {
    echo "# deploy.sh 가 만든다. 다음 배포 때 다시 쓰므로 직접 고치지 않는다."
    printf "APP_IMAGE='%s'\n" "$IMAGE"
    printf "WEB_IMAGE='%s'\n" "$WEB_IMAGE"
    printf "RELEASE='%s'\n" "$RELEASE"
    printf "APP_BIND='%s'\n" "$APP_BIND"
    printf "APP_PORT='%s'\n" "$APP_PORT"
    printf "SPRING_PROFILES_ACTIVE='%s'\n" "${SPRING_PROFILES_ACTIVE:-$ENV_NAME}"
    for i in "${!keys[@]}"; do
      if [ -n "${value[${names[$i]}]+set}" ]; then
        printf "%s='%s'\n" "${keys[$i]}" "${value[${names[$i]}]}"
      fi
    done
    if [ "$MODE" = preview ]; then
      printf "APP_DB_URL='jdbc:postgresql://postgres:5432/%s'\n" "$PREVIEW_DB"
      printf "APP_DB_USERNAME='%s'\n" "$PREVIEW_DB"
      printf "APP_DB_PASSWORD='%s'\n" "$preview_password"
    fi
  } >"$target.tmp" || {
    rm -f "$target.tmp"
    say "새 .env 를 쓰지 못했다(디스크를 확인한다)"
    return 1
  }
  if ! { chmod 600 "$target.tmp" && mv "$target.tmp" "$target"; }; then
    rm -f "$target.tmp"
    say "새 .env 를 쓰지 못했다(디스크를 확인한다)"
    return 1
  fi
}

# 상태 확인 한 번: API 200, 그리고 기대 커밋이 있으면 화면 200 + 헤더 X-Neuringo-Release 가 그 커밋.
check_once() { # 기대커밋(없으면 화면은 안 본다)
  curl -fsS -o /dev/null --max-time 5 "$BASE_URL/api/v1/csrf" 2>/dev/null || return 1
  [ -n "$1" ] || return 0
  local headers
  headers=$(curl -fsS -o /dev/null -D - --max-time 5 "$BASE_URL/" 2>/dev/null) || return 1
  grep -qi "^x-neuringo-release: $1" <<<"$headers"
}

healthy() { # 기대커밋
  local deadline=$((SECONDS + HEALTH_TIMEOUT))
  while :; do
    check_once "$1" && return 0
    [ "$SECONDS" -lt "$deadline" ] || return 1
    sleep "$HEALTH_INTERVAL"
  done
}

# 응답한 것이 정말 그 이미지인지 본다(up 이 컨테이너를 바꾸기 전에 실패하면 옛 컨테이너가 200 을 돌려준다).
running_is() { # 백엔드이미지 화면이미지(없으면 안 본다) compose함수
  local id
  id=$("$3" ps -q backend 2>>"$docker_log" | head -n 1)
  [ -n "$id" ] && [ "$(docker inspect --format '{{.Config.Image}}' "$id" 2>>"$docker_log")" = "$1" ] || return 1
  [ -n "$2" ] || return 0
  id=$("$3" ps -q web 2>>"$docker_log" | head -n 1)
  [ -n "$id" ] && [ "$(docker inspect --format '{{.Config.Image}}' "$id" 2>>"$docker_log")" = "$2" ]
}

stop_app() { # compose함수 — 떠 있으면 멈춘다(계속 재시작하지 않게). DB 는 그대로 둔다
  "$1" stop web backend >>"$docker_log" 2>&1 || true
}

# 미리보기 DB 는 기본 DB 와 같은 postgres 안의 다른 DB 다. 그 DB 의 주인 계정만 붙을 수 있고,
# 기본 DB·postgres·template1 에는 PUBLIC 접속을 막아 미리보기 계정이 다른 DB 에 붙지 못한다(앱은 기본 DB 의 주인 계정).
# DROP DATABASE 는 트랜잭션 안에서 못 돈다. psql 표준 입력은 문장마다 따로 돈다(-1 을 주지 않는다).
psql_admin() { # compose함수 — 표준 입력의 SQL 을 postgres 관리자 계정으로 돌린다
  # 아래 $POSTGRES_* 는 컨테이너 안의 셸이 풀어야 하므로 작은따옴표가 맞다.
  # shellcheck disable=SC2016
  "$1" exec -T postgres sh -c 'psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >>"$docker_log" 2>&1
}

make_preview_db() {
  local main_db
  main_db=$(env_value DB_NAME "$new_env")
  [[ "$main_db" =~ ^[A-Za-z_][A-Za-z0-9_]{0,62}$ ]] || { say "DB_NAME 모양이 틀렸다"; return 1; }
  new_compose up -d --wait postgres >>"$docker_log" 2>&1 || { say "DB 를 띄우지 못했다"; return 1; }
  psql_admin new_compose <<SQL
REVOKE CONNECT ON DATABASE "$main_db" FROM PUBLIC;
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
REVOKE CONNECT ON DATABASE template1 FROM PUBLIC;
DROP DATABASE IF EXISTS $PREVIEW_DB WITH (FORCE);
DROP ROLE IF EXISTS $PREVIEW_DB;
CREATE ROLE $PREVIEW_DB LOGIN PASSWORD '$preview_password';
CREATE DATABASE $PREVIEW_DB OWNER $PREVIEW_DB;
SQL
}

drop_preview_db() { # compose함수 — 미리보기에서 나온 뒤. 실패해도 배포 결과는 바꾸지 않는다(다음 미리보기가 다시 지운다)
  if psql_admin "$1" <<SQL; then
DROP DATABASE IF EXISTS $PREVIEW_DB WITH (FORCE);
DROP ROLE IF EXISTS $PREVIEW_DB;
SQL
    say "미리보기 DB 를 지웠다"
  else
    say "미리보기 DB 를 지우지 못했다(다음 미리보기 때 다시 지운다)"
  fi
}

flyway_version() { # compose함수 DB이름
  # shellcheck disable=SC2016
  "$1" exec -T postgres sh -c "psql -X -tA -U \"\$POSTGRES_USER\" -d '$2' -c 'select coalesce(max(version::int), 0) from flyway_schema_history where success'" 2>>"$docker_log" | tr -d '[:space:]'
}

# 앱 로그(개인정보가 섞일 수 있다)는 LOG_DAYS 일만, 계속 덧붙는 로그는 10 MB 를 넘으면 뒤쪽만 남긴다.
trim_logs() {
  local f
  find "$log_dir" -type f -name 'failed-*.log' -mtime +"$LOG_DAYS" -delete 2>/dev/null || true
  for f in "$log_dir"/*.log; do
    [ -f "$f" ] || continue
    if [ "$(wc -c <"$f" | tr -d ' ')" -gt 10485760 ]; then
      tail -n 5000 "$f" >"$f.tmp" && mv "$f.tmp" "$f"
    fi
  done
}

write_release() { # 모드 커밋 PR 아이디
  {
    echo "RELEASE=$2"
    echo "MODE=$1"
    echo "PR=$3"
    echo "BY=$4"
    echo "AT=$(iso "$(date +%s)")"
  } >"$release_file"
}

clear_preview_state() { rm -f "$lease_file" "$base_env" "$base_compose" "$base_release"; }

registry=${IMAGE%%/*}
logged_in=0
# 바로 아래 trap 이 끝날 때 부른다(shellcheck 은 trap 으로 부르는 것을 보지 못한다).
# shellcheck disable=SC2329
on_exit() {
  rm -f "$new_env"
  if [ "$logged_in" = 1 ]; then docker logout "$registry" >>"$docker_log" 2>&1 || true; fi
}
trap on_exit EXIT

# 같은 저장소의 이미지는 모두 합쳐 KEEP_IMAGES 개만 남긴다. 지금·직전·기준점 이미지는 늘 남기고 나머지는 최근 것부터.
# 배포마다 수백 MB 씩 쌓여 디스크를 채우는 것을 막는다.
prune_images() {
  local repo=${IMAGE%:*} ref kept=0 protected
  protected=" $IMAGE $WEB_IMAGE $old_images $(env_value APP_IMAGE "$base_env") $(env_value WEB_IMAGE "$base_env") "
  local all=()
  mapfile -t all < <(docker image ls --format '{{.Repository}}:{{.Tag}}' "$repo" 2>>"$docker_log")
  for ref in "${all[@]}"; do
    [[ "$protected" == *" $ref "* ]] && kept=$((kept + 1))
  done
  for ref in "${all[@]}"; do
    [ -n "$ref" ] || continue
    [[ "$protected" == *" $ref "* ]] && continue
    if [ "$kept" -lt "$KEEP_IMAGES" ]; then
      kept=$((kept + 1))
    else
      docker image rm "$ref" >>"$docker_log" 2>&1 || true
    fi
  done
  docker image prune -f >>"$docker_log" 2>&1 || true
}

say "$MODE $(short "$RELEASE")${PREVIEW_PR:+ (PR #$PREVIEW_PR)}${current_release:+ · 지금 $(short "$current_release")${current_mode:+ $current_mode}}"
echo "DEPLOY_MODE=$MODE"
echo "DEPLOY_RELEASE=$RELEASE"

# develop 이 이미 이 커밋으로 떠 있고 응답하면 아무것도 바꾸지 않는다(서버를 켠 직후·같은 커밋을 다시 부른 경우).
# 막 켠 서버는 컨테이너가 뜨는 중일 수 있어 상태 확인 시간만큼 기다려 본다. 설정값만 바꿔 다시 띄울 때는 FORCE=1(수동 배포).
if [ "$MODE" = develop ] && [ "${FORCE:-0}" != 1 ] && [ "$current_mode" = develop ] &&
  [ "$current_release" = "$RELEASE" ] && healthy "$RELEASE"; then
  say "이미 이 커밋이 떠 있다"
  echo "DEPLOY_RESULT=unchanged"
  exit 0
fi

if [ "$MODE" = preview ]; then
  preview_password=$(random_hex 24)
fi
render_env "$new_env"
old_images="$(env_value APP_IMAGE "$env_file") $(env_value WEB_IMAGE "$env_file")"

# 3. develop: 배포 전 백업. DB 볼륨이 있으면(첫 배포가 아니면) 반드시 한다. DB 가 멈춰 있으면 지금 설정으로 띄운 뒤 한다.
#    백업이 안 되면 아무것도 바꾸지 않고 멈춘다. 미리보기는 기본 DB 를 건드리지 않아 하지 않는다.
if [ "$MODE" = develop ] && [ "${SKIP_BACKUP:-0}" != 1 ] && docker volume inspect "$DB_VOLUME" >/dev/null 2>&1; then
  if [ ! -f "$env_file" ]; then
    say "DB 볼륨은 있는데 지금 .env 가 없다. 사람이 확인한다(SSM 세션)"
    exit 1
  fi
  if [ -z "$(compose_at "$env_file" "$PREV_COMPOSE_FILE" ps -q postgres 2>>"$docker_log")" ]; then
    say "DB 가 멈춰 있어 백업하려고 띄운다"
    if ! compose_at "$env_file" "$PREV_COMPOSE_FILE" up -d --wait postgres >>"$docker_log" 2>&1; then
      say "DB 를 띄우지 못해 배포하지 않는다"
      exit 1
    fi
  fi
  if ! LABEL=pre-deploy NEURINGO_ENV="$ENV_NAME" NEURINGO_HOME="$HOME_DIR" COMPOSE_FILE="$PREV_COMPOSE_FILE" \
    bash "$HERE/backup.sh"; then
    say "배포 전 백업이 실패해서 배포하지 않는다"
    exit 1
  fi
fi

# 4. 이미지 받기. ECR 이면 서버 역할로 로그인한다(저장한 비밀번호 없음).
if [[ "$registry" == *.dkr.ecr.*.amazonaws.com ]]; then
  if ! aws ecr get-login-password --region "$REGION" 2>>"$docker_log" |
    docker login --username AWS --password-stdin "$registry" >>"$docker_log" 2>&1; then
    say "ECR 에 로그인하지 못했다. 서버 역할 권한(ecr:GetAuthorizationToken)을 확인한다"
    exit 1
  fi
  logged_in=1
fi
if ! new_compose pull --quiet backend web >>"$docker_log" 2>&1; then
  say "이미지를 받지 못했다. 서버 역할 권한(ecr:BatchGetImage)과 태그를 확인한다"
  exit 1
fi

# 5. preview: 미리보기 DB 를 새로 만든다(지난 미리보기 데이터는 지운다).
if [ "$MODE" = preview ]; then
  if ! make_preview_db; then
    say "미리보기 DB 를 만들지 못해 배포하지 않는다. 서버의 $docker_log 를 본다"
    exit 1
  fi
  say "미리보기 DB 를 새로 만들었다"
  echo "PREVIEW_DB=fresh"
fi

# 되돌릴 곳. 미리보기에 처음 들어갈 때는 지금(develop) 상태를 기준점으로 남긴다(바꾸기 직전에).
if [ "$MODE" = preview ] && [ ! -f "$base_env" ] && [ -f "$env_file" ]; then
  cp "$env_file" "$base_env"
  cp "$PREV_COMPOSE_FILE" "$base_compose"
  if [ -f "$release_file" ]; then cp "$release_file" "$base_release"; fi
  say "develop 상태를 되돌림 기준점으로 남겼다"
fi
if [ -f "$base_env" ]; then
  back_env=$base_env
  back_file=$base_compose
else
  back_env=$env_file
  back_file=$PREV_COMPOSE_FILE
fi

# 6. 바꾸고 확인한다. up 이 실패해도(컨테이너 일부만 바뀌었을 수 있다) 되돌리기로 간다.
up_ok=1
new_compose up -d --remove-orphans >>"$docker_log" 2>&1 || up_ok=0
if [ "$up_ok" = 1 ] && healthy "$RELEASE" && running_is "$IMAGE" "$WEB_IMAGE" new_compose; then
  mv "$new_env" "$env_file"
  if [ "$MODE" = preview ]; then
    write_release preview "$RELEASE" "$PREVIEW_PR" "$PREVIEW_BY"
    until_at=$((now + PREVIEW_TTL))
    {
      echo "PR=$PREVIEW_PR"
      echo "BY=$PREVIEW_BY"
      echo "UNTIL=$until_at"
      echo "RELEASE=$RELEASE"
    } >"$lease_file"
    echo "FLYWAY_VERSION=V$(flyway_version now_compose "$PREVIEW_DB")"
    echo "PREVIEW_UNTIL=$(iso "$until_at")"
  else
    write_release develop "$RELEASE" "" ""
    echo "FLYWAY_VERSION=V$(flyway_version now_compose "$(env_value DB_NAME "$env_file")")"
    if [ -f "$base_env" ] || [ "$current_mode" = preview ]; then
      drop_preview_db now_compose
      clear_preview_state
      echo "PREVIEW_ENDED=1"
    fi
  fi
  prune_images
  trim_logs
  say "배포 완료 $(short "$RELEASE") (${MODE}, $((SECONDS - started))초)"
  echo "DEPLOY_SECONDS=$((SECONDS - started))"
  echo "DEPLOY_RESULT=ok"
  exit 0
fi

failed_log="$log_dir/failed-$(short "$RELEASE")-$(date -u +%Y%m%dT%H%M%SZ).log"
new_compose logs --no-color --tail 200 backend web >"$failed_log" 2>&1 || true
if [ "$up_ok" = 0 ]; then
  say "새 컨테이너를 띄우지 못했다(compose up 실패). docker 출력은 서버의 $docker_log"
else
  say "상태 확인 실패(${HEALTH_TIMEOUT}초 안에 새 화면·API 가 응답하지 않았다). 앱 로그는 서버의 $failed_log"
fi
trim_logs

if [ ! -f "$back_env" ]; then
  # 첫 배포. 되돌릴 것이 없다. DB 는 이미 이 설정(비밀번호)으로 만들어졌으므로 새 .env 를 남겨 둔다
  # (남기지 않으면 다음 배포가 "DB 볼륨은 있는데 .env 가 없다"로 계속 멈춘다).
  mv "$new_env" "$env_file"
  stop_app now_compose
  say "되돌릴 직전 상태가 없어 web·backend 를 멈췄다"
  echo "DEPLOY_RESULT=failed"
  exit 1
fi

# 되돌린다. 기준점(미리보기 전 develop) 또는 직전 상태의 .env·compose 로 띄운다. 같은 이미지를 새 설정으로 다시 배포하다
# 실패한 경우도 옛 설정으로 돌아간다. 옛 상태가 web 이 없던 때라도(--remove-orphans 가 web 을 내린다) 돌아간다.
back_image=$(env_value APP_IMAGE "$back_env")
back_web=$(env_value WEB_IMAGE "$back_env")
back_release=$(env_value RELEASE "$back_env")
back_compose up -d --remove-orphans >>"$docker_log" 2>&1 || true
if healthy "$back_release" && running_is "$back_image" "$back_web" back_compose; then
  if [ "$back_env" = "$base_env" ]; then
    # 기준점(develop)으로 돌아왔다. 미리보기는 끝난 것으로 본다. 기준점 파일을 옮기기 전에 그 설정으로 미리보기 DB 를 지운다.
    drop_preview_db back_compose
    mv "$base_env" "$env_file"
    if [ -f "$base_release" ]; then mv "$base_release" "$release_file"; else rm -f "$release_file"; fi
    clear_preview_state
    say "미리보기 전 develop 상태로 되돌렸다 $(short "${back_release:-$(tag_of "$back_image")}")"
  else
    say "직전 상태로 되돌렸다 $(short "${back_release:-$(tag_of "$back_image")}")"
  fi
  echo "DEPLOY_RESULT=rolled_back"
  exit 2
fi
stop_app back_compose
say "되돌리기도 실패해 web·backend 를 멈췄다. SSM 세션으로 서버에 들어가 $docker_log 와 $failed_log 를 본다"
echo "DEPLOY_RESULT=rollback_failed"
exit 3
