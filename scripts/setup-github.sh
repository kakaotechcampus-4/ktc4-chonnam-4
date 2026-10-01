#!/usr/bin/env bash
# 라벨 · 마일스톤 일괄 생성 (여러 번 돌려도 안전)
#
#   bash scripts/setup-github.sh            # 미리보기만 한다. 레포에는 아무것도 만들지 않는다.
#   bash scripts/setup-github.sh --apply    # 실제로 만든다 (팀 공유 레포라 팀과 합의한 뒤에)
#
# 필요: gh CLI 로그인 (gh auth status), repo 에 write 권한
set -euo pipefail

APPLY=false
case "${1:-}" in
  --apply) APPLY=true ;;
  "") ;;
  *)
    echo "사용법: bash scripts/setup-github.sh [--apply]" >&2
    exit 2
    ;;
esac

REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
TODAY="$(date +%F)"
if $APPLY; then
  echo "대상 repo: $REPO  (적용)"
else
  echo "대상 repo: $REPO  (미리보기 — 만들려면 --apply)"
fi
echo

echo "== 라벨 =="
create_label() { # name color description
  if $APPLY; then
    gh label create "$1" --color "$2" --description "$3" --force --repo "$REPO" >/dev/null
  fi
  echo "  $1 — $3"
}
# 상태
create_label "todo"        "ededed" "착수 전"
create_label "in-progress" "1d76db" "진행 중 (담당자당 1개)"
create_label "review"      "fbca04" "리뷰 대기"
create_label "done"        "0e8a16" "완료 기준까지 확인함"
create_label "blocked"     "b60205" "선행 결정·의존성 때문에 착수·완료 불가"
# 영역
create_label "FE"          "c5def5" "frontend"
create_label "BEAI"        "bfd4f2" "backend & AI"
create_label "infra"       "d4c5f9" "EC2·RDS·CI/CD"
# 우선순위
create_label "P0"          "e11d21" "9/25 사용자 테스트 MVP"
create_label "P1"          "eb6420" "11/6 필수 기능"
create_label "P2"          "fef2c0" "11/13 품질·운영"
create_label "Decision Needed" "5319e7" "정책 결정 전 구현 보류"
echo

echo "== 마일스톤 =="
existing_milestones="$(gh api "repos/$REPO/milestones?state=all&per_page=100" -q '.[].title')"
create_milestone() { # title due(YYYY-MM-DD)
  local note=""
  [[ "$2" < "$TODAY" ]] && note="  (기한 지남)"
  if grep -Fxq "$1" <<<"$existing_milestones"; then
    echo "  $1 (이미 있음)"
  elif $APPLY; then
    gh api "repos/$REPO/milestones" -X POST \
      -f title="$1" -f due_on="$2T14:59:59Z" >/dev/null
    echo "  $1  →  $2$note"
  else
    echo "  $1  →  $2$note"
  fi
}
# due_on 은 UTC. 14:59:59Z = 한국시간 23:59:59
create_milestone "준비 스프린트" "2026-09-20"
create_milestone "스프린트 1"   "2026-09-25"
create_milestone "스프린트 2"   "2026-10-02"
create_milestone "스프린트 3"   "2026-10-09"
create_milestone "스프린트 4"   "2026-10-16"
create_milestone "스프린트 5"   "2026-10-23"
create_milestone "스프린트 6"   "2026-10-30"
create_milestone "스프린트 7"   "2026-11-06"
create_milestone "스프린트 8"   "2026-11-13"
create_milestone "최종 검수"    "2026-11-20"
echo
if $APPLY; then
  echo "끝."
else
  echo "미리보기 끝. 실제로 만들려면: bash scripts/setup-github.sh --apply"
fi
