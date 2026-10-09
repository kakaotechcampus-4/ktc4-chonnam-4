#!/usr/bin/env bash
# codeql-comment.sh·codeql-summary.sh 자체 검사. 가짜 gh 로 포크 PR 찾기와 코멘트 새로 달기·고쳐 쓰기를 흉내 낸다.
#   bash scripts/test-codeql-comment.sh   (verify.sh workflows 에 포함)
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
lacks() { ! has "$@"; }
same() { [ "$1" = "$2" ]; }

# 가짜 gh: 받은 인자를 한 줄씩 기록하고, 요청 종류에 맞는 응답(--jq 를 거친 뒤의 모양)을 낸다.
mkdir -p "$tmp/bin"
cat >"$tmp/bin/gh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$FAKE_LOG"
case "$*" in
  *"/pulls"*) cat "$FAKE_PULLS" ;;
  *"-X PATCH"*) ;;
  *"/issues/"*"/comments -f body="*) ;;
  *"/issues/"*"/comments"*) cat "$FAKE_COMMENTS" ;;
esac
EOF
chmod +x "$tmp/bin/gh"

run() { # 인자...
  : >"$tmp/log"
  code=0
  out=$(PATH="$tmp/bin:$PATH" FAKE_LOG="$tmp/log" FAKE_PULLS="$tmp/pulls" FAKE_COMMENTS="$tmp/comments" \
    GITHUB_REPOSITORY=team/repo bash scripts/codeql-comment.sh "$@" 2>&1) || code=$?
  log=$(cat "$tmp/log")
}

SHA=1111111111111111111111111111111111111111
OLD=2222222222222222222222222222222222222222

echo "codeql-comment.sh 자체 검사"

printf '42 %s alice/repo\n' "$SHA" >"$tmp/pulls"
run find alice/repo 'feat/a&b#1' "$SHA"
check "head 가 분석한 커밋인 열린 PR 의 번호를 낸다" same "$out" 42
check "열린 PR 만 찾는다" has "$log" "state=open"
check "포크 주인:브랜치로 찾는다(특수 글자는 -f 로 넘겨 인코딩된다)" has "$log" "-f head=alice:feat/a&b#1"

printf '42 %s alice/repo\n' "$OLD" >"$tmp/pulls"
run find alice/repo feat/a "$SHA"
check "그 뒤 push 해서 head 가 달라졌으면 번호를 내지 않는다(옛 결과로 덮지 않는다)" same "$out" ""
check "그때도 실패가 아니다" same "$code" 0

printf '42 %s mallory/repo\n' "$SHA" >"$tmp/pulls"
run find alice/repo feat/a "$SHA"
check "head 저장소가 다르면 번호를 내지 않는다" same "$out" ""

: >"$tmp/pulls"
run find alice/repo feat/a "$SHA"
check "열린 PR 이 없으면 빈 출력" same "$out" ""

printf '## CodeQL 정적 분석 결과\n표\n' >"$tmp/summary"

: >"$tmp/comments"
run post 42 "$tmp/summary"
check "기존 코멘트가 없으면 새로 단다" has "$log" "repos/team/repo/issues/42/comments -f body=<!-- codeql-summary -->"
check "그때 고쳐 쓰지 않는다" lacks "$log" "-X PATCH"
check "요약 내용을 그대로 싣는다" has "$log" "## CodeQL 정적 분석 결과"

echo 987 >"$tmp/comments"
run post 42 "$tmp/summary"
check "기존 코멘트가 있으면 그것을 고쳐 쓴다" has "$log" "-X PATCH repos/team/repo/issues/comments/987"
check "그때 새로 달지 않는다" lacks "$log" "repos/team/repo/issues/42/comments -f body="

run post '42; rm -rf /' "$tmp/summary"
check "PR 번호가 숫자가 아니면 거부한다" same "$code" 2
check "그때 gh 를 부르지 않는다" same "$log" ""

echo "codeql-summary.sh 링크 커밋"
cat >"$tmp/java.sarif" <<'EOF'
{"runs":[{"tool":{"driver":{"rules":[{"id":"java/x","shortDescription":{"text":"X"},"properties":{"security-severity":"7.5"}}]}},
  "results":[{"ruleId":"java/x","rule":{"index":0},"message":{"text":"m"},
    "locations":[{"physicalLocation":{"artifactLocation":{"uri":"A.java"},"region":{"startLine":3}}}]}]}]}
EOF
summary=$(GITHUB_REPOSITORY=team/repo GITHUB_SHA="$OLD" LINK_SHA="$SHA" HEAD_SHA="$SHA" bash scripts/codeql-summary.sh "$tmp/java.sarif")
check "LINK_SHA 가 있으면 그 커밋으로 링크한다(workflow_run 의 GITHUB_SHA 는 develop)" has "$summary" "blob/$SHA/A.java#L3"
summary=$(GITHUB_REPOSITORY=team/repo GITHUB_SHA="$OLD" bash scripts/codeql-summary.sh "$tmp/java.sarif")
check "없으면 지금처럼 GITHUB_SHA 로 링크한다" has "$summary" "blob/$OLD/A.java#L3"
check "발견 건수를 적는다" has "$summary" "발견 **1건**"

if [ "$failures" -gt 0 ]; then
  echo "실패 $failures 건"
  exit 1
fi
echo "전부 통과"
