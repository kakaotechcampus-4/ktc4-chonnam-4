#!/usr/bin/env bash
# 사람이 테스트 주소를 쓰는 중이면 자동 배포를 미룬다. release.yml 이 저절로 돈 배포(develop 반영·PR 을 열거나 push 해서 돈
# 미리보기) 전에 부른다. 사람이 직접 누른 배포는 부르지 않는다(누른 사람이 정한다).
#   bash scripts/wait-idle.sh <최대 기다릴 분>
#
# 서버의 status.sh 로 사람이 앱을 쓴 마지막 시각(STATUS_LAST_USE = 마지막 API 요청)을 보고, 그 뒤 QUIET_MINUTES 가 지났으면 끝낸다.
# 최대 시간까지 계속 쓰이면 75(미룸). 서버를 읽지 못하거나(꺼짐·SSM 실패) 옛 status.sh(값이 없음)면 기다리지 않는다 — 배포가
# 그 상황을 따로 다룬다(서버 꺼짐 69 등).
# 출력(표준 출력, KEY=값): WAIT_RESULT=idle|busy|unknown · WAIT_LAST_USE=<시각, 없으면 빈 값> · WAIT_WAITED=<기다린 분>
# 환경변수: QUIET_MINUTES(기본 5), POLL_SECONDS(기본 60), STATUS_CMD(상태를 내는 명령, 자체 검사용. 기본은 ssm-run.sh 로 status.sh)
set -euo pipefail

max=${1:-}
[[ "$max" =~ ^[0-9]{1,3}$ ]] || {
  echo "쓰는 법: wait-idle.sh <최대 기다릴 분(0~999)>" >&2
  exit 64
}
quiet=${QUIET_MINUTES:-5}
poll=${POLL_SECONDS:-60}
here=$(cd "$(dirname "$0")" && pwd)

read_status() {
  if [ -n "${STATUS_CMD:-}" ]; then
    bash -c "$STATUS_CMD"
    return
  fi
  local remote
  remote=$(mktemp)
  # shellcheck disable=SC2016 # 서버에서 풀린다
  printf '#!/usr/bin/env bash\nf=/opt/neuringo/current/deploy/host/status.sh\n[ -f "$f" ] && bash "$f"\n' >"$remote"
  bash "$here/ssm-run.sh" "$remote" "status (wait idle)" || true
  rm -f "$remote"
}

finish() { # 결과 마지막사용 종료코드
  echo "WAIT_RESULT=$1"
  echo "WAIT_LAST_USE=$2"
  echo "WAIT_WAITED=$((($(date +%s) - start) / 60))"
  exit "$3"
}

start=$(date +%s)
while :; do
  out=$(read_status 2>/dev/null) || out=""
  grep -q '^STATUS_LAST_USE=' <<<"$out" || finish unknown "" 0
  last=$(sed -n 's/^STATUS_LAST_USE=//p' <<<"$out" | tail -n 1)
  [ -n "$last" ] || finish idle "" 0
  at=$(date -u -d "$last" +%s 2>/dev/null) || finish unknown "" 0
  idle=$((($(date +%s) - at) / 60))
  [ "$idle" -lt "$quiet" ] || finish idle "$last" 0
  [ $(($(date +%s) - start)) -lt $((max * 60)) ] || finish busy "$last" 75
  echo "쓰는 중이다(마지막 API 요청 ${idle}분 전, ${quiet}분 동안 없으면 배포한다). ${poll}초 뒤 다시 본다" >&2
  sleep "$poll"
done
