#!/usr/bin/env bash
# CI 와 같은 검사를 로컬에서 돌린다. 명령은 워크플로와 똑같이 둔다.
# 여기서 초록이면 PR 에서도 초록이어야 한다. 다르게 나오면 이 스크립트나 워크플로 중 하나가 틀린 것이다.
#
#   bash scripts/verify.sh              # frontend + backend + workflows + infra + security
#   bash scripts/verify.sh frontend     # Frontend CI 와 같다
#   bash scripts/verify.sh backend      # Backend CI 와 같다 (Docker 필요 — Testcontainers)
#   bash scripts/verify.sh workflows    # Workflow lint 와 같다 (Docker 필요 — actionlint·zizmor·shellcheck)
#   bash scripts/verify.sh infra        # Infra 의 check 와 같다 (Docker 필요 — Terraform fmt·validate·test·구성 검사, AWS 호출 없음)
#   bash scripts/verify.sh security     # Security 와 같다 — 공개하면 안 되는 파일·값 검사 + gitleaks (gitleaks 는 Docker 필요)
#   bash scripts/verify.sh e2e          # E2E 와 같다 (Docker·JDK 21·Node 24, 8080·5173 이 비어 있어야 한다)
#   bash scripts/verify.sh docker       # Docker build 와 같다 (Docker 필요 — 이미지 빌드·compose 기동, 18080 을 쓴다)
#
# PowerShell 에서는 docs/testing.md 의 개별 명령을 그대로 쓰면 된다.
set -uo pipefail

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT" || exit 1

# 운영진 소유 워크플로(CODEOWNERS). 팀이 고칠 수 없으므로 린트 대상에서 뺀다.
OPS_OWNED="assign-mentor.yml convention-check.yml notify-discord.yml"

ACTIONLINT_IMAGE=rhysd/actionlint:1.7.12
ZIZMOR_IMAGE=ghcr.io/zizmorcore/zizmor:1.30.1
SHELLCHECK_IMAGE=koalaman/shellcheck:v0.11.0
GITLEAKS_IMAGE=zricethezav/gitleaks:v8.30.1

failed=()

run() { # 이름 명령...
  local name=$1
  shift
  printf '\n\033[1m▶ %s\033[0m\n' "$name"
  if "$@"; then
    printf '\033[32m✓ %s\033[0m\n' "$name"
  else
    printf '\033[31m✗ %s\033[0m\n' "$name"
    failed+=("$name")
  fi
}

require_docker() {
  docker info >/dev/null 2>&1 && return 0
  echo "Docker 가 꺼져 있습니다. Testcontainers 와 린트 도구가 전부 Docker 로 돕니다. Docker Desktop 을 켜고 다시 실행하세요." >&2
  return 1
}

warn_node_version() {
  local want node_major
  want=$(cat frontend/.nvmrc)
  node_major=$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo "없음")
  if [ "$node_major" != "$want" ]; then
    echo "경고: 지금 Node 는 $node_major 이고 CI 는 frontend/.nvmrc 의 Node $want LTS 로 돈다. 결과가 다를 수 있다." >&2
  fi
}

warn_java_version() {
  local java_major
  java_major=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')
  if [ "${java_major:-없음}" != "21" ]; then
    echo "경고: 지금 java 는 ${java_major:-없음} 이다. Gradle toolchain 은 21 을 쓰지만 CI 는 Temurin 21 로 돈다." >&2
  fi
}

# Git Bash(Windows)에서는 Docker 에 Windows 경로를 넘겨야 마운트가 된다.
host_root() { if pwd -W >/dev/null 2>&1; then pwd -W; else pwd; fi; }
in_docker() { MSYS_NO_PATHCONV=1 docker run --rm -v "$(host_root):/repo" -w /repo "$@"; }

team_workflows() {
  local file
  for file in .github/workflows/*.yml; do
    case " $OPS_OWNED " in *" ${file##*/} "*) continue ;; esac
    echo "$file"
  done
}

frontend_install() { (cd frontend && npm ci --no-audit --no-fund); }
frontend_lint() { (cd frontend && npm run lint); }
frontend_build() { (cd frontend && npm run build); }
frontend_test() { (cd frontend && npm run test:coverage); }

backend_build() { require_docker && (cd backend && ./gradlew build --no-daemon); }
migration_guard() { bash scripts/check-migrations.sh "${MIGRATION_BASE:-origin/develop}"; }

actionlint_check() {
  local files
  mapfile -t files < <(team_workflows)
  require_docker || return 1
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    # 아래 {{ }} 는 셸 변수가 아니라 actionlint 의 Go 템플릿이라 작은따옴표가 맞다.
    # actionlint 는 형식 문자열의 \n 을 먼저 줄바꿈으로 바꾼다. {{"\n"}} 처럼 따옴표 안에 두면 템플릿이 깨지므로 밖에 둔다.
    # shellcheck disable=SC2016
    in_docker "$ACTIONLINT_IMAGE" \
      -format '{{range $err := .}}::error file={{$err.Filepath}},line={{$err.Line}},col={{$err.Column}}::{{$err.Message}}\n{{end}}' \
      "${files[@]}"
  else
    in_docker "$ACTIONLINT_IMAGE" -color "${files[@]}"
  fi
}

# zizmor 는 1차 도입 동안 경고만 한다. 지적이 있어도 실패로 치지 않는다(docs/testing.md 전환 조건 참고).
zizmor_check() {
  local files format=plain code=0
  mapfile -t files < <(team_workflows)
  [ -f .github/dependabot.yml ] && files+=(.github/dependabot.yml)
  require_docker || return 1
  [ -n "${GITHUB_ACTIONS:-}" ] && format=github
  in_docker "$ZIZMOR_IMAGE" --offline --persona=regular --format="$format" "${files[@]}" || code=$?
  if [ "$code" -ge 11 ]; then
    echo "경고: zizmor 지적이 있다(종료 코드 $code, 14 는 high). 지금은 차단하지 않는다." >&2
  elif [ "$code" -ne 0 ]; then
    return "$code"
  fi
}

shellcheck_check() { require_docker && in_docker "$SHELLCHECK_IMAGE" scripts/*.sh deploy/host/*.sh; }
migration_guard_selftest() { bash scripts/test-check-migrations.sh; }
e2e_scan_selftest() { bash scripts/test-e2e-scan.sh; }
e2e_record_selftest() { bash scripts/test-e2e-record.sh; }
aws_probe_selftest() { bash scripts/test-aws-probe.sh; }
host_deploy_selftest() { bash scripts/test-host-deploy.sh; }
ssm_run_selftest() { bash scripts/test-ssm-run.sh; }
web_deploy_selftest() { bash scripts/test-web-deploy.sh; }
host_setup_selftest() { bash scripts/test-host-setup.sh; }
infra_outputs_selftest() { bash scripts/test-infra-outputs.sh; }
tf_selftest() { bash scripts/test-tf.sh; }
public_files_selftest() { bash scripts/test-check-public-files.sh; }
codeql_comment_selftest() { bash scripts/test-codeql-comment.sh; }
plan_guard_selftest() { node --test scripts/tf-plan-guard.test.mjs; }

# Terraform 은 scripts/tf-run.sh 가 고정 이미지(hashicorp/terraform:1.16.4)로 돌린다. infra.yml 도 같은 스크립트를 쓴다.
# .tf 가 있는 디렉터리(모듈·환경)를 모두 찾는다. tests/ 는 .tftest.hcl 이라 따로 잡히지 않는다.
infra_dirs() { find infra -name '*.tf' -not -path '*/.terraform/*' -exec dirname {} \; | sort -u; }
infra_fmt() { require_docker && bash scripts/tf-run.sh infra fmt -check -recursive -diff; }
# plan 이 자격증명으로 코드를 돌리기 전에 위험한 구성(provisioner·외부 모듈·외부 provider·비밀값 data)을 막는다.
infra_policy() { node scripts/tf-plan-guard.mjs --config infra; }
# backend 없이 init 해서 자격증명 없이 돈다. provider 는 잠금 파일에 있는 것만 받는다. test 는 mock provider 라 AWS 를 부르지 않는다.
infra_check() { # 디렉터리
  require_docker || return 1
  bash scripts/tf-run.sh "$1" init -backend=false -input=false -lockfile=readonly >/dev/null &&
    bash scripts/tf-run.sh "$1" validate &&
    bash scripts/tf-run.sh "$1" test
}

# GitHub·AWS 로 가면 안 되는 파일·값(.env·키·state·계정 ID 든 ARN·로그인 포털 주소 등)이 커밋에 있는지 본다.
# 범위는 gitleaks 와 같다(PR 은 그 PR 의 커밋, develop push·매주는 지금 파일 전체). Docker 없이 돈다.
public_files_check() {
  if [ "${GITLEAKS_FULL:-0}" = 1 ]; then
    bash scripts/check-public-files.sh
  else
    bash scripts/check-public-files.sh "$(git rev-parse "${GITLEAKS_BASE:-$(git merge-base origin/develop HEAD)}")"
  fi
}

# 기본은 origin/develop 에서 갈라진 뒤의 커밋만 본다. CI 는 PR 이면 GITLEAKS_BASE=HEAD^1(= PR 의 커밋),
# develop push·매주 실행이면 GITLEAKS_FULL=1(모든 브랜치의 전체 이력)로 부른다. 비밀 값은 출력에서 가린다(--redact).
gitleaks_check() {
  local range base common
  require_docker || return 1
  if [ "${GITLEAKS_FULL:-0}" = 1 ]; then
    range=--all
  else
    base=$(git rev-parse "${GITLEAKS_BASE:-$(git merge-base origin/develop HEAD)}")
    range="$base..$(git rev-parse HEAD)"
  fi
  # git worktree 에서는 .git 이 원래 레포의 .git 을 가리키는 파일이라, 컨테이너 안에서 이력을 못 읽고 "0 commits scanned" 로
  # 통과해 버린다. 공통 .git 이 있는 원래 레포를 올려 같은 커밋 범위를 검사한다. 범위는 여기서 SHA 로 풀어 넘긴다.
  common=$(git rev-parse --path-format=absolute --git-common-dir)
  (cd "$common/.." && in_docker "$GITLEAKS_IMAGE" git --redact --no-banner --log-opts="$range" /repo)
}

e2e_run() { require_docker && bash scripts/e2e.sh; }
docker_smoke() { require_docker && bash scripts/docker-smoke.sh; }

frontend() {
  warn_node_version
  run "frontend: npm ci" frontend_install
  run "frontend: lint" frontend_lint
  run "frontend: build (tsc 타입검사 포함)" frontend_build
  run "frontend: test + coverage" frontend_test
}

backend() {
  warn_java_version
  run "backend: gradlew build (spotless + test)" backend_build
  run "backend: 마이그레이션 가드" migration_guard
}

workflows() {
  run "workflows: actionlint" actionlint_check
  run "workflows: zizmor (경고만)" zizmor_check
  run "scripts: shellcheck" shellcheck_check
  run "scripts: 마이그레이션 가드 자체 검사" migration_guard_selftest
  run "scripts: 개인정보 마커 스캔 자체 검사" e2e_scan_selftest
  run "scripts: E2E 경고 기록 자체 검사" e2e_record_selftest
  run "scripts: AWS 권한 확인 자체 검사" aws_probe_selftest
  run "scripts: 서버 배포·백업·복구 확인·배포 묶음 자체 검사" host_deploy_selftest
  run "scripts: 서버 기본 설정 자체 검사" host_setup_selftest
  run "scripts: SSM 실행 자체 검사" ssm_run_selftest
  run "scripts: 인프라 값 읽기 자체 검사" infra_outputs_selftest
  run "scripts: 프론트 배포 자체 검사" web_deploy_selftest
  run "scripts: Terraform 실행·state 버킷 자체 검사" tf_selftest
  run "scripts: plan 가드 자체 검사" plan_guard_selftest
  run "scripts: 공개 파일 검사 자체 검사" public_files_selftest
  run "scripts: CodeQL 코멘트(포크 PR 찾기·고쳐 쓰기) 자체 검사" codeql_comment_selftest
}

infra() {
  local dir
  run "infra: terraform fmt" infra_fmt
  run "infra: 구성 검사(provisioner·외부 모듈·외부 provider·비밀값 data 금지)" infra_policy
  while read -r dir; do
    run "infra: $dir (init·validate·test)" infra_check "$dir"
  done < <(infra_dirs)
}

security() {
  run "security: 공개하면 안 되는 파일·값" public_files_check
  run "security: gitleaks (비밀 정보)" gitleaks_check
}

e2e() {
  warn_node_version
  warn_java_version
  run "e2e: 종단 테스트 + 개인정보 마커 스캔" e2e_run
}

# 함수 이름을 docker 로 하면 docker 명령을 가려 버린다.
image() {
  run "docker: 이미지 빌드 → root 아님 → compose 기동 → 요청 받음(csrf 200)" docker_smoke
}

case "${1:-all}" in
  frontend) frontend ;;
  backend) backend ;;
  workflows) workflows ;;
  infra) infra ;;
  security) security ;;
  e2e) e2e ;;
  docker) image ;;
  all)
    frontend
    backend
    workflows
    infra
    security
    ;;
  *)
    echo "사용법: bash scripts/verify.sh [frontend|backend|workflows|infra|security|e2e|docker|all]" >&2
    exit 2
    ;;
esac

echo
if [ "${#failed[@]}" -gt 0 ]; then
  printf '\033[31m실패 %d건:\033[0m\n' "${#failed[@]}"
  printf '  - %s\n' "${failed[@]}"
  exit 1
fi
printf '\033[32m전부 통과\033[0m\n'
