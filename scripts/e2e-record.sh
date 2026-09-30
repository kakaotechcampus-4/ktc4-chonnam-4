#!/usr/bin/env bash
# E2E 경고 기록. 실패했거나 재시도 끝에 통과한 테스트가 있으면 Issue "E2E 경고 기록" 에 댓글로 남긴다.
# PR 에서는 E2E 가 막지 않고 경고만 하므로, 경고가 어떤 이유로 났는지 나중에 모아 볼 수 있게 한곳에 쌓는다.
# Issue 가 없으면(처음이거나 누가 닫았으면) 새로 만든다. 원인은 확인한 사람이 그 댓글을 인용해 적는다.
#
#   CI 에서만 부른다(e2e.yml). 필요한 환경 변수: GH_TOKEN, GITHUB_REPOSITORY, E2E_OUTCOME, E2E_WHERE, E2E_SHA, E2E_RUN_URL
#   E2E_RECORD_DRY_RUN=1 이면 올리지 않고 댓글 본문만 출력한다(scripts/test-e2e-record.sh)
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

REPORT=${E2E_REPORT:-frontend/test-results/playwright-results.json}
TITLE="E2E 경고 기록"

body=$(node scripts/e2e-record-body.mjs "$REPORT")
if [ -z "$body" ]; then
  echo "기록할 E2E 경고가 없다."
  exit 0
fi

if [ "${E2E_RECORD_DRY_RUN:-0}" = 1 ]; then
  echo "$body"
  exit 0
fi

# 검색 API 는 새 Issue 가 늦게 잡혀서 쓰지 않는다. 봇이 만든 열린 Issue 중에서 제목이 같은 것을 찾는다.
issue=$(gh api --paginate "repos/$GITHUB_REPOSITORY/issues?state=open&per_page=100" \
  --jq ".[] | select(.pull_request == null and .user.login == \"github-actions[bot]\" and .title == \"$TITLE\") | .number" |
  head -n1)

if [ -z "$issue" ]; then
  intro=$(
    cat <<'EOF'
PR 에서 E2E 가 실패해도 머지는 막지 않고 경고만 합니다(`docs/testing.md` "CI 가 빨간색일 때").
경고가 난 이유를 나중에 모아 보려고, 실패했거나 재시도 끝에 통과한 테스트를 `e2e.yml` 이 이 Issue 에 댓글로 남깁니다(PR·develop 모두).

- 경고를 확인한 분은 그 댓글을 인용(Quote reply)해서 원인을 적어 주세요. 예: CI 서버 지연 / 실제 버그 / 테스트 코드 문제
- 같은 테스트가 반복해서 올라오면 그 테스트를 고칠 차례입니다.
- 이 Issue 를 닫으면 다음 경고 때 새 Issue 가 만들어집니다.
EOF
  )
  issue=$(gh api "repos/$GITHUB_REPOSITORY/issues" -f title="$TITLE" -f body="$intro" --jq .number)
  echo "Issue #$issue 를 새로 만들었다."
fi

gh api "repos/$GITHUB_REPOSITORY/issues/$issue/comments" -f body="$body" >/dev/null
url="${GITHUB_SERVER_URL:-https://github.com}/$GITHUB_REPOSITORY/issues/$issue"
echo "E2E 경고를 $url 에 기록했다."
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "E2E 경고를 [$TITLE #$issue]($url) 에 기록했습니다. 원인을 확인하면 그 댓글에 적어 주세요." >>"$GITHUB_STEP_SUMMARY"
fi
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "recorded=true" >>"$GITHUB_OUTPUT"
fi
