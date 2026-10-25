#!/usr/bin/env bash
# 서버 상태를 KEY=값 줄로 알린다. dev-server.yml 이 상태 보기·켜기·미리보기 뒤에 SSM 으로 부른다.
#   NEURINGO_ENV=dev [EDGE_DOMAIN=<CloudFront 주소>] [EDGE_WAIT=초] bash deploy/host/status.sh
#
# - 지금 올라간 것: 커밋·develop/preview·PR·누가·언제(state/<환경>.release), 컨테이너 상태, 화면에 마지막 요청이 온 시각, 서버를 켠 시각
# - 사람이 마지막으로 앱을 쓴 시각(STATUS_LAST_USE): 자동 배포가 쓰는 중이면 미룬다(scripts/wait-idle.sh)
# - 원본 비밀 헤더를 확인하나(STATUS_ORIGIN_CHECK)와, 서버 안에서 헤더 없이·붙여 불러 본 결과(403·200, 값은 찍지 않는다)
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
# 화면(nginx) 컨테이너 로그의 마지막 줄 시각 = 테스트 주소로 들어온 마지막 요청(안 쓰면 끄기가 본다). 요청 내용은 찍지 않는다.
web=$(docker ps -q --filter "label=com.docker.compose.project=neuringo-$ENV_NAME" \
  --filter "label=com.docker.compose.service=web" 2>/dev/null | head -n 1)
last_request=""
if [ -n "$web" ]; then
  stamp=$(docker logs --tail 1 --timestamps "$web" 2>&1 | awk 'NR == 1 {print $1}')
  [ -z "$stamp" ] || last_request=$(date -u -d "$stamp" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null) || last_request=""
fi
echo "STATUS_LAST_REQUEST=$last_request"
# 사람이 앱을 쓴 마지막 시각 = 화면 기록에서 API 요청(/api/…)의 마지막 줄(최근 60분). 상태 확인(/api/v1/csrf·화면 /)과
# 403(원본 비밀 헤더가 없는 요청 등)은 세지 않는다. 비어 있으면 60분 동안 쓴 사람이 없다.
last_use=""
if [ -n "$web" ]; then
  stamp=$(docker logs --since 60m --timestamps "$web" 2>&1 | grep -E '"[A-Z]+ /api/' |
    grep -vE '"[A-Z]+ /api/v1/csrf[ ?]' | grep -vE '" 403 ' | tail -n 1 | awk '{print $1}')
  [ -z "$stamp" ] || last_use=$(date -u -d "$stamp" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null) || last_use=""
fi
echo "STATUS_LAST_USE=$last_use"
# 서버를 켠 시각(켜자마자 미리보기를 빌드하는 몇 분 동안 안 쓰면 끄기가 끄지 않게).
if read -r up _ </proc/uptime 2>/dev/null; then
  echo "STATUS_BOOTED_AT=$(iso "$(($(date +%s) - ${up%.*}))")"
fi
echo "STATUS_ORIGIN_CHECK=$([ -f "$state_dir/nginx-origin/verify.conf" ] && echo on || echo off)"
# 원본 비밀 헤더가 실제로 막는지 서버 안에서 본다(밖에서는 보안 그룹이 CloudFront 만 받아 직접 닿지 못한다).
# 헤더 없이 → 403 이어야 하고, deploy.sh 가 둔 헤더 파일을 붙이면 → 200. 헤더 값은 파일로 넘겨 찍지 않는다.
# /api/v1/csrf 와 403 은 STATUS_LAST_USE 에 세지 않는다.
if [ -f "$state_dir/nginx-origin/verify.conf" ] && [ -f "$state_dir/origin-header" ]; then
  base=${STATUS_BASE_URL:-http://127.0.0.1:80}
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$base/api/v1/csrf" 2>/dev/null) || true
  echo "STATUS_ORIGIN_NO_HEADER=${code:-000}"
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -H "@$state_dir/origin-header" "$base/api/v1/csrf" 2>/dev/null) || true
  echo "STATUS_ORIGIN_WITH_HEADER=${code:-000}"
fi
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
