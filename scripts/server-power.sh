#!/usr/bin/env bash
# 팀 서버(EC2, 팀 계정에 1대)를 켜거나 끈다. dev-server.yml 이 OIDC 자격증명을 받은 뒤 부른다.
#   bash scripts/server-power.sh state   # 지금 상태
#   bash scripts/server-power.sh perms   # 켜기·끄기 권한이 있는지(DryRun, 실제로 바꾸지 않는다)
#   bash scripts/server-power.sh start   # 켜고, 공인 주소가 생기고 SSM 이 붙을 때까지 기다린다
#   bash scripts/server-power.sh stop    # 배포·백업이 끝나길 기다린 뒤 끄고, 꺼질 때까지 기다린다
#
# 출력(KEY=값, $GITHUB_OUTPUT 이 있으면 거기에도): SERVER_STATE, SERVER_SECONDS, SERVER_HOW. 공인 주소(SERVER_DNS)는 $GITHUB_OUTPUT 에만
# 종료 코드: 0 · 1 실패 · 77 권한 없음(배포 역할에 켜기 권한이 없다 → 콘솔에서 켠 뒤 다시 누른다)
# - 끄기 권한(ec2:StopInstances)이 없으면 서버 안에서 OS 를 끈다(SSM). 그 전에 "OS 를 끄면 중지"(종료가 아님)인지 확인한다.
#   확인할 수 없으면 끄지 않는다(서버가 삭제되면 운영진에게 다시 받아야 한다).
# - 인스턴스 ID·공인 주소는 찍지 않는다(GitHub Actions 에서는 ::add-mask::).
set -euo pipefail

action=${1:?state·start·stop 중 하나}
REGION=${AWS_REGION:-ap-northeast-2}
WAIT=${SERVER_WAIT:-300}
POLL=${SERVER_POLL:-5}
HERE=$(cd "$(dirname "$0")" && pwd)
SSM_RUN=${SSM_RUN:-$HERE/ssm-run.sh}
out=${GITHUB_OUTPUT:-/dev/null}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
started=$SECONDS

error_code() { grep -oE '\([A-Za-z.]+\)' "$work/err" | head -n 1 || echo '(코드 없음)'; }
emit() { echo "$1=$2"; echo "$1=$2" >>"$out"; }
mask() { if [ -n "${GITHUB_ACTIONS:-}" ] && [ -n "$1" ]; then echo "::add-mask::$1"; fi; }

describe() { # → "ID 상태 공인DNS"
  aws ec2 describe-instances --region "$REGION" \
    --filters Name=instance-state-name,Values=pending,running,stopping,stopped \
    --query 'Reservations[].Instances[].[InstanceId,State.Name,PublicDnsName]' --output text 2>"$work/err"
}

if ! rows=$(describe); then
  echo "서버 목록을 읽지 못했다 $(error_code)" >&2
  exit 1
fi
if [ "$(grep -c . <<<"$rows")" != 1 ]; then
  echo "팀 서버가 1대가 아니다($(grep -c . <<<"$rows")대). 운영진에 확인한다" >&2
  exit 1
fi
read -r id state dns <<<"$rows"
[ "$dns" = None ] && dns=""
mask "$id"
mask "$dns"

wait_for() { # 원하는상태 — 공인 DNS 도 다시 읽는다
  local deadline=$((SECONDS + WAIT))
  while :; do
    rows=$(describe) || { echo "서버 상태를 읽지 못했다 $(error_code)" >&2; return 1; }
    read -r _ state dns <<<"$rows"
    [ "$dns" = None ] && dns=""
    if [ "$state" = "$1" ] && { [ "$1" != running ] || [ -n "$dns" ]; }; then
      mask "$dns"
      return 0
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "${WAIT}초 안에 $1 이 되지 않았다(지금 $state)" >&2
      return 1
    fi
    sleep "$POLL"
  done
}

# SSM 이 서버에 붙어야 배포·상태 확인을 보낼 수 있다(켠 직후 30~60초). 읽을 권한이 없으면 잠깐 기다리고 넘어간다.
wait_ssm() {
  local deadline=$((SECONDS + WAIT)) ping
  while :; do
    if ! ping=$(aws ssm describe-instance-information --region "$REGION" \
      --filters "Key=InstanceIds,Values=$id" --query 'InstanceInformationList[0].PingStatus' --output text 2>"$work/err"); then
      echo "SSM 연결 상태를 읽지 못했다 $(error_code). 30초 기다리고 넘어간다"
      sleep 30
      return 0
    fi
    [ "$ping" = Online ] && return 0
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "${WAIT}초 안에 SSM 이 서버에 붙지 않았다" >&2
      return 1
    fi
    sleep "$POLL"
  done
}

case $action in
  state) ;;
  perms)
    # 실제로 켜고 끄지 않고(DryRun) 배포 역할에 권한이 있는지만 본다. DryRunOperation = 권한 있음.
    for op in start stop; do
      aws ec2 "$op-instances" --dry-run --region "$REGION" --instance-ids "$id" >/dev/null 2>"$work/err" || true
      if grep -q DryRunOperation "$work/err"; then emit "PERM_${op^^}" yes; else emit "PERM_${op^^}" no; fi
    done
    ;;
  start)
    case $state in
      running) echo "이미 켜져 있다" ;;
      stopping)
        echo "꺼지는 중이다. 다 꺼진 뒤 켠다"
        wait_for stopped
        ;;
    esac
    if [ "$state" != running ]; then
      if ! aws ec2 start-instances --region "$REGION" --instance-ids "$id" >/dev/null 2>"$work/err"; then
        if grep -qE 'UnauthorizedOperation|AccessDenied' "$work/err"; then
          echo "배포 역할에 서버 켜기 권한(ec2:StartInstances)이 없다. 콘솔(EC2 → 인스턴스 → 인스턴스 상태 → 인스턴스 시작)에서 켠 뒤 다시 누른다" >&2
          exit 77
        fi
        echo "서버를 켜지 못했다 $(error_code)" >&2
        exit 1
      fi
      wait_for running
      emit SERVER_HOW start-instances
    fi
    wait_ssm
    ;;
  stop)
    if [ "$state" = stopped ]; then
      echo "이미 꺼져 있다"
    else
      [ "$state" = running ] || wait_for running
      # 배포·백업 중이면 끝날 때까지 기다린다(같은 서버 잠금). 끝나면 바로 끈다.
      lock="$work/lock.sh"
      printf '%s\n' '#!/usr/bin/env bash' 'mkdir -p /opt/neuringo/state' \
        'flock -w 600 /opt/neuringo/state/deploy.lock true && echo "[power] 배포·백업이 없다"' >"$lock"
      INSTANCE_ID=$id bash "$SSM_RUN" "$lock" "wait for deploy lock" || echo "배포 잠금을 확인하지 못했다. 그대로 끈다"
      if aws ec2 stop-instances --region "$REGION" --instance-ids "$id" >/dev/null 2>"$work/err"; then
        emit SERVER_HOW stop-instances
      elif grep -qE 'UnauthorizedOperation|AccessDenied' "$work/err"; then
        # 끄기 권한이 없다. OS 를 끄면 "중지"가 되는지(종료=삭제가 아닌지) 확인한 뒤에만 서버 안에서 끈다.
        how=$(aws ec2 describe-instance-attribute --region "$REGION" --instance-id "$id" \
          --attribute instanceInitiatedShutdownBehavior --query InstanceInitiatedShutdownBehavior.Value \
          --output text 2>"$work/err" || echo unknown)
        if [ "$how" != stop ]; then
          echo "배포 역할에 끄기 권한이 없고, OS 를 끌 때의 동작이 stop 인지 확인하지 못했다($how). 콘솔(인스턴스 상태 → 인스턴스 중지)에서 끈다" >&2
          exit 77
        fi
        printf '%s\n' '#!/usr/bin/env bash' 'shutdown -h +1 >/dev/null 2>&1 && echo "[power] 1분 뒤 꺼진다"' >"$lock"
        INSTANCE_ID=$id bash "$SSM_RUN" "$lock" "shutdown"
        emit SERVER_HOW os-shutdown
      else
        echo "서버를 끄지 못했다 $(error_code)" >&2
        exit 1
      fi
      wait_for stopped
    fi
    ;;
  *)
    echo "state·perms·start·stop 중 하나" >&2
    exit 2
    ;;
esac

emit SERVER_STATE "$state"
# 공인 주소는 출력에 찍지 않고 워크플로 출력으로만 넘긴다(edge-toggle.sh 가 CloudFront 원본으로 쓴다).
echo "SERVER_DNS=$dns" >>"$out"
emit SERVER_SECONDS "$((SECONDS - started))"
