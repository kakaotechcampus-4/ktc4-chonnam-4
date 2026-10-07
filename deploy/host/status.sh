#!/usr/bin/env bash
# 서버 상태를 KEY=값 줄로 알린다. dev-server.yml 이 상태 보기·켜기·미리보기 뒤에 SSM 으로 부른다.
#   NEURINGO_ENV=dev [EDGE_DOMAIN=<CloudFront 주소>] [EDGE_WAIT=초] bash deploy/host/status.sh
#
# - 지금 올라간 것: 커밋·develop/preview·PR·누가·언제(state/<환경>.release), 컨테이너 상태
# - 미리보기 자리: PR·누가·끝나는 시각·아직 유효한지(state/preview.lease)
# - EDGE_DOMAIN 이 있으면 서버에서 CloudFront 주소로 API·화면을 불러 상태 코드와 화면 헤더의 커밋이 지금 것과 같은지 본다.
#   CloudFront 는 한국에서만 열려 GitHub 러너(미국)는 403 을 받는다. 서버는 서울이라 통과한다.
#   EDGE_WAIT 초 동안 다시 불러 본다(대문을 막 열었을 때 퍼지는 시간). 주소는 출력에 찍지 않는다.
# 바꾸는 것은 없다. 잠금도 잡지 않는다(배포 중에도 볼 수 있게).
set -uo pipefail

ENV_NAME=${NEURINGO_ENV:-dev}
HOME_DIR=${NEURINGO_HOME:-/opt/neuringo}
EDGE_WAIT=${EDGE_WAIT:-0}
EDGE_INTERVAL=${EDGE_INTERVAL:-10}
state_dir="$HOME_DIR/state"
release_file="$state_dir/$ENV_NAME.release"
lease_file="$state_dir/preview.lease"

kv() { [ -f "$2" ] && sed -n "s/^$1=//p" "$2" | tail -n 1; }
iso() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -r "$1" +%Y-%m-%dT%H:%M:%SZ; }

release=$(kv RELEASE "$release_file")
mode=$(kv MODE "$release_file")
echo "STATUS_RELEASE=${release:-없음}"
echo "STATUS_MODE=${mode:-없음}"
echo "STATUS_PR=$(kv PR "$release_file")"
echo "STATUS_BY=$(kv BY "$release_file")"
echo "STATUS_AT=$(kv AT "$release_file")"

until_at=$(kv UNTIL "$lease_file")
[[ "$until_at" =~ ^[0-9]+$ ]] || until_at=0
if [ "$mode" = preview ] && [ "$until_at" -gt "$(date +%s)" ]; then
  echo "LEASE_ACTIVE=1"
  echo "LEASE_PR=$(kv PR "$lease_file")"
  echo "LEASE_BY=$(kv BY "$lease_file")"
  echo "LEASE_UNTIL=$(iso "$until_at")"
else
  echo "LEASE_ACTIVE=0"
fi

services=$(docker ps --filter "label=com.docker.compose.project=neuringo-$ENV_NAME" \
  --format '{{.Label "com.docker.compose.service"}}:{{.State}}' 2>/dev/null | sort | tr '\n' ' ')
echo "STATUS_CONTAINERS=${services:-없음}"
echo "STATUS_MEM_AVAILABLE_MB=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo ?)"
echo "STATUS_DISK_FREE_GB=$(df -BG --output=avail "$HOME_DIR" 2>/dev/null | tail -n 1 | tr -dc 0-9 || echo ?)"

[ -n "${EDGE_DOMAIN:-}" ] || exit 0
if ! [[ "$EDGE_DOMAIN" =~ ^[a-z0-9]+\.cloudfront\.net$ ]]; then
  echo "EDGE_CHECK=주소 모양이 틀렸다"
  exit 0
fi
deadline=$((SECONDS + EDGE_WAIT))
while :; do
  api=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "https://$EDGE_DOMAIN/api/v1/csrf" 2>/dev/null || echo 000)
  headers=$(curl -s -o /dev/null -D - -w '%{http_code}' --max-time 10 "https://$EDGE_DOMAIN/" 2>/dev/null || echo 000)
  page=${headers##*$'\n'}
  served=$(sed -n 's/^[Xx]-[Nn]euringo-[Rr]elease: *\([0-9A-Za-z._-]*\).*/\1/p' <<<"$headers" | head -n 1)
  match=no
  [ -n "$release" ] && [ "$served" = "$release" ] && match=yes
  if { [ "$api" = 200 ] && [ "$page" = 200 ] && [ "$match" = yes ]; } || [ "$SECONDS" -ge "$deadline" ]; then
    break
  fi
  sleep "$EDGE_INTERVAL"
done
echo "EDGE_API=$api"
echo "EDGE_PAGE=$page"
echo "EDGE_RELEASE=${served:-없음}"
echo "EDGE_RELEASE_MATCH=$match"
