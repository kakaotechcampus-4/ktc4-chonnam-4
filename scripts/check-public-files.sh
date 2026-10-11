#!/usr/bin/env bash
# 공개 레포(GitHub)에 올라가면 안 되는 파일·값이 커밋에 들어 있는지 본다. gitleaks(비밀 문자열)를 보완한다.
#   bash scripts/check-public-files.sh           # 지금 파일 전체(추적 중 + 아직 추가 안 한 새 파일, .gitignore 제외)
#   bash scripts/check-public-files.sh <기준>    # 위 + 기준 뒤 커밋에 한 번이라도 들어온 파일 이름·추가된 줄
#                                                #   (뒤 커밋에서 지워도 이력에 남아 공개되므로 같이 본다)
# 커밋하기 전에 돌리면 올리기 전에 잡는다. CI(Security)는 PR 커밋 범위로 돌려 머지를 막는다.
#
# AWS 로 가는 것(백엔드 이미지·서버 배포 묶음·프론트 dist)은 전부 CI 가 깨끗한 checkout 에서 만든다.
# 그래서 커밋에 없는 파일은 AWS 로도 못 가고, 여기서 막으면 두 곳을 같이 막는다(docs/cd-architecture.md 8절).
#
# 막는 것
#   이름: .env·키·인증서·Terraform state/plan/변수 파일·자격증명 폴더·DB 덤프·로컬 DB·로컬 AI 설정
#   내용: 계정 ID 가 든 ARN·ECR 주소, EC2 공인 주소, CloudFront 테스트 주소, AWS 로그인 포털 주소, 운영진 노션 문서 링크
#   허용: 이름 끝이 .example·.sample·.template 인 견본, AWS 문서 예시 계정 123456789012·예시 주소 d111111abcdef8, 문서용 IP 대역
#         (192.0.2·198.51.100·203.0.113 — 자체 검사의 가짜 값은 이 대역만 쓴다)
# 걸린 곳은 파일·줄·규칙만 찍는다. 값은 찍지 않는다(공개 로그).
set -euo pipefail

base=${1:-}
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

found=0
report() { # 위치 규칙
  echo "  ✗ $1 — $2"
  found=$((found + 1))
}

# ── 이름 ─────────────────────────────────────────────────────
# 규칙<TAB>설명. 경로 전체에 대소문자 구분 없이 맞춘다.
NAME_RULES=$(
  cat <<'EOF'
(^|/)\.env(rc)?(\.[^/]+)?$|\.env(\.[^/]+)?$	환경 파일(.env) — 견본은 .env.example 로
\.(pem|key|p12|pfx|jks|keystore|ppk|ovpn|kdbx)$	키·인증서·자격증명 파일
(^|/)id_(rsa|dsa|ecdsa|ed25519)(\.pub)?$	SSH 키
\.tfstate(\.|$)|(^|/)\.terraform\.tfstate\.lock\.info$	Terraform state(리소스 ID·주소가 들어 있다)
\.tfplan$|(^|/)tfplan$	Terraform plan 파일(값이 그대로 들어 있다)
\.tfvars(\.json)?$	Terraform 변수 파일 — 값은 secret·변수로 넘긴다
(^|/)\.terraform/	Terraform 캐시(.terraform/)
(^|/)(\.aws|\.ssh)/|(^|/)\.docker/config\.json$|(^|/)\.(netrc|git-credentials|npmrc|pypirc)$	자격증명이 들어가는 파일·폴더
application-[^/]*secret[^/]*\.ya?ml$	Spring 비밀 설정
\.(dump|bak)$|\.sql\.gz$	DB 덤프·백업(개인정보가 들어 있다)
\.(sqlite3?|db)$	로컬 DB 파일
(^|/)\.claude/	로컬 AI 도구 설정·메모
(^|/)crash(\.[^/]+)?\.log$	Terraform 충돌 로그(값이 들어 있다)
EOF
)
ALLOWED_NAME='\.(example|sample|template)$'

paths=$(git ls-files --cached --others --exclude-standard)
if [ -n "$base" ]; then
  paths=$(printf '%s\n%s\n' "$paths" "$(git log --format= --name-only "$base..HEAD")")
fi
paths=$(printf '%s\n' "$paths" | sed '/^$/d' | sort -u)

while IFS=$'\t' read -r rule why; do
  [ -n "$rule" ] || continue
  while IFS= read -r path; do
    [ -n "$path" ] || continue
    report "$path" "$why"
  done < <(printf '%s\n' "$paths" | grep -iE -- "$rule" | grep -viE -- "$ALLOWED_NAME" || true)
done <<<"$NAME_RULES"

# ── 내용 ─────────────────────────────────────────────────────
# 규칙<TAB>설명<TAB>허용(맞은 문자열이 이것과 맞으면 통과, 없으면 -)
CONTENT_RULES=$(
  cat <<'EOF'
arn:aws[a-z-]*:[a-z0-9-]*:[a-z0-9-]*:[0-9]{12}:	계정 ID 가 든 ARN	:(123456789012|000000000000):
[0-9]{12}\.dkr\.ecr\.	계정 ID 가 든 ECR 주소	^(123456789012|000000000000)\.
ec2-[0-9]{1,3}-[0-9]{1,3}-[0-9]{1,3}-[0-9]{1,3}\.	EC2 공인 주소	^ec2-(192-0-2|198-51-100|203-0-113)-
d[a-z0-9]{12,13}\.cloudfront\.net	CloudFront 테스트 주소(외부인 가입·남용을 부르지 않게)	^d(111111abcdef8|1abcdefghijkl)\.
[a-z0-9-]+\.awsapps\.com|signin\.aws/platform|eli\.so/	AWS 로그인 포털 주소	-
elice[-]track	운영진 노션 문서 링크(공개 레포에 넣지 않는다)	-
EOF
)

# 지금 파일 + (기준이 있으면) 이력에서 추가된 줄. "파일<TAB>줄번호<TAB>내용" 으로 모은다.
lines_now() { git grep -I -n --untracked -E -e "$1" -- . | sed -E 's/^([^:]*):([0-9]+):/\1\t\2\t/' || true; }
lines_added() {
  git log -p --unified=0 --format= "$base..HEAD" | awk '
    /^\+\+\+ / { file = substr($0, 7); next }
    /^@@/ { split($3, a, ","); line = substr(a[1], 2) + 0; next }
    /^\+/ { print file "\t" line "\t(이력) " substr($0, 2); line++ }' | grep -E -- "$1" || true
}

while IFS=$'\t' read -r rule why allow; do
  [ -n "$rule" ] || continue
  {
    lines_now "$rule"
    if [ -n "$base" ]; then lines_added "$rule"; fi
  } | while IFS=$'\t' read -r file line text; do
    # 맞은 문자열마다 허용 목록과 견준다. 하나라도 허용 밖이면 걸린다.
    bad=$(grep -oE -- "$rule" <<<"$text" | { if [ "$allow" = - ]; then cat; else grep -vE -- "$allow" || true; fi; })
    [ -n "$bad" ] || continue
    case $text in "(이력) "*) where="$file:$line(이력)" ;; *) where="$file:$line" ;; esac
    echo "$where	$why"
  done
done <<<"$CONTENT_RULES" >"${TMPDIR:-/tmp}/public-files.$$"

while IFS=$'\t' read -r where why; do
  [ -n "$where" ] || continue
  report "$where" "$why"
done <"${TMPDIR:-/tmp}/public-files.$$"
rm -f "${TMPDIR:-/tmp}/public-files.$$"

if [ "$found" -gt 0 ]; then
  echo "공개하면 안 되는 파일·값 $found 건. 커밋에서 빼고(git rm --cached), 이미 푸시했으면 키·비밀번호는 폐기(rotate)한다." >&2
  echo "견본이면 이름을 .example 로, 자체 검사의 가짜 값이면 예시 계정 123456789012·문서용 IP 대역을 쓴다." >&2
  exit 1
fi
echo "공개하면 안 되는 파일·값 없음"
