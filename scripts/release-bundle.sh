#!/usr/bin/env bash
# 배포 묶음(deploy/compose.dev.yml + deploy/host)을 서버에서 돌릴 스크립트 하나로 만든다. deploy.yml 이 ssm-run.sh 로 보낸다.
#   IMAGE=<…/neuringo/backend:SHA> RELEASE=<커밋 SHA> [NEURINGO_ENV=dev] [BACKUP_BUCKET=<버킷>] \
#     bash scripts/release-bundle.sh >remote.sh
#
# 서버에서 하는 일: 묶음을 $NEURINGO_HOME/releases/<SHA> 에 풀고(SHA256 확인) deploy.sh 를 돌린다.
# 성공하면 $NEURINGO_HOME/current 를 그 묶음으로 돌리고(매일 백업이 쓴다) 옛 묶음은 최근 5개만 남긴다.
# 만든 스크립트에는 이미지 주소(계정 ID 포함)가 들어 있다. 로그에 찍지 말고 파일로만 넘긴다(SSM 기록에는 남는다).
set -euo pipefail

: "${IMAGE:?IMAGE 가 필요하다}"
: "${RELEASE:?RELEASE(커밋 SHA)가 필요하다}"
ENV_NAME=${NEURINGO_ENV:-dev}
BUCKET=${BACKUP_BUCKET:-}

# 만든 스크립트 안에서 작은따옴표로 감싸므로 값의 모양을 먼저 확인한다.
[[ "$RELEASE" =~ ^[0-9a-f]{7,40}$ ]] || { echo "RELEASE 는 커밋 SHA 여야 한다" >&2; exit 1; }
[[ "$ENV_NAME" =~ ^(dev|prod)$ ]] || { echo "NEURINGO_ENV 는 dev 또는 prod" >&2; exit 1; }
[[ "$IMAGE" =~ ^[a-z0-9.-]+(:[0-9]+)?/[a-z0-9._/-]+:[A-Za-z0-9._-]+$ ]] || { echo "IMAGE 모양이 틀렸다" >&2; exit 1; }
[[ -z "$BUCKET" || "$BUCKET" =~ ^[a-z0-9.-]{3,63}$ ]] || { echo "BACKUP_BUCKET 모양이 틀렸다" >&2; exit 1; }

cd "$(git rev-parse --show-toplevel)"
archive=$(mktemp)
trap 'rm -f "$archive"' EXIT
tar -czf "$archive" deploy/compose.dev.yml deploy/host
sum=$(sha256sum "$archive" | cut -d' ' -f1)
bundle=$(base64 -w0 <"$archive" 2>/dev/null || base64 <"$archive" | tr -d '\n')

cat <<EOF
#!/usr/bin/env bash
# release-bundle.sh 가 만든 서버 스크립트 (release $RELEASE)
set -euo pipefail
home=\${NEURINGO_HOME:-/opt/neuringo}
dir="\$home/releases/$RELEASE"
umask 077
mkdir -p "\$dir"
echo '$bundle' | base64 -d >"\$dir/bundle.tgz"
if ! echo "$sum  \$dir/bundle.tgz" | sha256sum -c --quiet - >/dev/null 2>&1; then
  echo "[release] 배포 묶음이 깨졌다(SHA256 불일치)"
  exit 1
fi
tar -xzf "\$dir/bundle.tgz" -C "\$dir" --no-same-owner
rc=0
IMAGE='$IMAGE' NEURINGO_ENV='$ENV_NAME' BACKUP_BUCKET='$BUCKET' NEURINGO_HOME="\$home" \\
  bash "\$dir/deploy/host/deploy.sh" || rc=\$?
if [ "\$rc" = 0 ]; then
  # 같은 SHA 를 다시 배포하면 폴더 시각이 옛날 그대로라 아래 정리에서 지워질 수 있다. 지금 시각으로 바꾼다.
  touch "\$dir"
  ln -sfn "\$dir" "\$home/current.tmp"
  mv -Tf "\$home/current.tmp" "\$home/current"
  find "\$home/releases" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' | sort -rn | tail -n +6 | cut -d' ' -f2- |
    while IFS= read -r old; do rm -rf "\$old"; done
fi
exit \$rc
EOF
