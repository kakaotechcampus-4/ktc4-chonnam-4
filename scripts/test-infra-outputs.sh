#!/usr/bin/env bash
# infra-outputs.sh 자체 검사. 가짜 aws 로 "인프라 있음·일부 없음·권한 없음"을 흉내 낸다.
#   bash scripts/test-infra-outputs.sh   (verify.sh workflows 에 포함)
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

REPO=123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/neuringo/backend
mkdir -p "$tmp/bin"
cat >"$tmp/bin/aws" <<'EOF'
#!/usr/bin/env bash
if [ "${FAKE_DENY:-0}" = 1 ]; then
  echo "An error occurred (AccessDeniedException) when calling the GetParameters operation: arn:aws:sts::123456789012:assumed-role/x" >&2
  exit 254
fi
cat "$FAKE_PARAMS"
EOF
chmod +x "$tmp/bin/aws"

all() {
  printf '/neuringo/dev/infra/ecr-repository-url\t%s\n' "$REPO"
  printf '/neuringo/dev/infra/web-bucket\tneuringo-dev-web-ab12\n'
  printf '/neuringo/dev/infra/cloudfront-distribution-id\tE2FAKE\n'
  printf '/neuringo/dev/infra/cloudfront-domain\td111.cloudfront.net\n'
  printf '/neuringo/dev/infra/backup-bucket\tneuringo-dev-backups-ab12\n'
}

run() {
  : >"$tmp/out"
  : >"$tmp/summary"
  code=0
  log=$(PATH="$tmp/bin:$PATH" FAKE_PARAMS="$tmp/params" GITHUB_ACTIONS=true GITHUB_OUTPUT="$tmp/out" \
    GITHUB_STEP_SUMMARY="$tmp/summary" bash scripts/infra-outputs.sh dev 2>&1) || code=$?
  out=$(cat "$tmp/out")
  summary=$(cat "$tmp/summary")
}

echo "infra-outputs.sh 자체 검사"

all >"$tmp/params"
run
check "인프라가 다 있으면 ready=true" has "$out" "ready=true"
check "ECR 주소를 넘긴다" has "$out" "ecr_repository_url=$REPO"
check "CloudFront 주소를 넘긴다" has "$out" "cloudfront_domain=d111.cloudfront.net"
check "계정 ID 가 든 ECR 주소를 가린다" has "$log" "::add-mask::$REPO"
check "버킷 이름을 가린다" has "$log" "::add-mask::neuringo-dev-backups-ab12"
check "사용자 테스트 주소도 가린다(외부인 가입·남용을 부르지 않게)" has "$log" "::add-mask::d111.cloudfront.net"

all | grep -v web-bucket >"$tmp/params"
run
check "하나라도 없으면 ready=false" has "$out" "ready=false"
check "없으면 0 으로 끝난다(건너뜀이지 실패가 아니다)" same "$code" 0
check "없는 값의 이름을 Summary 에 적는다" has "$summary" "/neuringo/dev/infra/web-bucket"
check "그때 다른 값은 넘기지 않는다" lacks "$out" "ecr_repository_url"

FAKE_DENY=1 run
check "권한이 없으면 1 로 끝난다(조용히 건너뛰지 않는다)" same "$code" 1
check "그때 ready=true 를 내지 않는다" lacks "$out" "ready=true"
check "권한 오류 코드를 적는다" has "$summary" "(AccessDeniedException)"
check "오류 본문(계정 ID)은 적지 않는다" lacks "$summary$log" "123456789012"

if [ "$failures" -gt 0 ]; then
  echo "infra-outputs.sh 자체 검사 실패: $failures 건" >&2
  exit 1
fi
echo "infra-outputs.sh 자체 검사 통과"
