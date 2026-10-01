#!/usr/bin/env bash
# CodeQL 결과(SARIF)를 마크다운 표로 바꾼다. codeql.yml 의 Report job 이 실행 요약과 PR 코멘트에 쓴다.
#
#   bash scripts/codeql-summary.sh <SARIF 디렉터리 또는 파일>...
#
# 왜: CodeQL 경고 목록은 Security 탭 안쪽에 있고, 로그인·레포 권한이 없으면 아예 보이지 않는다.
#     PR 코멘트와 실행 요약은 PR 을 볼 수 있는 사람이면 누구나 보므로 검사한 규칙 수와 발견 위치를 여기에 남긴다.
#     0건이어도 0건이라고 적는다(돌았는지 안 돌았는지 구분되게).
set -euo pipefail

[ $# -gt 0 ] || { echo "SARIF 디렉터리나 파일을 넘긴다" >&2; exit 2; }
repo="${GITHUB_REPOSITORY:-}"
sha="${GITHUB_SHA:-}"

shopt -s nullglob
files=()
for target in "$@"; do
  if [ -d "$target" ]; then
    files+=("$target"/*.sarif)
  elif [ -f "$target" ]; then
    files+=("$target")
  fi
done

echo "## CodeQL 정적 분석 결과"
echo
# PR 은 머지 커밋을 분석하므로 링크는 GITHUB_SHA 로, 표시는 PR 의 head 커밋(HEAD_SHA)으로 한다.
shown="${HEAD_SHA:-$sha}"
if [ -n "$shown" ]; then
  echo "커밋 \`${shown:0:7}\` 기준"
  echo
fi

if [ ${#files[@]} -eq 0 ]; then
  echo "결과 파일이 없다. Analyze job 이 실패했는지 로그를 확인한다."
  exit 0
fi

for f in "${files[@]}"; do
  jq -r --arg repo "$repo" --arg sha "$sha" --arg name "$(basename "$f" .sarif)" '
    def rules($run): [($run.tool.driver.rules // [])[], ($run.tool.extensions // [])[].rules[]?];
    # 결과가 가리키는 규칙: toolComponent 가 있으면 그 확장의 rules, 없으면 driver 의 rules.
    def rule_of($run):
      (if .rule.toolComponent.index != null
         then $run.tool.extensions[.rule.toolComponent.index].rules[.rule.index]
         elif .rule.index != null then $run.tool.driver.rules[.rule.index]
         else null end) as $r
      | ($r // (.ruleId as $id | rules($run) | map(select(.id == $id)) | first) // {});
    def severity($r):
      ($r.properties["security-severity"] // "" | tonumber? ) as $s
      | if $s != null then
          (if $s >= 9 then "critical" elif $s >= 7 then "high" elif $s >= 4 then "medium" else "low" end)
        else ($r.properties["problem.severity"] // .level // "warning") end;
    def cell: gsub("\\[(?<t>[^\\]]*)\\]\\([0-9]+\\)"; "\(.t)") | gsub("\\|"; "\\|") | gsub("\n"; " ");

    [.runs[] as $run | $run.results[]? | select(.suppressions == null or (.suppressions | length) == 0)
      | rule_of($run) as $r
      | .locations[0].physicalLocation as $loc
      | {sev: severity($r),
         id: (.ruleId // $r.id),
         title: ($r.shortDescription.text // $r.name // ""),
         path: ($loc.artifactLocation.uri // "?"),
         line: ($loc.region.startLine // 1),
         msg: (.message.text // "")}] as $found
    | ([.runs[] as $run | rules($run)[]] | length) as $nrules
    | "### \({java: "백엔드 (Java)", javascript: "프론트 (JS/TS)"}[$name] // $name)",
      "",
      "검사 규칙 **\($nrules)개** · 발견 **\($found | length)건**",
      "",
      if ($found | length) == 0 then
        "발견된 문제가 없다."
      else
        "| 심각도 | 규칙 | 위치 | 내용 |",
        "|---|---|---|---|",
        ($found | sort_by({critical:0, high:1, error:1, medium:2, warning:2, low:3}[.sev] // 4)[]
          | (if $repo != "" and $sha != ""
               then "[\(.path):\(.line)](https://github.com/\($repo)/blob/\($sha)/\(.path)#L\(.line))"
               else "\(.path):\(.line)" end) as $where
          | "| \(.sev) | `\(.id)`<br>\(.title | cell) | \($where) | \(.msg | cell) |")
      end,
      ""
  ' "$f"
done

if [ -n "$repo" ]; then
  echo "경고 처리 이력(고침·무시)은 [Security → Code scanning](https://github.com/$repo/security/code-scanning) 에 있다(로그인한 레포 멤버만 보인다)."
fi
