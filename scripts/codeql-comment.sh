#!/usr/bin/env bash
# CodeQL 요약(codeql-summary.sh 출력)을 PR 코멘트 하나로 단다. 이미 있으면 그 코멘트를 고쳐 쓴다.
#
#   bash scripts/codeql-comment.sh post <PR 번호> <요약 파일>
#   bash scripts/codeql-comment.sh find <head 저장소(owner/name)> <head 브랜치> <head SHA>   # 열린 PR 번호를 찍는다. 없으면 빈 출력
#
# 같은 레포 브랜치에서 온 PR 은 codeql.yml 의 Report job 이 post 를 바로 부른다.
# 포크에서 온 PR 은 토큰이 읽기 전용이라 codeql-comment.yml(workflow_run)이 find 로 PR 을 찾은 뒤 post 를 부른다.
# 그쪽 실행의 workflow_run.pull_requests 는 포크 PR 이면 비어 있고, 산출물에 적힌 PR 번호는 포크 코드가 마음대로 쓸 수 있다.
# 그래서 head 저장소·브랜치로 열린 PR 을 찾고, 그 PR 의 지금 head 가 분석한 커밋과 같을 때만 번호를 낸다.
# 그 사이 새로 push 했으면 빈 출력이다(새 커밋의 실행이 다시 단다. 옛 결과로 덮지 않는다).
#
# GH_TOKEN·GITHUB_REPOSITORY 가 있어야 한다.
set -euo pipefail

MARKER='<!-- codeql-summary -->'
repo="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY 가 없다}"

find_pr() { # head 저장소 브랜치 SHA
  local head_repo=$1 branch=$2 sha=$3
  # 브랜치 이름에 & # 같은 글자가 있어도 -f 가 쿼리로 인코딩한다.
  gh api -X GET "repos/$repo/pulls" -f state=open -f head="${head_repo%%/*}:$branch" -f per_page=100 \
    --jq '.[] | "\(.number) \(.head.sha) \(.head.repo.full_name // "")"' |
    awk -v sha="$sha" -v hr="$head_repo" '$2 == sha && $3 == hr { print $1; exit }'
}

post() { # PR 번호 요약 파일
  local pr=$1 file=$2 body id
  [[ "$pr" =~ ^[0-9]+$ ]] || { echo "PR 번호가 숫자가 아니다: $pr" >&2; return 2; }
  body="$MARKER"$'\n'"$(cat "$file")"
  id=$(gh api --paginate "repos/$repo/issues/$pr/comments" \
    --jq ".[] | select(.user.login == \"github-actions[bot]\" and (.body | startswith(\"$MARKER\"))) | .id" | head -n1)
  if [ -n "$id" ]; then
    gh api -X PATCH "repos/$repo/issues/comments/$id" -f body="$body" >/dev/null
    echo "PR #$pr 의 CodeQL 코멘트를 고쳐 썼다"
  else
    gh api "repos/$repo/issues/$pr/comments" -f body="$body" >/dev/null
    echo "PR #$pr 에 CodeQL 코멘트를 달았다"
  fi
}

case "${1:-}" in
  find) [ $# -eq 4 ] || { echo "사용법: $0 find <head 저장소> <head 브랜치> <head SHA>" >&2; exit 2; }; find_pr "$2" "$3" "$4" ;;
  post) [ $# -eq 3 ] || { echo "사용법: $0 post <PR 번호> <요약 파일>" >&2; exit 2; }; post "$2" "$3" ;;
  *) echo "사용법: $0 find|post ..." >&2; exit 2 ;;
esac
