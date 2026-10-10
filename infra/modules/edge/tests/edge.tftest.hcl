# AWS 없이 도는 검사(mock provider). 화면·API 를 서버 하나에서 같은 출처로 내는 앞단의 약속을 본다.

mock_provider "aws" {
  mock_resource "aws_cloudfront_distribution" {
    defaults = {
      arn         = "arn:aws:cloudfront::123456789012:distribution/E2FAKE"
      domain_name = "d111111abcdef8.cloudfront.net"
    }
  }
}

# 접두사 목록은 이름으로 찾는다. 가짜 provider 는 그 id 를 정해 주지 않아서 값을 준다.
override_data {
  target = data.aws_ec2_managed_prefix_list.cloudfront
  values = { id = "pl-cloudfront" }
}

# 운영진 보안 그룹에 이미 있는 규칙. 기본은 없음.
override_data {
  target = data.aws_vpc_security_group_rules.instance
  values = { ids = [] }
}

variables {
  env                         = "dev"
  origin_domain               = "ec2-203-0-113-10.ap-northeast-2.compute.amazonaws.com"
  instance_security_group_ids = ["sg-0123456789abcdef0"]
}

run "same_origin_front_door" {
  command = apply

  assert {
    condition     = length(aws_cloudfront_distribution.this.origin) == 1
    error_message = "원본은 서버 하나다(화면·API 가 같은 커밋으로 함께 바뀐다)"
  }
  assert {
    condition     = one(aws_cloudfront_distribution.this.origin).custom_origin_config[0].origin_protocol_policy == "http-only"
    error_message = "원본은 EC2 80번(HTTP)이다. 도메인·인증서가 없어서다"
  }
  assert {
    condition     = one(aws_cloudfront_distribution.this.origin).custom_origin_config[0].origin_read_timeout == 60
    error_message = "AI 한 턴을 기다린다(신청 없이 올릴 수 있는 최대 60초)"
  }
  assert {
    condition     = one(aws_cloudfront_distribution.this.default_cache_behavior).viewer_protocol_policy == "redirect-to-https"
    error_message = "화면은 HTTPS 로만 연다(카메라·마이크는 HTTPS 에서만 열린다)"
  }
  assert {
    condition     = one(aws_cloudfront_distribution.this.default_cache_behavior).cache_policy_id == "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"
    error_message = "index.html·화면 주소는 캐시하지 않는다(새 배포·미리보기가 바로 보이게)"
  }
  assert {
    condition     = length(aws_cloudfront_distribution.this.custom_error_response) == 0
    error_message = "사용자 지정 오류 응답을 쓰면 /api 의 404·403 까지 index.html 200 으로 바뀐다(화면 주소는 서버의 nginx 가 처리한다)"
  }
  assert {
    condition     = [for b in aws_cloudfront_distribution.this.ordered_cache_behavior : b.path_pattern] == ["/assets/*", "/api/*"]
    error_message = "/assets/* 와 /api/* 를 따로 다룬다"
  }
  assert {
    condition     = aws_cloudfront_distribution.this.ordered_cache_behavior[0].cache_policy_id == "658327ea-f89d-4fab-a63d-7e88639e58f6"
    error_message = "빌드한 화면 파일(/assets/*, 이름에 해시)은 캐시한다"
  }
  assert {
    condition     = aws_cloudfront_distribution.this.ordered_cache_behavior[1].cache_policy_id == "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"
    error_message = "/api/* 는 캐시하지 않는다(사람마다 응답이 다르다)"
  }
  assert {
    condition     = aws_cloudfront_distribution.this.ordered_cache_behavior[1].origin_request_policy_id == "b689b0a8-53d0-40ab-baf2-68738e2966ac"
    error_message = "/api/* 는 Authorization·쿠키·쿼리를 모두 넘긴다(GET 의 Bearer 토큰)"
  }
  assert {
    condition     = contains(aws_cloudfront_distribution.this.ordered_cache_behavior[1].allowed_methods, "PATCH") && contains(aws_cloudfront_distribution.this.ordered_cache_behavior[1].allowed_methods, "DELETE")
    error_message = "/api/* 는 모든 메서드를 받는다"
  }
  assert {
    condition     = aws_cloudfront_distribution.this.ordered_cache_behavior[1].viewer_protocol_policy == "https-only"
    error_message = "/api/* 는 HTTPS 로만 받는다(토큰·비밀번호가 평문으로 오지 않게)"
  }
  assert {
    condition = alltrue(concat(
      [one(aws_cloudfront_distribution.this.default_cache_behavior).response_headers_policy_id == "67f7725c-6f97-4210-82d7-5512b31e9d03"],
      [for b in aws_cloudfront_distribution.this.ordered_cache_behavior : b.response_headers_policy_id == "67f7725c-6f97-4210-82d7-5512b31e9d03"],
    ))
    error_message = "모든 응답에 보안 헤더(HSTS·nosniff 등)를 붙인다"
  }
  assert {
    condition     = aws_cloudfront_distribution.this.price_class == "PriceClass_200"
    error_message = "한국 엣지가 들어 있는 등급을 쓴다"
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.cloudfront_http.prefix_list_id == "pl-cloudfront" && aws_vpc_security_group_ingress_rule.cloudfront_http.from_port == 80 && aws_vpc_security_group_ingress_rule.cloudfront_http.to_port == 80
    error_message = "보안 그룹은 CloudFront 원본용 접두사 목록의 80번만 연다"
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.cloudfront_http.cidr_ipv4 == null && aws_vpc_security_group_ingress_rule.cloudfront_http.cidr_ipv6 == null
    error_message = "인터넷 전체(0.0.0.0/0)에 열지 않는다"
  }
  assert {
    condition     = one(one(aws_cloudfront_distribution.this.restrictions).geo_restriction).restriction_type == "whitelist" && one(one(aws_cloudfront_distribution.this.restrictions).geo_restriction).locations == toset(["KR"])
    error_message = "사용자 테스트는 국내라 한국에서만 연다(외부인 가입·남용을 줄인다)"
  }
  assert {
    condition     = aws_cloudfront_distribution.this.enabled
    error_message = "처음 만들 때는 켜져 있다(그 뒤 켜고 끄기는 edge-toggle.sh)"
  }
}

run "refuses_open_http_rule" {
  command = plan

  override_data {
    target = data.aws_vpc_security_group_rules.instance
    values = { ids = ["sgr-0open000000000000"] }
  }
  override_data {
    target = data.aws_vpc_security_group_rule.instance
    values = { is_egress = false, cidr_ipv4 = "0.0.0.0/0", ip_protocol = "tcp", from_port = 80, to_port = 80 }
  }

  expect_failures = [aws_vpc_security_group_ingress_rule.cloudfront_http]
}

run "refuses_open_all_ports_rule" {
  command = plan

  override_data {
    target = data.aws_vpc_security_group_rules.instance
    values = { ids = ["sgr-0all0000000000000"] }
  }
  override_data {
    target = data.aws_vpc_security_group_rule.instance
    values = { is_egress = false, cidr_ipv6 = "::/0", ip_protocol = "-1" }
  }

  expect_failures = [aws_vpc_security_group_ingress_rule.cloudfront_http]
}

run "allows_open_rule_on_other_port" {
  command = plan

  override_data {
    target = data.aws_vpc_security_group_rules.instance
    values = { ids = ["sgr-0https00000000000"] }
  }
  override_data {
    target = data.aws_vpc_security_group_rule.instance
    values = { is_egress = false, cidr_ipv4 = "0.0.0.0/0", ip_protocol = "tcp", from_port = 443, to_port = 443 }
  }

  assert {
    condition     = length(local.open_http_rules) == 0
    error_message = "80번을 포함하지 않는 규칙은 막지 않는다"
  }
}

run "refuses_empty_origin" {
  command = plan

  variables {
    origin_domain = ""
  }

  expect_failures = [var.origin_domain]
}
