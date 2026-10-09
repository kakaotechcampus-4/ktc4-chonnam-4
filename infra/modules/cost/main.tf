# 비용: 월 예산(AWS Budgets) 실제 80%·예측 100% 에 알림. 이상 탐지(Cost Anomaly Detection)는 권한이 확인되면 켠다(⚑4).
# 팀 계정은 팀 전용이라 계정 합계가 곧 팀 비용이다. 금액은 팀이 정한다(docs/cd-architecture.md 7·11절).

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
  type = string
}

variable "monthly_limit_usd" {
  description = "월 예산(USD)"
  type        = number
  default     = 70
}

variable "alert_topic_arn" {
  description = "알림을 보낼 SNS 주제"
  type        = string
}

variable "enable_anomaly_detection" {
  description = "Cost Anomaly Detection 을 켤지(멤버 계정에서 막혀 있을 수 있다)"
  type        = bool
  default     = false
}

resource "aws_budgets_budget" "monthly" {
  name         = "neuringo-${var.env}-monthly"
  budget_type  = "COST"
  limit_amount = format("%.2f", var.monthly_limit_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator       = "GREATER_THAN"
    threshold                 = 80
    threshold_type            = "PERCENTAGE"
    notification_type         = "ACTUAL"
    subscriber_sns_topic_arns = [var.alert_topic_arn]
  }

  notification {
    comparison_operator       = "GREATER_THAN"
    threshold                 = 100
    threshold_type            = "PERCENTAGE"
    notification_type         = "FORECASTED"
    subscriber_sns_topic_arns = [var.alert_topic_arn]
  }
}

resource "aws_ce_anomaly_monitor" "services" {
  count             = var.enable_anomaly_detection ? 1 : 0
  name              = "neuringo-${var.env}-services"
  monitor_type      = "DIMENSIONAL"
  monitor_dimension = "SERVICE"
}

resource "aws_ce_anomaly_subscription" "alerts" {
  count            = var.enable_anomaly_detection ? 1 : 0
  name             = "neuringo-${var.env}-anomalies"
  frequency        = "IMMEDIATE"
  monitor_arn_list = [aws_ce_anomaly_monitor.services[0].arn]

  subscriber {
    type    = "SNS"
    address = var.alert_topic_arn
  }

  threshold_expression {
    dimension {
      key           = "ANOMALY_TOTAL_IMPACT_ABSOLUTE"
      match_options = ["GREATER_THAN_OR_EQUAL"]
      values        = ["5"]
    }
  }
}
