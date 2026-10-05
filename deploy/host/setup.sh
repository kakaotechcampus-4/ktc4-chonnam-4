#!/usr/bin/env bash
# 팀 서버(EC2, Ubuntu 24.04) 기본 설정. host-setup.yml 이 SSM 으로 root 로 돌린다. 여러 번 돌려도 결과가 같다.
#   bash deploy/host/setup.sh                 # 서버에서(root)
#   SETUP_DRY_RUN=1 bash deploy/host/setup.sh  # 무엇을 할지만 보여 준다(아무것도 바꾸지 않는다)
#
# - Docker·Compose 플러그인(Ubuntu 패키지 docker.io·docker-compose-v2), AWS CLI(snap)
# - swap 2 GiB: 메모리 4 GiB 를 backend·postgres 가 나눠 쓰다 순간적으로 넘칠 때 앱이 죽지 않게
# - journald 로그 상한 500 MB, 보안 업데이트 자동 설치(unattended-upgrades)
# - /opt/neuringo/{releases,state,backups,logs} (root 만, 700)
# 이미 된 것은 건너뛰고 "이미 있음" 이라고 적는다. 22번·SSH 키는 건드리지 않는다.
set -euo pipefail

DRY=${SETUP_DRY_RUN:-0}
ETC=${SETUP_ETC:-/etc}
HOME_DIR=${NEURINGO_HOME:-/opt/neuringo}
SWAP_FILE=${SETUP_SWAP_FILE:-/swapfile}
SWAP_SIZE=${SETUP_SWAP_SIZE:-2G}

say() { echo "[setup] $*"; }
act() { # 명령... — 마른 실행이면 보여 주기만 한다
  if [ "$DRY" = 1 ]; then
    echo "  + $*"
  else
    "$@"
  fi
}
write_file() { # 경로 내용
  if [ "$DRY" = 1 ]; then
    echo "  + $1 에 쓴다"
  else
    mkdir -p "$(dirname "$1")"
    printf '%s\n' "$2" >"$1"
  fi
}

if [ "$DRY" != 1 ] && [ "$(id -u)" != 0 ]; then
  say "root 로 돌려야 한다(SSM 은 root 로 돈다). 미리 보려면 SETUP_DRY_RUN=1"
  exit 1
fi

if [ -r "$ETC/os-release" ] && ! grep -q 'VERSION_ID="24.04"' "$ETC/os-release"; then
  say "경고: Ubuntu 24.04 가 아니다. 패키지 이름이 다를 수 있다"
fi

apt_updated=0
apt_install() { # 패키지...
  if [ "$apt_updated" = 0 ]; then
    act env DEBIAN_FRONTEND=noninteractive apt-get update -q
    apt_updated=1
  fi
  act env DEBIAN_FRONTEND=noninteractive apt-get install -y -q "$@"
}

# 1. Docker·Compose
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  say "Docker·Compose: 이미 있음"
else
  say "Docker·Compose: 설치"
  apt_install docker.io docker-compose-v2
  act systemctl enable --now docker
fi

# 2. AWS CLI (Parameter Store·ECR·S3 를 서버 역할로 부른다)
if command -v aws >/dev/null 2>&1; then
  say "AWS CLI: 이미 있음"
else
  say "AWS CLI: 설치(snap)"
  act snap install aws-cli --classic
fi

# 3. swap
if [ -n "$(swapon --show --noheadings 2>/dev/null || true)" ]; then
  say "swap: 이미 있음"
else
  say "swap: $SWAP_SIZE 만듦"
  act fallocate -l "$SWAP_SIZE" "$SWAP_FILE"
  act chmod 600 "$SWAP_FILE"
  act mkswap "$SWAP_FILE"
  act swapon "$SWAP_FILE"
  if ! grep -qs "^$SWAP_FILE " "$ETC/fstab"; then
    if [ "$DRY" = 1 ]; then
      echo "  + $ETC/fstab 에 $SWAP_FILE 추가"
    else
      echo "$SWAP_FILE none swap sw 0 0" >>"$ETC/fstab"
    fi
  fi
fi
if [ ! -f "$ETC/sysctl.d/99-neuringo.conf" ]; then
  say "swappiness 10 (메모리가 남을 때는 swap 을 덜 쓴다)"
  write_file "$ETC/sysctl.d/99-neuringo.conf" "vm.swappiness=10"
  act sysctl -q -p "$ETC/sysctl.d/99-neuringo.conf"
fi

# 4. journald 로그 상한
if [ -f "$ETC/systemd/journald.conf.d/neuringo.conf" ]; then
  say "journald 상한: 이미 있음"
else
  say "journald 상한 500 MB"
  write_file "$ETC/systemd/journald.conf.d/neuringo.conf" "[Journal]
SystemMaxUse=500M"
  act systemctl restart systemd-journald
fi

# 5. 보안 업데이트 자동 설치
if dpkg -s unattended-upgrades >/dev/null 2>&1; then
  say "보안 업데이트 자동 설치: 이미 있음"
else
  say "보안 업데이트 자동 설치: 설치"
  apt_install unattended-upgrades
fi

# 6. 배포 폴더
for sub in releases state backups logs; do
  if [ -d "$HOME_DIR/$sub" ]; then
    continue
  fi
  act mkdir -p "$HOME_DIR/$sub"
  act chmod 700 "$HOME_DIR/$sub"
done
say "배포 폴더: $HOME_DIR/{releases,state,backups,logs}"

say "끝"
echo "SETUP_RESULT=ok"
