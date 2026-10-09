#!/usr/bin/env bash
# web-deploy.sh 자체 검사. 가짜 aws 로 순서(assets → 그 밖 → index.html → 캐시 비우기)·캐시 설정·비노출을 본다.
#   bash scripts/test-web-deploy.sh   (verify.sh workflows 에 포함)
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

BUCKET=neuringo-dev-web-fake
DIST_ID=E2FAKEDISTRIBUTION

mkdir -p "$tmp/bin" "$tmp/dist/assets"
cat >"$tmp/bin/aws" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$FAKE_CALLS"
if [ -n "${FAKE_FAIL:-}" ] && [[ "$*" == *"$FAKE_FAIL"* ]]; then
  echo "An error occurred (AccessDenied) when calling the PutObject operation: Access Denied for bucket neuringo-dev-web-fake" >&2
  exit 1
fi
[ "$1" = cloudfront ] && echo I123INVALIDATION
exit 0
EOF
chmod +x "$tmp/bin/aws"
echo '<!doctype html>' >"$tmp/dist/index.html"
echo 'x' >"$tmp/dist/favicon.svg"
echo 'js' >"$tmp/dist/assets/index-abc123.js"

run() {
  rm -f "$tmp/calls"
  code=0
  out=$(PATH="$tmp/bin:$PATH" FAKE_CALLS="$tmp/calls" WEB_BUCKET=$BUCKET CF_DISTRIBUTION_ID=$DIST_ID \
    bash scripts/web-deploy.sh "$tmp/dist" 2>&1) || code=$?
  calls=$(cat "$tmp/calls" 2>/dev/null || true)
}

echo "web-deploy.sh 자체 검사"

run
check "성공하면 0" same "$code" 0
check "assets 는 1년 캐시(immutable)" has "$(sed -n 1p "$tmp/calls")" "s3 sync $tmp/dist/assets s3://$BUCKET/assets --cache-control public,max-age=31536000,immutable"
check "그 밖의 파일은 index.html·assets 를 빼고 5분 캐시, 없는 파일 지움" has "$(sed -n 2p "$tmp/calls")" "--exclude assets/* --exclude index.html --cache-control public,max-age=300 --delete"
check "index.html 은 마지막에 캐시 없이" has "$(sed -n 3p "$tmp/calls")" "s3 cp $tmp/dist/index.html s3://$BUCKET/index.html --cache-control no-cache"
check "그다음 CloudFront 캐시를 비운다" has "$(sed -n 4p "$tmp/calls")" "cloudfront create-invalidation --distribution-id $DIST_ID --paths /index.html /"
check "assets 는 지우지 않는다(--delete 없음)" lacks "$(sed -n 1p "$tmp/calls")" "--delete"
check "결과 줄" has "$out" "WEB_DEPLOY_RESULT=ok"
check "출력에 버킷 이름이 없다" lacks "$out" "$BUCKET"
check "출력에 배포 ID 가 없다" lacks "$out" "$DIST_ID"

FAKE_FAIL="index.html s3://" run
check "index.html 을 못 올리면 1" same "$code" 1
check "그때 캐시를 비우지 않는다" lacks "$calls" "create-invalidation"
check "오류 코드만 말하고 버킷 이름은 찍지 않는다" lacks "$out" "$BUCKET"

rm "$tmp/dist/index.html"
run
check "빌드 결과가 없으면 1" same "$code" 1
check "아무것도 올리지 않는다" same "$calls" ""

if [ "$failures" -gt 0 ]; then
  echo "web-deploy.sh 자체 검사 실패: $failures 건" >&2
  exit 1
fi
echo "web-deploy.sh 자체 검사 통과"
