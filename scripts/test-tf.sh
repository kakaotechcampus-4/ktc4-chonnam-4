#!/usr/bin/env bash
# tf-run.sh·tf-state.sh 자체 검사. 가짜 docker·aws 로 흉내 낸다(AWS·Terraform 을 부르지 않는다).
#   bash scripts/test-tf.sh   (verify.sh workflows 에 포함)
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

ACCOUNT=123456789012
mkdir -p "$tmp/bin"

# ── tf-run.sh ────────────────────────────────────────────────
cat >"$tmp/bin/docker" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@" >"$FAKE_ARGS"
printf '%s\n' "${FAKE_STDOUT:-}"
printf '%s\n' "${FAKE_STDERR:-}" >&2
exit "${FAKE_CODE:-0}"
EOF
chmod +x "$tmp/bin/docker"

tf() { # [이름=값...] -- terraform 인자...   (CI·자격증명 환경은 지우고 시작한다)
  local envs=()
  while [ "$1" != -- ]; do
    envs+=("$1")
    shift
  done
  shift
  code=0
  env -u GITHUB_ACTIONS -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN -u TF_VAR_alert_emails \
    PATH="$tmp/bin:$PATH" FAKE_ARGS="$tmp/args" "${envs[@]}" \
    bash scripts/tf-run.sh infra/live/dev "$@" >"$tmp/stdout" 2>"$tmp/stderr" || code=$?
  args=$(cat "$tmp/args" 2>/dev/null || true)
  stdout=$(cat "$tmp/stdout")
  stderr=$(cat "$tmp/stderr")
}

echo "tf-run.sh 자체 검사"

: >"$tmp/args"
tf -- validate
check "고정 이미지 hashicorp/terraform:1.16.4 로 돈다" has "$args" "hashicorp/terraform:1.16.4@sha256:"
check "Terraform 인자를 그대로 넘긴다" same "$(tail -n1 <<<"$args")" "validate"
check "디렉터리 안에서 돈다" has "$args" "/repo/infra/live/dev"
check "프로바이더 캐시 볼륨을 쓴다" has "$args" "neuringo-tf-plugins:/plugins"
check "자동화 모드(TF_IN_AUTOMATION)" has "$args" "TF_IN_AUTOMATION=1"
check "자격증명이 없으면 넘기지 않는다" lacks "$args" "AWS_ACCESS_KEY_ID"

tf AWS_ACCESS_KEY_ID=AKIAFAKE AWS_SECRET_ACCESS_KEY=x AWS_SESSION_TOKEN=y TF_VAR_alert_emails= -- plan
check "자격증명이 있으면 이름만 넘긴다(값은 명령줄에 안 싣는다)" has "$args" "AWS_SESSION_TOKEN"
check "자격증명 값은 docker 인자에 없다" lacks "$args" "AKIAFAKE"
check "빈 TF_VAR_alert_emails 는 넘기지 않는다" lacks "$args" "TF_VAR_alert_emails"

tf FAKE_CODE=2 -- plan -detailed-exitcode
check "Terraform 종료 코드를 그대로 낸다(로컬)" same "$code" 2

tf TF_LOG=DEBUG -- plan
check "로컬에서는 TF_LOG 를 넘긴다(디버깅)" has "$args" "TF_LOG"
tf GITHUB_ACTIONS=true TF_LOG=DEBUG -- plan
check "GitHub Actions 에서는 TF_LOG 를 넘기지 않는다(디버그 로그에 비밀값이 나온다)" lacks "$args" "TF_LOG"
check "넘기지 않는다고 알린다" has "$stderr" "TF_LOG"

secret_err="Error: creating S3 Bucket: AccessDenied arn:aws:iam::$ACCOUNT:role/ktc-github-deploy
account $ACCOUNT ip 203.0.113.20 host ec2-203-0-113-20.ap-northeast-2.compute.amazonaws.com
sg-0123456789abcdef0 i-0abc1234def567890 distribution E2QWRUHAPOMQZL mail dev@example.com"
tf GITHUB_ACTIONS=true FAKE_CODE=1 "FAKE_STDERR=$secret_err" 'FAKE_STDOUT={"n":123456789012}' -- plan
check "GitHub Actions 에서도 종료 코드를 그대로 낸다" same "$code" 1
check "오류의 계정 ID 를 가린다" lacks "$stderr" "$ACCOUNT"
check "오류의 ARN 을 가린다" lacks "$stderr" "arn:aws:iam"
check "오류의 IP·EC2 주소를 가린다" lacks "$stderr" "203.0.113.20"
check "오류의 보안 그룹·서버 ID 를 가린다" lacks "$stderr" "sg-0123456789abcdef0"
check "오류의 CloudFront 배포 ID 를 가린다" lacks "$stderr" "E2QWRUHAPOMQZL"
check "오류의 메일을 가린다" lacks "$stderr" "dev@example.com"
check "가려도 오류 내용은 남는다" has "$stderr" "Error: creating S3 Bucket: AccessDenied"
check "표준 출력(show -json)은 건드리지 않는다" same "$stdout" '{"n":123456789012}'

code=0
PATH="$tmp/bin:$PATH" FAKE_ARGS="$tmp/args" bash scripts/tf-run.sh infra/없는곳 validate >/dev/null 2>&1 || code=$?
check "없는 디렉터리면 멈춘다" same "$code" 1

# ── tf-state.sh ──────────────────────────────────────────────
cat >"$tmp/bin/aws" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$FAKE_CALLS"
op=$2
case $op in
  head-bucket)
    case ${FAKE_HEAD:-ok} in
      404) echo "An error occurred (404) when calling the HeadBucket operation: Not Found" >&2; exit 254 ;;
      403) echo "An error occurred (403) when calling the HeadBucket operation: Forbidden" >&2; exit 254 ;;
    esac ;;
  create-bucket)
    if [ "${FAKE_CREATE:-ok}" = deny ]; then
      echo "An error occurred (AccessDenied) when calling the CreateBucket operation: User: arn:aws:sts::123456789012:assumed-role/x is not authorized" >&2
      exit 254
    fi ;;
esac
if [ "$op" = "${FAKE_PUT_FAIL:-}" ]; then
  echo "An error occurred (AccessDenied) when calling the operation: arn:aws:sts::123456789012:assumed-role/x" >&2
  exit 254
fi
EOF
chmod +x "$tmp/bin/aws"

state() { # 모드 [환경...]
  local mode=$1
  shift
  : >"$tmp/calls"
  : >"$tmp/out"
  : >"$tmp/summary"
  code=0
  log=$(env PATH="$tmp/bin:$PATH" FAKE_CALLS="$tmp/calls" GITHUB_ACTIONS=true GITHUB_OUTPUT="$tmp/out" \
    GITHUB_STEP_SUMMARY="$tmp/summary" AWS_ACCOUNT_ID=$ACCOUNT "$@" bash scripts/tf-state.sh "$mode" 2>&1) || code=$?
  calls=$(cat "$tmp/calls")
  out=$(cat "$tmp/out")
  summary=$(cat "$tmp/summary")
}

echo "tf-state.sh 자체 검사"

state check
check "있으면 ready=true" has "$out" "ready=true"
check "버킷 이름을 넘긴다" has "$out" "bucket=neuringo-tfstate-$ACCOUNT"
check "계정 ID 가 든 버킷 이름을 가린다" has "$log" "::add-mask::neuringo-tfstate-$ACCOUNT"
check "남의 버킷이 아닌지 소유 계정을 확인한다" has "$calls" "--expected-bucket-owner $ACCOUNT"
check "check 는 아무것도 바꾸지 않는다" lacks "$calls" "put-"

state check FAKE_HEAD=404
check "없으면 ready=false" has "$out" "ready=false"
check "없으면 missing=true(첫 실행인지 부르는 쪽이 정한다)" has "$out" "missing=true"
check "없어도 0 으로 끝난다(건너뜀)" same "$code" 0
check "check 는 만들지 않는다(승인 전에는 AWS 에 쓰지 않는다)" lacks "$calls" "create-bucket"
check "check 는 아무 설정도 걸지 않는다" lacks "$calls" "put-"
check "없을 때 Summary 는 부르는 쪽이 쓴다" same "$summary" ""

state check FAKE_HEAD=403
check "볼 권한이 없으면 1 로 끝난다(조용히 건너뛰지 않는다)" same "$code" 1
check "권한 문제(⚑1)라고 적는다" has "$summary" "⚑1"
check "권한 문제는 '없음'과 구분한다" lacks "$out" "missing=true"

state ensure FAKE_HEAD=404
check "ensure 는 없으면 만든다" has "$calls" "create-bucket"
check "서울 리전에 만든다" has "$calls" "LocationConstraint=ap-northeast-2"
check "버전 관리를 켠다" has "$calls" "put-bucket-versioning --versioning-configuration Status=Enabled"
check "공개 차단을 건다" has "$calls" "RestrictPublicBuckets=true"
check "암호화를 건다" has "$calls" '"SSEAlgorithm":"AES256"'
check "HTTPS 가 아니면 막는다" has "$calls" '"aws:SecureTransport":"false"'
check "옛 state 버전을 정리한다(수명 주기)" has "$calls" '"NoncurrentDays":30'
check "만들면 ready=true" has "$out" "ready=true"

state ensure
check "이미 있으면 만들지 않는다" lacks "$calls" "create-bucket"
check "이미 있어도 설정은 다시 건다" has "$calls" "put-bucket-versioning"

state ensure FAKE_HEAD=404 FAKE_CREATE=deny
check "만들 권한이 없으면 1 로 끝난다(승인한 apply 가 조용히 안 돌지 않게)" same "$code" 1
check "만들 권한 문제(⚑1)라고 적는다" has "$summary" "⚑1"
check "그때 설정은 걸지 않는다" lacks "$calls" "put-"
check "그때 ready 를 내지 않는다(만들기 실패)" lacks "$out" "ready=true"

state ensure FAKE_PUT_FAIL=put-bucket-versioning
check "설정을 못 걸면 실패한다" same "$code" 1
check "실패 이유는 오류 코드만 적는다" has "$log" "버전 관리 실패: (AccessDenied)"
check "그때 ready 를 내지 않는다" lacks "$out" "ready="

for mode in check ensure; do
  for env in FAKE_HEAD=403 FAKE_PUT_FAIL=put-bucket-versioning "FAKE_HEAD=404 FAKE_CREATE=deny"; do
    # shellcheck disable=SC2086 # 환경 여러 개를 단어로 나눠 넘긴다
    state "$mode" $env
    log_without_mask=$(grep -v '::add-mask::' <<<"$log" || true)
    check "$mode $env: 로그·Summary 에 계정 ID 가 없다" lacks "$log_without_mask$summary" "$ACCOUNT"
  done
done

code=0
env -u AWS_ACCOUNT_ID bash scripts/tf-state.sh check >/dev/null 2>&1 || code=$?
check "AWS_ACCOUNT_ID 가 없으면 멈춘다" same "$code" 2

if [ "$failures" -gt 0 ]; then
  echo "tf-run.sh·tf-state.sh 자체 검사 실패: $failures 건" >&2
  exit 1
fi
echo "tf-run.sh·tf-state.sh 자체 검사 통과"
