# AWS 없이 도는 검사(mock provider). 예산 금액·알림 기준·이상 탐지 스위치를 본다.

mock_provider "aws" {
  mock_resource "aws_ce_anomaly_monitor" {
    defaults = {
      arn = "arn:aws:ce::123456789012:anomalymonitor/0000-fake"
    }
  }
}

variables {
  env             = "dev"
  alert_topic_arn = "arn:aws:sns:ap-northeast-2:123456789012:neuringo-dev-alerts"
}

run "monthly_budget_alerts_at_80_actual_and_100_forecast" {
  command = apply

  assert {
    condition     = aws_budgets_budget.monthly.limit_amount == "70.00" && aws_budgets_budget.monthly.time_unit == "MONTHLY"
    error_message = "월 예산 기본값은 70 USD 다(팀이 바꿀 수 있다)"
  }
  assert {
    condition     = toset([for n in aws_budgets_budget.monthly.notification : "${n.notification_type}:${n.threshold}"]) == toset(["ACTUAL:80", "FORECASTED:100"])
    error_message = "실제 80% · 예측 100% 에 알린다"
  }
  assert {
    condition     = length(aws_ce_anomaly_monitor.services) == 0
    error_message = "이상 탐지는 권한 확인 전에는 꺼 둔다"
  }
}

run "anomaly_detection_can_be_enabled" {
  command = apply

  variables {
    enable_anomaly_detection = true
  }

  assert {
    condition     = length(aws_ce_anomaly_monitor.services) == 1 && length(aws_ce_anomaly_subscription.alerts) == 1
    error_message = "켜면 서비스별 모니터와 알림 구독을 만든다"
  }
}
