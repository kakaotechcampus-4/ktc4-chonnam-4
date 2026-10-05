# AWS 없이 도는 검사(mock provider). 로그 보관 기간·비용 경보·메일 주소 비노출을 본다.

mock_provider "aws" {
  mock_resource "aws_sns_topic" {
    defaults = {
      arn = "arn:aws:sns:ap-northeast-2:123456789012:neuringo-dev-alerts"
    }
  }
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{}"
    }
  }
}

variables {
  env          = "dev"
  instance_id  = "i-0123456789abcdef0"
  alert_emails = ["a@example.com", "b@example.com"]
}

run "logs_kept_30_days_and_cost_alarm_wired" {
  command = apply

  assert {
    condition     = aws_cloudwatch_log_group.backend.retention_in_days == 30
    error_message = "로그는 30일 보관한다(S3-JEONG-02, 비용)"
  }
  assert {
    condition     = aws_cloudwatch_metric_alarm.cpu_surplus_charged.metric_name == "CPUSurplusCreditsCharged" && aws_cloudwatch_metric_alarm.cpu_surplus_charged.threshold == 0
    error_message = "t3 초과 크레딧 과금이 생기면 바로 알린다"
  }
  assert {
    condition     = contains(aws_cloudwatch_metric_alarm.cpu_surplus_charged.alarm_actions, "arn:aws:sns:ap-northeast-2:123456789012:neuringo-dev-alerts")
    error_message = "경보는 알림 주제로 보낸다"
  }
  assert {
    condition     = length(aws_sns_topic_subscription.email) == 2
    error_message = "메일마다 구독을 만든다(순번으로, 주소는 리소스 이름에 안 나온다)"
  }
  assert {
    condition     = aws_cloudwatch_metric_alarm.status_check_failed.treat_missing_data == "breaching"
    error_message = "서버가 꺼져 지표가 끊기면 경보로 본다(꺼진 서버의 옛 IP 를 CloudFront 가 보고 있을 수 있다)"
  }
  assert {
    condition = alltrue([
      for p in ["budgets.amazonaws.com", "cloudwatch.amazonaws.com", "costalerts.amazonaws.com"] :
      contains(one(one(data.aws_iam_policy_document.alerts.statement).principals).identifiers, p)
    ])
    error_message = "예산·경보·비용 이상 탐지가 알림 주제로 보낼 수 있다"
  }
  assert {
    condition     = anytrue([for c in one(data.aws_iam_policy_document.alerts.statement).condition : c.variable == "aws:SourceAccount"])
    error_message = "알림 주제에는 같은 계정의 서비스만 보낸다"
  }
}

run "no_subscriptions_without_emails" {
  command = apply

  variables {
    alert_emails = []
  }

  assert {
    condition     = length(aws_sns_topic_subscription.email) == 0
    error_message = "메일이 없으면 구독을 만들지 않는다"
  }
}
