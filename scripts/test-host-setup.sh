#!/usr/bin/env bash
# deploy/host/setup.sh 자체 검사. 마른 실행(SETUP_DRY_RUN=1)으로 "새 서버"와 "이미 설정된 서버"를 흉내 낸다.
# 새 서버면 설치·설정을 계획하고, 이미 된 서버면 아무것도 하지 않는지 본다(여러 번 돌려도 결과가 같아야 한다).
#   bash scripts/test-host-setup.sh   (verify.sh workflows 에 포함)
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

# 새 서버: docker·aws·swap 이 없고 /etc 도 비어 있다. 실제 명령이 불리면 표시를 남긴다.
mkdir -p "$tmp/fresh/bin" "$tmp/fresh/etc" "$tmp/configured/bin" "$tmp/configured/etc/systemd/journald.conf.d" "$tmp/configured/etc/sysctl.d"
for cmd in apt-get systemctl snap fallocate mkswap sysctl; do
  printf '#!/usr/bin/env bash\necho "%s $*" >>"%s/real-calls"\n' "$cmd" "$tmp" >"$tmp/fresh/bin/$cmd"
  cp "$tmp/fresh/bin/$cmd" "$tmp/configured/bin/$cmd"
done
printf '#!/usr/bin/env bash\nexit 0\n' >"$tmp/fresh/bin/swapon"
printf '#!/usr/bin/env bash\nexit 1\n' >"$tmp/fresh/bin/dpkg"
printf 'VERSION_ID="24.04"\n' >"$tmp/fresh/etc/os-release"
# 이미 된 서버: docker compose·aws·swap·dpkg 가 있고 설정 파일도 있다.
printf '#!/usr/bin/env bash\nexit 0\n' >"$tmp/configured/bin/docker"
printf '#!/usr/bin/env bash\nexit 0\n' >"$tmp/configured/bin/aws"
printf '#!/usr/bin/env bash\necho "/swapfile file 2G 0B -2"\n' >"$tmp/configured/bin/swapon"
printf '#!/usr/bin/env bash\nexit 0\n' >"$tmp/configured/bin/dpkg"
printf 'VERSION_ID="24.04"\n' >"$tmp/configured/etc/os-release"
echo "vm.swappiness=10" >"$tmp/configured/etc/sysctl.d/99-neuringo.conf"
printf '[Journal]\nSystemMaxUse=500M\n' >"$tmp/configured/etc/systemd/journald.conf.d/neuringo.conf"
mkdir -p "$tmp/configured/home/releases" "$tmp/configured/home/state" "$tmp/configured/home/backups" "$tmp/configured/home/logs"
chmod +x "$tmp"/fresh/bin/* "$tmp"/configured/bin/*

run() { # 상황(fresh|configured)
  code=0
  # 새 서버에 docker·aws 가 없게 하려고 PATH 를 좁힌다(기본 명령은 /usr/bin·/bin 에서).
  out=$(PATH="$tmp/$1/bin:/usr/bin:/bin" SETUP_DRY_RUN=1 SETUP_ETC="$tmp/$1/etc" NEURINGO_HOME="$tmp/$1/home" \
    SETUP_SWAP_FILE="$tmp/$1/swapfile" bash deploy/host/setup.sh 2>&1) || code=$?
}

echo "setup.sh 자체 검사"

skip_docker=0
if [ -x /usr/bin/docker ] || [ -x /bin/docker ]; then
  echo "  (이 컴퓨터의 /usr/bin 에 docker 가 있어 '새 서버' 경우의 Docker 설치 계획은 확인하지 않는다)"
  skip_docker=1
fi

run fresh
check "새 서버: 마른 실행은 0 으로 끝난다" same "$code" 0
if [ "$skip_docker" = 0 ]; then
  check "새 서버: Docker·Compose 를 설치한다" has "$out" "apt-get install -y -q docker.io docker-compose-v2"
fi
check "새 서버: swap 을 만든다" has "$out" "fallocate -l 2G"
check "새 서버: swappiness 를 정한다" has "$out" "99-neuringo.conf 에 쓴다"
check "새 서버: journald 상한을 둔다" has "$out" "journald.conf.d/neuringo.conf 에 쓴다"
check "새 서버: 보안 업데이트를 설치한다" has "$out" "unattended-upgrades"
check "새 서버: 배포 폴더를 700 으로 만든다" has "$out" "chmod 700 $tmp/fresh/home/state"
check "마른 실행은 실제로 아무것도 부르지 않는다" bash -c "[ ! -f '$tmp/real-calls' ]"
check "마른 실행은 파일을 만들지 않는다" bash -c "[ ! -e '$tmp/fresh/etc/sysctl.d/99-neuringo.conf' ] && [ ! -e '$tmp/fresh/home' ]"

run configured
check "설정된 서버: 0 으로 끝난다" same "$code" 0
check "설정된 서버: 설치·쓰기 계획이 없다" lacks "$out" "  + "
check "설정된 서버: 이미 있다고 적는다" has "$out" "Docker·Compose: 이미 있음"

code=0
out=$(SETUP_DRY_RUN=0 bash -c '[ "$(id -u)" = 0 ] && exit 0; bash deploy/host/setup.sh' 2>&1) || code=$?
if [ "$(id -u)" != 0 ]; then
  check "root 가 아니면 실제 실행을 거절한다" same "$code" 1
fi

if [ "$failures" -gt 0 ]; then
  echo "setup.sh 자체 검사 실패: $failures 건" >&2
  exit 1
fi
echo "setup.sh 자체 검사 통과"
