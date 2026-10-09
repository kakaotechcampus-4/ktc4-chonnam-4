# 관측·알림: 로그 그룹(30일 보관, S3-JEONG-02), 알림 주제(SNS), 서버 경보.
#   - t3 는 기본이 무제한(unlimited) 크레딧이라 CPU 를 오래 쓰면 초과 크레딧이 과금된다 → 과금이 생기면 바로 알린다
#   - 상태 검사 실패·서버 꺼짐(지표가 끊기면 경보로 본다). 고정 IP 가 없어 서버가 꺼진 동안 CloudFront 가 옛 IP 를 보므로
#     꺼지면 바로 알린다(서버를 끄기 전에는 Infra 로 CloudFront 를 끈다)
# 메일 구독은 받는 사람이 메일의 확인 링크를 눌러야 시작된다. 메일 주소는 개인정보라 레포에 적지 않고 변수(secret)로 받는다.

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

variable "instance_id" {
  description = "경보를 걸 EC2"
  type        = string
}

variable "alert_emails" {
  description = "경보·예산 알림을 받을 메일(개인정보라 secret 으로 넘긴다)"
  type        = list(string)
  default     = []
  sensitive   = true
}

variable "log_retention_days" {
  type    = number
  default = 30
}

resource "aws_cloudwatch_log_group" "backend" {
  name              = "/neuringo/${var.env}/backend"
  retention_in_days = var.log_retention_days
}

resource "aws_sns_topic" "alerts" {
  name = "neuringo-${var.env}-alerts"
}

data "aws_caller_identity" "current" {}

# 예산(AWS Budgets)·경보(CloudWatch)·비용 이상 탐지(costalerts)가 이 주제로 보낼 수 있게 한다. 같은 계정에서만.
data "aws_iam_policy_document" "alerts" {
  statement {
    sid       = "AllowBudgetsAndCloudWatch"
    effect    = "Allow"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alerts.arn]
    principals {
      type        = "Service"
      identifiers = ["budgets.amazonaws.com", "cloudwatch.amazonaws.com", "costalerts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_sns_topic_policy" "alerts" {
  arn    = aws_sns_topic.alerts.arn
  policy = data.aws_iam_policy_document.alerts.json
}

# 메일 주소가 계획(plan) 출력·리소스 주소에 나오지 않게 순번으로 만든다.
resource "aws_sns_topic_subscription" "email" {
  count     = nonsensitive(length(var.alert_emails))
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_emails[count.index]
}

resource "aws_cloudwatch_metric_alarm" "cpu_surplus_charged" {
  alarm_name          = "neuringo-${var.env}-cpu-surplus-credits-charged"
  alarm_description   = "t3 초과 CPU 크레딧이 과금되기 시작했다(무제한 모드). CPU 를 오래 쓰는 작업을 찾는다"
  namespace           = "AWS/EC2"
  metric_name         = "CPUSurplusCreditsCharged"
  dimensions          = { InstanceId = var.instance_id }
  statistic           = "Sum"
  period              = 3600
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "status_check_failed" {
  alarm_name          = "neuringo-${var.env}-status-check-failed"
  alarm_description   = "서버 상태 검사 실패 또는 서버가 꺼졌다. 꺼졌다면 CloudFront 가 옛 IP 를 보고 있으니 서버를 켜고 Infra 를 다시 돌리거나 CloudFront 를 끈다(EDGE_ENABLED=false)"
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed"
  dimensions          = { InstanceId = var.instance_id }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 2
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
}

output "alert_topic_arn" {
  value = aws_sns_topic.alerts.arn
}

output "log_group_name" {
  value = aws_cloudwatch_log_group.backend.name
}
