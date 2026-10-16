# Dev server 안 쓰면 끄기: EventBridge(10분마다) → Lambda(lambda/idle_off.py).
# GitHub Actions 예약은 이 레포에서 몇 시간씩 밀리거나 건너뛰어(2026-10-11: 20분마다가 1시간 동안 0번) AWS 안으로 옮겼다.
# 판단·순서는 lambda/idle_off.py 머리말. 끄는 순서는 dev-server.yml 의 끄기와 같다(대문 닫기 → 확인 → 잠금 → 서버 중지).
#
# 권한은 이 서버 하나·이 배포 하나로 좁힌다(서버 켜기·지우기, IAM, 다른 리소스는 없다). 역할 경로는 /neuringo/(운영진 /ktc/ 는 쓰지 않는다).
# 비용: Lambda·EventBridge 는 무료 사용량 안(한 달 약 4,300번 · 대부분 1초 안에 끝남). 로그는 14일만 둔다.
# 실패(서버 상태를 못 읽음·대문이 안 닫힘·권한)는 Lambda 오류 경보가 알림 주제로 메일을 보낸다.

terraform {
  required_version = "= 1.16.4"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 6.67.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "= 2.7.1"
    }
  }
}

variable "env" {
  description = "환경 이름(dev·prod)"
  type        = string
}

variable "instance_id" {
  description = "끌 EC2(팀 서버 1대)"
  type        = string
}

variable "distribution_id" {
  description = "끄기 전에 닫을 CloudFront 배포"
  type        = string
  sensitive   = true
}

variable "alert_topic_arn" {
  description = "실패를 알릴 SNS 주제"
  type        = string
}

variable "idle_minutes" {
  description = "이만큼 안 쓰면 끈다(분). 2026-10-11 60 → 30(팀 결정)"
  type        = number
  default     = 30

  validation {
    condition     = var.idle_minutes >= 20 && var.idle_minutes <= 720
    error_message = "20~720분 사이로 정한다(너무 짧으면 빌드 중에 꺼질 수 있다)."
  }
}

variable "check_every_minutes" {
  description = "판단 간격(분). 실제로 꺼지는 때는 마지막 사용 뒤 idle_minutes ~ idle_minutes + 이 값"
  type        = number
  default     = 10

  validation {
    condition     = var.check_every_minutes >= 5 && var.check_every_minutes <= 30
    error_message = "5~30분 사이로 정한다."
  }
}

variable "log_retention_days" {
  type    = number
  default = 14
}

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  name         = "neuringo-${var.env}-idle-off"
  account      = data.aws_caller_identity.current.account_id
  region       = data.aws_region.current.region
  instance_arn = "arn:aws:ec2:${local.region}:${local.account}:instance/${var.instance_id}"
  # 정책은 plan 때 내용이 정해져야 가드(tf-plan-guard.mjs)가 범위를 본다. 만들 리소스의 속성 대신 이름으로 ARN 을 짓는다.
  log_group = "/aws/lambda/${local.name}"
}

data "archive_file" "code" {
  type             = "zip"
  source_file      = "${path.module}/lambda/idle_off.py"
  output_path      = "${path.module}/build/idle_off.zip"
  output_file_mode = "0644" # 어느 OS 에서 묶어도 같은 해시(불필요한 재배포를 막는다)
}

resource "aws_cloudwatch_log_group" "this" {
  name              = local.log_group
  retention_in_days = var.log_retention_days
}

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account]
    }
  }
}

resource "aws_iam_role" "this" {
  name               = local.name
  path               = "/neuringo/"
  assume_role_policy = data.aws_iam_policy_document.assume.json
}

data "aws_iam_policy_document" "permissions" {
  statement {
    sid       = "Logs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["arn:aws:logs:${local.region}:${local.account}:log-group:${local.log_group}:*"]
  }
  statement {
    sid       = "ReadServer" # Describe* 는 리소스로 좁힐 수 없다(읽기만)
    actions   = ["ec2:DescribeInstances", "ssm:ListCommands", "ssm:GetCommandInvocation"]
    resources = ["*"]
  }
  statement {
    sid       = "StopThisServer"
    actions   = ["ec2:StopInstances"]
    resources = [local.instance_arn]
  }
  statement {
    sid     = "AskThisServer" # status.sh 읽기 · 배포 잠금 기다리기
    actions = ["ssm:SendCommand"]
    resources = [
      local.instance_arn,
      "arn:aws:ssm:${local.region}::document/AWS-RunShellScript",
    ]
  }
  statement {
    sid       = "CloseThisEdge"
    actions   = ["cloudfront:GetDistribution", "cloudfront:GetDistributionConfig", "cloudfront:UpdateDistribution"]
    resources = ["arn:aws:cloudfront::${local.account}:distribution/${var.distribution_id}"]
  }
}

resource "aws_iam_role_policy" "this" {
  name   = "idle-off"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.permissions.json
}

resource "aws_lambda_function" "this" {
  function_name    = local.name
  description      = "Dev server 안 쓰면 끄기(EventBridge 10분마다)"
  role             = aws_iam_role.this.arn
  runtime          = "python3.13"
  handler          = "idle_off.handler"
  filename         = data.archive_file.code.output_path
  source_code_hash = data.archive_file.code.output_base64sha256
  memory_size      = 128
  timeout          = 900 # 대문이 다 닫히길(2~3분)·배포 잠금(최대 240초) 기다린다

  environment {
    variables = {
      INSTANCE_ID     = var.instance_id
      DISTRIBUTION_ID = var.distribution_id
      IDLE_MINUTES    = tostring(var.idle_minutes)
    }
  }

  depends_on = [aws_cloudwatch_log_group.this, aws_iam_role_policy.this]
}

resource "aws_cloudwatch_event_rule" "every" {
  name                = local.name
  description         = "Dev server 안 쓰면 끄기 판단"
  schedule_expression = "rate(${var.check_every_minutes} minutes)"
}

resource "aws_cloudwatch_event_target" "lambda" {
  rule = aws_cloudwatch_event_rule.every.name
  arn  = aws_lambda_function.this.arn

  retry_policy {
    maximum_retry_attempts       = 0 # 다음 실행이 다시 본다
    maximum_event_age_in_seconds = 300
  }
}

resource "aws_lambda_permission" "events" {
  statement_id  = "AllowEventBridge"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.this.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.every.arn
}

resource "aws_cloudwatch_metric_alarm" "errors" {
  alarm_name          = "${local.name}-errors"
  alarm_description   = "Dev server 안 쓰면 끄기가 실패했다(서버 상태를 못 읽음·대문이 안 닫힘·권한). 서버가 켜진 채일 수 있다. CloudWatch 로그 ${local.log_group} 를 보고, 급하면 Dev server → 끄기"
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = aws_lambda_function.this.function_name }
  statistic           = "Sum"
  period              = 600
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alert_topic_arn]
  ok_actions          = [var.alert_topic_arn]
}

output "function_name" {
  value = aws_lambda_function.this.function_name
}
