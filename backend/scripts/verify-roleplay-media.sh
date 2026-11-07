#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
media_tmp="$(mktemp -d /tmp/neuringo-media-tests.XXXXXX)"
media_container="neuringo-media-tests-$$"
cleanup() {
  docker rm -f "$media_container" >/dev/null 2>&1 || true
  rm -rf "$media_tmp"
}
trap cleanup EXIT
docker build -q -t neuringo-media-validation:local "$repo_root/backend/src/test/resources/roleplay-media" >/dev/null
docker run -d --name "$media_container" --network none --read-only --cap-drop ALL \
  --security-opt no-new-privileges --user "$(id -u):$(id -g)" \
  -v "$media_tmp:$media_tmp" neuringo-media-validation:local >/dev/null
for tool in ffmpeg ffprobe; do
  cat > "$media_tmp/$tool" <<WRAPPER
#!/bin/sh
exec docker exec '$media_container' /usr/bin/$tool "\$@"
WRAPPER
  chmod 700 "$media_tmp/$tool"
done
export ROLEPLAY_MEDIA_REAL_TOOLS=true
export ROLEPLAY_MEDIA_TEST_DIRECTORY="$media_tmp"
export ROLEPLAY_MEDIA_TEST_FFMPEG="$media_tmp/ffmpeg"
export ROLEPLAY_MEDIA_TEST_FFPROBE="$media_tmp/ffprobe"
cd "$repo_root"
if [[ "${ROLEPLAY_MEDIA_TEST_ALL:-false}" == "true" ]]; then
  ./backend/gradlew -p backend "$@" check
else
  ./backend/gradlew -p backend "$@" test --tests '*FfmpegRoleplayAudioInspector*'
fi
