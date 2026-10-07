#!/usr/bin/env bash
# edge-toggle.sh · server-power.sh 자체 검사. 가짜 aws 로 CloudFront·EC2 를 흉내 낸다(실제 AWS 필요 없음).
# - 대문(CloudFront): 켤 때 새 서버 주소로 바꾸고 연다 / 끌 때는 다 꺼진 것을 본 뒤에만 끝낸다 / 그사이 바뀌었으면 덮어쓰지 않는다
# - 서버(EC2): 켜고 끈다 / 권한이 없으면 77 / 끄기 권한이 없으면 "OS 를 끄면 중지"일 때만 서버 안에서 끈다
# - 출력(공개 로그)에 배포 ID·주소·인스턴스 ID 가 없다
#   bash scripts/test-dev-server.sh   (verify.sh workflows 에 포함)
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
file_has() { [ -f "$1" ] && grep -qF -- "$2" "$1"; }
same() { [ "$1" = "$2" ]; }

DIST=E2FAKEDIST0001
INSTANCE=i-0123456789abcdef0
OLD_DNS=ec2-203-0-113-10.ap-northeast-2.compute.amazonaws.com
NEW_DNS=ec2-198-51-100-7.ap-northeast-2.compute.amazonaws.com
mkdir -p "$tmp/bin"

# 가짜 aws. CloudFront 설정은 $FAKE/cf.json, 상태 확인 횟수는 $FAKE/polls(FAKE_CF_POLLS 번째부터 Deployed).
# EC2 는 $FAKE/ec2(ID 상태 주소 줄), 호출은 $FAKE/calls 에 남긴다.
cat >"$tmp/bin/aws" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$FAKE/calls"
deny() { echo "An error occurred ($1) when calling the $2 operation: arn:aws:sts::123456789012:assumed-role/ktc-github-deploy/x is not authorized" >&2; exit 254; }
case "$1 $2" in
  "cloudfront get-distribution-config") cat "$FAKE/cf.json" ;;
  "cloudfront update-distribution")
    [ "${FAKE_CF_PRECONDITION:-0}" = 1 ] && { echo "An error occurred (PreconditionFailed) when calling the UpdateDistribution operation" >&2; exit 254; }
    for a in "$@"; do case $a in file://*) cfg=${a#file://} ;; esac; done
    node -e 'const fs=require("fs");const [cf,cfg]=process.argv.slice(1);const g=JSON.parse(fs.readFileSync(cf));g.DistributionConfig=JSON.parse(fs.readFileSync(cfg));g.ETag="E-NEXT";fs.writeFileSync(cf,JSON.stringify(g))' "$FAKE/cf.json" "$cfg"
    echo 0 >"$FAKE/polls"
    echo InProgress ;;
  "cloudfront get-distribution")
    n=$(($(cat "$FAKE/polls" 2>/dev/null || echo 99) + 1)); echo "$n" >"$FAKE/polls"
    enabled=$(node -e 'console.log(JSON.parse(require("fs").readFileSync(process.argv[1])).DistributionConfig.Enabled?"True":"False")' "$FAKE/cf.json")
    if [ "$n" -ge "${FAKE_CF_POLLS:-1}" ]; then echo "Deployed	$enabled"; else echo "InProgress	$enabled"; fi ;;
  "ec2 describe-instances") cat "$FAKE/ec2" ;;
  "ec2 start-instances")
    [ "${FAKE_EC2_DENY:-0}" = 1 ] && deny UnauthorizedOperation StartInstances
    [[ " $* " == *" --dry-run "* ]] && { echo "An error occurred (DryRunOperation) when calling the StartInstances operation: Request would have succeeded" >&2; exit 254; }
    sed -i "s/ stopped None/ running $FAKE_NEW_DNS/" "$FAKE/ec2" ;;
  "ec2 stop-instances")
    [ "${FAKE_EC2_DENY:-0}" = 1 ] && deny UnauthorizedOperation StopInstances
    [[ " $* " == *" --dry-run "* ]] && { echo "An error occurred (DryRunOperation) when calling the StopInstances operation: Request would have succeeded" >&2; exit 254; }
    sed -i 's/ running .*/ stopped None/' "$FAKE/ec2" ;;
  "ec2 describe-instance-attribute") echo "${FAKE_SHUTDOWN:-stop}" ;;
  "ssm describe-instance-information") echo Online ;;
esac
EOF
# 가짜 ssm-run.sh. 서버에서 돌릴 스크립트 내용을 남기고, OS 끄기면 서버를 꺼진 것으로 바꾼다.
cat >"$tmp/bin/ssm-run" <<'EOF'
#!/usr/bin/env bash
cat "$1" >>"$FAKE/ssm"
if grep -q 'shutdown -h' "$1"; then sed -i 's/ running .*/ stopped None/' "$FAKE/ec2"; fi
exit 0
EOF
chmod +x "$tmp/bin/aws" "$tmp/bin/ssm-run"

cf_config() { # 켜짐(true|false) 주소
  cat >"$tmp/fake/cf.json" <<EOF
{"ETag":"E-ONE","DistributionConfig":{"CallerReference":"terraform-1","Enabled":$1,
"Origins":{"Quantity":1,"Items":[{"Id":"app","DomainName":"$2","CustomOriginConfig":{"HTTPPort":80}}]},
"Restrictions":{"GeoRestriction":{"RestrictionType":"whitelist","Quantity":1,"Items":["KR"]}}}}
EOF
}
new_case() {
  rm -rf "${tmp:?}/fake"
  mkdir -p "$tmp/fake"
}
edge() { # on|off|status [주소]
  code=0
  out=$(PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" CF_DISTRIBUTION_ID=$DIST EDGE_POLL=0 bash scripts/edge-toggle.sh "$@" 2>&1) || code=$?
}
power() { # state|start|stop
  : >"$tmp/out"
  code=0
  out=$(PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" FAKE_NEW_DNS=$NEW_DNS SSM_RUN="$tmp/bin/ssm-run" SERVER_POLL=0 \
    GITHUB_OUTPUT="$tmp/out" bash scripts/server-power.sh "$@" 2>&1) || code=$?
}
no_leak() {
  lacks "$out" "$DIST" && lacks "$out" "$OLD_DNS" && lacks "$out" "$NEW_DNS" && lacks "$out" "$INSTANCE" &&
    lacks "$out" "123456789012"
}
cf_value() { node -e 'const c=JSON.parse(require("fs").readFileSync(process.argv[1])).DistributionConfig;console.log(process.argv[2]==="domain"?c.Origins.Items[0].DomainName:c.Enabled)' "$tmp/fake/cf.json" "$1"; }

echo "edge-toggle.sh · server-power.sh 자체 검사"

# ── 대문(CloudFront) ──────────────────────────────────────
new_case
cf_config false "$OLD_DNS"
edge on "$NEW_DNS"
check "켜기가 성공한다" same "$code" 0
check "새 서버 주소로 바꾼다" same "$(cf_value domain)" "$NEW_DNS"
check "켠다" same "$(cf_value enabled)" true
check "읽은 버전(ETag)을 붙여 바꾼다(그사이 바뀌었으면 덮어쓰지 않게)" file_has "$tmp/fake/calls" "--if-match E-ONE"
check "바꿨다고 알려 준다" has "$out" "EDGE_RESULT=changed"
check "켜기 출력에 배포 ID·주소가 없다" no_leak

rm -f "$tmp/fake/calls"
edge on "$NEW_DNS"
check "이미 켜져 있고 주소도 같으면 바꾸지 않는다" has "$out" "EDGE_RESULT=unchanged"
check "그때 update 를 부르지 않는다" bash -c "! grep -q update-distribution '$tmp/fake/calls'"

new_case
cf_config true "$OLD_DNS"
FAKE_CF_POLLS=3 edge off
check "끄기가 성공한다" same "$code" 0
check "다 퍼져서(Deployed) 꺼진 것을 본 뒤에 끝난다" bash -c "grep -q 'EDGE_STATUS=Deployed' <<<\"\$0\" && grep -q 'EDGE_ENABLED=False' <<<\"\$0\"" "$out"
check "그때까지 상태를 다시 읽는다" same "$(grep -c 'get-distribution --id' "$tmp/fake/calls")" 3
check "끌 때 주소는 그대로 둔다" same "$(cf_value domain)" "$OLD_DNS"

new_case
cf_config true "$OLD_DNS"
code=0
out=$(PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" FAKE_CF_POLLS=99 CF_DISTRIBUTION_ID=$DIST EDGE_POLL=0 EDGE_WAIT=0 \
  bash scripts/edge-toggle.sh off 2>&1) || code=$?
check "시간 안에 다 꺼지지 않으면 1 로 끝난다" same "$code" 1
check "그때 서버를 끄지 말라고 알려 준다" has "$out" "서버를 끄지 않는다"

new_case
cf_config true "$OLD_DNS"
FAKE_CF_PRECONDITION=1 edge off
check "그사이 다른 실행이 바꿨으면 1 로 끝난다" same "$code" 1
check "다시 누르라고 알려 준다" has "$out" "다시 누른다"

new_case
cf_config false "$OLD_DNS"
edge on "evil.example.com"
check "EC2 공인 주소가 아니면 켜지 않는다" same "$code" 1
check "그때 설정은 그대로다" same "$(cf_value enabled)" false

edge status
check "상태만 볼 수 있다" has "$out" "EDGE_ENABLED=False"
check "상태 출력에도 배포 ID 가 없다" no_leak

# ── 서버(EC2) ───────────────────────────────────────────
new_case
echo "$INSTANCE stopped None" >"$tmp/fake/ec2"
power state
check "상태: 꺼져 있다" has "$out" "SERVER_STATE=stopped"

power perms
check "권한 확인: 켜기·끄기 권한이 있다(DryRun)" bash -c "grep -q PERM_START=yes <<<\"\$0\" && grep -q PERM_STOP=yes <<<\"\$0\"" "$out"
check "권한 확인은 서버를 바꾸지 않는다" file_has "$tmp/fake/ec2" "stopped"
FAKE_EC2_DENY=1 power perms
check "권한 확인: 없으면 no" has "$out" "PERM_START=no"

power start
check "켜기가 성공한다(서버)" same "$code" 0
check "켜져 있다" has "$out" "SERVER_STATE=running"
check "새 공인 주소를 워크플로 출력으로 넘긴다" file_has "$tmp/out" "SERVER_DNS=$NEW_DNS"
check "공인 주소·인스턴스 ID 는 출력에 찍지 않는다" no_leak

rm -f "$tmp/fake/calls"
power start
check "이미 켜져 있으면 다시 켜지 않는다" bash -c "! grep -q start-instances '$tmp/fake/calls'"

new_case
echo "$INSTANCE stopped None" >"$tmp/fake/ec2"
FAKE_EC2_DENY=1 power start
check "켜기 권한이 없으면 77 로 끝난다" same "$code" 77
check "콘솔에서 켜라고 알려 준다" has "$out" "콘솔"
check "권한 오류 본문(계정 ID)을 찍지 않는다" no_leak

new_case
echo "$INSTANCE running $OLD_DNS" >"$tmp/fake/ec2"
power stop
check "끄기가 성공한다(서버)" same "$code" 0
check "배포·백업 잠금을 기다린 뒤 끈다" file_has "$tmp/fake/ssm" "deploy.lock"
check "꺼져 있다" has "$out" "SERVER_STATE=stopped"
check "stop-instances 로 껐다고 알려 준다" has "$out" "SERVER_HOW=stop-instances"

new_case
echo "$INSTANCE running $OLD_DNS" >"$tmp/fake/ec2"
FAKE_EC2_DENY=1 FAKE_SHUTDOWN=stop power stop
check "끄기 권한이 없어도 OS 를 끄면 중지되는 서버면 서버 안에서 끈다" same "$code" 0
check "그때 OS 끄기를 보낸다" file_has "$tmp/fake/ssm" "shutdown -h +1"
check "OS 로 껐다고 알려 준다" has "$out" "SERVER_HOW=os-shutdown"

new_case
echo "$INSTANCE running $OLD_DNS" >"$tmp/fake/ec2"
FAKE_EC2_DENY=1 FAKE_SHUTDOWN=terminate power stop
check "OS 를 끄면 삭제되는 서버면 끄지 않고 77 로 끝난다" same "$code" 77
check "그때 OS 끄기를 보내지 않는다" bash -c "[ ! -f '$tmp/fake/ssm' ] || ! grep -q 'shutdown -h' '$tmp/fake/ssm'"

new_case
printf '%s stopped None\ni-0fedcba9876543210 running %s\n' "$INSTANCE" "$OLD_DNS" >"$tmp/fake/ec2"
power start
check "서버가 1대가 아니면 1 로 끝난다" same "$code" 1

if [ "$failures" -gt 0 ]; then
  echo "edge-toggle.sh · server-power.sh 자체 검사 실패: $failures 건" >&2
  exit 1
fi
echo "edge-toggle.sh · server-power.sh 자체 검사 통과"
