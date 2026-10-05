#!/usr/bin/env bash
# Terraform 을 고정 이미지(hashicorp/terraform:1.16.4)로 돌린다. 로컬(verify.sh infra)과 infra.yml 이 같은 버전을 쓴다.
#   bash scripts/tf-run.sh <디렉터리> <terraform 인자...>
#   bash scripts/tf-run.sh infra/modules/edge test
#
# - 프로바이더는 docker 볼륨 neuringo-tf-plugins 에 캐시한다(디렉터리 7곳이 한 번만 받는다).
# - AWS 자격증명·TF_VAR_alert_emails 는 환경에 있을 때만 컨테이너에 넘긴다(검사는 자격증명 없이 돈다).
# - GitHub Actions 에서는 오류 출력(stderr)에서 계정 ID·ARN·IP·EC2 주소·리소스 ID·메일을 가린다(공개 로그).
#   표준 출력은 그대로다. plan·apply 원문은 부르는 쪽이 버리거나 줄만 골라 쓴다(show -json 은 파일로 받는다).
# - GitHub Actions 에서는 TF_LOG 를 넘기지 않는다. 디버그 로그에는 API 요청 본문(쓰기 전용 비밀값 포함)이 그대로 나온다.
# - 이미지는 태그와 digest 로 고정한다. 자격증명을 받는 컨테이너라 태그가 바뀌어도 다른 이미지가 돌지 않게.
set -euo pipefail

TF_IMAGE=hashicorp/terraform:1.16.4@sha256:985cdc6c1d9b0a65b83377f666efd2f740b47f02ac55be1ced3d18f7d3b0e829
PLUGIN_VOLUME=neuringo-tf-plugins

dir=${1:?Terraform 디렉터리가 필요하다(예: infra/live/dev)}
shift

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
[ -d "$dir" ] || {
  echo "디렉터리가 없다: $dir" >&2
  exit 1
}

# 공개 로그용으로 식별자를 가린다(scripts/ssm-run.sh 와 같은 규칙 + Terraform 오류에 자주 나오는 ID).
redact() {
  sed -E \
    -e 's/arn:aws[a-zA-Z-]*:[^[:space:]"]*/<arn>/g' \
    -e 's/ec2-[0-9-]+\.[a-z0-9.-]*amazonaws\.com/<서버 주소>/g' \
    -e 's/[0-9]{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com/<레지스트리>/g' \
    -e 's/(^|[^0-9])[0-9]{12}([^0-9]|$)/\1<계정>\2/g' \
    -e 's/(^|[^0-9.])([0-9]{1,3}\.){3}[0-9]{1,3}([^0-9.]|$)/\1<IP>\3/g' \
    -e 's/\b(i|sg|sgr|vpc|subnet|pl|eni|vol|ami)-[0-9a-f]{8,17}\b/<\1-…>/g' \
    -e 's/\bE[0-9A-Z]{12,13}\b/<배포 ID>/g' \
    -e 's/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/<메일>/g'
}

# Git Bash(Windows)에서는 Docker 에 Windows 경로를 넘겨야 마운트가 된다.
export MSYS_NO_PATHCONV=1
host_root() { if pwd -W >/dev/null 2>&1; then pwd -W; else pwd; fi; }

args=(--rm -v "$(host_root):/repo" -v "$PLUGIN_VOLUME:/plugins" -w "/repo/$dir"
  -e TF_PLUGIN_CACHE_DIR=/plugins -e TF_IN_AUTOMATION=1 -e TF_INPUT=0 -e CHECKPOINT_DISABLE=1)
for name in AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_REGION AWS_DEFAULT_REGION \
  TF_VAR_alert_emails TF_VAR_edge_enabled; do
  if [ -n "${!name:-}" ]; then args+=(-e "$name"); fi
done
if [ -n "${TF_LOG:-}" ]; then
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "TF_LOG 는 GitHub Actions 에서 넘기지 않는다(디버그 로그에 비밀값이 나온다). 로컬에서만 쓴다." >&2
  else
    args+=(-e TF_LOG)
  fi
fi

if [ -n "${GITHUB_ACTIONS:-}" ]; then
  # stderr 만 걸러 내고 stdout 은 그대로 둔다. pipefail 이라 Terraform 의 종료 코드가 그대로 나온다.
  { docker run "${args[@]}" "$TF_IMAGE" "$@" 2>&1 1>&3 3>&- | redact >&2; } 3>&1
else
  exec docker run "${args[@]}" "$TF_IMAGE" "$@"
fi
