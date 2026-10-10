# AWS 없이 도는 검사(mock provider). dev 환경이 모듈을 맞게 잇고, 서버가 1대가 아니면 멈추는지 본다.

mock_provider "aws" {
  mock_resource "aws_s3_bucket" {
    defaults = {
      arn                         = "arn:aws:s3:::neuringo-dev-fake"
      bucket_regional_domain_name = "neuringo-dev-fake.s3.ap-northeast-2.amazonaws.com"
    }
  }
  mock_resource "aws_cloudfront_distribution" {
    defaults = {
      arn         = "arn:aws:cloudfront::123456789012:distribution/E2FAKE"
      domain_name = "d111111abcdef8.cloudfront.net"
    }
  }
  mock_resource "aws_sns_topic" {
    defaults = {
      arn = "arn:aws:sns:ap-northeast-2:123456789012:neuringo-dev-alerts"
    }
  }
  mock_resource "aws_ecr_repository" {
    defaults = {
      repository_url = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/neuringo/backend"
    }
  }
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{}"
    }
  }
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }
}

override_data {
  target = data.aws_instances.app
  values = { ids = ["i-0123456789abcdef0"] }
}

override_data {
  target = data.aws_instance.app
  values = {
    id                     = "i-0123456789abcdef0"
    public_dns             = "ec2-203-0-113-10.ap-northeast-2.compute.amazonaws.com"
    vpc_security_group_ids = ["sg-0123456789abcdef0"]
  }
}

override_data {
  target = module.edge.data.aws_ec2_managed_prefix_list.cloudfront
  values = { id = "pl-cloudfront" }
}

override_data {
  target = module.edge.data.aws_vpc_security_group_rules.instance
  values = { ids = [] }
}

run "dev_wires_modules" {
  command = apply

  assert {
    condition     = output.cloudfront_domain == "d111111abcdef8.cloudfront.net"
    error_message = "사용자 테스트 주소(CloudFront 기본 주소)를 내보낸다"
  }
  assert {
    condition     = length(module.config.parameter_names) == 8
    error_message = "워크플로·서버가 읽는 Parameter Store 값 8개를 만든다"
  }
  assert {
    condition     = length(random_id.suffix.hex) == 6
    error_message = "버킷 이름 접미사는 6자리 16진수다"
  }
}

run "stops_when_no_server_is_running" {
  command = plan

  override_data {
    target = data.aws_instances.app
    values = { ids = [] }
  }

  expect_failures = [data.aws_instance.app]
}

run "stops_when_two_servers_are_running" {
  command = plan

  override_data {
    target = data.aws_instances.app
    values = { ids = ["i-0123456789abcdef0", "i-0fedcba9876543210"] }
  }

  expect_failures = [data.aws_instance.app]
}
