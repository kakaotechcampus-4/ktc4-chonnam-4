#!/usr/bin/env bash
# check-public-files.sh 자체 검사. 임시 git 레포에 파일을 넣고 걸리는지·통과하는지 본다.
#   bash scripts/test-check-public-files.sh   (verify.sh workflows 에 포함)
# 걸려야 하는 값은 실행할 때 조립한다(이 파일 자체가 검사에 걸리지 않게).
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
SCRIPT="$ROOT/scripts/check-public-files.sh"
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

# 걸려야 하는 값(조립)
acct=$(printf '%s%s' 4455 66778899)                  # 예시가 아닌 12자리 계정
arn="arn:aws:iam::$acct:role/x"
ecr="$acct.dkr.ecr.ap-northeast-2.amazonaws.com/neuringo/backend"
dns="ec2-$(printf '%s' 13-125-7-9).ap-northeast-2.compute.amazonaws.com"
portal="https://d-0000000000.aws$(printf apps).com/start"
notion="https://app.notion.com/p/elice$(printf -- -track)/x"
cf="d$(printf '%s' 3kq9x7m2p4w8z).cloudfront.net"     # 예시가 아닌 CloudFront 주소

repo() { # 새 임시 레포
  rm -rf "$tmp/r"
  mkdir -p "$tmp/r"
  git -C "$tmp/r" init -q
  git -C "$tmp/r" config user.email t@example.com
  git -C "$tmp/r" config user.name t
  git -C "$tmp/r" config core.autocrlf false
  echo ok >"$tmp/r/README.md"
  git -C "$tmp/r" add -A
  git -C "$tmp/r" commit -qm base
}
put() { # 경로 내용 — 넣고 커밋
  mkdir -p "$(dirname "$tmp/r/$1")"
  printf '%s\n' "$2" >"$tmp/r/$1"
  git -C "$tmp/r" add -A
  git -C "$tmp/r" commit -qm "add $1"
}
scan() { # [기준]
  code=0
  out=$(cd "$tmp/r" && bash "$SCRIPT" "$@" 2>&1) || code=$?
}

echo "check-public-files.sh 자체 검사"

repo
put deploy/.env.example "DB_PASSWORD="
put infra/terraform.tfvars.example 'monthly_budget_usd = 70'
put docs/a.md "예시 arn:aws:iam::123456789012:role/x · ec2-203-0-113-10.ap-northeast-2.compute.amazonaws.com · d111111abcdef8.cloudfront.net"
scan
check "견본(.example)·예시 계정·문서용 IP·예시 CloudFront 주소는 통과한다" same "$code" 0

for f in .env backend/.env.local infra/live/dev/terraform.tfstate infra/live/dev/dev.tfplan infra/prod.tfvars \
  deploy/server.pem .aws/credentials backend/src/main/resources/application-dev-secret.yml backups/daily.dump \
  .claude/settings.json infra/live/dev/.terraform/x; do
  repo
  put "$f" "x"
  scan
  check "이름으로 막는다: $f" same "$code" 1
done

repo
put docs/b.md "$arn"
scan
check "계정 ID 가 든 ARN 을 막는다" same "$code" 1
check "어느 파일 몇 번째 줄인지 알려 준다" has "$out" "docs/b.md:1"
check "값(계정 ID)은 찍지 않는다" lacks "$out" "$acct"

for v in "$ecr" "$dns" "$portal" "$notion" "$cf"; do
  repo
  put docs/c.md "주소 $v"
  scan
  check "내용으로 막는다: ${v:0:24}…" same "$code" 1
done

repo
printf 'arn:aws:iam::%s:role/x\n' "$acct" >"$tmp/r/new.txt"
scan
check "아직 커밋 안 한 새 파일도 본다(커밋 전에 잡는다)" same "$code" 1

repo
base=$(git -C "$tmp/r" rev-parse HEAD)
put .env "x"
git -C "$tmp/r" rm -q .env
git -C "$tmp/r" commit -qm "remove .env"
scan
check "지운 파일은 지금 파일만 보면 통과한다" same "$code" 0
scan "$base"
check "기준을 주면 이력에 들어왔던 파일도 막는다(공개 이력에 남는다)" same "$code" 1

repo
base=$(git -C "$tmp/r" rev-parse HEAD)
put docs/d.md "$arn"
put docs/d.md "가렸다"
scan "$base"
check "기준을 주면 이력에서 추가됐던 줄도 막는다" same "$code" 1
check "이력에서 찾은 것이라고 알려 준다" has "$out" "(이력)"

if [ "$failures" -gt 0 ]; then
  echo "check-public-files.sh 자체 검사 실패: $failures 건" >&2
  exit 1
fi
echo "check-public-files.sh 자체 검사 통과"
