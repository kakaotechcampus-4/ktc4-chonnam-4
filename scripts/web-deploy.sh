#!/usr/bin/env bash
# 프론트 빌드 결과(dist)를 S3 에 올리고 CloudFront 캐시를 비운다. deploy.yml 이 OIDC 로 자격증명을 받은 뒤 부른다.
#   WEB_BUCKET=<버킷> CF_DISTRIBUTION_ID=<배포 ID> bash scripts/web-deploy.sh [dist 폴더, 기본 frontend/dist]
#
# 순서가 중요하다: 새 assets(파일 이름에 해시) → 그 밖의 파일 → 마지막에 index.html.
# index.html 을 먼저 바꾸면 아직 없는 assets 를 가리키는 순간이 생긴다.
# - assets/* : 1년 캐시(immutable). 옛 assets 는 지우지 않는다(이미 화면을 연 사람이 옛 파일을 받을 수 있게)
# - index.html : 캐시하지 않는다(no-cache). 새 배포가 바로 보인다
# - 그 밖(favicon 등) : 5분 캐시, dist 에 없는 파일은 지운다
# 공개 로그라 버킷 이름·배포 ID 는 찍지 않는다.
set -euo pipefail

DIST=${1:-frontend/dist}
: "${WEB_BUCKET:?WEB_BUCKET 이 필요하다}"
: "${CF_DISTRIBUTION_ID:?CF_DISTRIBUTION_ID 가 필요하다}"
REGION=${AWS_REGION:-ap-northeast-2}

[ -f "$DIST/index.html" ] || {
  echo "$DIST/index.html 이 없다. 프론트를 먼저 빌드한다(npm run build)." >&2
  exit 1
}

err=$(mktemp)
trap 'rm -f "$err"' EXIT

step() { # 설명 aws인자...
  local what=$1
  shift
  if ! aws "$@" --region "$REGION" --only-show-errors >/dev/null 2>"$err"; then
    echo "$what 실패: $(grep -oE '\([A-Za-z]+\)' "$err" | head -n1 || echo '코드 없음')" >&2
    exit 1
  fi
  echo "$what"
}

if [ -d "$DIST/assets" ]; then
  step "assets 올림(1년 캐시)" s3 sync "$DIST/assets" "s3://$WEB_BUCKET/assets" \
    --cache-control "public,max-age=31536000,immutable"
fi
step "그 밖의 파일 올림(5분 캐시, 없는 파일 지움)" s3 sync "$DIST" "s3://$WEB_BUCKET" \
  --exclude "assets/*" --exclude "index.html" --cache-control "public,max-age=300" --delete
step "index.html 올림(캐시 안 함)" s3 cp "$DIST/index.html" "s3://$WEB_BUCKET/index.html" \
  --cache-control "no-cache" --content-type "text/html; charset=utf-8"

if ! aws cloudfront create-invalidation --distribution-id "$CF_DISTRIBUTION_ID" --paths "/index.html" "/" \
  --query Invalidation.Id --output text >/dev/null 2>"$err"; then
  echo "CloudFront 캐시 비우기 실패: $(grep -oE '\([A-Za-z]+\)' "$err" | head -n1 || echo '코드 없음')" >&2
  exit 1
fi
echo "CloudFront 캐시 비우기 요청함(/index.html, /)"
echo "WEB_DEPLOY_RESULT=ok"
