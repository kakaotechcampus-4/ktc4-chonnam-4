// tf-plan-guard.mjs 자체 검사. 가짜 plan JSON 으로 요약과 가드를 본다(verify.sh workflows 에 포함).
//   node --test scripts/tf-plan-guard.test.mjs
import { test } from "node:test"
import assert from "node:assert/strict"
import { review, reviewConfig, summary } from "./tf-plan-guard.mjs"

const change = (type, name, actions, after = {}, before = null, mode = "managed", afterUnknown = {}) => ({
  address: `module.x.${type}.${name}`,
  mode,
  type,
  name,
  change: { actions, after, before, after_unknown: afterUnknown },
})

test("허용 목록 안의 리소스를 만들면 통과하고 동작별로 센다", () => {
  const plan = {
    resource_changes: [
      change("aws_s3_bucket", "web", ["create"]),
      change("aws_ecr_repository", "this", ["create"]),
      change("aws_ssm_parameter", "web_bucket", ["update"]),
      change("aws_cloudfront_distribution", "this", ["no-op"]),
      change("aws_instances", "app", ["read"], {}, null, "data"),
    ],
  }
  const result = review(plan)
  assert.deepEqual(result.violations, [])
  assert.equal(result.rows.length, 3)
  const text = summary(result)
  assert.match(text, /만듦 2 · 고침 1/)
  assert.match(text, /가드 통과/)
})

test("허용 목록 밖(NAT Gateway·EC2·RDS)은 막는다", () => {
  const plan = {
    resource_changes: [
      change("aws_nat_gateway", "this", ["create"]),
      change("aws_instance", "extra", ["create"]),
      change("aws_db_instance", "main", ["create"]),
    ],
  }
  const { violations } = review(plan)
  assert.equal(violations.length, 3)
  assert.match(violations[0], /허용 목록 밖/)
})

test("버킷·ECR·배포를 지우거나 다시 만들면 막고, TF_ALLOW_DESTROY 면 통과한다", () => {
  const plan = {
    resource_changes: [
      change("aws_s3_bucket", "backups", ["delete"]),
      change("aws_ecr_repository", "this", ["delete", "create"]),
      change("aws_cloudfront_distribution", "this", ["create", "delete"]),
    ],
  }
  assert.equal(review(plan).violations.length, 3)
  assert.equal(review(plan, { allowDestroy: true }).violations.length, 0)
})

test("비밀값(DB 비밀번호·HMAC 키) 파라미터를 지우면 막고, 다른 파라미터는 지워도 된다", () => {
  const plan = {
    resource_changes: [
      change("aws_ssm_parameter", "db_password", ["delete"], {}, { name: "/neuringo/dev/db/password" }),
      change("aws_ssm_parameter", "hmac", ["delete"], {}, { name: "/neuringo/dev/child-access/hmac-secret" }),
      change("aws_ssm_parameter", "domain", ["delete"], {}, { name: "/neuringo/dev/infra/cloudfront-domain" }),
    ],
  }
  const { violations } = review(plan)
  assert.equal(violations.length, 2)
})

test("보안 그룹을 CIDR(인터넷 전체 포함)·22번으로 열면 막는다", () => {
  const plan = {
    resource_changes: [
      change("aws_vpc_security_group_ingress_rule", "open", ["create"], { cidr_ipv4: "0.0.0.0/0", from_port: 443, to_port: 443 }),
      change("aws_vpc_security_group_ingress_rule", "ssh", ["create"], { prefix_list_id: "pl-1", from_port: 22, to_port: 22 }),
      change("aws_vpc_security_group_ingress_rule", "ok", ["create"], { prefix_list_id: "pl-1", from_port: 80, to_port: 80 }),
    ],
  }
  const { violations } = review(plan)
  assert.equal(violations.length, 2)
  assert.match(violations[0], /CIDR/)
  assert.match(violations[1], /22번/)
})

test("CIDR 을 쪼개 넓게 열거나(0.0.0.0/1) plan 때 정해지지 않은 CIDR 도 막는다", () => {
  const plan = {
    resource_changes: [
      change("aws_vpc_security_group_ingress_rule", "half1", ["create"], { cidr_ipv4: "0.0.0.0/1", ip_protocol: "tcp", from_port: 80, to_port: 80 }),
      change("aws_vpc_security_group_ingress_rule", "v6half", ["create"], { cidr_ipv6: "::/1", ip_protocol: "tcp", from_port: 80, to_port: 80 }),
      change("aws_vpc_security_group_ingress_rule", "later", ["create"], { ip_protocol: "tcp", from_port: 80, to_port: 80 }, null, "managed", { cidr_ipv4: true }),
    ],
  }
  const { violations } = review(plan)
  assert.equal(violations.length, 3)
  assert.match(violations[2], /정해지지 않아/)
})

test("버킷 공개 차단을 끄거나 지우면 막는다", () => {
  const off = change("aws_s3_bucket_public_access_block", "web", ["update"], {
    block_public_acls: true,
    block_public_policy: false,
    ignore_public_acls: true,
    restrict_public_buckets: true,
  })
  const gone = change("aws_s3_bucket_public_access_block", "backups", ["delete"])
  const { violations } = review({ resource_changes: [off, gone] })
  assert.equal(violations.length, 2)
  assert.match(violations[0], /공개 차단을 끈다/)
  assert.equal(review({ resource_changes: [gone] }, { allowDestroy: true }).violations.length, 0)
})

test("누구에게나 허용하는 버킷 정책은 막고, 서비스 주체 허용·전체 Deny 는 통과한다", () => {
  const policy = (statements) => JSON.stringify({ Version: "2012-10-17", Statement: statements })
  const plan = {
    resource_changes: [
      change("aws_s3_bucket_policy", "open", ["update"], { policy: policy([{ Effect: "Allow", Principal: "*", Action: "s3:GetObject" }]) }),
      change("aws_s3_bucket_policy", "open_aws", ["update"], { policy: policy([{ Effect: "Allow", Principal: { AWS: ["*"] }, Action: "s3:*" }]) }),
      change("aws_s3_bucket_policy", "oac", ["update"], {
        policy: policy([
          { Effect: "Allow", Principal: { Service: "cloudfront.amazonaws.com" }, Action: "s3:GetObject" },
          { Effect: "Deny", Principal: "*", Action: "s3:*" },
        ]),
      }),
      change("aws_s3_bucket_policy", "unknown", ["create"], {}, null, "managed", { policy: true }),
    ],
  }
  const { violations } = review(plan)
  assert.equal(violations.length, 2)
  assert.match(violations[0], /Principal "\*"/)
})

test("모든 프로토콜(ip_protocol -1)로 열면 포트 칸이 비어 있어도 막는다", () => {
  const plan = {
    resource_changes: [
      change("aws_vpc_security_group_ingress_rule", "all", ["create"], { prefix_list_id: "pl-1", ip_protocol: "-1", from_port: null, to_port: null }),
      change("aws_vpc_security_group_ingress_rule", "tcp_all", ["update"], { prefix_list_id: "pl-1", ip_protocol: "tcp", from_port: 0, to_port: 65535 }),
    ],
  }
  const { violations } = review(plan)
  assert.equal(violations.length, 2)
  assert.match(violations[0], /모든 프로토콜·포트/)
  assert.match(violations[1], /22번/)
})

test("CloudFront 가장 비싼 요금 등급은 막는다", () => {
  const plan = { resource_changes: [change("aws_cloudfront_distribution", "this", ["update"], { price_class: "PriceClass_All" })] }
  assert.match(review(plan).violations[0], /PriceClass_All/)
})

test("요약에는 주소·동작만 있고 값(서버 주소·버킷 이름)은 없다", () => {
  const plan = {
    resource_changes: [
      change("aws_cloudfront_distribution", "this", ["create"], {
        origin: [{ domain_name: "ec2-203-0-113-10.ap-northeast-2.compute.amazonaws.com" }],
      }),
      change("aws_s3_bucket", "web", ["create"], { bucket: "neuringo-dev-web-abc123" }),
    ],
  }
  const text = summary(review(plan))
  assert.doesNotMatch(text, /ec2-203-0-113-10/)
  assert.doesNotMatch(text, /neuringo-dev-web-abc123/)
  assert.match(text, /module\.x\.aws_s3_bucket\.web/)
})

test("allow_destroy 로 지울 때는 지울 목록을 경고로 적고 '삭제 보호' 통과라고 하지 않는다", () => {
  const plan = { resource_changes: [change("aws_s3_bucket", "backups", ["delete"])] }
  const text = summary(review(plan, { allowDestroy: true }))
  assert.match(text, /⚠ allow_destroy/)
  assert.match(text, /module\.x\.aws_s3_bucket\.backups/)
  assert.doesNotMatch(text, /삭제 보호·/)
})

test("구성 검사: 우리 구성(로컬 모듈·aws·random)은 통과한다", () => {
  const files = [
    {
      path: "infra/live/dev/main.tf",
      text: [
        "terraform {",
        "  required_providers {",
        '    aws = { source = "hashicorp/aws", version = "= 6.67.0" }',
        "    random = {",
        '      source  = "registry.terraform.io/hashicorp/random"',
        "    }",
        "  }",
        "}",
        'provider "aws" {}',
        "# provisioner 는 쓰지 않는다 — 주석은 보지 않는다",
        'module "edge" {',
        '  source = "../../modules/edge"',
        "}",
        'data "aws_instances" "app" {}',
      ].join("\n"),
    },
  ]
  assert.deepEqual(reviewConfig(files), [])
})

test("구성 검사: provisioner·external·http·null_resource·terraform_data·비밀값 data 를 막는다", () => {
  const text = [
    'resource "aws_s3_bucket" "x" {',
    '  provisioner "local-exec" { command = "env" }',
    "}",
    'data "external" "x" {}',
    'data "http" "x" {}',
    'resource "null_resource" "x" {}',
    'resource "terraform_data" "x" {}',
    'data "aws_ssm_parameter" "db" {}',
  ].join("\n")
  const violations = reviewConfig([{ path: "infra/x.tf", text }])
  assert.equal(violations.length, 6)
  assert.match(violations[0], /infra\/x\.tf:2/)
})

test("구성 검사: 레포 밖 모듈·다른 provider 를 막는다", () => {
  const text = [
    'module "vpc" { source = "terraform-aws-modules/vpc/aws" }',
    'module "git" {',
    '  source = "git::https://example.com/mod.git"',
    "}",
    "terraform {",
    "  required_providers {",
    "    other = {",
    '      source = "someone/other"',
    "    }",
    "  }",
    "}",
    'provider "null" {}',
  ].join("\n")
  const violations = reviewConfig([{ path: "infra/y.tf", text }])
  assert.equal(violations.length, 3 + 1)
})

test("바뀌는 것이 없으면 그렇게 적는다", () => {
  assert.match(summary(review({ resource_changes: [] })), /바뀌는 리소스가 없다/)
})
