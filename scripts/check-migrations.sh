#!/usr/bin/env bash
# Flyway 마이그레이션 가드 — 이미 적용됐을 수 있는 마이그레이션을 고치거나 버전을 꼬는 변경을 막는다.
#
#   bash scripts/check-migrations.sh                # 로컬: origin/develop 과 비교 (커밋 안 한 변경·새 파일 포함)
#   bash scripts/check-migrations.sh origin/main    # 기준을 바꿔서
#   bash scripts/check-migrations.sh HEAD^1         # CI(PR): 머지 커밋의 첫 부모 = base 브랜치
#
# 왜: Flyway 는 적용한 파일의 체크섬을 DB 에 기록한다. 이미 적용된 V 파일을 고치면 그 DB 는 다음 기동 때
#     validate 오류로 뜨지 않는다. 버전이 기존 최대값보다 작은 파일도 같은 이유로 막힌다(outOfOrder=false).
#     CI 는 매번 빈 DB 에 처음부터 적용하므로 이 사고를 절대 잡지 못한다. 그래서 git 기준점과 비교한다.
#
# 대상은 운영 마이그레이션 디렉터리 하나다. 테스트 전용 src/test/resources/db/migration 은 보지 않는다.
set -euo pipefail

BASE_REF="${1:-origin/develop}"
MIGRATION_DIR="${MIGRATION_DIR:-backend/src/main/resources/db/migration}"

cd "$(git rev-parse --show-toplevel)"

if ! base=$(git merge-base "$BASE_REF" HEAD 2>/dev/null); then
  echo "기준 '$BASE_REF' 를 찾을 수 없습니다. 'git fetch origin' 뒤에 다시 실행하세요." >&2
  exit 2
fi

errors=0
report() { # file message
  errors=$((errors + 1))
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::error file=$1::$2"
  else
    printf '  ✗ %s\n    %s\n' "$1" "$2"
  fi
}

# V1__x.sql, V1_1__x.sql, V1.1__x.sql → "1", "1.1", "1.1"  (Flyway 는 _ 를 . 으로 읽는다)
version_of() {
  local name=${1##*/}
  name=${name#V}
  name=${name%%__*}
  echo "${name//_/.}"
}

# 1) base 에 이미 있던 파일은 삭제도 수정도 안 된다.
base_versions=()
while IFS= read -r file; do
  [ -n "$file" ] || continue
  case "${file##*/}" in V*__*.sql) base_versions+=("$(version_of "$file")") ;; esac
  if [ ! -e "$file" ]; then
    report "$file" "이미 있던 마이그레이션이 삭제됐습니다. 적용된 DB 에서는 되돌릴 수 없습니다. 파일을 되살리고, 지우려는 내용은 새 V 파일(DROP 등)로 적으세요."
  elif ! git diff --quiet "$base" -- "$file"; then
    report "$file" "이미 있던 마이그레이션이 수정됐습니다. 체크섬이 달라져 적용된 DB 가 기동하지 못합니다. 이 파일은 되돌리고, 바꿀 내용은 새 V<다음 번호>__<설명>.sql 로 추가하세요."
  fi
done < <(git ls-tree -r --name-only "$base" -- "$MIGRATION_DIR/")

max_base=""
if [ "${#base_versions[@]}" -gt 0 ]; then
  max_base=$(printf '%s\n' "${base_versions[@]}" | sort -V | tail -n 1)
fi

# 2) 지금 있는 파일: 이름 규칙, 버전 중복, 새 파일의 버전 역행
declare -A seen=()
if [ -d "$MIGRATION_DIR" ]; then
  for path in "$MIGRATION_DIR"/*; do
    [ -f "$path" ] || continue
    name=${path##*/}
    case "$name" in
      .gitkeep) continue ;;
      R__?*.sql) continue ;;
    esac

    if ! [[ "$name" =~ ^V[0-9]+([._][0-9]+)*__[A-Za-z0-9_.-]+\.sql$ ]]; then
      report "$path" "파일 이름이 Flyway 규칙과 다릅니다. V<번호>__<설명>.sql (밑줄 두 개, 확장자 소문자) 또는 R__<설명>.sql 로 지으세요."
      continue
    fi

    version=$(version_of "$name")
    if [ -n "${seen[$version]:-}" ]; then
      report "$path" "버전 $version 이 ${seen[$version]} 와 겹칩니다. Flyway 는 같은 버전이 둘이면 기동을 멈춥니다. 다른 사람과 동시에 만들었다면 뒤에 들어오는 쪽 번호를 올리세요."
    fi
    seen[$version]=$name

    if ! git cat-file -e "$base:$path" 2>/dev/null && [ -n "$max_base" ]; then
      highest=$(printf '%s\n%s\n' "$version" "$max_base" | sort -V | tail -n 1)
      if [ "$version" = "$max_base" ] || [ "$highest" != "$version" ]; then
        report "$path" "새 마이그레이션 버전 $version 이 기존 최대 버전 $max_base 보다 크지 않습니다. 이미 $max_base 까지 적용된 DB 는 이 파일을 적용하지 않고 기동을 막습니다. 번호를 $max_base 보다 크게 바꾸세요."
      fi
    fi
  done
fi

if [ "$errors" -gt 0 ]; then
  echo "마이그레이션 가드: 문제 ${errors}건 (기준: $BASE_REF)" >&2
  exit 1
fi
echo "마이그레이션 가드: 통과 (기준: $BASE_REF, 기존 최대 버전: ${max_base:-없음})"
