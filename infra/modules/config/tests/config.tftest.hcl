# AWS 없이 도는 검사(mock provider). 워크플로·서버가 읽는 이름과 비밀값 취급을 본다.

mock_provider "aws" {}
# random 은 로컬에서만 도는 provider 라 가짜로 바꾸지 않는다(가짜 provider 는 ephemeral 값을 만들지 못한다).

variables {
  env                        = "dev"
  ecr_repository_url         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/neuringo/backend"
  cloudfront_distribution_id = "E2FAKE"
  cloudfront_domain          = "d111111abcdef8.cloudfront.net"
  backup_bucket              = "neuringo-dev-backups-abc123"
}

run "names_match_scripts_and_secrets_are_write_only" {
  command = apply

  # scripts/infra-outputs.sh 가 읽는 네 개와 deploy/host/params.txt 의 필수값 다섯 개
  assert {
    condition = output.parameter_names == sort([
      "/neuringo/dev/infra/ecr-repository-url",
      "/neuringo/dev/infra/cloudfront-distribution-id",
      "/neuringo/dev/infra/cloudfront-domain",
      "/neuringo/dev/infra/backup-bucket",
      "/neuringo/dev/db/name",
      "/neuringo/dev/db/username",
      "/neuringo/dev/db/password",
      "/neuringo/dev/child-access/hmac-secret",
    ])
    error_message = "Parameter Store 이름이 scripts/infra-outputs.sh·deploy/host/params.txt 와 맞아야 한다"
  }
  assert {
    condition     = alltrue([for p in [aws_ssm_parameter.db_password, aws_ssm_parameter.child_access_hmac_secret] : p.type == "SecureString"])
    error_message = "비밀값은 SecureString 이다"
  }
  # value_wo_version 은 쓰기 전용(value_wo)일 때만 쓴다. (가짜 provider 는 value 같은 계산 속성에 아무 값이나 채워서 그쪽은 보지 않는다)
  assert {
    condition     = aws_ssm_parameter.db_password.value_wo_version == 1 && aws_ssm_parameter.child_access_hmac_secret.value_wo_version == 1
    error_message = "비밀값은 쓰기 전용(value_wo)으로만 넣어 plan·state 에 남기지 않는다"
  }
  assert {
    condition     = aws_ssm_parameter.db_name.value == "neuringo_dev"
    error_message = "DB 이름은 환경마다 다르다"
  }
}
