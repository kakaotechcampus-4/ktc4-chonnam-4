#!/usr/bin/env bash
# 배포 묶음(deploy/compose.dev.yml + deploy/host)을 서버에서 돌릴 스크립트 하나로 만든다. release.yml 이 ssm-run.sh 로 보낸다.
#   IMAGE=<…/neuringo/backend:SHA> WEB_IMAGE=<…/neuringo/backend:web-SHA> RELEASE=<커밋 SHA> [NEURINGO_ENV=dev] \
#     [BACKUP_BUCKET=<버킷>] [MODE=develop|preview] [PREVIEW_PR=<번호> PREVIEW_BY=<아이디> PREVIEW_TTL=<초>] \
#     [END_PREVIEW=1] [FORCE=1] bash scripts/release-bundle.sh >remote.sh
#
# 서버에서 하는 일: 묶음을 $NEURINGO_HOME/releases/<SHA> 에 풀고(SHA256 확인) deploy.sh 를 돌린다.
# 성공하면 $NEURINGO_HOME/current 를 그 묶음으로 돌리고(매일 백업·상태 보기가 쓴다) 옛 묶음은 최근 5개만 남긴다.
# 묶음은 이 스크립트를 부른 체크아웃(develop)의 deploy/ 다. PR 미리보기여도 서버 스크립트는 develop 것이다.
# 만든 스크립트에는 이미지 주소(계정 ID 포함)가 들어 있다. 로그에 찍지 말고 파일로만 넘긴다(SSM 기록에는 남는다).
set -euo pipefail

: "${IMAGE:?IMAGE 가 필요하다}"
: "${WEB_IMAGE:?WEB_IMAGE 가 필요하다}"
: "${RELEASE:?RELEASE(커밋 SHA)가 필요하다}"
ENV_NAME=${NEURINGO_ENV:-dev}
BUCKET=${BACKUP_BUCKET:-}
MODE=${MODE:-develop}
PR=${PREVIEW_PR:-}
BY=${PREVIEW_BY:-}
TTL=${PREVIEW_TTL:-3600}
END=${END_PREVIEW:-0}
FORCE=${FORCE:-0}

# 만든 스크립트 안에서 작은따옴표로 감싸므로 값의 모양을 먼저 확인한다.
image_re='^[a-z0-9.-]+(:[0-9]+)?/[a-z0-9._/-]+:[A-Za-z0-9._-]+$'
[[ "$RELEASE" =~ ^[0-9a-f]{7,40}$ ]] || { echo "RELEASE 는 커밋 SHA 여야 한다" >&2; exit 1; }
[[ "$ENV_NAME" =~ ^(dev|prod)$ ]] || { echo "NEURINGO_ENV 는 dev 또는 prod" >&2; exit 1; }
[[ "$IMAGE" =~ $image_re ]] || { echo "IMAGE 모양이 틀렸다" >&2; exit 1; }
[[ "$WEB_IMAGE" =~ $image_re ]] || { echo "WEB_IMAGE 모양이 틀렸다" >&2; exit 1; }
[[ -z "$BUCKET" || "$BUCKET" =~ ^[a-z0-9.-]{3,63}$ ]] || { echo "BACKUP_BUCKET 모양이 틀렸다" >&2; exit 1; }
[[ "$MODE" =~ ^(develop|preview)$ ]] || { echo "MODE 는 develop 또는 preview" >&2; exit 1; }
[[ -z "$PR" || "$PR" =~ ^[0-9]{1,6}$ ]] || { echo "PREVIEW_PR 은 숫자" >&2; exit 1; }
[[ -z "$BY" || "$BY" =~ ^[A-Za-z0-9-]{1,39}$ ]] || { echo "PREVIEW_BY 모양이 틀렸다" >&2; exit 1; }
[[ "$TTL" =~ ^[0-9]{2,6}$ ]] || { echo "PREVIEW_TTL 은 초" >&2; exit 1; }
[[ "$END" =~ ^[01]$ && "$FORCE" =~ ^[01]$ ]] || { echo "END_PREVIEW·FORCE 는 0 또는 1" >&2; exit 1; }

cd "$(git rev-parse --show-toplevel)"
archive=$(mktemp)
trap 'rm -f "$archive"' EXIT
tar -czf "$archive" deploy/compose.dev.yml deploy/host
sum=$(sha256sum "$archive" | cut -d' ' -f1)
bundle=$(base64 -w0 <"$archive" 2>/dev/null || base64 <"$archive" | tr -d '\n')

cat <<EOF
#!/usr/bin/env bash
# release-bundle.sh 가 만든 서버 스크립트 (release $RELEASE, $MODE)
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
IMAGE='$IMAGE' WEB_IMAGE='$WEB_IMAGE' RELEASE='$RELEASE' NEURINGO_ENV='$ENV_NAME' BACKUP_BUCKET='$BUCKET' \\
  MODE='$MODE' PREVIEW_PR='$PR' PREVIEW_BY='$BY' PREVIEW_TTL='$TTL' END_PREVIEW='$END' FORCE='$FORCE' \\
  NEURINGO_HOME="\$home" bash "\$dir/deploy/host/deploy.sh" || rc=\$?
if [ "\$rc" = 0 ]; then
  # 같은 SHA 를 다시 배포하면 폴더 시각이 옛날 그대로라 아래 정리에서 지워질 수 있다. 지금 시각으로 바꾼다.
  touch "\$dir"
  ln -sfn "\$dir" "\$home/current.tmp"
  mv -Tf "\$home/current.tmp" "\$home/current"
fi
find "\$home/releases" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' | sort -rn | tail -n +6 | cut -d' ' -f2- |
  while IFS= read -r old; do [ "\$old" -ef "\$home/current" ] || rm -rf "\$old"; done
exit \$rc
EOF
