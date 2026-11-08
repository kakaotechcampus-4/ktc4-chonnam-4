#!/usr/bin/env bash
# edge-toggle.sh · server-power.sh 자체 검사. 가짜 aws 로 CloudFront·EC2 를 흉내 낸다(실제 AWS 필요 없음).
# - 대문(CloudFront): 켤 때 새 서버 주소로 바꾸고 연다 / 끌 때는 다 꺼진 것을 본 뒤에만 끝낸다 / 그사이 바뀌었으면 덮어쓰지 않는다
# - 서버(EC2): 켜고 끈다 / 권한이 없으면 77 / 끄기 권한이 없으면 "OS 를 끄면 중지"일 때만 서버 안에서 끈다
# - 출력(공개 로그)에 배포 ID·주소·인스턴스 ID 가 없다
# - dev-server.yml 판정 단계: 포크 PR 은 저장소 변수가 빈 값이어도 실패 없이 건너뛴다
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
  "logs filter-log-events")
    [ -f "$FAKE/logs.err" ] && { cat "$FAKE/logs.err" >&2; exit 254; }
    cat "$FAKE/logs.json" ;;
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
# GitHub Actions 밖처럼 돌린다. Actions 안에서는 ::add-mask::<값> 줄이 찍히는데, 그건 가리라는 지시라 노출이 아니다.
# 가리라는 지시가 나오는지는 아래에서 GITHUB_ACTIONS=true 로 따로 본다.
edge() { # on|off|status [주소]
  code=0
  out=$(env -u GITHUB_ACTIONS PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" CF_DISTRIBUTION_ID=$DIST EDGE_POLL=0 \
    bash scripts/edge-toggle.sh "$@" 2>&1) || code=$?
}
idle_last() {
  code=0
  out=$(env -u GITHUB_ACTIONS PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" bash scripts/idle-last.sh 2>&1) || code=$?
}
power() { # state|start|stop
  : >"$tmp/out"
  code=0
  out=$(env -u GITHUB_ACTIONS PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" FAKE_NEW_DNS=$NEW_DNS SSM_RUN="$tmp/bin/ssm-run" \
    SERVER_POLL=0 GITHUB_OUTPUT="$tmp/out" bash scripts/server-power.sh "$@" 2>&1) || code=$?
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
gha=$(GITHUB_ACTIONS=true PATH="$tmp/bin:$PATH" FAKE="$tmp/fake" SSM_RUN="$tmp/bin/ssm-run" GITHUB_OUTPUT=/dev/null \
  bash scripts/server-power.sh state 2>&1) || true
check "GitHub Actions 에서는 인스턴스 ID 를 가리라고 지시한다(::add-mask::)" has "$gha" "::add-mask::$INSTANCE"
check "GitHub Actions 에서는 공인 주소도 가리라고 지시한다(::add-mask::)" has "$gha" "::add-mask::$NEW_DNS"

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

# ── 안 쓰면 끄기(Lambda) 마지막 판단 읽기 ───────────────────
new_case
# 순서가 섞여 와도 가장 늦은 판단을 고른다. JSON 이 아닌 줄(START·오류 추적)은 건너뛴다.
cat >"$tmp/fake/logs.json" <<'EOF'
[[1791692400000, "{\"result\": \"stopped\", \"reason\": \"자리가 비었고 | 마지막 사용 13:06 (KST) 뒤 33분\\n동안 안 썼다\"}\n"],
 [1791691800000, "{\"result\": \"keep\", \"reason\": \"마지막 사용 13:06 (KST), 3분 전\"}"],
 [1791692460000, "[ERROR] RuntimeError: result 없음"]]
EOF
idle_last
check "로그를 읽으면 ok" has "$out" "IDLE_LOG=ok"
check "가장 늦은 판단(끈 것)을 고른다" has "$out" "IDLE_RESULT=stopped"
check "시각은 로그 시각(UTC)" has "$out" "IDLE_AT=2026-10-11T04:20:00Z"
check "까닭에서 표를 깨는 | 와 줄바꿈을 지운다" has "$out" "IDLE_REASON=자리가 비었고 마지막 사용 13:06 (KST) 뒤 33분 동안 안 썼다"

new_case
echo '[]' >"$tmp/fake/logs.json"
idle_last
check "최근 판단이 없으면 none" has "$out" "IDLE_LOG=none"

new_case
echo "An error occurred (ResourceNotFoundException) when calling the FilterLogEvents operation: The specified log group does not exist." >"$tmp/fake/logs.err"
idle_last
check "로그 그룹이 없으면(apply 전) none · 실패로 끝나지 않는다" bash -c "grep -q 'IDLE_LOG=none' <<<\"\$0\" && [ \"\$1\" = 0 ]" "$out" "$code"

new_case
echo "An error occurred (AccessDeniedException) when calling the FilterLogEvents operation: arn:aws:sts::123456789012:assumed-role/ktc-github-deploy/x is not authorized" >"$tmp/fake/logs.err"
idle_last
check "권한이 없으면 unreadable · 실패로 끝나지 않는다" bash -c "grep -q 'IDLE_LOG=unreadable' <<<\"\$0\" && [ \"\$1\" = 0 ]" "$out" "$code"
check "오류 원문(역할 ARN·계정 ID)을 찍지 않는다" no_leak

echo "Dev server 판정(plan 단계) 자체 검사"
# dev-server.yml 의 'Decide what to do' 스크립트를 꺼내 GitHub 밖에서 돌린다. gh 는 가짜(부르면 기록하고 실패)라 실제 PR 을 건드리지 않는다.
plan_script() {
  awk '
    $0 == "      - name: Decide what to do" { step = 1; next }
    step && $0 == "        run: |" { run = 1; next }
    run && /^          / { print substr($0, 11); next }
    run && /^[[:space:]]*$/ { print ""; next }
    run { exit }
  ' .github/workflows/dev-server.yml
}
mkdir -p "$tmp/plan-bin"
# 기본은 실패한다. FAKE_* 를 주면 그 API 만 답한다(24시간 모드 파일·PR·합친 커밋의 부모·라벨·파일 목록).
cat >"$tmp/plan-bin/gh" <<'EOF'
#!/usr/bin/env bash
echo "gh $*" >>"$PLAN_GH_CALLS"
case "$*" in
  *"/contents/infra/live/dev/always-on.json"*) [ -n "${FAKE_ALWAYS_ON:-}" ] && printf '%s\n' "$FAKE_ALWAYS_ON" && exit 0 ;;
  *"/contents/frontend/Dockerfile"*) [ -n "${FAKE_PR_JSON:-}" ] && exit 0 ;;
  *"/labels --jq"*) [ -n "${FAKE_LABELS+x}" ] && printf '%s' "$FAKE_LABELS" && exit 0 ;;
  *"-X POST"*"/labels"*) exit 0 ;;
  *"/pulls/"*"/files"*) [ -n "${FAKE_FILES:-}" ] && printf '%s\n' "$FAKE_FILES" && exit 0 ;;
  *"/pulls/"*) [ -n "${FAKE_PR_JSON:-}" ] && printf '%s\n' "$FAKE_PR_JSON" && exit 0 ;;
  *"/commits/"*) [ -n "${FAKE_PARENTS:-}" ] && printf '%s\n' "$FAKE_PARENTS" && exit 0 ;;
esac
exit 1
EOF
chmod +x "$tmp/plan-bin/gh"
plan_script >"$tmp/plan.sh"
check "plan 단계 스크립트를 꺼냈다" file_has "$tmp/plan.sh" 'AWS_ACCOUNT_ID'
decide() { # 환경변수=값... (GitHub 이 이 단계에 넘기는 값)
  : >"$tmp/plan-out"
  : >"$tmp/plan-summary"
  : >"$tmp/plan-gh"
  code=0
  out=$(env -i PATH="$tmp/plan-bin:$PATH" PLAN_GH_CALLS="$tmp/plan-gh" \
    GITHUB_OUTPUT="$tmp/plan-out" GITHUB_STEP_SUMMARY="$tmp/plan-summary" \
    REPO=kakaotechcampus-4/ktc4-chonnam-4 ACTOR=someone "$@" \
    bash --noprofile --norc -eo pipefail "$tmp/plan.sh" 2>&1) || code=$?
}

# 포크에서 온 PR 실행에는 GitHub 이 저장소 변수를 넘기지 않는다(AWS_ACCOUNT_ID 가 빈 값).
decide EVENT=pull_request PR_ACTION=opened PR=101 HEAD_REPO=someone/ktc4-chonnam-4 AWS_ACCOUNT_ID=
check "포크 PR: 저장소 변수가 빈 값이어도 실패하지 않는다" same "$code" 0
check "포크 PR: 할 일 없음(task=none)으로 끝난다" file_has "$tmp/plan-out" "task=none"
check "포크 PR: 요약에 포크라서 건너뛴다고 쓴다" file_has "$tmp/plan-summary" "포크에서 온 PR"
check "포크 PR: GitHub API 를 부르지 않는다" same "$(cat "$tmp/plan-gh")" ""

decide EVENT=workflow_dispatch ACTION="상태 보기" AWS_ACCOUNT_ID=
check "버튼 실행: 저장소 변수가 없으면 실패한다" same "$code" 1
check "버튼 실행: 오류에 AWS_ACCOUNT_ID 가 없다고 쓴다" has "$out" "AWS_ACCOUNT_ID(12자리)가 없다"

decide EVENT=workflow_dispatch ACTION="상태 보기" AWS_ACCOUNT_ID=000000000000
check "버튼 실행: 저장소 변수가 있으면 할 일을 정한다" file_has "$tmp/plan-out" "task=상태 보기"

# 24시간 모드(develop 의 infra/live/dev/always-on.json): PR 을 열어도 저절로 올리지 않고(테스트 주소는 develop),
# push 는 이 PR 이 자리를 잡고 있을 때만 다시 올린다.
check "24시간 모드 파일이 Terraform 변수 이름 그대로다" file_has infra/live/dev/always-on.json '"always_on_until"'
ON='{ "always_on_until": "2999-12-31T23:59:00+09:00" }'
OFF='{ "always_on_until": "2000-01-01T00:00:00+09:00" }'
opened=(EVENT=pull_request PR_ACTION=opened PR=101 HEAD_REPO=kakaotechcampus-4/ktc4-chonnam-4 AWS_ACCOUNT_ID=000000000000
  HAS_PREVIEW=false HAS_NO_PREVIEW=false DRAFT=false BASE_REF=develop AUTHOR_TYPE=User FAKE_LABELS= FAKE_FILES=backend/src/A.java)
decide "${opened[@]}" FAKE_ALWAYS_ON="$ON"
check "24시간 모드: PR 을 열어도 실패 없이 건너뛴다" same "$code" 0
check "24시간 모드: 할 일 없음(task=none)" file_has "$tmp/plan-out" "task=none"
# 끝 시각은 KST 로 쓴다(러너에는 시간대 자료가 있다. Git Bash 는 없어 UTC 로 찍혀 시각 자체는 보지 않는다).
check "24시간 모드: 요약에 까닭과 보는 법(preview 라벨)을 쓴다" bash -c "grep -qE '24시간 모드\(12/31 [0-9:]{5} KST 까지\)' '$tmp/plan-summary' && grep -qF 'preview 라벨' '$tmp/plan-summary'"
check "24시간 모드: preview 라벨을 붙이지 않는다" bash -c "! grep -q 'POST' '$tmp/plan-gh'"
check "24시간 모드: develop 의 파일을 읽는다" file_has "$tmp/plan-gh" "always-on.json?ref=develop"
decide "${opened[@]}" FAKE_ALWAYS_ON="$OFF"
check "24시간 모드가 끝났으면 전처럼 미리보기로 간다(가짜 gh 라 PR 읽기에서 멈춤)" has "$out" "PR #101 을 읽지 못했다"
decide "${opened[@]}"
check "24시간 모드 파일을 읽지 못하면 전처럼 간다" has "$out" "PR #101 을 읽지 못했다"
if command -v jq >/dev/null 2>&1; then
  MERGE=1111111111111111111111111111111111111111
  HEAD=2222222222222222222222222222222222222222
  pushed=(EVENT=pull_request PR_ACTION=synchronize PR=101 HEAD_REPO=kakaotechcampus-4/ktc4-chonnam-4 AWS_ACCOUNT_ID=000000000000
    HAS_PREVIEW=true HAS_NO_PREVIEW=false "MERGE_SHA=$MERGE" FAKE_LABELS=preview
    FAKE_PR_JSON="{\"base\":{\"ref\":\"develop\"},\"head\":{\"sha\":\"$HEAD\"},\"title\":\"t\",\"user\":{\"login\":\"someone\"}}"
    FAKE_PARENTS="3333333333333333333333333333333333333333 $HEAD")
  decide "${pushed[@]}" FAKE_ALWAYS_ON="$ON"
  check "24시간 모드의 push: 다시 올리기로 정한다" file_has "$tmp/plan-out" "task=PR 미리보기 다시 올리기"
  check "24시간 모드의 push: 이 PR 의 자리일 때만(own_slot_only)" file_has "$tmp/plan-out" "own_slot_only=true"
  decide "${pushed[@]}" FAKE_ALWAYS_ON="$OFF"
  check "24시간 모드가 아니면 push 는 전처럼(자리가 비었어도)" file_has "$tmp/plan-out" "own_slot_only=false"
else
  echo "  - jq 가 없어 push 판정 검사는 건너뛴다(CI 에서 돈다)"
fi

if [ "$failures" -gt 0 ]; then
  echo "edge-toggle.sh · server-power.sh · Dev server 판정 자체 검사 실패: $failures 건" >&2
  exit 1
fi
echo "edge-toggle.sh · server-power.sh · Dev server 판정 자체 검사 통과"
