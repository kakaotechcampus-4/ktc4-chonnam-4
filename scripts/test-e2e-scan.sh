#!/usr/bin/env bash
# e2e.sh scan 자체 검사. 마커가 로그에 있으면 실패하고, 없으면 통과해야 한다.
#   bash scripts/test-e2e-scan.sh   (verify.sh workflows 에 포함)
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

marker="e2e-selftest-$RANDOM"
failures=0

expect() { # 기대(pass|fail) 설명
  local want=$1 what=$2 got=pass
  E2E_LOG_DIR="$tmp" bash scripts/e2e.sh scan >/dev/null 2>&1 || got=fail
  if [ "$got" = "$want" ]; then
    echo "  ✓ $what"
  else
    echo "  ✗ $what (기대 $want, 실제 $got)"
    failures=$((failures + 1))
  fi
}

echo "e2e.sh scan 자체 검사"

expect pass "마커 파일이 없으면(E2E 가 시작 전에 멈춤) 통과"

echo "$marker" >"$tmp/marker"
printf 'INFO Started NeuringoBeApplication\nDEBUG insert into child (class_id,display_name,status,child_id) values (?,?,?,?)\n' >"$tmp/backend.log"
printf 'LOG:  database system is ready to accept connections\n' >"$tmp/db.log"
expect pass "로그에 마커가 없으면 통과"

printf 'DEBUG created child 김하늘 %s\n' "$marker" >>"$tmp/backend.log"
expect fail "백엔드 로그에 마커가 한 줄이라도 있으면 실패"

printf 'INFO Started NeuringoBeApplication\n' >"$tmp/backend.log"
printf 'DETAIL:  Key (display_name)=(김하늘 %s) already exists.\n' "$marker" >>"$tmp/db.log"
expect fail "DB 로그에 마커가 있어도 실패"

if [ "$failures" -gt 0 ]; then
  echo "자체 검사 실패 ${failures}건"
  exit 1
fi
echo "e2e.sh scan 자체 검사: 전부 통과"
