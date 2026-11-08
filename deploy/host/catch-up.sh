#!/usr/bin/env bash
# 떠 있어야 할 develop(state/<환경>.wanted)이 서버에 떠 있지 않으면 이어서 띄운다. 자동 끄기 Lambda 가 10분마다 SSM 으로 부른다
# (infra/modules/idle-off). wanted 에 적힌 묶음 폴더의 이 스크립트를 부르므로 늘 develop 의 코드다(PR 코드가 아니다).
#   NEURINGO_ENV=dev bash <묶음>/deploy/host/catch-up.sh
#
# 이어 띄우는 때: 아래를 모두 만족할 때 wanted 의 커밋으로 deploy.sh(develop)를 부른다.
#   - 미리보기 자리가 비었다(자리 시간이 지났거나 끝냈다)
#   - 지금 떠 있는 것이 wanted 의 develop 이 아니다(develop 배포가 미리보기·쓰는 사람 때문에 건너뛰어졌거나, 시간이 지난 미리보기가 남았다)
#   - wanted 를 실제로 바꾸다 실패한 적이 없다(TRIED 인데 DONE 이 없으면 사람이 본다 — 같은 커밋을 되풀이해 띄우지 않는다)
#   - 5분(QUIET_MINUTES) 동안 아무도 앱을 쓰지 않았다(STATUS_LAST_USE, 쓰는 사람을 끊지 않게)
#   - 다른 배포·백업이 잠금을 쥐고 있지 않다
# 출력(KEY=값, Lambda 가 읽는다): CATCHUP_RESULT=none|unknown|up_to_date|preview|failed_before|busy|locked|running|ok|failed
#   · CATCHUP_RELEASE(앞 7자리) · CATCHUP_DEPLOY_RESULT · CATCHUP_SECONDS. 배포 출력은 서버의 logs/catch-up-<환경>.log 에만 남긴다.
# 늘 0 으로 끝난다(결과는 CATCHUP_RESULT). 이미지 주소(계정 ID 포함)는 찍지 않는다.
set -uo pipefail

ENV_NAME=${NEURINGO_ENV:-dev}
HOME_DIR=${NEURINGO_HOME:-/opt/neuringo}
QUIET_MINUTES=${QUIET_MINUTES:-5}
HERE=$(cd "$(dirname "$0")" && pwd)
state_dir="$HOME_DIR/state"
wanted_file="$state_dir/$ENV_NAME.wanted"
log_file="$HOME_DIR/logs/catch-up-$ENV_NAME.log"
started=$SECONDS

finish() { # 결과 [배포 결과]
  echo "CATCHUP_RESULT=$1"
  echo "CATCHUP_RELEASE=${release:0:7}"
  [ -z "${2:-}" ] || echo "CATCHUP_DEPLOY_RESULT=$2"
  echo "CATCHUP_SECONDS=$((SECONDS - started))"
  exit 0
}
kv() { [ -f "$2" ] && sed -n "s/^$1=//p" "$2" | tail -n 1; }

[[ "$ENV_NAME" =~ ^(dev|prod)$ ]] || { echo "NEURINGO_ENV 는 dev 또는 prod" >&2; exit 64; }
release=""
[ -f "$wanted_file" ] || finish none

release=$(kv RELEASE "$wanted_file")
image=$(kv IMAGE "$wanted_file")
web_image=$(kv WEB_IMAGE "$wanted_file")
bucket=$(kv BACKUP_BUCKET "$wanted_file")
dir=$(kv DIR "$wanted_file")
image_re='^[a-z0-9.-]+(:[0-9]+)?/[a-z0-9._/-]+:[A-Za-z0-9._-]+$'
if ! [[ "$release" =~ ^[0-9a-f]{7,40}$ && "$image" =~ $image_re && "$web_image" =~ $image_re ]] ||
  ! [[ -z "$bucket" || "$bucket" =~ ^[a-z0-9.-]{3,63}$ ]] ||
  [[ "$dir" != "$HOME_DIR/releases/"* || "$dir" == *..* ]] || [ ! -f "$dir/deploy/host/deploy.sh" ]; then
  finish none # 옛 기록이거나 묶음이 지워졌다. 다음 develop 배포가 새로 적는다
fi

# 한 번에 하나(Lambda 가 겹쳐 돌아도). deploy.sh 의 배포 잠금과는 다른 파일이다.
mkdir -p "$state_dir" "$HOME_DIR/logs"
exec 8>"$state_dir/catch-up.lock"
if command -v flock >/dev/null 2>&1; then flock -n 8 || finish running; fi

status=$(NEURINGO_ENV="$ENV_NAME" NEURINGO_HOME="$HOME_DIR" bash "$HERE/status.sh" 2>/dev/null)
value() { sed -n "s/^$1=//p" <<<"$status" | tail -n 1; }
[ -n "$(value LEASE_ACTIVE)" ] || finish unknown # 상태를 읽지 못하면 건드리지 않는다
[ "$(value LEASE_ACTIVE)" != 1 ] || finish preview
if [ "$(value STATUS_MODE)" = develop ] && [ "$(value STATUS_RELEASE)" = "$release" ]; then finish up_to_date; fi
if [ "$(kv TRIED "$wanted_file")" = 1 ] && [ "$(kv DONE "$wanted_file")" != 1 ]; then finish failed_before; fi
last_use=$(value STATUS_LAST_USE)
if [ -n "$last_use" ] && at=$(date -u -d "$last_use" +%s 2>/dev/null) &&
  [ $(($(date +%s) - at)) -lt $((QUIET_MINUTES * 60)) ]; then
  finish busy
fi
if command -v flock >/dev/null 2>&1 && ! flock -n "$state_dir/deploy.lock" true; then finish locked; fi

rc=0
out=$(IMAGE="$image" WEB_IMAGE="$web_image" RELEASE="$release" NEURINGO_ENV="$ENV_NAME" BACKUP_BUCKET="$bucket" \
  MODE=develop LOCK_WAIT=60 NEURINGO_HOME="$HOME_DIR" bash "$dir/deploy/host/deploy.sh" 2>&1) || rc=$?
{
  echo "== $(date -u +%Y-%m-%dT%H:%M:%SZ) catch-up ${release:0:7} (종료 코드 $rc)"
  printf '%s\n' "$out"
} >>"$log_file"
if [ "$(wc -c <"$log_file" | tr -d ' ')" -gt 1048576 ]; then tail -n 2000 "$log_file" >"$log_file.tmp" && mv "$log_file.tmp" "$log_file"; fi
result=$(sed -n 's/^DEPLOY_RESULT=//p' <<<"$out" | tail -n 1)
[ "$rc" != 75 ] || finish preview "$result" # 그사이 누가 미리보기 자리를 잡았다
if [ "$rc" = 0 ]; then
  # release-bundle.sh 와 같다: 상태 보기·매일 백업·다음 배포가 쓰는 current 를 이 묶음으로 돌린다.
  touch "$dir"
  ln -sfn "$dir" "$HOME_DIR/current.tmp"
  mv -Tf "$HOME_DIR/current.tmp" "$HOME_DIR/current"
  finish ok "${result:-ok}"
fi
finish failed "${result:-exit-$rc}"
