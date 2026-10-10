# Parameter Store 값.
#   /neuringo/<env>/infra/…  워크플로가 읽는 인프라 값(scripts/infra-outputs.sh)
#   /neuringo/<env>/db/…, child-access/…  서버 .env 에 들어가는 값(deploy/host/params.txt)
# 비밀값(DB 비밀번호·아동 입장 코드 HMAC 키)은 ephemeral 값 + 쓰기 전용 인자(value_wo)로 넣어 plan·state 에 남기지 않는다.
# *_version 을 올릴 때만 새로 만든다. DB 비밀번호는 postgres 볼륨을 처음 만들 때만 적용되므로, 이미 돈 DB 에서 바꾸면
# 앱이 접속하지 못한다(바꾸려면 DB 안의 비밀번호도 같이 바꾼다).
# 외부 AI 키 같은 사람이 넣는 값은 여기서 만들지 않는다(담당자가 Parameter Store 에 직접 넣는다).

terraform {
  required_version = "= 1.16.4"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 6.67.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "= 3.9.1"
    }
  }
}

variable "env" {
  description = "환경 이름(dev·prod)"
  type        = string
}

variable "ecr_repository_url" {
  type      = string
  sensitive = true
}

variable "cloudfront_distribution_id" {
  type      = string
  sensitive = true
}

variable "cloudfront_domain" {
  type = string
}

variable "backup_bucket" {
  type = string
}

variable "db_username" {
  type    = string
  default = "neuringo"
}

variable "db_password_version" {
  description = "올리면 DB 비밀번호를 새로 만든다(주석의 주의 참고)"
  type        = number
  default     = 1
}

variable "hmac_secret_version" {
  description = "올리면 아동 입장 코드 HMAC 키를 새로 만든다(이미 발급한 코드는 다시 발급해야 한다)"
  type        = number
  default     = 1
}

locals {
  prefix = "/neuringo/${var.env}"
}

# ── 워크플로가 읽는 인프라 값 ─────────────────────────────────
resource "aws_ssm_parameter" "ecr_repository_url" {
  name  = "${local.prefix}/infra/ecr-repository-url"
  type  = "String"
  value = var.ecr_repository_url
}

resource "aws_ssm_parameter" "cloudfront_distribution_id" {
  name  = "${local.prefix}/infra/cloudfront-distribution-id"
  type  = "String"
  value = var.cloudfront_distribution_id
}

resource "aws_ssm_parameter" "cloudfront_domain" {
  name  = "${local.prefix}/infra/cloudfront-domain"
  type  = "String"
  value = var.cloudfront_domain
}

resource "aws_ssm_parameter" "backup_bucket" {
  name  = "${local.prefix}/infra/backup-bucket"
  type  = "String"
  value = var.backup_bucket
}

# ── 서버 .env 값 ─────────────────────────────────────────────
resource "aws_ssm_parameter" "db_name" {
  name  = "${local.prefix}/db/name"
  type  = "String"
  value = "neuringo_${var.env}"
}

resource "aws_ssm_parameter" "db_username" {
  name  = "${local.prefix}/db/username"
  type  = "String"
  value = var.db_username
}

# deploy.sh 가 .env 에 작은따옴표로 감싸 넣으므로 ' " $ ` \ 는 쓰지 않는다.
ephemeral "random_password" "db" {
  length           = 32
  special          = true
  override_special = "-_.~!#%^*+="
}

resource "aws_ssm_parameter" "db_password" {
  name             = "${local.prefix}/db/password"
  type             = "SecureString"
  value_wo         = ephemeral.random_password.db.result
  value_wo_version = var.db_password_version
}

ephemeral "random_password" "hmac" {
  length  = 64
  special = false
}

resource "aws_ssm_parameter" "child_access_hmac_secret" {
  name             = "${local.prefix}/child-access/hmac-secret"
  type             = "SecureString"
  value_wo         = ephemeral.random_password.hmac.result
  value_wo_version = var.hmac_secret_version
}

output "parameter_names" {
  description = "만든 Parameter Store 이름(검사·문서용)"
  value = sort([
    aws_ssm_parameter.ecr_repository_url.name,
    aws_ssm_parameter.cloudfront_distribution_id.name,
    aws_ssm_parameter.cloudfront_domain.name,
    aws_ssm_parameter.backup_bucket.name,
    aws_ssm_parameter.db_name.name,
    aws_ssm_parameter.db_username.name,
    aws_ssm_parameter.db_password.name,
    aws_ssm_parameter.child_access_hmac_secret.name,
  ])
}
