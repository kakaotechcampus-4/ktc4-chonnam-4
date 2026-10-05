# 백엔드 이미지 저장소(ECR). 태그는 커밋 SHA 하나만 쓰고 덮어쓰지 못하게 한다(되돌리기가 그 태그를 믿는다).
# 이미지가 쌓이면 저장 비용이 늘어서 최근 keep_images 개만 남긴다.

terraform {
  required_version = "= 1.16.4"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 6.67.0"
    }
  }
}

variable "name" {
  description = "저장소 이름"
  type        = string
  default     = "neuringo/backend"
}

variable "keep_images" {
  description = "남길 이미지 수"
  type        = number
  default     = 10
}

resource "aws_ecr_repository" "this" {
  name                 = var.name
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "this" {
  repository = aws_ecr_repository.this.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "최근 ${var.keep_images}개만 남긴다"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = var.keep_images
      }
      action = { type = "expire" }
    }]
  })
}

output "repository_url" {
  description = "이미지 주소(계정 ID 가 들어 있다)"
  value       = aws_ecr_repository.this.repository_url
  sensitive   = true
}

output "repository_name" {
  value = aws_ecr_repository.this.name
}
