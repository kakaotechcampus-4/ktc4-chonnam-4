// Terraform plan(JSON)을 읽어 바뀌는 리소스를 표로 요약하고, 비용·안전 가드에 걸리면 실패한다. infra.yml 이 부른다.
// plan 원문은 공개 로그에 찍지 않는다(서버 주소·버킷 이름 같은 값이 들어 있다). 여기서는 주소·동작만 적는다.
//
//   terraform show -json tfplan >plan.json && node scripts/tf-plan-guard.mjs plan.json
//   node scripts/tf-plan-guard.mjs --config infra   # 자격증명 없이 .tf 를 읽어 위험한 구성을 막는다(verify.sh infra)
//
// plan 에서 막는 것(docs/cd-architecture.md 7·8절):
//   - 허용 목록 밖 리소스(NAT Gateway·ALB·EC2·RDS 등 — 비싸거나 운영진이 막아 둔 것)
//   - IAM 역할은 Lambda 만 맡을 수 있고(운영진 경로 /ktc/ 금지), 정책에 전체(*)·IAM·서버 만들기·지우기·켜기 권한이 없어야 한다.
//     Lambda 는 AWS 서비스(EventBridge 등) 하나만 부를 수 있고, 메모리 512 MB·900초 이하
//   - 상태가 있는 리소스(버킷·ECR·배포·비밀값)와 공개 차단을 지우거나 다시 만들기 — 정말 지울 때만 TF_ALLOW_DESTROY=1
//   - 보안 그룹 인바운드를 CIDR 로 여는 것(접두사 목록·보안 그룹 참조만 허용), 22번·모든 포트(ip_protocol -1)
//   - 버킷 공개 차단을 하나라도 끄는 것, 누구에게나(Principal "*") 허용하는 버킷 정책
//   - CloudFront 가장 비싼 요금 등급(PriceClass_All)
// 구성(--config)에서 막는 것. plan 자체가 자격증명으로 코드를 돌리므로, plan 전에 자격증명 없는 검사 잡에서 본다:
//   - provisioner, external·http 데이터 소스, null_resource·terraform_data(임의 명령·외부 호출)
//   - 레포 밖 모듈(인터넷 주소·git), aws·random·archive(Lambda 코드 묶기, 로컬에서만 돈다) 밖 provider
//   - data "aws_ssm_parameter"(읽은 비밀값이 state 에 평문으로 남는다)
import { readdirSync, readFileSync, statSync } from "node:fs"
import { join } from "node:path"
import { pathToFileURL } from "node:url"

export const ALLOWED_TYPES = new Set([
  "aws_budgets_budget",
  "aws_ce_anomaly_monitor",
  "aws_ce_anomaly_subscription",
  "aws_cloudfront_distribution",
  "aws_cloudfront_function",
  "aws_cloudfront_origin_access_control",
  "aws_cloudwatch_event_rule",
  "aws_cloudwatch_event_target",
  "aws_cloudwatch_log_group",
  "aws_cloudwatch_metric_alarm",
  "aws_ecr_lifecycle_policy",
  "aws_ecr_repository",
  "aws_iam_role",
  "aws_iam_role_policy",
  "aws_lambda_function",
  "aws_lambda_permission",
  "aws_s3_bucket",
  "aws_s3_bucket_lifecycle_configuration",
  "aws_s3_bucket_ownership_controls",
  "aws_s3_bucket_policy",
  "aws_s3_bucket_public_access_block",
  "aws_s3_bucket_server_side_encryption_configuration",
  "aws_sns_topic",
  "aws_sns_topic_policy",
  "aws_sns_topic_subscription",
  "aws_ssm_parameter",
  "aws_vpc_security_group_ingress_rule",
  "random_id",
])

// 지우면 데이터·주소·비밀값·보호를 잃는 것.
const PROTECTED_TYPES = new Set([
  "aws_s3_bucket",
  "aws_ecr_repository",
  "aws_cloudfront_distribution",
  "aws_s3_bucket_public_access_block",
])
const isSecretParameter = (change) =>
  change.type === "aws_ssm_parameter" && /\/(db|child-access)\//.test(change.change?.before?.name ?? "")

const PUBLIC_ACCESS_FLAGS = ["block_public_acls", "block_public_policy", "ignore_public_acls", "restrict_public_buckets"]

function kind(actions) {
  const set = actions.join(",")
  if (set === "create") return "만듦"
  if (set === "update") return "고침"
  if (set === "delete") return "지움"
  if (set === "delete,create" || set === "create,delete") return "다시 만듦"
  return null // no-op · read
}

// 누구에게나 허용하는 문장이 있는지. plan 때 정해지지 않은 정책(문자열이 아님)은 볼 수 없어 넘긴다.
function allowsEveryone(policy) {
  if (typeof policy !== "string") return false
  let doc
  try {
    doc = JSON.parse(policy)
  } catch {
    return false
  }
  return [doc.Statement ?? []].flat().some((st) => {
    if (st.Effect !== "Allow") return false
    if (st.Principal === "*") return true
    return [st.Principal?.AWS ?? []].flat().includes("*")
  })
}

// IAM 정책 JSON 의 문장들. plan 때 정해지지 않았거나(문자열이 아님) 읽을 수 없으면 null.
function policyStatements(policy) {
  if (typeof policy !== "string") return null
  try {
    return [JSON.parse(policy).Statement ?? []].flat()
  } catch {
    return null
  }
}

// 역할을 맡을 수 있는 쪽은 Lambda 하나뿐이다(사람·다른 계정·다른 서비스가 이 권한을 쓰지 못하게).
const ROLE_SERVICES = new Set(["lambda.amazonaws.com"])
// 팀 역할에 주지 않는 권한: 전체, IAM(권한 키우기)·역할 맡기·조직, 서버 만들기·지우기·켜기·설정 바꾸기(운영진 제한·비용).
const FORBIDDEN_ACTION = /^(\*|[a-z0-9-]+:\*|iam:.*|sts:.*|organizations:.*|ec2:(RunInstances|TerminateInstances|StartInstances|ModifyInstanceAttribute))$/i

function reviewIam(change, after, unknown, at, violations) {
  if (change.type === "aws_iam_role") {
    if (typeof after.path === "string" && after.path.startsWith("/ktc/")) violations.push(`${at}: 역할 경로 /ktc/ 는 운영진 것이다`)
    const statements = policyStatements(after.assume_role_policy)
    if (!statements) {
      violations.push(`${at}: 역할을 맡을 수 있는 쪽이 plan 때 정해지지 않아 확인할 수 없다`)
      return
    }
    for (const st of statements) {
      if (st.Effect !== "Allow") continue
      const principal = st.Principal ?? {}
      const services = typeof principal === "object" ? [principal.Service ?? []].flat() : []
      const onlyServices = typeof principal === "object" && Object.keys(principal).every((k) => k === "Service")
      if (!onlyServices || services.length === 0 || services.some((s) => !ROLE_SERVICES.has(s))) {
        violations.push(`${at}: Lambda 말고도 이 역할을 맡을 수 있다(${JSON.stringify(principal)})`)
      }
    }
  }
  if (change.type === "aws_iam_role_policy") {
    const statements = policyStatements(after.policy)
    if (!statements) {
      violations.push(`${at}: 정책이 plan 때 정해지지 않아 확인할 수 없다`)
      return
    }
    for (const st of statements) {
      if (st.Effect !== "Allow") continue
      if (st.NotAction || st.NotResource) violations.push(`${at}: NotAction·NotResource 는 쓰지 않는다(뜻밖에 넓어진다)`)
      for (const action of [st.Action ?? []].flat()) {
        if (FORBIDDEN_ACTION.test(action)) violations.push(`${at}: \`${action}\` 권한은 주지 않는다(전체·IAM·서버 만들기·지우기·켜기)`)
      }
    }
  }
  if (change.type === "aws_lambda_permission") {
    if (!/^[a-z0-9-]+\.amazonaws\.com$/.test(after.principal ?? "")) {
      violations.push(`${at}: AWS 서비스만 Lambda 를 부를 수 있다(지금 \`${after.principal}\`)`)
    }
    if (!after.source_arn && !unknown.source_arn) violations.push(`${at}: 부르는 쪽(source_arn)을 하나로 좁힌다`)
    if (after.function_url_auth_type) violations.push(`${at}: Lambda 함수 URL 은 열지 않는다`)
  }
  if (change.type === "aws_lambda_function") {
    if ((after.memory_size ?? 128) > 512) violations.push(`${at}: Lambda 메모리는 512 MB 이하(비용)`)
    if ((after.timeout ?? 3) > 900) violations.push(`${at}: Lambda 제한 시간은 900초 이하`)
  }
}

// 마크다운 표 칸. 역슬래시를 먼저, 그다음 | 를 이스케이프한다.
const cell = (text) => String(text).replace(/\\/g, "\\\\").replace(/\|/g, "\\|")

export function review(plan, { allowDestroy = false } = {}) {
  const rows = []
  const violations = []
  const destroyed = []
  for (const change of plan.resource_changes ?? []) {
    if (change.mode !== "managed") continue
    const actions = change.change?.actions ?? []
    const what = kind(actions)
    if (!what) continue
    rows.push({ address: change.address, what })

    const after = change.change?.after ?? {}
    const unknown = change.change?.after_unknown ?? {}
    const at = `\`${change.address}\``
    if (actions.includes("create") || actions.includes("update")) {
      if (!ALLOWED_TYPES.has(change.type)) {
        violations.push(`${at}: \`${change.type}\` 는 허용 목록 밖이다(비용·운영진 제한)`)
      }
      if (change.type === "aws_vpc_security_group_ingress_rule") {
        // CIDR 은 쪼개서(0.0.0.0/1 + 128.0.0.0/1) 넓게 열 수 있어 아예 받지 않는다. 우리는 접두사 목록만 쓴다.
        if (after.cidr_ipv4 || after.cidr_ipv6) {
          violations.push(`${at}: 보안 그룹을 CIDR 로 연다(접두사 목록·보안 그룹 참조만 허용한다)`)
        } else if (unknown.cidr_ipv4 || unknown.cidr_ipv6) {
          violations.push(`${at}: 보안 그룹 CIDR 이 plan 때 정해지지 않아 확인할 수 없다`)
        }
        // ip_protocol "-1"(모든 프로토콜)이면 포트 칸이 비어 있다. 22번을 포함해 전부 열리므로 따로 막는다.
        if (after.ip_protocol === "-1" || after.ip_protocol === "all") {
          violations.push(`${at}: 모든 프로토콜·포트를 연다(22번 포함)`)
        } else if (typeof after.from_port === "number" && after.from_port <= 22 && 22 <= after.to_port) {
          violations.push(`${at}: 22번을 연다(SSM 을 쓴다)`)
        }
      }
      if (change.type === "aws_s3_bucket_public_access_block" && PUBLIC_ACCESS_FLAGS.some((f) => after[f] !== true)) {
        violations.push(`${at}: 버킷 공개 차단을 끈다`)
      }
      if (change.type === "aws_s3_bucket_policy" && allowsEveryone(after.policy)) {
        violations.push(`${at}: 누구에게나(Principal "*") 허용하는 버킷 정책이다`)
      }
      if (change.type === "aws_cloudfront_distribution" && after.price_class === "PriceClass_All") {
        violations.push(`${at}: CloudFront 가장 비싼 요금 등급(PriceClass_All)`)
      }
      reviewIam(change, after, unknown, at, violations)
    }
    if (actions.includes("delete") && (PROTECTED_TYPES.has(change.type) || isSecretParameter(change))) {
      destroyed.push({ address: change.address, what })
      if (!allowDestroy) {
        violations.push(`${at}: ${what === "지움" ? "지운다" : "지웠다 다시 만든다"}(데이터·주소·비밀값·보호를 잃는다). 정말 그럴 때만 TF_ALLOW_DESTROY=1`)
      }
    }
  }
  return { rows, violations, destroyed, allowDestroy }
}

export function summary({ rows, violations, destroyed = [], allowDestroy = false }) {
  const lines = ["### Terraform plan", ""]
  if (rows.length === 0) {
    lines.push("바뀌는 리소스가 없다.")
  } else {
    const counts = {}
    for (const row of rows) counts[row.what] = (counts[row.what] ?? 0) + 1
    lines.push(Object.entries(counts).map(([what, n]) => `${what} ${n}`).join(" · "), "")
    lines.push("| 리소스 | 동작 |", "|---|---|")
    for (const row of rows) lines.push(`| \`${cell(row.address)}\` | ${row.what} |`)
  }
  lines.push("")
  if (allowDestroy && destroyed.length > 0) {
    lines.push("#### ⚠ allow_destroy — 아래를 지우거나 다시 만든다(데이터·주소·비밀값을 잃는다)", "")
    for (const row of destroyed) lines.push(`- \`${cell(row.address)}\` ${row.what}`)
    lines.push("")
  }
  if (violations.length > 0) {
    lines.push("#### ⛔ 가드에 걸렸다", "")
    for (const v of violations) lines.push(`- ${v}`)
  } else if (allowDestroy) {
    lines.push("가드 통과(허용 목록·보안 그룹·공개 차단·요금 등급·IAM 범위). allow_destroy 가 켜져 삭제 보호는 껐다.")
  } else {
    lines.push("가드 통과(허용 목록·삭제 보호·보안 그룹·공개 차단·요금 등급·IAM 범위).")
  }
  return lines.join("\n")
}

// ── 구성 검사(--config) ─────────────────────────────────────────
const ALLOWED_PROVIDERS = new Set(["aws", "random", "archive"])
const ALLOWED_PROVIDER_SOURCES = new Set(["hashicorp/aws", "hashicorp/random", "hashicorp/archive"])

// 줄 맨 앞의 주석과 /* */ 주석만 지운다(문자열 안의 // 를 지워 검사를 피하지 않게 줄 끝 주석은 둔다).
const stripComments = (text) =>
  text.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, " ")).replace(/^\s*(#|\/\/).*$/gm, "")

export function reviewConfig(files) {
  const violations = []
  for (const { path, text } of files) {
    stripComments(text)
      .split("\n")
      .forEach((line, i) => {
        const at = `\`${path}:${i + 1}\``
        if (/\bprovisioner\s+"/.test(line)) {
          violations.push(`${at}: provisioner 는 쓰지 않는다(apply 컨테이너의 자격증명으로 임의 명령이 돈다)`)
        }
        if (/\bdata\s+"(external|http)"/.test(line)) {
          violations.push(`${at}: external·http 데이터 소스는 쓰지 않는다(plan 때 자격증명으로 외부 코드·호출이 돈다)`)
        }
        if (/\bresource\s+"(null_resource|terraform_data)"/.test(line)) {
          violations.push(`${at}: null_resource·terraform_data 는 쓰지 않는다(임의 명령)`)
        }
        if (/\bdata\s+"aws_ssm_parameter"/.test(line)) {
          violations.push(`${at}: data "aws_ssm_parameter" 는 쓰지 않는다(읽은 비밀값이 state 에 평문으로 남는다)`)
        }
        const provider = line.match(/^\s*provider\s+"([^"]+)"/)
        if (provider && !ALLOWED_PROVIDERS.has(provider[1])) {
          violations.push(`${at}: provider \`${provider[1]}\` 는 쓰지 않는다(aws·random·archive 만)`)
        }
        // 한 줄짜리 블록(module "x" { source = "…" })도 있어 줄 어디에 있든 찾는다.
        for (const [, value] of line.matchAll(/\bsource\s*=\s*"([^"]+)"/g)) {
          if (value.startsWith("./") || value.startsWith("../")) continue
          const address = value.replace(/^registry\.terraform\.io\//, "")
          if (/^[a-z0-9-]+\/[a-z0-9-]+$/.test(address)) {
            if (!ALLOWED_PROVIDER_SOURCES.has(address)) {
              violations.push(`${at}: provider \`${address}\` 는 쓰지 않는다(hashicorp/aws·hashicorp/random·hashicorp/archive 만)`)
            }
          } else {
            violations.push(`${at}: 레포 밖 모듈은 쓰지 않는다(리뷰 뒤에 바뀔 수 있다). infra/modules/ 아래에 둔다`)
          }
        }
      })
  }
  return violations
}

function listTf(dir) {
  const out = []
  for (const name of readdirSync(dir)) {
    if (name === ".terraform") continue
    const path = join(dir, name)
    if (statSync(path).isDirectory()) out.push(...listTf(path))
    else if (name.endsWith(".tf")) out.push(path)
  }
  return out.sort()
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  if (process.argv[2] === "--config") {
    const files = listTf(process.argv[3] ?? "infra").map((path) => ({ path: path.replace(/\\/g, "/"), text: readFileSync(path, "utf8") }))
    const violations = reviewConfig(files)
    if (violations.length > 0) {
      console.log(["### Terraform 구성 검사", "", "#### ⛔ 쓰지 않기로 한 구성이 있다", "", ...violations.map((v) => `- ${v}`)].join("\n"))
      process.exit(1)
    }
    console.log(`Terraform 구성 검사 통과(.tf ${files.length}개: provisioner·외부 모듈·외부 provider·비밀값 data 없음)`)
    process.exit(0)
  }
  const file = process.argv[2]
  if (!file) {
    console.error("plan JSON 파일이 필요하다: node scripts/tf-plan-guard.mjs plan.json")
    process.exit(2)
  }
  const result = review(JSON.parse(readFileSync(file, "utf8")), { allowDestroy: process.env.TF_ALLOW_DESTROY === "1" })
  console.log(summary(result))
  process.exit(result.violations.length > 0 ? 1 : 0)
}
