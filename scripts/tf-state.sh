#!/usr/bin/env bash
# Terraform state 버킷(neuringo-tfstate-<계정 ID>)을 확인하거나 만든다. infra.yml 이 OIDC 로 자격증명을 받은 뒤 부른다.
#   AWS_ACCOUNT_ID=<12자리> bash scripts/tf-state.sh check    # 있는지만 본다. 아무것도 만들지 않는다(plan 잡)
#   AWS_ACCOUNT_ID=<12자리> bash scripts/tf-state.sh ensure   # 없으면 만들고 설정을 다시 건다(승인 뒤 apply 잡만)
#
# 출력($GITHUB_OUTPUT, 없으면 stdout): ready(true/false), bucket, 없을 때 missing=true. 버킷 이름에 계정 ID 가 들어가 로그에서 가린다.
# check 에서 버킷이 없으면 missing=true 만 내고 Summary 는 쓰지 않는다(첫 실행인지에 따라 부르는 쪽이 정한다).
# 권한이 없으면 1 로 끝난다(조용히 건너뛰면 drift·apply 가 실제로는 안 돌았는데 초록불이 된다).
# 설정(ensure 때마다 다시 건다, 여러 번 돌려도 결과가 같다): 공개 차단·소유자 강제·암호화(SSE-S3)·버전 관리(state 를
#   되돌릴 수 있게)·HTTPS 만·옛 버전 30일 뒤 삭제·태그. 이 버킷은 Terraform 이 자기 state 를 둘 곳이라 Terraform 밖에서 만든다.
set -euo pipefail

MODE=${1:?check 또는 ensure}
REGION=${AWS_REGION:-ap-northeast-2}
out=${GITHUB_OUTPUT:-/dev/stdout}
summary=${GITHUB_STEP_SUMMARY:-/dev/null}

case $MODE in check | ensure) ;; *)
  echo "사용법: bash scripts/tf-state.sh check|ensure" >&2
  exit 2
  ;;
esac
[[ "${AWS_ACCOUNT_ID:-}" =~ ^[0-9]{12}$ ]] || {
  echo "AWS_ACCOUNT_ID(12자리)가 필요하다" >&2
  exit 2
}

BUCKET="neuringo-tfstate-$AWS_ACCOUNT_ID"
[ -n "${GITHUB_ACTIONS:-}" ] && echo "::add-mask::$BUCKET"

err=$(mktemp)
trap 'rm -f "$err"' EXIT

# 오류 본문에는 ARN·계정 ID 가 들어 있어 코드만 남긴다. 예: (AccessDenied), (404)
code_of() { grep -oE '\([A-Za-z0-9]+\)' "$err" | head -n1 || echo '(코드 없음)'; }

fail() { # 이유
  printf '### Terraform state 버킷 문제\n\n%s\n' "$1" >>"$summary"
  echo "::error title=state 버킷::$1"
  echo "$1" >&2
  exit 1
}

step() { # 설명 s3api인자...
  local what=$1
  shift
  if ! aws s3api "$@" --bucket "$BUCKET" --region "$REGION" >/dev/null 2>"$err"; then
    echo "state 버킷 $what 실패: $(code_of)" >&2
    exit 1
  fi
}

exists=true
if ! aws s3api head-bucket --bucket "$BUCKET" --expected-bucket-owner "$AWS_ACCOUNT_ID" --region "$REGION" >/dev/null 2>"$err"; then
  case $(code_of) in
    "(404)" | "(NoSuchBucket)") exists=false ;;
    "(403)" | "(AccessDenied)") fail "state 버킷을 볼 권한이 없다(⚑1). 배포 역할에 state 버킷 읽기 권한이 있는지 aws-probe 로 확인한다." ;;
    *)
      echo "state 버킷 확인 실패: $(code_of)" >&2
      exit 1
      ;;
  esac
fi

if [ "$exists" = false ]; then
  if [ "$MODE" = check ]; then
    {
      echo "ready=false"
      echo "missing=true"
      echo "bucket=$BUCKET"
    } >>"$out"
    exit 0
  fi
  if ! aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" \
    --create-bucket-configuration "LocationConstraint=$REGION" --object-ownership BucketOwnerEnforced >/dev/null 2>"$err"; then
    case $(code_of) in
      "(AccessDenied)") fail "state 버킷을 만들 권한이 없다(⚑1). 운영진에 요청하거나 팀 역할을 쓴다(docs/cd-architecture.md 4절)." ;;
      *)
        echo "state 버킷 만들기 실패: $(code_of)" >&2
        exit 1
        ;;
    esac
  fi
  echo "state 버킷을 만들었다"
fi

if [ "$MODE" = ensure ]; then
  arn="arn:aws:s3:::$BUCKET"
  step "공개 차단" put-public-access-block --public-access-block-configuration \
    BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
  step "소유자 강제" put-bucket-ownership-controls --ownership-controls 'Rules=[{ObjectOwnership=BucketOwnerEnforced}]'
  step "암호화" put-bucket-encryption --server-side-encryption-configuration \
    '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
  step "버전 관리" put-bucket-versioning --versioning-configuration Status=Enabled
  step "HTTPS 만" put-bucket-policy --policy "$(printf '{"Version":"2012-10-17","Statement":[{"Sid":"DenyInsecureTransport","Effect":"Deny","Principal":"*","Action":"s3:*","Resource":["%s","%s/*"],"Condition":{"Bool":{"aws:SecureTransport":"false"}}}]}' "$arn" "$arn")"
  step "수명 주기" put-bucket-lifecycle-configuration --lifecycle-configuration \
    '{"Rules":[{"ID":"old-state-versions","Status":"Enabled","Filter":{"Prefix":""},"NoncurrentVersionExpiration":{"NoncurrentDays":30},"Expiration":{"ExpiredObjectDeleteMarker":true},"AbortIncompleteMultipartUpload":{"DaysAfterInitiation":7}}]}'
  step "태그" put-bucket-tagging --tagging \
    'TagSet=[{Key=Project,Value=neuringo},{Key=ManagedBy,Value=infra.yml},{Key=Owner,Value=VS-017}]'
  echo "state 버킷 설정을 확인했다(공개 차단·암호화·버전 관리·HTTPS 만·수명 주기)"
fi

{
  echo "ready=true"
  echo "bucket=$BUCKET"
} >>"$out"
