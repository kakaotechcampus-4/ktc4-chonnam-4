# AWS 없이 도는 검사(mock provider). ECR 은 태그 불변·푸시 스캔·수명 주기가 있어야 한다.

mock_provider "aws" {}

run "ecr_is_immutable_scanned_and_pruned" {
  command = apply

  assert {
    condition     = aws_ecr_repository.this.image_tag_mutability == "IMMUTABLE"
    error_message = "태그를 덮어쓸 수 있으면 되돌리기가 엉뚱한 이미지를 띄울 수 있다"
  }
  assert {
    condition     = aws_ecr_repository.this.image_scanning_configuration[0].scan_on_push
    error_message = "푸시할 때 취약점 스캔을 켠다"
  }
  assert {
    condition     = jsondecode(aws_ecr_lifecycle_policy.this.policy).rules[0].selection.countNumber == 30
    error_message = "이미지는 최근 30개(백엔드·화면 약 15커밋)만 남긴다(저장 비용)"
  }
}
