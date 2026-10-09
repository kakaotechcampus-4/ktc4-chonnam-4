# AWS 없이 도는 검사(mock provider). 백업 버킷은 공개 차단·암호화·HTTPS 만·보관 기간이 있어야 한다.

mock_provider "aws" {
  mock_resource "aws_s3_bucket" {
    defaults = {
      arn = "arn:aws:s3:::neuringo-dev-backups-abc123"
    }
  }
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Statement\":[{\"Sid\":\"DenyInsecureTransport\",\"Effect\":\"Deny\"}]}"
    }
  }
}

variables {
  env    = "dev"
  suffix = "abc123"
}

run "backup_bucket_is_private_encrypted_and_expires" {
  command = apply

  assert {
    condition     = aws_s3_bucket.backups.bucket == "neuringo-dev-backups-abc123"
    error_message = "버킷 이름에는 계정 ID 대신 환경·접미사를 쓴다"
  }
  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.backups.block_public_acls,
      aws_s3_bucket_public_access_block.backups.block_public_policy,
      aws_s3_bucket_public_access_block.backups.ignore_public_acls,
      aws_s3_bucket_public_access_block.backups.restrict_public_buckets,
    ])
    error_message = "백업(개인정보가 들어 있다)은 공개를 모두 막는다"
  }
  assert {
    condition     = one(aws_s3_bucket_server_side_encryption_configuration.backups.rule).apply_server_side_encryption_by_default[0].sse_algorithm == "AES256"
    error_message = "저장 암호화를 켠다"
  }
  assert {
    condition     = one(aws_s3_bucket_lifecycle_configuration.backups.rule).expiration[0].days == 14
    error_message = "백업은 14일 뒤 지운다(보관 기준·비용)"
  }
  assert {
    condition     = one(data.aws_iam_policy_document.backups.statement).effect == "Deny" && anytrue([for c in one(data.aws_iam_policy_document.backups.statement).condition : c.variable == "aws:SecureTransport" && contains(c.values, "false")])
    error_message = "HTTPS 가 아닌 접근은 막는다(Deny)"
  }
}
