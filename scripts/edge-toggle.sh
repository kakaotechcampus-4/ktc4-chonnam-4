#!/usr/bin/env bash
# CloudFront(대문)를 켜거나 끈다. dev-server.yml 이 서버를 켠 뒤·끄기 전에 OIDC 자격증명을 받고 부른다.
#   CF_DISTRIBUTION_ID=<배포 ID> bash scripts/edge-toggle.sh on <EC2 공인 DNS>   # 원본을 새 서버 주소로 바꾸고 켠다
#   CF_DISTRIBUTION_ID=<배포 ID> bash scripts/edge-toggle.sh off                 # 끈다. 다 퍼질 때까지 기다리고 꺼진 것을 확인한다
#   CF_DISTRIBUTION_ID=<배포 ID> bash scripts/edge-toggle.sh status
#
# 고정 IP(Elastic IP)가 없어 서버를 껐다 켜면 공인 주소가 바뀐다. 꺼진 서버의 옛 IP 는 다른 AWS 고객에게 갈 수 있어서
# 서버를 끄기 전에 대문을 닫고(다 닫힌 것을 확인한 뒤에만 서버를 끈다), 켠 뒤 새 주소를 넣고 연다.
# Terraform 을 거치지 않는다(승인·plan 없이 몇 초). 구조(캐시·보안 헤더·나라 제한)는 Terraform 이 관리하고,
# 여기서는 Enabled 와 원본(app) 주소만 바꾼다(scripts/edge-toggle.mjs 가 나머지가 그대로인지 확인한다).
# 바꿀 때는 읽은 설정의 버전(ETag)을 붙인다. 그사이 Terraform 등이 바꿨으면 실패한다(덮어쓰지 않는다).
#
# 출력(KEY=값): EDGE_RESULT=changed|unchanged, EDGE_STATUS=Deployed|InProgress, EDGE_ENABLED=true|false, EDGE_SECONDS
# 배포 ID·주소는 찍지 않는다(공개 로그). 환경변수: EDGE_WAIT(off 때 기다릴 초, 기본 900), EDGE_POLL(기본 10)
set -euo pipefail

action=${1:?on·off·status 중 하나}
domain=${2:-}
: "${CF_DISTRIBUTION_ID:?CF_DISTRIBUTION_ID(배포 ID)가 필요하다}"
WAIT=${EDGE_WAIT:-900}
POLL=${EDGE_POLL:-10}
HERE=$(cd "$(dirname "$0")" && pwd)

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
started=$SECONDS

error_code() { grep -oE '\([A-Za-z]+\)' "$work/err" | head -n 1 || echo '(코드 없음)'; }

state() { # → "상태 켜짐" (예: "Deployed false")
  aws cloudfront get-distribution --id "$CF_DISTRIBUTION_ID" \
    --query '[Distribution.Status,Distribution.DistributionConfig.Enabled]' --output text 2>"$work/err"
}

report() { # 결과 상태 켜짐
  echo "EDGE_RESULT=$1"
  echo "EDGE_STATUS=$2"
  echo "EDGE_ENABLED=$3"
  echo "EDGE_SECONDS=$((SECONDS - started))"
}

case $action in
  status)
    if ! now=$(state); then
      echo "CloudFront 상태를 읽지 못했다 $(error_code)" >&2
      exit 1
    fi
    read -r status enabled <<<"$now"
    report unchanged "$status" "$enabled"
    exit 0
    ;;
  on | off) ;;
  *)
    echo "on·off·status 중 하나" >&2
    exit 2
    ;;
esac

if ! aws cloudfront get-distribution-config --id "$CF_DISTRIBUTION_ID" --output json >"$work/get.json" 2>"$work/err"; then
  echo "CloudFront 설정을 읽지 못했다 $(error_code). 배포 역할의 cloudfront:GetDistributionConfig 권한을 확인한다" >&2
  exit 1
fi
if ! node "$HERE/edge-toggle.mjs" "$work/get.json" "$action" "${domain:--}" "$work/config.json" >"$work/plan" 2>"$work/err"; then
  cat "$work/err" >&2
  exit 1
fi
changed=$(sed -n 's/^CHANGED=//p' "$work/plan")
etag=$(sed -n 's/^ETAG=//p' "$work/plan")

result=unchanged
if [ "$changed" = 1 ]; then
  if ! aws cloudfront update-distribution --id "$CF_DISTRIBUTION_ID" --if-match "$etag" \
    --distribution-config "file://$work/config.json" --query Distribution.Status --output text >/dev/null 2>"$work/err"; then
    if grep -q PreconditionFailed "$work/err"; then
      echo "그사이 다른 실행(Terraform 등)이 CloudFront 를 바꿨다. 끝난 뒤 다시 누른다" >&2
    else
      echo "CloudFront 를 바꾸지 못했다 $(error_code). 배포 역할의 cloudfront:UpdateDistribution 권한을 확인한다" >&2
    fi
    exit 1
  fi
  result=changed
fi

want=$([ "$action" = on ] && echo True || echo False)
# 켤 때는 기다리지 않는다(서버에서 CloudFront 주소로 불러 보는 확인이 실제 준비를 본다).
# 끌 때는 다 퍼져서 정말 꺼진 것을 본 뒤에만 끝낸다. 그 전에 서버를 끄면 그사이 옛 IP 로 요청이 갈 수 있다.
deadline=$((SECONDS + WAIT))
while :; do
  if ! now=$(state); then
    echo "CloudFront 상태를 읽지 못했다 $(error_code)" >&2
    exit 1
  fi
  read -r status enabled <<<"$now"
  if [ "$action" = on ] || { [ "$status" = Deployed ] && [ "${enabled,,}" = "${want,,}" ]; }; then
    break
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    report "$result" "$status" "$enabled"
    echo "${WAIT}초 안에 CloudFront 가 다 꺼지지 않았다(상태 $status). 서버를 끄지 않는다. 잠시 뒤 끄기를 다시 누른다" >&2
    exit 1
  fi
  sleep "$POLL"
done
report "$result" "$status" "$enabled"
