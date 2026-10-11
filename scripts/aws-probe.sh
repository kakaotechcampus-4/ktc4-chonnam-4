#!/usr/bin/env bash
# AWS 권한 확인 — CD 설계 0단계(docs/cd-architecture.md "0단계"·⚑ 표).
# 지금 자격증명으로 설계의 갈림길(⚑)마다 무엇이 되는지 표로 남긴다. Actions 에서는 OIDC 로 받은 ktc-github-deploy 로 돈다.
#   - 읽기 호출과 IAM 정책 시뮬레이션(iam simulate-principal-policy)만 한다. 아무것도 만들거나 바꾸지 않는다.
#   - 공개 레포라 실행 로그·요약도 공개된다. 계정 ID·ARN·인스턴스 ID·주소·오류 메시지 본문은 찍지 않고 오류 코드만 적는다.
#   - Cost Explorer 조회는 요청당 0.01 USD 가 든다(실행 한 번에 1건).
#
#   bash scripts/aws-probe.sh   # 로컬은 aws sso login 뒤. 표는 stdout 과 $GITHUB_STEP_SUMMARY(있으면)에 쓴다.
#
# 환경변수: AWS_REGION(기본 ap-northeast-2), PROBE_DEPLOY_ROLE(기본 ktc-github-deploy)
set -euo pipefail

REGION=${AWS_REGION:-ap-northeast-2}
DEPLOY_ROLE=${PROBE_DEPLOY_ROLE:-ktc-github-deploy}

report=$(mktemp)
err=$(mktemp)
trap 'rm -f "$report" "$err"' EXIT

say() { printf '%s\n' "$*" >>"$report"; }

# aws 오류 출력에서 코드만 꺼낸다. 본문에는 계정 ID·ARN 이 들어 있어서 찍지 않는다.
error_code() {
  local code
  code=$(grep -oE 'An error occurred \([A-Za-z0-9.]+\)' "$err" | head -n1 | sed -E 's/.*\(([^)]*)\).*/\1/' || true)
  if [ -n "$code" ]; then
    echo "$code"
  elif grep -qi 'could not connect' "$err"; then
    echo "연결 실패"
  else
    echo "코드 없음"
  fi
}

is_denied() { # 오류코드
  case $1 in
    AccessDenied | AccessDeniedException | UnauthorizedOperation | UnauthorizedException | AuthorizationError | Forbidden | ForbiddenException) return 0 ;;
    *) return 1 ;;
  esac
}

# 값을 받아 오는 호출. 값은 판단에만 쓰고 표에는 옮기지 않는다.
value() { aws "$@" --region "$REGION" --output text 2>"$err"; }

# 읽기 호출 한 줄. 결과는 버리고 되는지만 본다.
check() { # 이름 aws인자...
  local name=$1 code
  shift
  if aws "$@" --region "$REGION" >/dev/null 2>"$err"; then
    say "| $name | ✅ 가능 |"
  else
    code=$(error_code)
    if is_denied "$code"; then
      say "| $name | ⛔ 거부 (\`$code\`) |"
    else
      say "| $name | ⚠️ 확인 필요 (\`$code\`) |"
    fi
  fi
}

# IAM 정책 시뮬레이션. 역할에 붙은 정책·권한 경계와 조직 정책(SCP)을 함께 본다. 리소스 정책은 보지 않는다.
simulate() { # 제목 역할ARN 리소스ARN(없으면 -) 작업...
  local title=$1 arn=$2 resource=$3 result action decision org mark
  shift 3
  local args=(iam simulate-principal-policy --policy-source-arn "$arn" --action-names "$@"
    --query 'EvaluationResults[].[EvalActionName,EvalDecision,OrganizationsDecisionDetail.AllowedByOrganizations]')
  [ "$resource" = - ] || args+=(--resource-arns "$resource")
  say ""
  say "#### $title"
  say ""
  if ! result=$(value "${args[@]}"); then
    say "시뮬레이션을 하지 못했다(\`$(error_code)\`). 이 묶음은 실제로 써 보며 확인한다."
    return 0
  fi
  say "| 작업 | 결과 |"
  say "|---|---|"
  while IFS=$'\t' read -r action decision org; do
    [ -n "$action" ] || continue
    case $decision in
      allowed) mark="✅ 허용" ;;
      explicitDeny) mark="⛔ 명시적 거부" ;;
      *) mark="⛔ 허용 없음" ;;
    esac
    [ "$org" = False ] && mark="$mark · 조직 정책(SCP)에서 막힘"
    say "| \`$action\` | $mark |"
  done <<<"$result"
}

role_arn() { # 역할이름 — 읽을 수 없으면 경로 없는 ARN 으로 대신한다
  local arn
  if arn=$(value iam get-role --role-name "$1" --query Role.Arn); then
    echo "$arn"
  else
    echo "arn:aws:iam::$account:role/$1"
  fi
}

yes_no() { if [ -n "$1" ] && [ "$1" != none ] && [ "$1" != None ]; then echo 있음; else echo 없음; fi; }
required() { if [ "$1" = required ]; then echo 예; else echo 아니오; fi; }

if ! account=$(value sts get-caller-identity --query Account); then
  echo "AWS 자격증명이 없다(\`$(error_code)\`). Actions 에서는 OIDC 단계를, 로컬에서는 aws sso login 을 먼저 확인한다." >&2
  exit 1
fi
[ -n "${GITHUB_ACTIONS:-}" ] && echo "::add-mask::$account"
session_arn=$(value sts get-caller-identity --query Arn)
session_role=$(sed -E 's#^arn:aws:sts::[0-9]+:assumed-role/([^/]+)/.*#\1#' <<<"$session_arn")
case $session_role in arn:*) session_role="(역할이 아닌 자격증명)" ;; esac

month_start=$(date -u +%Y-%m-01)
tomorrow=$(date -u -d tomorrow +%Y-%m-%d 2>/dev/null || date -u -v+1d +%Y-%m-%d)

say "## AWS 권한 확인 ($REGION)"
say ""
say "읽기 호출과 IAM 정책 시뮬레이션만 했다. 계정 ID·ARN·ID·주소는 적지 않는다."
say ""
say "### 1. 자격증명"
say ""
say "| 확인 | 결과 |"
say "|---|---|"
say "| 지금 자격증명의 역할 | \`$session_role\` |"
say ""
say "### 2. 읽기 호출"
say ""
say "| 확인 | 결과 |"
say "|---|---|"
check "EC2 서버 목록" ec2 describe-instances --max-results 5
check "SSM 관리 대상(서버 연결)" ssm describe-instance-information --max-results 5
check "보안 그룹 읽기" ec2 describe-security-groups --max-results 5
check "CloudFront 원본용 접두사 목록" ec2 describe-managed-prefix-lists --filters Name=prefix-list-name,Values=com.amazonaws.global.cloudfront.origin-facing
check "ECR 저장소 목록" ecr describe-repositories --max-results 1
check "S3 버킷 목록" s3api list-buckets --max-items 1
check "CloudFront 배포 목록" cloudfront list-distributions --max-items 1
check "API Gateway(HTTP API) 목록" apigatewayv2 get-apis --max-results 1
check "CloudWatch 로그 그룹" logs describe-log-groups --limit 1
check "CloudWatch 경보" cloudwatch describe-alarms --max-records 1
check "Parameter Store" ssm describe-parameters --max-results 1
check "SNS 주제" sns list-topics
check "Budgets(예산)" budgets describe-budgets --account-id "$account" --max-results 1
check "Cost Explorer(이번 달 비용)" ce get-cost-and-usage --time-period "Start=$month_start,End=$tomorrow" --granularity MONTHLY --metrics UnblendedCost
check "Cost Anomaly Detection" ce get-anomaly-monitors --max-results 1
check "Bedrock 모델 목록" bedrock list-foundation-models
check "Bedrock 추론 프로필" bedrock list-inference-profiles --max-results 1
check "Polly 한국어 음성" polly describe-voices --language-code ko-KR
check "Transcribe" transcribe list-transcription-jobs --max-results 1
check "RDS" rds describe-db-instances --max-records 20
check "Lambda" lambda list-functions --max-items 1
check "IAM 배포 역할 읽기" iam get-role --role-name "$DEPLOY_ROLE"

say ""
say "### 3. 서버 (값 대신 있음·없음)"
say ""
instance_role=""
if count=$(value ec2 describe-instances --query 'length(Reservations[].Instances[])') &&
  facts=$(value ec2 describe-instances --query "Reservations[0].Instances[0].[InstanceType, State.Name, MetadataOptions.HttpTokens || 'none', PublicDnsName || 'none', IamInstanceProfile.Arn || 'none']"); then
  IFS=$'\t' read -r itype state tokens dns profile <<<"$facts"
  # 아래 `Online` 은 셸 명령 치환이 아니라 JMESPath 의 리터럴이라 작은따옴표가 맞다.
  # shellcheck disable=SC2016
  online=$(value ssm describe-instance-information --query 'length(InstanceInformationList[?PingStatus==`Online`])' || echo "읽지 못함")
  say "| 확인 | 결과 |"
  say "|---|---|"
  say "| 서버 수 | $count |"
  say "| 사양·상태 | \`$itype\` · $state |"
  say "| IMDSv2 필수 | $(required "$tokens") |"
  say "| 공인 DNS | $(yes_no "$dns") |"
  say "| 인스턴스 역할 | $(yes_no "$profile") |"
  say "| SSM 온라인 | $online |"
  if [ "$(yes_no "$profile")" = 있음 ]; then
    instance_role=$(value iam get-instance-profile --instance-profile-name "${profile##*/}" --query 'InstanceProfile.Roles[0].Arn' || true)
  fi
else
  say "서버 정보를 읽지 못했다(\`$(error_code)\`)."
fi

# 배포 역할을 어디서 받을 수 있는지(신뢰 정책의 sub). 넓으면 레포 쓰기 권한이 곧 배포 권한이다(docs/cd-architecture.md 8절).
# sub 에는 레포 이름만 들어 있다(공개). 혹시 숫자 12자리가 섞이면 가린다.
say ""
say "### 3-1. 배포 역할을 받을 수 있는 곳(신뢰 정책 \`sub\`)"
say ""
# 아래 큰따옴표는 JMESPath 의 키 따옴표라 작은따옴표 안에 둔다.
# shellcheck disable=SC2016
if subs=$(value iam get-role --role-name "$DEPLOY_ROLE" \
  --query 'Role.AssumeRolePolicyDocument.Statement[].Condition.[StringLike."token.actions.githubusercontent.com:sub", StringEquals."token.actions.githubusercontent.com:sub"][][]'); then
  wide=0
  say "| sub | 뜻 |"
  say "|---|---|"
  while IFS= read -r sub; do
    case $sub in "" | None) continue ;; esac
    case $sub in
      *:environment:*) meaning="그 Environment 의 잡만(Environment 보호 규칙이 경계가 된다)" ;;
      *:pull_request)
        meaning="⚠ PR(리뷰 전 코드)도 받는다"
        wide=1
        ;;
      *'*'*)
        meaning="⚠ 이 레포의 어느 브랜치·PR·환경이든 받는다(쓰기 권한 = 배포 권한)"
        wide=1
        ;;
      *:ref:*) meaning="그 브랜치·태그의 실행만" ;;
      *) meaning="확인 필요" ;;
    esac
    say "| \`$(sed -E 's/[0-9]{12}/<계정>/g' <<<"$sub")\` | $meaning |"
  done < <(tr '\t' '\n' <<<"$subs")
  if [ "$wide" = 1 ]; then
    say ""
    say "넓게 열려 있다. develop 보호 규칙을 걸고, 팀 역할(sub = environment:…)로 나누는 것을 서두른다(docs/cd-architecture.md 4·8절)."
  fi
else
  say "신뢰 정책을 읽지 못했다(\`$(error_code)\`). 운영진에 sub 조건을 묻는다."
fi

say ""
say "### 4. 배포 역할(\`$DEPLOY_ROLE\`)로 할 수 있는 것"
deploy_arn=$(role_arn "$DEPLOY_ROLE")
simulate "Terraform state·S3" "$deploy_arn" - \
  s3:CreateBucket s3:PutBucketVersioning s3:PutEncryptionConfiguration s3:PutBucketPolicy \
  s3:PutBucketPublicAccessBlock s3:PutLifecycleConfiguration s3:PutObject
simulate "ECR" "$deploy_arn" - \
  ecr:CreateRepository ecr:PutLifecyclePolicy ecr:GetAuthorizationToken ecr:InitiateLayerUpload ecr:PutImage
simulate "HTTPS 앞단(CloudFront·API Gateway)" "$deploy_arn" - \
  cloudfront:CreateDistribution cloudfront:CreateOriginAccessControl cloudfront:CreateFunction \
  cloudfront:CreateInvalidation apigateway:POST
simulate "보안 그룹 규칙" "$deploy_arn" - \
  ec2:AuthorizeSecurityGroupIngress ec2:RevokeSecurityGroupIngress
simulate "서버 반영(SSM)·설정값" "$deploy_arn" - \
  ssm:SendCommand ssm:GetCommandInvocation ssm:PutParameter ssm:GetParameter ssm:CreateAssociation
simulate "IAM(팀 역할·정책)" "$deploy_arn" - \
  iam:CreateRole iam:CreatePolicy iam:AttachRolePolicy iam:PutRolePolicy iam:PassRole iam:TagRole
simulate "관측(로그·경보·대시보드·알림)" "$deploy_arn" - \
  logs:CreateLogGroup logs:PutRetentionPolicy cloudwatch:PutMetricAlarm cloudwatch:PutDashboard \
  sns:CreateTopic sns:Subscribe
simulate "비용" "$deploy_arn" - \
  budgets:ModifyBudget budgets:ViewBudget ce:GetCostAndUsage ce:GetCostForecast ce:CreateAnomalyMonitor
simulate "모델" "$deploy_arn" - \
  bedrock:InvokeModel lambda:CreateFunction
simulate "서버 운영" "$deploy_arn" - \
  ec2:StopInstances ec2:StartInstances ec2:ModifyInstanceCreditSpecification ec2:ReplaceIamInstanceProfileAssociation
simulate "막혀 있어야 하는 것(가이드 5절)" "$deploy_arn" - \
  rds:CreateDBInstance ec2:AllocateAddress ec2:RunInstances elasticloadbalancing:CreateLoadBalancer ec2:CreateNatGateway

say ""
say "### 5. 서버 역할(인스턴스 역할)로 할 수 있는 것"
if [ -n "$instance_role" ]; then
  simulate "앱·배포 스크립트가 서버에서 부르는 것" "$instance_role" - \
    ecr:GetAuthorizationToken ecr:BatchGetImage ecr:GetDownloadUrlForLayer \
    s3:GetObject s3:PutObject s3:ListBucket \
    ssm:GetParameter ssm:GetParameters ssm:GetParametersByPath kms:Decrypt \
    logs:CreateLogStream logs:PutLogEvents cloudwatch:PutMetricData \
    bedrock:InvokeModel bedrock:InvokeModelWithResponseStream \
    transcribe:StartStreamTranscription polly:SynthesizeSpeech
  simulate "배포 역할이 서버 역할에 정책을 붙일 수 있나" "$deploy_arn" "$instance_role" \
    iam:AttachRolePolicy iam:PutRolePolicy
else
  say ""
  say "서버 역할을 찾지 못해 건너뛰었다."
fi

say ""
say "### 읽는 법"
say ""
say "- ✅ 허용 · ⛔ 거부/허용 없음 · ⚠️ 확인 필요(오류 코드만 적었다). \"조직 정책(SCP)에서 막힘\"은 운영진 정책이라 요청해야 바뀐다."
say "- 시뮬레이션은 정책만 본다. 실제 호출 결과(2절)가 우선이다."
say "- 결과는 docs/cd-architecture.md 의 ⚑ 표에 옮겨 적고 갈림길을 정한다."

cat "$report"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  cat "$report" >>"$GITHUB_STEP_SUMMARY"
fi
