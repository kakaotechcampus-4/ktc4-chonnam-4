#!/usr/bin/env bash
# 팀 서버(EC2)에서 bash 스크립트를 SSM(send-command)으로 돌리고 끝날 때까지 기다린다. 22번·SSH 키 없이 쓴다.
# deploy.yml·host-setup.yml·ops-backup.yml 이 OIDC 로 자격증명을 받은 뒤 부른다.
#   bash scripts/ssm-run.sh <서버에서 돌릴 스크립트 파일> [설명]
#
# - 서버는 지금 켜져 있는 EC2 하나를 찾는다(팀 계정에 1대). 0대·여러 대면 멈춘다. INSTANCE_ID 로 정할 수도 있다.
# - 스크립트는 base64 로 보내 서버에서 bash 로 돌린다(따옴표·sh/bash 차이 걱정 없음).
#   스크립트 내용은 SSM 명령 기록·CloudTrail 에 남는다. 비밀값은 넣지 말고 서버가 Parameter Store 에서 읽게 한다.
# - 서버 출력은 공개 로그에 남으므로 계정 ID·ARN·IP·EC2 주소를 가린다.
# - 끝나면 서버 스크립트의 종료 코드로 끝난다. 시간이 넘으면 124. 켜진 서버가 없으면 69(서버가 꺼져 있음 — 부르는 쪽이
#   건너뛸지 정한다. 팀 서버는 쓰지 않을 때 꺼 둔다).
#
# 환경변수: AWS_REGION(기본 ap-northeast-2), SSM_TIMEOUT(초, 기본 900), SSM_POLL(초, 기본 5),
#           SSM_GRACE(전달·마무리 여유 초, 기본 60), INSTANCE_ID
set -euo pipefail

script=${1:?서버에서 돌릴 스크립트 파일이 필요하다}
comment=${2:-neuringo}
REGION=${AWS_REGION:-ap-northeast-2}
TIMEOUT=${SSM_TIMEOUT:-900}
POLL=${SSM_POLL:-5}

[ -f "$script" ] || {
  echo "스크립트 파일이 없다: $script" >&2
  exit 1
}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# 공개 로그용으로 식별자를 가린다.
redact() {
  sed -E \
    -e 's/arn:aws[a-zA-Z-]*:[^[:space:]"]*/<arn>/g' \
    -e 's/ec2-[0-9-]+\.[a-z0-9.-]*amazonaws\.com/<서버 주소>/g' \
    -e 's/[0-9]{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com/<레지스트리>/g' \
    -e 's/(^|[^0-9])[0-9]{12}([^0-9]|$)/\1<계정>\2/g' \
    -e 's/(^|[^0-9.])([0-9]{1,3}\.){3}[0-9]{1,3}([^0-9.]|$)/\1<IP>\3/g' \
    -e 's/i-[0-9a-f]{8,17}/<서버>/g'
}

if [ -z "${INSTANCE_ID:-}" ]; then
  if ! ids=$(aws ec2 describe-instances --region "$REGION" --filters Name=instance-state-name,Values=running \
    --query 'Reservations[].Instances[].InstanceId' --output text 2>"$work/err"); then
    echo "서버 목록을 읽지 못했다: $(redact <"$work/err" | head -n 1)" >&2
    exit 1
  fi
  read -r -a found <<<"$ids"
  if [ "${#found[@]}" -eq 0 ]; then
    echo "켜져 있는 서버가 없다(0대). 서버가 꺼져 있으면 콘솔(EC2 → 인스턴스 → 인스턴스 시작)에서 켠 뒤 다시 돌린다." >&2
    exit 69
  fi
  if [ "${#found[@]}" -ne 1 ]; then
    echo "켜져 있는 서버가 ${#found[@]}대다. 1대여야 한다(서버가 꺼져 있으면 콘솔에서 시작하거나 INSTANCE_ID 로 정한다)." >&2
    exit 1
  fi
  INSTANCE_ID=${found[0]}
fi
[ -n "${GITHUB_ACTIONS:-}" ] && echo "::add-mask::$INSTANCE_ID"

# 서버에서: base64 를 풀어 bash 로 돌리고, 끝나면 임시 파일을 지운다.
payload=$(base64 -w0 <"$script" 2>/dev/null || base64 <"$script" | tr -d '\n')
remote="f=\$(mktemp); echo '$payload' | base64 -d >\"\$f\"; bash \"\$f\"; rc=\$?; rm -f \"\$f\"; exit \$rc"
# JSON 문자열로 넣는다. 내용은 base64 와 위의 고정 문자뿐이라 역슬래시·큰따옴표만 바꾸면 된다.
escaped=${remote//\\/\\\\}
escaped=${escaped//\"/\\\"}
printf '{"commands":["%s"],"executionTimeout":["%s"]}' "$escaped" "$TIMEOUT" >"$work/params.json"

if ! command_id=$(aws ssm send-command --region "$REGION" --instance-ids "$INSTANCE_ID" \
  --document-name AWS-RunShellScript --comment "$comment" --timeout-seconds 60 \
  --parameters "file://$work/params.json" --query Command.CommandId --output text 2>"$work/err"); then
  echo "SSM 명령을 보내지 못했다: $(redact <"$work/err" | head -n 1)" >&2
  exit 1
fi
echo "SSM 명령을 보냈다($comment). 끝날 때까지 기다린다(최대 ${TIMEOUT}초)."

deadline=$((SECONDS + TIMEOUT + ${SSM_GRACE:-60}))
status=Pending
code=-1
while :; do
  sleep "$POLL"
  if result=$(aws ssm get-command-invocation --region "$REGION" --command-id "$command_id" \
    --instance-id "$INSTANCE_ID" --query '[Status,ResponseCode]' --output text 2>"$work/err"); then
    read -r status code <<<"$result"
    case $status in
      Pending | InProgress | Delayed | Cancelling) ;;
      *) break ;;
    esac
  elif ! grep -q InvocationDoesNotExist "$work/err"; then
    echo "결과를 읽지 못했다: $(redact <"$work/err" | head -n 1)" >&2
    exit 1
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "${TIMEOUT}초 안에 끝나지 않았다(마지막 상태 $status)." >&2
    exit 124
  fi
done

echo "── 서버 출력 ──"
aws ssm get-command-invocation --region "$REGION" --command-id "$command_id" --instance-id "$INSTANCE_ID" \
  --query StandardOutputContent --output text 2>/dev/null | redact || true
errors=$(aws ssm get-command-invocation --region "$REGION" --command-id "$command_id" --instance-id "$INSTANCE_ID" \
  --query StandardErrorContent --output text 2>/dev/null | redact || true)
if [ -n "$errors" ] && [ "$errors" != None ]; then
  echo "── 서버 오류 출력 ──"
  echo "$errors"
fi
echo "──────────────"
echo "SSM 상태 $status, 종료 코드 $code"

case $status in
  Success) exit 0 ;;
  Failed)
    if [[ "$code" =~ ^[0-9]+$ ]] && [ "$code" -gt 0 ]; then exit "$code"; fi
    exit 1
    ;;
  *TimedOut) exit 124 ;;
  *) exit 1 ;;
esac
