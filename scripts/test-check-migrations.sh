#!/usr/bin/env bash
# check-migrations.sh 자체를 검사한다. 임시 git 저장소에 시나리오를 만들어 막아야 할 것은 막고
# 통과해야 할 것은 통과하는지 본다. 실제 레포의 마이그레이션 파일은 건드리지 않는다.
#
#   bash scripts/test-check-migrations.sh
set -euo pipefail

GUARD="$(cd "$(dirname "$0")" && pwd)/check-migrations.sh"
DIR=backend/src/main/resources/db/migration
failures=0

new_repo() {
  local repo
  repo=$(mktemp -d)
  git -C "$repo" init -q -b main
  git -C "$repo" config user.email "guard-test@example.invalid"
  git -C "$repo" config user.name "guard-test"
  git -C "$repo" config core.autocrlf false
  mkdir -p "$repo/$DIR"
  echo "CREATE TABLE a (id INT);" > "$repo/$DIR/V1__create_a.sql"
  echo "CREATE TABLE b (id INT);" > "$repo/$DIR/V2__create_b.sql"
  git -C "$repo" add -A && git -C "$repo" commit -qm base
  git -C "$repo" branch base
  echo "$repo"
}

expect() { # pass|fail 설명 repo [base-ref]
  local want=$1 label=$2 repo=$3 ref=${4:-base} got
  if (cd "$repo" && bash "$GUARD" "$ref" >/dev/null 2>&1); then got=pass; else got=fail; fi
  if [ "$got" = "$want" ]; then
    echo "  ok    $label"
  else
    echo "  FAIL  $label (기대 $want, 실제 $got)"
    failures=$((failures + 1))
  fi
  rm -rf "$repo"
}

r=$(new_repo); echo "CREATE TABLE c (id INT);" > "$r/$DIR/V3__create_c.sql"
expect pass "다음 번호로 새 파일 추가" "$r"

r=$(new_repo); echo "CREATE VIEW v AS SELECT 1;" > "$r/$DIR/R__view.sql"
expect pass "반복 마이그레이션(R__) 추가" "$r"

r=$(new_repo); echo "ALTER TABLE a ADD x INT;" >> "$r/$DIR/V1__create_a.sql"
expect fail "이미 있던 V1 수정" "$r"

r=$(new_repo); rm "$r/$DIR/V2__create_b.sql"
expect fail "이미 있던 V2 삭제" "$r"

r=$(new_repo); echo "SELECT 1;" > "$r/$DIR/V1_5__late.sql"
expect fail "기존 최대(V2)보다 작은 새 버전" "$r"

r=$(new_repo); echo "SELECT 1;" > "$r/$DIR/V3__one.sql"; echo "SELECT 2;" > "$r/$DIR/V3__two.sql"
expect fail "같은 버전 두 개" "$r"

r=$(new_repo); echo "SELECT 1;" > "$r/$DIR/V3_bad_name.sql"
expect fail "밑줄 하나짜리 잘못된 이름" "$r"

# CI 모양: 기능 브랜치를 base 에 --no-ff 로 머지한 커밋에서 HEAD^1 과 비교한다.
r=$(new_repo)
git -C "$r" checkout -qb feature
echo "CREATE TABLE c (id INT);" > "$r/$DIR/V3__create_c.sql"
git -C "$r" add -A && git -C "$r" commit -qm feature
git -C "$r" checkout -q main && git -C "$r" merge -q --no-ff feature -m merge
expect pass "CI: 머지 커밋에서 HEAD^1 기준, 새 파일만 추가" "$r" "HEAD^1"

r=$(new_repo)
git -C "$r" checkout -qb feature
echo "ALTER TABLE a ADD x INT;" >> "$r/$DIR/V1__create_a.sql"
git -C "$r" commit -qam feature
git -C "$r" checkout -q main && git -C "$r" merge -q --no-ff feature -m merge
expect fail "CI: 머지 커밋에서 HEAD^1 기준, 기존 파일 수정" "$r" "HEAD^1"

if [ "$failures" -gt 0 ]; then
  echo "check-migrations 자체 검사: ${failures}건 실패" >&2
  exit 1
fi
echo "check-migrations 자체 검사: 전부 통과"
