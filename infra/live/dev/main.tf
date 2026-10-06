# dev 환경(사용자 테스트 겸). docs/cd-architecture.md 2·4절.
# 운영진이 준 EC2·VPC·보안 그룹은 만들지 않고 읽기만 한다(data source). 보안 그룹에는 규칙만 더한다.
# 바꾸는 길은 PR(plan 리뷰) → develop 머지 → infra.yml 의 apply 하나뿐이다. 콘솔에서 만들지 않는다.

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

  # bucket·key·region·use_lockfile 은 infra.yml 이 -backend-config 로 넘긴다(state 버킷 이름에 계정 ID 가 들어간다).
  backend "s3" {}
}

provider "aws" {
  region = "ap-northeast-2"

  default_tags {
    tags = {
      Project   = "neuringo"
      Env       = var.env
      ManagedBy = "terraform"
      Owner     = "VS-017"
    }
  }
}

variable "env" {
  type    = string
  default = "dev"
}

variable "monthly_budget_usd" {
  description = "월 예산(USD). 팀이 정한다"
  type        = number
  default     = 70
}

variable "alert_emails" {
  description = "경보·예산 알림 메일(infra.yml 이 secret 으로 넘긴다)"
  type        = list(string)
  default     = []
  sensitive   = true
}

variable "enable_anomaly_detection" {
  description = "Cost Anomaly Detection(⚑4 권한 확인 뒤 켠다)"
  type        = bool
  default     = false
}

variable "edge_enabled" {
  description = "CloudFront 를 켤지. 서버를 끄기 전에 false(저장소 변수 EDGE_ENABLED)로 끄고, 켠 뒤 다시 true 로 apply 한다"
  type        = bool
  default     = true
}

variable "db_password_version" {
  type    = number
  default = 1
}

variable "hmac_secret_version" {
  type    = number
  default = 1
}

# ── 운영진이 준 서버(팀당 1대) ─────────────────────────────────
data "aws_instances" "app" {
  instance_state_names = ["running"]
}

locals {
  instance_ids = data.aws_instances.app.ids
}

data "aws_instance" "app" {
  instance_id = length(local.instance_ids) == 1 ? local.instance_ids[0] : null

  lifecycle {
    precondition {
      condition     = length(local.instance_ids) == 1
      error_message = "켜져 있는 EC2 가 1대여야 한다. 서버가 꺼져 있으면 콘솔에서 시작한 뒤 다시 돌린다."
    }
  }
}

# 버킷 이름은 전 세계에서 겹치면 안 된다. 계정 ID 대신 무작위 접미사를 쓴다(공개 로그에 남아도 되게).
resource "random_id" "suffix" {
  byte_length = 3
}

module "registry" {
  source = "../../modules/registry"
}

module "storage" {
  source = "../../modules/storage"
  env    = var.env
  suffix = random_id.suffix.hex
}

module "edge" {
  source                      = "../../modules/edge"
  env                         = var.env
  suffix                      = random_id.suffix.hex
  api_origin_domain           = data.aws_instance.app.public_dns
  instance_security_group_ids = tolist(data.aws_instance.app.vpc_security_group_ids)
  enabled                     = var.edge_enabled
}

module "observability" {
  source       = "../../modules/observability"
  env          = var.env
  instance_id  = data.aws_instance.app.id
  alert_emails = var.alert_emails
}

module "cost" {
  source                   = "../../modules/cost"
  env                      = var.env
  monthly_limit_usd        = var.monthly_budget_usd
  alert_topic_arn          = module.observability.alert_topic_arn
  enable_anomaly_detection = var.enable_anomaly_detection
}

module "config" {
  source                     = "../../modules/config"
  env                        = var.env
  ecr_repository_url         = module.registry.repository_url
  web_bucket                 = module.edge.web_bucket
  cloudfront_distribution_id = module.edge.distribution_id
  cloudfront_domain          = module.edge.domain
  backup_bucket              = module.storage.bucket
  db_password_version        = var.db_password_version
  hmac_secret_version        = var.hmac_secret_version
}

output "cloudfront_domain" {
  description = "사용자 테스트 주소: https://<이 값>/"
  value       = module.edge.domain
}
