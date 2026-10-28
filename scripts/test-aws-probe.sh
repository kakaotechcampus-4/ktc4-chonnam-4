#!/usr/bin/env bash
# aws-probe.sh 자체 검사. 가짜 aws 로 허용·거부·연결 실패·SCP 차단을 흉내 내고,
# 표가 맞게 나뉘는지와 공개 로그에 계정 ID·ARN·주소·오류 본문이 새지 않는지 본다.
#   bash scripts/test-aws-probe.sh   (verify.sh workflows 에 포함, AWS 자격증명 필요 없음)
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

mkdir -p "$tmp/bin"
# 가짜 aws. 실제 CLI 와 같이 거부는 254, 연결 실패는 255 로 끝나고 오류 본문에 계정 ID·ARN 을 담는다.
cat >"$tmp/bin/aws" <<'EOF'
#!/usr/bin/env bash
args="$*"
deny() {
  echo "An error occurred ($1) when calling the $2 operation: User: arn:aws:sts::123456789012:assumed-role/ktc-github-deploy/GitHubActions is not authorized to perform: $3" >&2
  exit 254
}
if [ "${FAKE_NO_CREDS:-0}" = 1 ]; then
  echo "Unable to locate credentials. You can configure credentials by running \"aws configure\"." >&2
  exit 253
fi
case "$args" in
  "sts get-caller-identity --query Account"*) echo 123456789012 ;;
  "sts get-caller-identity --query Arn"*) echo arn:aws:sts::123456789012:assumed-role/ktc-github-deploy/GitHubActions ;;
  "ec2 describe-instances --query length"*) echo 1 ;;
  *HttpPutResponseHopLimit*) echo "${FAKE_HOP:-1}" ;;
  *"SecurityGroups[].GroupId"*) printf 'sg-0aaa1111\tsg-0bbb2222\n' ;;
  "ec2 describe-security-group-rules"*FromPort*) echo "${FAKE_SSH:-0}" ;;
  "ec2 describe-security-group-rules"*CidrIpv4*) echo "${FAKE_OPEN:-0}" ;;
  "iam list-attached-role-policies"*) printf '%s\n' "${FAKE_POLICIES:-PowerUserAccess AmazonSSMManagedInstanceCore}" ;;
  "s3api list-buckets --query"*) printf 'neuringo-dev-backups-123456789012\tneuringo-tfstate-123456789012\n' ;;
  "s3api get-public-access-block"*) printf 'True\tTrue\tTrue\tTrue\n' ;;
  "s3api get-bucket-policy-status --bucket neuringo-dev-backups"*) echo False ;;
  "s3api get-bucket-policy-status"*)
    echo "An error occurred (NoSuchBucketPolicy) when calling the GetBucketPolicyStatus operation: The bucket policy does not exist" >&2
    exit 254 ;;
  "sns list-topics --query"*) echo arn:aws:sns:ap-northeast-2:123456789012:neuringo-dev-alerts ;;
  "sns list-subscriptions-by-topic"*) printf '%s\n' "${FAKE_SNS_SUBS:-PendingConfirmation}" ;;
  "ec2 describe-instances --query Reservations"*)
    printf 't3.medium\trunning\trequired\tec2-203-0-113-20.ap-northeast-2.compute.amazonaws.com\tarn:aws:iam::123456789012:instance-profile/ktc-ec2-ssm-profile\n' ;;
  "ssm describe-instance-information --query"*) echo 1 ;;
  "iam get-instance-profile"*) echo arn:aws:iam::123456789012:role/ktc-ec2-ssm-role ;;
  *AssumeRolePolicyDocument*) printf '%s\n' "${FAKE_SUBS:-repo:kakaotechcampus-4/ktc4-chonnam-4:*}" ;;
  "iam get-role --role-name ktc-github-deploy --query"*) echo arn:aws:iam::123456789012:role/ktc-github-deploy ;;
  "ecr describe-repositories"*) deny AccessDeniedException DescribeRepositories "ecr:DescribeRepositories on resource: arn:aws:ecr:ap-northeast-2:123456789012:repository/*" ;;
  "budgets describe-budgets"*) deny AccessDeniedException DescribeBudgets budgets:ViewBudget ;;
  "ec2 describe-security-groups"*) deny UnauthorizedOperation DescribeSecurityGroups ec2:DescribeSecurityGroups ;;
  "cloudfront list-distributions"*)
    echo 'Could not connect to the endpoint URL: "https://cloudfront.amazonaws.com/2020-05-31/distribution?MaxItems=1"' >&2
    exit 255 ;;
  "iam simulate-principal-policy"*)
    collect=0
    for arg in "$@"; do
      case $arg in
        --action-names) collect=1; continue ;;
        --*) collect=0 ;;
      esac
      [ "$collect" = 1 ] || continue
      case $arg in
        rds:* | ec2:AllocateAddress | ec2:CreateNatGateway) printf '%s\timplicitDeny\tFalse\n' "$arg" ;;
        iam:PutRolePolicy) printf '%s\texplicitDeny\tTrue\n' "$arg" ;;
        bedrock:InvokeModel) printf '%s\timplicitDeny\tTrue\n' "$arg" ;;
        *) printf '%s\tallowed\tTrue\n' "$arg" ;;
      esac
    done ;;
  *) : ;;
esac
EOF
chmod +x "$tmp/bin/aws"

echo "aws-probe.sh 자체 검사"

summary="$tmp/summary.md"
code=0
out=$(env -u GITHUB_ACTIONS PATH="$tmp/bin:$PATH" GITHUB_STEP_SUMMARY="$summary" bash scripts/aws-probe.sh 2>&1) || code=$?

check "거부가 있어도 끝까지 돌고 0 으로 끝난다" test "$code" -eq 0
check "허용된 읽기 호출은 가능" has "$out" "| S3 버킷 목록 | ✅ 가능 |"
check "AccessDeniedException 은 거부" has "$out" "| ECR 저장소 목록 | ⛔ 거부 (\`AccessDeniedException\`) |"
check "EC2 의 UnauthorizedOperation 도 거부" has "$out" "| 보안 그룹 읽기 | ⛔ 거부 (\`UnauthorizedOperation\`) |"
check "연결 실패는 확인 필요" has "$out" "| CloudFront 배포 목록 | ⚠️ 확인 필요 (\`연결 실패\`) |"
check "시뮬레이션 허용" has "$out" "| \`ecr:CreateRepository\` | ✅ 허용 |"
check "조직 정책(SCP) 차단을 따로 적는다" has "$out" "| \`rds:CreateDBInstance\` | ⛔ 허용 없음 · 조직 정책(SCP)에서 막힘 |"
check "명시적 거부" has "$out" "| \`iam:PutRolePolicy\` | ⛔ 명시적 거부 |"
check "서버 역할 시뮬레이션도 한다" has "$out" "| \`ecr:BatchGetImage\` | ✅ 허용 |"
check "서버 사양은 적는다" has "$out" "| 사양·상태 | \`t3.medium\` · running |"
check "주소 대신 있음·없음" has "$out" "| 공인 DNS | 있음 |"
check "IMDSv2 필수 여부" has "$out" "| IMDSv2 필수 | 예 |"
check "역할 이름은 적는다" has "$out" "| 지금 자격증명의 역할 | \`ktc-github-deploy\` |"
check "계정 ID 를 찍지 않는다" lacks "$out" "123456789012"
check "ARN 을 찍지 않는다" lacks "$out" "arn:aws"
check "공인 DNS 를 찍지 않는다" lacks "$out" "ec2-203-0-113-20"
check "오류 메시지 본문을 찍지 않는다" lacks "$out" "is not authorized"
check "실행 요약에도 같은 표를 쓴다" has "$(cat "$summary")" "| ECR 저장소 목록 | ⛔ 거부 (\`AccessDeniedException\`) |"
check "실행 요약에도 계정 ID 가 없다" lacks "$(cat "$summary")" "123456789012"
check "배포 역할을 받을 수 있는 곳(신뢰 정책 sub)을 적는다" has "$out" "| \`repo:kakaotechcampus-4/ktc4-chonnam-4:*\` |"
check "와일드카드면 쓰기 권한 = 배포 권한이라고 경고한다" has "$out" "쓰기 권한 = 배포 권한"
check "그때 할 일을 알려 준다" has "$out" "develop 보호 규칙"

check "운영진 안내: IMDS 홉 제한 1" has "$out" "| IMDS 홉 제한 | 1 — 컨테이너는 서버 역할을 받지 못한다 |"
check "운영진 안내: 넓은 서버 역할은 이름과 함께 알린다" has "$out" "| 서버 역할의 관리형 정책 | ⚠️ \`PowerUserAccess AmazonSSMManagedInstanceCore\`"
check "운영진 안내: 22번 인바운드 없음" has "$out" "| 22번(SSH) 인바운드 | 없음 |"
check "운영진 안내: 인터넷 전체에 열린 인바운드 없음" has "$out" "| 인터넷 전체에 열린 인바운드 | 없음 |"
check "운영진 안내: 백업 버킷 공개 차단·IsPublic" has "$out" "| 백업 버킷 | 공개 차단 4/4 · IsPublic false |"
check "운영진 안내: 정책 없는 버킷은 공개 아님" has "$out" "| Terraform state 버킷 | 공개 차단 4/4 · 정책 없음(공개 아님) |"
check "경보 메일 구독이 확인되지 않았으면 경고한다" has "$out" "| 경보 메일 받는 사람 | ⚠️ 확인됨 0 · 확인 대기 1"
check "보안 그룹 ID 를 찍지 않는다" lacks "$out" "sg-0aaa1111"

out=$(env -u GITHUB_ACTIONS PATH="$tmp/bin:$PATH" FAKE_HOP=2 FAKE_SSH=1 FAKE_OPEN=2 FAKE_POLICIES=$'AmazonSSMManagedInstanceCore\tneuringo-app' \
  FAKE_SNS_SUBS=$'arn:aws:sns:ap-northeast-2:123456789012:neuringo-dev-alerts:abc\tPendingConfirmation' bash scripts/aws-probe.sh 2>&1) || true
check "홉 제한이 2 이상이면 경고한다" has "$out" "| IMDS 홉 제한 | ⚠️ 2"
check "22번 인바운드가 있으면 경고한다" has "$out" "| 22번(SSH) 인바운드 | ⚠️ 1개"
check "인터넷 전체에 열린 인바운드가 있으면 경고한다" has "$out" "| 인터넷 전체에 열린 인바운드 | ⚠️ 2개"
check "좁은 서버 역할은 경고하지 않는다" has "$out" "| 서버 역할의 관리형 정책 | \`AmazonSSMManagedInstanceCore neuringo-app\` |"
check "확인된 구독 수를 센다" has "$out" "| 경보 메일 받는 사람 | 확인됨 1 · 확인 대기 1 |"
check "그때도 계정 ID 를 찍지 않는다" lacks "$out" "123456789012"

out=$(env -u GITHUB_ACTIONS PATH="$tmp/bin:$PATH" FAKE_SUBS=$'repo:kakaotechcampus-4/ktc4-chonnam-4:environment:infra\trepo:kakaotechcampus-4/ktc4-chonnam-4:environment:dev' \
  bash scripts/aws-probe.sh 2>&1) || true
check "Environment 로 좁혀져 있으면 그렇게 적는다" has "$out" "그 Environment 의 잡만"
check "그때는 넓다는 경고를 하지 않는다" lacks "$out" "넓게 열려 있다"

code=0
out=$(env -u GITHUB_ACTIONS FAKE_NO_CREDS=1 PATH="$tmp/bin:$PATH" bash scripts/aws-probe.sh 2>&1) || code=$?
check "자격증명이 없으면 실패하고 이유를 말한다" test "$code" -ne 0
check "자격증명 안내" has "$out" "AWS 자격증명이 없다"

if [ "$failures" -gt 0 ]; then
  echo "aws-probe.sh 자체 검사 실패: $failures 건" >&2
  exit 1
fi
echo "aws-probe.sh 자체 검사 통과"
