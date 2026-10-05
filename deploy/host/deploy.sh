#!/usr/bin/env bash
# 서버(EC2)에서 백엔드를 새 이미지로 바꾸고 상태를 확인한다. 안 되면 직전 상태(이미지·설정·compose)로 되돌린다.
# deploy.yml 이 SSM(send-command)으로 배포 묶음을 풀고 이 스크립트를 부른다. 사람이 서버에서 직접 불러도 된다.
#   IMAGE=<레지스트리>/neuringo/backend:<커밋 SHA> NEURINGO_ENV=dev bash deploy/host/deploy.sh
#
# 순서: 설정값(Parameter Store) → 새 .env(.env.new, 권한 600) → 배포 전 백업 → 이미지 받기 → up
#       → 상태 확인(응답 200 + 실제로 새 이미지가 떠 있는지) → 성공하면 새 .env 로 바꾸고 기록 · 옛 이미지·로그 정리
#       → 실패하면 옛 .env 와 직전 묶음의 compose 로 직전 이미지를 다시 띄운다(설정 탓에 실패해도 되돌릴 수 있게)
# 종료 코드: 0 성공 · 1 실패(바꾸기 전에 멈췄거나 되돌릴 이미지가 없다) · 2 실패했지만 직전 상태로 되돌렸다 · 3 되돌리기도 실패
# 1(바꾼 뒤)·3 일 때는 backend 를 멈춘다(계속 재시작하며 공용 서버의 CPU 크레딧을 태우지 않게).
#
# 출력은 SSM 을 거쳐 Actions 로그(공개)에 남는다. 비밀값·레지스트리 주소(계정 ID 가 들어 있다)·앱 로그는 찍지 않는다.
# 앱 로그와 docker 출력은 서버의 $NEURINGO_HOME/logs 에 남기고 SSM 세션으로 본다(30일·크기 상한).
set -euo pipefail

: "${IMAGE:?IMAGE(배포할 이미지 전체 주소)가 필요하다}"
ENV_NAME=${NEURINGO_ENV:-dev}
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
HEALTH_URL=${HEALTH_URL:-http://127.0.0.1:$APP_PORT/api/v1/csrf}
HEALTH_TIMEOUT=${HEALTH_TIMEOUT:-120}
HEALTH_INTERVAL=${HEALTH_INTERVAL:-3}
KEEP_IMAGES=${KEEP_IMAGES:-3}
LOG_DAYS=${LOG_DAYS:-30}
PROJECT="neuringo-$ENV_NAME"
DB_VOLUME="${PROJECT}_postgres-data"

state_dir="$HOME_DIR/state"
log_dir="$HOME_DIR/logs"
env_file="$state_dir/$ENV_NAME.env"
new_env="$env_file.new"
current_file="$state_dir/$ENV_NAME.current"
previous_file="$state_dir/$ENV_NAME.previous"
docker_log="$log_dir/docker-$ENV_NAME.log"

say() { echo "[deploy] $*"; }
tag_of() { echo "${1##*:}"; } # 태그(커밋 SHA)만. 앞의 레지스트리 주소에는 계정 ID 가 있다
compose_at() { # .env compose파일 인자...
  local env=$1 file=$2
  shift 2
  APP_ENV_FILE="$env" docker compose --env-file "$env" -f "$file" -p "$PROJECT" "$@"
}
new_compose() { compose_at "$new_env" "$COMPOSE_FILE" "$@"; }
old_compose() { compose_at "$env_file" "$PREV_COMPOSE_FILE" "$@"; }
# 첫 배포가 실패해 새 .env 를 자리에 둔 뒤 쓴다. stop_backend 에 이름으로 넘겨 부른다(shellcheck 은 그것을 보지 못한다).
# shellcheck disable=SC2329
compose_now() { compose_at "$env_file" "$COMPOSE_FILE" "$@"; }

umask 077
mkdir -p "$state_dir" "$log_dir"

# 배포는 서버에서도 한 번에 하나만. 매일 백업·서버 설정도 같은 잠금을 잡는다(백업은 몇 분이면 끝나니 기다린다).
# flock 이 없는 곳(로컬 자체 검사)에서는 건너뛴다.
if command -v flock >/dev/null 2>&1; then
  exec 9>"$state_dir/deploy.lock"
  flock -w "${LOCK_WAIT:-300}" 9 || {
    say "다른 배포·백업이 ${LOCK_WAIT:-300}초 안에 끝나지 않았다"
    exit 1
  }
fi

# 1. 설정값: params.txt 에 적힌 것만 Parameter Store 에서 읽어 새 .env 를 만든다(권한 600).
#    지금 .env 는 되돌릴 때 쓰므로 성공할 때까지 건드리지 않는다.
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

  # 중간에 쓰기가 실패하면(디스크 부족 등) 반쪽 파일을 남기지 않는다.
  {
    echo "# deploy.sh 가 만든다. 다음 배포 때 다시 쓰므로 직접 고치지 않는다."
    printf "APP_IMAGE='%s'\n" "$IMAGE"
    printf "APP_BIND='%s'\n" "$APP_BIND"
    printf "APP_PORT='%s'\n" "$APP_PORT"
    printf "SPRING_PROFILES_ACTIVE='%s'\n" "${SPRING_PROFILES_ACTIVE:-$ENV_NAME}"
    for i in "${!keys[@]}"; do
      if [ -n "${value[${names[$i]}]+set}" ]; then
        printf "%s='%s'\n" "${keys[$i]}" "${value[${names[$i]}]}"
      fi
    done
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

healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT))
  while :; do
    if curl -fsS -o /dev/null --max-time 5 "$HEALTH_URL" 2>/dev/null; then
      return 0
    fi
    [ "$SECONDS" -lt "$deadline" ] || return 1
    sleep "$HEALTH_INTERVAL"
  done
}

# 응답한 것이 정말 그 이미지인지 본다(up 이 컨테이너를 바꾸기 전에 실패하면 옛 컨테이너가 200 을 돌려준다).
running_is() { # 이미지 compose함수
  local id
  id=$("$2" ps -q backend 2>>"$docker_log" | head -n 1)
  [ -n "$id" ] && [ "$(docker inspect --format '{{.Config.Image}}' "$id" 2>>"$docker_log")" = "$1" ]
}

stop_backend() { # compose함수 — 떠 있으면 멈춘다(계속 재시작하지 않게). DB 는 그대로 둔다
  "$1" stop backend >>"$docker_log" 2>&1 || true
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

registry=${IMAGE%%/*}
logged_in=0
# 바로 아래 trap 이 끝날 때 부른다(shellcheck 은 trap 으로 부르는 것을 보지 못한다).
# shellcheck disable=SC2329
on_exit() {
  rm -f "$new_env"
  if [ "$logged_in" = 1 ]; then docker logout "$registry" >>"$docker_log" 2>&1 || true; fi
}
trap on_exit EXIT

# 같은 저장소의 이미지는 모두 합쳐 KEEP_IMAGES 개만 남긴다(지금·직전은 늘 남긴다, 나머지는 최근 것부터).
# 배포마다 수백 MB 씩 쌓여 디스크를 채우는 것을 막는다.
prune_images() {
  local repo=${IMAGE%:*} ref kept=1
  if [ -n "$previous" ] && [ "$previous" != "$IMAGE" ]; then
    kept=2
  fi
  while IFS= read -r ref; do
    [ -n "$ref" ] || continue
    if [ "$ref" = "$IMAGE" ] || [ "$ref" = "$previous" ]; then
      continue
    fi
    if [ "$kept" -lt "$KEEP_IMAGES" ]; then
      kept=$((kept + 1))
    else
      docker image rm "$ref" >>"$docker_log" 2>&1 || true
    fi
  done < <(docker image ls --format '{{.Repository}}:{{.Tag}}' "$repo" 2>>"$docker_log")
  docker image prune -f >>"$docker_log" 2>&1 || true
}

render_env "$new_env"
previous=$(cat "$current_file" 2>/dev/null || true)
say "새 이미지 $(tag_of "$IMAGE")${previous:+ · 직전 $(tag_of "$previous")}"

# 2. 배포 전 백업. DB 볼륨이 있으면(첫 배포가 아니면) 반드시 한다. DB 가 멈춰 있으면 지금 설정으로 띄운 뒤 한다.
#    백업이 안 되면 아무것도 바꾸지 않고 멈춘다.
if [ "${SKIP_BACKUP:-0}" != 1 ] && docker volume inspect "$DB_VOLUME" >/dev/null 2>&1; then
  if [ ! -f "$env_file" ]; then
    say "DB 볼륨은 있는데 지금 .env 가 없다. 사람이 확인한다(SSM 세션)"
    exit 1
  fi
  if [ -z "$(old_compose ps -q postgres 2>>"$docker_log")" ]; then
    say "DB 가 멈춰 있어 백업하려고 띄운다"
    if ! old_compose up -d --wait postgres >>"$docker_log" 2>&1; then
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

# 3. 이미지 받기. ECR 이면 서버 역할로 로그인한다(저장한 비밀번호 없음).
if [[ "$registry" == *.dkr.ecr.*.amazonaws.com ]]; then
  if ! aws ecr get-login-password --region "$REGION" 2>>"$docker_log" |
    docker login --username AWS --password-stdin "$registry" >>"$docker_log" 2>&1; then
    say "ECR 에 로그인하지 못했다. 서버 역할 권한(ecr:GetAuthorizationToken)을 확인한다"
    exit 1
  fi
  logged_in=1
fi
if ! new_compose pull --quiet backend >>"$docker_log" 2>&1; then
  say "이미지를 받지 못했다. 서버 역할 권한(ecr:BatchGetImage)과 태그를 확인한다"
  exit 1
fi

# 4. 바꾸고 확인한다. up 이 실패해도(컨테이너 일부만 바뀌었을 수 있다) 되돌리기로 간다.
up_ok=1
new_compose up -d --remove-orphans >>"$docker_log" 2>&1 || up_ok=0
if [ "$up_ok" = 1 ] && healthy && running_is "$IMAGE" new_compose; then
  mv "$new_env" "$env_file"
  if [ -n "$previous" ] && [ "$previous" != "$IMAGE" ]; then
    echo "$previous" >"$previous_file"
  fi
  echo "$IMAGE" >"$current_file"
  prune_images
  trim_logs
  say "배포 완료 $(tag_of "$IMAGE")"
  echo "DEPLOY_RESULT=ok"
  exit 0
fi

failed_log="$log_dir/failed-$(tag_of "$IMAGE")-$(date -u +%Y%m%dT%H%M%SZ).log"
new_compose logs --no-color --tail 200 backend >"$failed_log" 2>&1 || true
if [ "$up_ok" = 0 ]; then
  say "새 컨테이너를 띄우지 못했다(compose up 실패). docker 출력은 서버의 $docker_log"
else
  say "상태 확인 실패(${HEALTH_TIMEOUT}초 안에 새 이미지가 200 을 돌려주지 않았다). 앱 로그는 서버의 $failed_log"
fi
trim_logs

if [ ! -f "$env_file" ]; then
  # 첫 배포. 되돌릴 것이 없다. DB 는 이미 이 설정(비밀번호)으로 만들어졌으므로 새 .env 를 남겨 둔다
  # (남기지 않으면 다음 배포가 "DB 볼륨은 있는데 .env 가 없다"로 계속 멈춘다).
  mv "$new_env" "$env_file"
  stop_backend compose_now
  say "되돌릴 직전 상태가 없어 backend 를 멈췄다"
  echo "DEPLOY_RESULT=failed"
  exit 1
fi

# 옛 .env(직전 이미지·직전 설정값)와 직전 묶음의 compose 로 되돌린다. 같은 이미지를 새 설정으로 다시 배포하다
# 실패한 경우도 옛 설정으로 돌아간다.
old_image=$(sed -n "s/^APP_IMAGE='\(.*\)'$/\1/p" "$env_file")
old_compose up -d --remove-orphans >>"$docker_log" 2>&1 || true
if healthy && running_is "$old_image" old_compose; then
  say "직전 상태로 되돌렸다 $(tag_of "$old_image")"
  echo "DEPLOY_RESULT=rolled_back"
  exit 2
fi
stop_backend old_compose
say "되돌리기도 실패해 backend 를 멈췄다. SSM 세션으로 서버에 들어가 $docker_log 와 $failed_log 를 본다"
echo "DEPLOY_RESULT=rollback_failed"
exit 3
