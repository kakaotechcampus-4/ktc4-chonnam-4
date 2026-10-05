# DB 백업 버킷. 서버의 backup.sh 가 pg_dump 를 올린다(deploy/host/backup.sh).
# 공개 차단·암호화·HTTPS 만·14일 뒤 삭제. 버킷 이름에 계정 ID 대신 무작위 접미사를 쓴다(공개 로그에 남아도 되게).

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

variable "suffix" {
  description = "버킷 이름 끝의 무작위 접미사(전 세계에서 겹치지 않게)"
  type        = string
}

variable "backup_days" {
  description = "백업 보관 일수"
  type        = number
  default     = 14
}

resource "aws_s3_bucket" "backups" {
  bucket = "neuringo-${var.env}-backups-${var.suffix}"
}

resource "aws_s3_bucket_public_access_block" "backups" {
  bucket                  = aws_s3_bucket.backups.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "backups" {
  bucket = aws_s3_bucket.backups.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "backups" {
  bucket = aws_s3_bucket.backups.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "backups" {
  bucket = aws_s3_bucket.backups.id
  rule {
    id     = "expire-backups"
    status = "Enabled"
    filter {
      prefix = ""
    }
    expiration {
      days = var.backup_days
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

data "aws_iam_policy_document" "backups" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.backups.arn, "${aws_s3_bucket.backups.arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "backups" {
  bucket = aws_s3_bucket.backups.id
  policy = data.aws_iam_policy_document.backups.json

  depends_on = [aws_s3_bucket_public_access_block.backups]
}

output "bucket" {
  description = "백업 버킷 이름"
  value       = aws_s3_bucket.backups.bucket
}

output "bucket_arn" {
  value = aws_s3_bucket.backups.arn
}
