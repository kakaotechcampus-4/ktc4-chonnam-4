#!/usr/bin/env bash
# Terraform 이 Parameter Store(/neuringo/<환경>/infra/…)에 적어 둔 인프라 값을 읽어 워크플로 출력으로 넘긴다.
# deploy.yml·ops-backup.yml 이 OIDC 로 자격증명을 받은 뒤 부른다.
#   bash scripts/infra-outputs.sh dev
#
# 출력($GITHUB_OUTPUT, 없으면 stdout): ready, ecr_repository_url, web_bucket, cloudfront_distribution_id,
#   cloudfront_domain, backup_bucket. 하나라도 없으면 ready=false 와 이유를 Summary 에 남긴다(아직 1단계 Terraform 전).
# 읽을 권한이 없으면 1 로 끝난다(조용히 건너뛰면 배포·백업이 실제로는 안 돌았는데 초록불이 된다).
# 계정 ID 가 든 ECR 주소·버킷 이름·배포 ID, 사용자 테스트 주소(CloudFront)는 로그에서 가린다(::add-mask::).
# 테스트 주소를 공개 로그에 남기면 외부인 가입·남용을 부른다. 팀에는 따로 알린다.
set -euo pipefail

ENV_NAME=${1:-dev}
REGION=${AWS_REGION:-ap-northeast-2}
prefix="/neuringo/$ENV_NAME/infra"
out=${GITHUB_OUTPUT:-/dev/stdout}
summary=${GITHUB_STEP_SUMMARY:-/dev/null}

keys=(ecr-repository-url web-bucket cloudfront-distribution-id cloudfront-domain backup-bucket)
names=()
for key in "${keys[@]}"; do names+=("$prefix/$key"); done

err=$(mktemp)
trap 'rm -f "$err"' EXIT
if ! result=$(aws ssm get-parameters --names "${names[@]}" --region "$REGION" \
  --query 'Parameters[].[Name,Value]' --output text 2>"$err"); then
  code=$(grep -oE '\([A-Za-z]+\)' "$err" | head -n1 || echo '코드 없음')
  printf "### 인프라 값을 읽지 못했다\n\nParameter Store 를 읽지 못했다 %s. 배포 역할의 \`ssm:GetParameters\` 권한을 확인한다.\n" "$code" >>"$summary"
  echo "::error title=인프라 값::Parameter Store 를 읽지 못했다 $code"
  exit 1
fi

declare -A value=()
while IFS=$'\t' read -r name val; do
  [ -n "$name" ] || continue
  value["${name##*/}"]=$val
done <<<"$result"

missing=()
for key in "${keys[@]}"; do
  [ -n "${value[$key]:-}" ] || missing+=("$prefix/$key")
done
if [ "${#missing[@]}" -gt 0 ]; then
  echo "ready=false" >>"$out"
  {
    echo "### 배포를 건너뛰었다"
    echo
    echo "인프라가 아직 없다(1단계 Terraform 전). Parameter Store 에 없는 값:"
    printf -- "- \`%s\`\n" "${missing[@]}"
  } >>"$summary"
  echo "::notice title=배포 건너뜀::인프라 값 ${#missing[@]}개가 없다(Terraform 전)"
  exit 0
fi

if [ -n "${GITHUB_ACTIONS:-}" ]; then
  for key in ecr-repository-url web-bucket cloudfront-distribution-id cloudfront-domain backup-bucket; do
    echo "::add-mask::${value[$key]}"
  done
fi
{
  echo "ready=true"
  echo "ecr_repository_url=${value[ecr-repository-url]}"
  echo "web_bucket=${value[web-bucket]}"
  echo "cloudfront_distribution_id=${value[cloudfront-distribution-id]}"
  echo "cloudfront_domain=${value[cloudfront-domain]}"
  echo "backup_bucket=${value[backup-bucket]}"
} >>"$out"
