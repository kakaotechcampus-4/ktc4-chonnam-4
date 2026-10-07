# 사용자 앞단: CloudFront 기본 주소(https://<id>.cloudfront.net) 하나로 화면과 API 를 같은 출처로 낸다.
# 원본은 서버(EC2 80번) 하나다. 서버의 web(nginx) 컨테이너가 화면 파일을 내주고 /api/ 를 backend 로 넘긴다.
# 화면과 API 가 같은 커밋으로 함께 바뀐다(PR 미리보기도 서버 한 곳만 바꾸면 된다).
#   /assets/*  → 캐시(파일 이름에 해시가 있다)
#   /api/*     → 캐시 끔. Authorization·쿠키·쿼리를 모두 넘긴다(CloudFront 는 GET 의 Authorization 을 기본으로 지운다)
#   그 밖      → 캐시 끔(index.html·화면 주소. 새 배포·미리보기가 바로 보이게)
# 같은 출처라 dev·prod 보안 설정(CORS 없음·세션 CSRF·쿠키)을 그대로 쓴다. 카메라·마이크는 HTTPS 화면에서만 열린다.
# EC2 보안 그룹에는 CloudFront 원본용 관리형 접두사 목록 → TCP 80 만 연다(22번은 열지 않는다).
# 운영진 보안 그룹에 인터넷 전체에서 80번(또는 모든 포트)으로 들어오는 규칙이 이미 있으면 plan 을 멈춘다.
# 한국에서만 연다(allowed_countries).
#
# 켜고 끄기(enabled)는 Terraform 이 아니라 dev-server.yml 의 scripts/edge-toggle.sh 가 한다(ignore_changes).
# 고정 IP 가 없어 서버를 끄면 옛 IP 가 다른 AWS 고객에게 갈 수 있다. 그래서 서버를 끄기 전에 CloudFront 를 끄고,
# 켠 뒤 새 주소를 넣고 연다. 이 일을 승인·plan 없이 몇 초 만에 하려고 Terraform 밖에 둔다.
# 원본 주소는 Terraform 이 계속 관리한다(서버가 켜져 있을 때 Terraform 이 읽는 주소와 edge-toggle.sh 가 넣는 주소가 같다).

terraform {
  required_version = "= 1.16.4"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 6.67.0"
    }
  }
}

variable "env" {
  description = "환경 이름(dev·prod)"
  type        = string
}

variable "origin_domain" {
  description = "원본(EC2 공인 DNS). 서버를 중지·시작하면 바뀐다(Elastic IP 가 없을 때). dev-server.yml 이 켤 때 새 값으로 바꾼다"
  type        = string

  validation {
    condition     = length(var.origin_domain) > 0
    error_message = "EC2 공인 DNS 가 비었다. 서버가 켜져 있는지 확인한다."
  }
}

variable "instance_security_group_ids" {
  description = "원본(EC2)의 보안 그룹들. 운영진 소유라 첫 번째에 규칙만 더하고, 전부를 열린 규칙이 없는지 본다"
  type        = list(string)

  validation {
    condition     = length(var.instance_security_group_ids) > 0
    error_message = "EC2 에 보안 그룹이 없다."
  }
}

variable "allowed_countries" {
  description = "화면·API 를 열 나라(ISO 코드). 빈 목록이면 제한하지 않는다. 사용자 테스트는 국내라 KR 만 연다"
  type        = list(string)
  default     = ["KR"]
}

variable "price_class" {
  description = "CloudFront 요금 등급. PriceClass_200 에 한국 엣지가 들어 있다"
  type        = string
  default     = "PriceClass_200"
}

# CloudFront 관리형 정책. AWS 가 정한 고정 ID 다(관리형 캐시·원본 요청·응답 헤더 정책 문서, 2026-10-05 확인).
# 이름으로 찾는 data source 를 쓰지 않는 이유: 조회 권한이 덜 필요하고, AWS 없이 도는 검사(terraform test)가 값을 확인할 수 있다.
locals {
  cache_policy_optimized    = "658327ea-f89d-4fab-a63d-7e88639e58f6" # Managed-CachingOptimized
  cache_policy_disabled     = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad" # Managed-CachingDisabled
  origin_request_all_viewer = "b689b0a8-53d0-40ab-baf2-68738e2966ac" # Managed-AllViewerExceptHostHeader(Authorization·쿠키·쿼리·CloudFront-Forwarded-Proto 포함)
  response_headers_security = "67f7725c-6f97-4210-82d7-5512b31e9d03" # Managed-SecurityHeadersPolicy(HSTS·nosniff 등)
  origin_id                 = "app"
}

data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

# 운영진 보안 그룹에 이미 있는 규칙. 인터넷 전체(0.0.0.0/0·::/0)에서 80번이나 모든 포트로 들어오는 규칙이 있으면
# CloudFront 를 거치지 않고 서버가 평문으로 열린다(web 은 0.0.0.0:80 에 뜬다). 매일 drift 검사도 다시 본다.
data "aws_vpc_security_group_rules" "instance" {
  filter {
    name   = "group-id"
    values = var.instance_security_group_ids
  }
}

data "aws_vpc_security_group_rule" "instance" {
  for_each               = toset(data.aws_vpc_security_group_rules.instance.ids)
  security_group_rule_id = each.value
}

locals {
  open_http_rules = [
    for id, r in data.aws_vpc_security_group_rule.instance : id
    if !r.is_egress && (r.cidr_ipv4 == "0.0.0.0/0" || r.cidr_ipv6 == "::/0") &&
    (r.ip_protocol == "-1" || (coalesce(r.from_port, 0) <= 80 && coalesce(r.to_port, 65535) >= 80))
  ]
}

# ── 배포 ─────────────────────────────────────────────────
resource "aws_cloudfront_distribution" "this" {
  # 처음 만들 때만 켠다. 그 뒤로는 scripts/edge-toggle.sh 가 서버를 켜고 끌 때 바꾼다(위 설명).
  enabled         = true
  comment         = "neuringo ${var.env}"
  price_class     = var.price_class
  http_version    = "http2and3"
  is_ipv6_enabled = true

  origin {
    origin_id   = local.origin_id
    domain_name = var.origin_domain
    custom_origin_config {
      http_port                = 80
      https_port               = 443
      origin_protocol_policy   = "http-only"
      origin_ssl_protocols     = ["TLSv1.2"]
      origin_read_timeout      = 60
      origin_keepalive_timeout = 5
    }
  }

  # 화면 주소(/classrooms 같은 SPA 경로)와 index.html. 서버의 nginx 가 index.html 로 돌린다.
  default_cache_behavior {
    target_origin_id           = local.origin_id
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = local.cache_policy_disabled
    response_headers_policy_id = local.response_headers_security
  }

  ordered_cache_behavior {
    path_pattern               = "/assets/*"
    target_origin_id           = local.origin_id
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = local.cache_policy_optimized
    response_headers_policy_id = local.response_headers_security
  }

  ordered_cache_behavior {
    path_pattern               = "/api/*"
    target_origin_id           = local.origin_id
    viewer_protocol_policy     = "https-only"
    allowed_methods            = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = local.cache_policy_disabled
    origin_request_policy_id   = local.origin_request_all_viewer
    response_headers_policy_id = local.response_headers_security
  }

  restrictions {
    geo_restriction {
      restriction_type = length(var.allowed_countries) > 0 ? "whitelist" : "none"
      locations        = var.allowed_countries
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }

  lifecycle {
    ignore_changes = [enabled]
  }
}

# ── EC2 보안 그룹: CloudFront 에서 오는 80 번만 ────────────────────
resource "aws_vpc_security_group_ingress_rule" "cloudfront_http" {
  security_group_id = sort(var.instance_security_group_ids)[0]
  description       = "neuringo ${var.env}: CloudFront origin-facing only"
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront.id
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80

  lifecycle {
    precondition {
      condition     = length(local.open_http_rules) == 0
      error_message = "서버 보안 그룹에 인터넷 전체(0.0.0.0/0·::/0)에서 80번(또는 모든 포트)으로 들어오는 규칙이 있다. CloudFront 를 거치지 않고 서버가 평문으로 열린다. 팀원이 연 규칙인지 확인해 지우거나 운영진에 문의한 뒤 다시 돌린다."
    }
  }
}

output "distribution_id" {
  value     = aws_cloudfront_distribution.this.id
  sensitive = true
}

output "domain" {
  description = "사용자 테스트 주소(https://<이 값>/)"
  value       = aws_cloudfront_distribution.this.domain_name
}
