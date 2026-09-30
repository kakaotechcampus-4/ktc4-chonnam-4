#!/usr/bin/env bash
# e2e-record.sh 자체 검사. 실패·재시도 끝에 통과한 테스트만 댓글에 들어가고, 모두 통과하면 아무것도 남기지 않아야 한다.
#   bash scripts/test-e2e-record.sh   (verify.sh workflows 에 포함, Node 필요)
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

record() { # 리포트 E2E_OUTCOME
  E2E_RECORD_DRY_RUN=1 E2E_REPORT="$1" E2E_OUTCOME="$2" E2E_WHERE="PR #99" E2E_SHA=0123456789abcdef \
    E2E_RUN_URL=https://example.invalid/run bash scripts/e2e-record.sh
}

has() { grep -qF -- "$2" <<<"$1"; }
lacks() { ! has "$@"; }

echo "e2e-record.sh 자체 검사"

# 실제 Playwright JSON 리포트와 같은 모양(파일 suite 안에 describe 없이 spec).
cat >"$tmp/mixed.json" <<'EOF'
{
  "suites": [
    {
      "title": "instructor-failures.spec.ts",
      "file": "instructor-failures.spec.ts",
      "specs": [
        {
          "title": "서버가 500 을 주면 오류 문구를 보여 준다",
          "file": "instructor-failures.spec.ts",
          "tests": [
            {
              "projectName": "chromium",
              "status": "unexpected",
              "results": [
                { "status": "failed", "error": { "message": "\u001b[31mError: expect(locator).toBeVisible() failed\u001b[39m\n\nLocator: getByText('서버')" } },
                { "status": "failed", "error": { "message": "Error: expect(locator).toBeVisible() failed" } }
              ]
            }
          ]
        },
        {
          "title": "없는 학급 주소 | 찾을 수 없음",
          "file": "instructor-failures.spec.ts",
          "tests": [{ "projectName": "chromium", "status": "expected", "results": [{ "status": "passed" }] }]
        }
      ],
      "suites": [
        {
          "title": "아동",
          "file": "child-flow.spec.ts",
          "specs": [
            {
              "title": "사용 종료하면 코드 입력 화면으로 돌아간다",
              "file": "child-flow.spec.ts",
              "tests": [
                {
                  "projectName": "tablet",
                  "status": "flaky",
                  "results": [
                    { "status": "timedOut", "errors": [{ "message": "Test timeout of 30000ms exceeded." }] },
                    { "status": "passed" }
                  ]
                }
              ]
            }
          ]
        }
      ]
    }
  ],
  "errors": []
}
EOF
out=$(record "$tmp/mixed.json" failure)
check "실패한 테스트가 들어간다" has "$out" "| 실패 | instructor-failures.spec.ts › 서버가 500 을 주면 오류 문구를 보여 준다 | chromium |"
check "재시도 끝에 통과한 테스트도 들어간다(중첩 suite)" has "$out" "| 재시도 후 통과 | child-flow.spec.ts › 사용 종료하면 코드 입력 화면으로 돌아간다 | tablet |"
check "통과한 테스트는 들어가지 않는다" lacks "$out" "없는 학급 주소"
check "첫 오류 메시지의 첫 줄이 색 코드 없이 들어간다" has "$out" "  Error: expect(locator).toBeVisible() failed"
check "ANSI 색 코드가 남지 않는다" lacks "$out" $'\e['
check "어디서(PR 번호)·어느 커밋인지 적는다" has "$out" "PR #99 · \`0123456\`"
check "원인을 적을 자리를 안내한다" has "$out" "**원인:**"

# 테스트 이름에 역슬래시·| 가 있어도 표 칸이 깨지지 않아야 한다(역슬래시를 먼저 이스케이프).
cat >"$tmp/escape.json" <<'EOF'
{ "suites": [{ "title": "a.spec.ts", "file": "a.spec.ts", "specs": [{ "title": "경로 C:\\ 와 a|b", "file": "a.spec.ts", "tests": [{ "projectName": "chromium", "status": "unexpected", "results": [{ "status": "failed" }] }] }] }], "errors": [] }
EOF
out=$(record "$tmp/escape.json" failure)
check "표 칸의 역슬래시와 | 를 이스케이프한다" has "$out" '| 실패 | a.spec.ts › 경로 C:\\ 와 a\|b | chromium |'

cat >"$tmp/pass.json" <<'EOF'
{ "suites": [{ "title": "a.spec.ts", "file": "a.spec.ts", "specs": [{ "title": "통과", "file": "a.spec.ts", "tests": [{ "projectName": "chromium", "status": "expected", "results": [{ "status": "passed" }] }] }] }], "errors": [] }
EOF
out=$(record "$tmp/pass.json" success)
check "모두 통과하면 기록하지 않는다" has "$out" "기록할 E2E 경고가 없다"

out=$(record "$tmp/missing.json" failure)
check "리포트 없이 E2E 가 실패하면(서버 기동 실패 등) 그 사실을 남긴다" has "$out" "테스트가 시작되기 전에 실패했습니다"

out=$(record "$tmp/missing.json" skipped)
check "E2E 가 돌지 않았으면 기록하지 않는다" has "$out" "기록할 E2E 경고가 없다"

if [ "$failures" -gt 0 ]; then
  echo "자체 검사 실패 ${failures}건"
  exit 1
fi
echo "e2e-record.sh 자체 검사: 전부 통과"
