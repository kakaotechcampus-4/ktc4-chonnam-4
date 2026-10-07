#!/usr/bin/env bash
# ssm-run.sh 자체 검사. 가짜 aws 로 SSM 을 흉내 내고, 실제로 서버에 보낼 명령을 여기서 sh 로 돌려 본다(AWS 필요 없음).
# - 서버 스크립트의 종료 코드를 그대로 돌려준다(배포 되돌림 2 등) / 서버가 0대·여러 대면 멈춘다 / 시간이 넘으면 124
# - 공개 로그에 계정 ID·ARN·IP·EC2 주소·인스턴스 ID 가 나오지 않는다
#   bash scripts/test-ssm-run.sh   (verify.sh workflows 에 포함)
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

PY=$(command -v python3 || command -v python)
INSTANCE=i-0abc1234def567890

mkdir -p "$tmp/bin" "$tmp/fake"
cat >"$tmp/bin/aws" <<'EOF'
#!/usr/bin/env bash
args="$*"
case "$args" in
  "ec2 describe-instances"*)
    [ "${FAKE_EC2_FAIL:-0}" = 1 ] && { echo "An error occurred (UnauthorizedOperation) when calling the DescribeInstances operation: arn:aws:sts::123456789012:assumed-role/x" >&2; exit 254; }
    echo "${FAKE_INSTANCES:-}" ;;
  "ssm send-command"*)
    for a in "$@"; do case $a in file://*) cp "${a#file://}" "$FAKE/params.json" ;; esac; done
    echo cmd-0001 ;;
  *"--query [Status,ResponseCode]"*)
    n=$(( $(cat "$FAKE/polls" 2>/dev/null || echo 0) + 1 ))
    echo "$n" >"$FAKE/polls"
    if [ "${FAKE_NOT_YET:-0}" = 1 ] && [ "$n" = 1 ]; then
      echo "An error occurred (InvocationDoesNotExist) when calling the GetCommandInvocation operation" >&2
      exit 254
    fi
    if [ "$n" -le 2 ]; then printf 'InProgress\t-1\n'; else printf '%s\t%s\n' "${FAKE_STATUS:-Success}" "${FAKE_CODE:-0}"; fi ;;
  *"--query StandardOutputContent"*) printf '%s\n' "${FAKE_STDOUT:-}" ;;
  *"--query StandardErrorContent"*) printf '%s\n' "${FAKE_STDERR:-None}" ;;
esac
EOF
chmod +x "$tmp/bin/aws"

# 서버에서 돌 스크립트: bash 로 돌아야 하고, 표시를 남기고, 종료 코드 7 로 끝난다.
cat >"$tmp/remote.sh" <<'EOF'
#!/usr/bin/env bash
[[ -n "${BASH_VERSION:-}" ]] || exit 9
echo "서버에서 돌았다" >"$MARK"
exit 7
EOF

run() { # 결과 코드는 $code, 출력은 $out. GitHub Actions 밖처럼 돌린다(가림 지시 줄이 없게)
  rm -f "$tmp/fake/polls"
  code=0
  out=$(env -u GITHUB_ACTIONS PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" SSM_POLL=0 SSM_GRACE=0 \
    bash scripts/ssm-run.sh "$tmp/remote.sh" "자체 검사" 2>&1) || code=$?
}

echo "ssm-run.sh 자체 검사"

export FAKE_INSTANCES=$INSTANCE
export FAKE_STDOUT="[deploy] 배포 완료 abc
pull 123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/neuringo/backend:abc
role arn:aws:iam::123456789012:role/ktc-ec2-ssm-role on $INSTANCE
host ec2-198-51-100-2.ap-northeast-2.compute.amazonaws.com 10.0.1.23
edge https://d111111abcdef8.cloudfront.net/api/v1/csrf
DEPLOY_RESULT=ok"
run
check "성공하면 0 으로 끝난다" same "$code" 0
check "서버 출력을 보여 준다" has "$out" "[deploy] 배포 완료 abc"
check "결과 줄을 보여 준다" has "$out" "DEPLOY_RESULT=ok"
check "레지스트리 주소를 가린다" has "$out" "<레지스트리>/neuringo/backend:abc"
check "계정 ID 가 없다" lacks "$out" "123456789012"
check "ARN 이 없다" lacks "$out" "arn:aws"
check "EC2 주소가 없다" lacks "$out" "ec2-198-51-100-2"
check "IP 가 없다" lacks "$out" "10.0.1.23"
check "테스트 주소(CloudFront)가 없다" lacks "$out" "cloudfront.net"
check "인스턴스 ID 가 없다" lacks "$out" "$INSTANCE"

# GitHub Actions 에서는 인스턴스 ID 를 가리라는 지시(::add-mask::)만 남기고, 그 밖의 줄에는 ID 가 없다.
gha_out=$(GITHUB_ACTIONS=true PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" SSM_POLL=0 SSM_GRACE=0 \
  bash scripts/ssm-run.sh "$tmp/remote.sh" "자체 검사" 2>&1 || true)
check "GitHub Actions 에서는 인스턴스 ID 를 가린다(::add-mask::)" has "$gha_out" "::add-mask::$INSTANCE"
check "가림 지시 말고는 인스턴스 ID 가 나오지 않는다" lacks "$(grep -v '^::add-mask::' <<<"$gha_out")" "$INSTANCE"

params=$(cat "$tmp/fake/params.json")
check "SSM 매개변수가 올바른 JSON 이다" "$PY" -c 'import json,sys; json.load(open(sys.argv[1]))' "$tmp/fake/params.json"
check "제한 시간을 넘긴다" has "$params" '"executionTimeout":["900"]'
cmd=$("$PY" -c 'import json,sys; print(json.load(open(sys.argv[1]))["commands"][0])' "$tmp/fake/params.json")
code=0
MARK="$tmp/marker" sh -c "$cmd" >/dev/null 2>&1 || code=$?
check "서버(sh)에서 그 명령을 돌리면 스크립트가 bash 로 돈다" has "$(cat "$tmp/marker" 2>/dev/null)" "서버에서 돌았다"
check "서버 스크립트의 종료 코드가 그대로 나온다" same "$code" 7

FAKE_STATUS=Failed FAKE_CODE=2 run
check "서버 스크립트가 2(되돌림)로 끝나면 2 로 끝난다" same "$code" 2

FAKE_NOT_YET=1 run
check "명령이 아직 등록되지 않았으면 다시 묻는다" same "$code" 0

FAKE_STATUS=TimedOut FAKE_CODE=-1 run
check "SSM 이 시간 초과를 알리면 124" same "$code" 124

FAKE_STATUS=InProgress SSM_TIMEOUT=0 run
check "기다릴 시간이 넘으면 124" same "$code" 124

FAKE_INSTANCES="" run
check "켜진 서버가 없으면 69(꺼져 있음 — 부르는 쪽이 건너뛸지 정한다)" same "$code" 69
check "몇 대인지 알려 준다" has "$out" "0대"
check "켜는 방법을 알려 준다" has "$out" "인스턴스 시작"

FAKE_INSTANCES="$INSTANCE i-0fff1234def567890" run
check "켜진 서버가 여러 대면 1" same "$code" 1

FAKE_EC2_FAIL=1 run
check "서버 목록을 못 읽으면 1" same "$code" 1
check "그 오류에서도 ARN·계정 ID 를 가린다" lacks "$out" "123456789012"

if [ "$failures" -gt 0 ]; then
  echo "ssm-run.sh 자체 검사 실패: $failures 건" >&2
  exit 1
fi
echo "ssm-run.sh 자체 검사 통과"
