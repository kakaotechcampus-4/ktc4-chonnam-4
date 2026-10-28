#!/usr/bin/env bash
# wait-idle.sh 자체 검사. 서버 상태 대신 STATUS_CMD 로 마지막 사용 시각을 흉내 낸다(AWS 필요 없음).
# - 5분 넘게 안 쓰였거나 60분 동안 쓴 기록이 없으면 바로 배포 / 쓰는 중이면 기다렸다가 멈추면 배포 / 끝까지 쓰면 75(미룸)
# - 서버를 읽지 못하거나 옛 status.sh(값 없음)면 기다리지 않는다
#   bash scripts/test-wait-idle.sh   (verify.sh workflows 에 포함)
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
ago() { date -u -d "@$(($(date +%s) - $1 * 60))" +%Y-%m-%dT%H:%M:%SZ; }

run_wait() { # 최대분 상태명령
  code=0
  out=$(STATUS_CMD=$2 POLL_SECONDS=0 bash scripts/wait-idle.sh "$1" 2>"$tmp/err") || code=$?
}

echo "wait-idle.sh 자체 검사"

run_wait 15 "echo STATUS_LAST_USE=$(ago 7)"
check "마지막 사용이 5분보다 오래면 바로 배포한다" bash -c "[ $code = 0 ] && grep -q WAIT_RESULT=idle <<<\"\$0\"" "$out"

run_wait 15 "echo STATUS_LAST_USE="
check "60분 동안 쓴 기록이 없으면 바로 배포한다" bash -c "[ $code = 0 ] && grep -q WAIT_RESULT=idle <<<\"\$0\"" "$out"

# 처음 두 번은 쓰는 중, 세 번째부터 안 쓰임
echo 0 >"$tmp/n"
cmd="n=\$(cat '$tmp/n'); echo \$((n + 1)) >'$tmp/n'; if [ \"\$n\" -lt 2 ]; then echo STATUS_LAST_USE=$(ago 1); else echo STATUS_LAST_USE=$(ago 9); fi"
run_wait 15 "$cmd"
check "쓰는 중이면 기다렸다가 멈추면 배포한다" bash -c "[ $code = 0 ] && grep -q WAIT_RESULT=idle <<<\"\$0\" && [ \$(cat '$tmp/n') = 3 ]" "$out"
check "기다리는 까닭을 알린다" has "$(cat "$tmp/err")" "쓰는 중이다"

run_wait 0 "echo STATUS_LAST_USE=$(ago 1)"
check "최대 시간까지 계속 쓰면 75(미룸)" bash -c "[ $code = 75 ] && grep -q WAIT_RESULT=busy <<<\"\$0\"" "$out"
check "미룰 때 마지막 사용 시각을 남긴다" has "$out" "WAIT_LAST_USE=20"

run_wait 15 "echo STATUS_RELEASE=abc1234"
check "옛 status.sh(값 없음)면 기다리지 않는다" bash -c "[ $code = 0 ] && grep -q WAIT_RESULT=unknown <<<\"\$0\"" "$out"

run_wait 15 "exit 69"
check "서버를 읽지 못하면(꺼짐 등) 기다리지 않는다" bash -c "[ $code = 0 ] && grep -q WAIT_RESULT=unknown <<<\"\$0\"" "$out"

run_wait 15 "echo STATUS_LAST_USE=not-a-time"
check "시각 모양이 틀리면 기다리지 않는다" bash -c "[ $code = 0 ] && grep -q WAIT_RESULT=unknown <<<\"\$0\"" "$out"

code=0
bash scripts/wait-idle.sh abc >/dev/null 2>&1 || code=$?
check "최대 분이 숫자가 아니면 64" [ "$code" = 64 ]

if [ "$failures" -gt 0 ]; then
  echo "실패 $failures 개" >&2
  exit 1
fi
echo "모두 통과"
