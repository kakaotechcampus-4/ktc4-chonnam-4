# AWS 없이 도는 검사(mock provider). 판단 간격·권한 범위·실패 알림을 본다. Lambda 코드는 lambda/test_idle_off.py.

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }
  mock_data "aws_region" {
    defaults = {
      region = "ap-northeast-2"
    }
  }
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{}"
    }
  }
  mock_resource "aws_cloudwatch_log_group" {
    defaults = {
      arn = "arn:aws:logs:ap-northeast-2:123456789012:log-group:/aws/lambda/neuringo-dev-idle-off"
    }
  }
  mock_resource "aws_lambda_function" {
    defaults = {
      arn = "arn:aws:lambda:ap-northeast-2:123456789012:function:neuringo-dev-idle-off"
    }
  }
  mock_resource "aws_cloudwatch_event_rule" {
    defaults = {
      arn = "arn:aws:events:ap-northeast-2:123456789012:rule/neuringo-dev-idle-off"
    }
  }
  mock_resource "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::123456789012:role/neuringo/neuringo-dev-idle-off"
    }
  }
}

mock_provider "archive" {}

variables {
  env             = "dev"
  instance_id     = "i-0123456789abcdef0"
  distribution_id = "E2EXAMPLE"
  alert_topic_arn = "arn:aws:sns:ap-northeast-2:123456789012:neuringo-dev-alerts"
}

run "checks_every_10_minutes_and_turns_off_after_30" {
  command = apply

  assert {
    condition     = aws_cloudwatch_event_rule.every.schedule_expression == "rate(10 minutes)"
    error_message = "10분마다 판단한다(GitHub 예약처럼 밀리지 않는다)"
  }
  assert {
    condition     = aws_lambda_function.this.environment[0].variables.IDLE_MINUTES == "30"
    error_message = "기본은 30분 동안 안 쓰면 끈다"
  }
  assert {
    condition     = aws_lambda_function.this.timeout == 900 && aws_lambda_function.this.memory_size == 128
    error_message = "대문이 다 닫히길 기다릴 시간(900초)·가장 작은 메모리"
  }
  assert {
    condition     = aws_lambda_permission.events.principal == "events.amazonaws.com" && aws_lambda_permission.events.source_arn == aws_cloudwatch_event_rule.every.arn
    error_message = "이 규칙만 Lambda 를 부를 수 있다"
  }
  assert {
    condition     = aws_cloudwatch_log_group.this.retention_in_days == 14
    error_message = "로그는 14일만 둔다(비용)"
  }
  assert {
    condition     = contains(aws_cloudwatch_metric_alarm.errors.alarm_actions, var.alert_topic_arn) && aws_cloudwatch_metric_alarm.errors.threshold == 0
    error_message = "한 번이라도 실패하면 메일로 알린다(서버가 켜진 채일 수 있다)"
  }
}

run "permissions_only_for_this_server_and_edge" {
  command = apply

  assert {
    condition     = aws_iam_role.this.path == "/neuringo/"
    error_message = "운영진 경로(/ktc/)가 아닌 팀 경로에 만든다"
  }
  assert {
    condition = alltrue([
      for s in data.aws_iam_policy_document.permissions.statement :
      !anytrue([for a in s.actions : a == "*" || startswith(a, "iam:") || a == "ec2:StartInstances" || a == "ec2:TerminateInstances"])
    ])
    error_message = "켜기·지우기·IAM 권한은 없다"
  }
  assert {
    condition = one([
      for s in data.aws_iam_policy_document.permissions.statement : s.resources if s.sid == "StopThisServer"
    ]) == toset(["arn:aws:ec2:ap-northeast-2:123456789012:instance/i-0123456789abcdef0"])
    error_message = "끄기는 이 서버 하나만"
  }
  assert {
    condition = one([
      for s in data.aws_iam_policy_document.permissions.statement : s.resources if s.sid == "CloseThisEdge"
    ]) == toset(["arn:aws:cloudfront::123456789012:distribution/E2EXAMPLE"])
    error_message = "대문 바꾸기는 이 배포 하나만"
  }
  assert {
    condition = one([
      for s in data.aws_iam_policy_document.permissions.statement : s.resources if s.sid == "Logs"
    ]) == toset(["arn:aws:logs:ap-northeast-2:123456789012:log-group:/aws/lambda/neuringo-dev-idle-off:*"])
    error_message = "로그는 제 로그 그룹에만 쓴다(만들기 전에도 정해지는 ARN — plan 가드가 본다)"
  }
  assert {
    condition = alltrue([
      for s in data.aws_iam_policy_document.permissions.statement :
      s.resources != toset(["*"]) || alltrue([for a in s.actions : can(regex("^(ec2:Describe|ssm:List|ssm:Get)", a))])
    ])
    error_message = "모든 리소스(*)에는 읽기만 준다"
  }
}

run "always_on_until_reaches_lambda" {
  command = apply

  variables {
    always_on_until = "2026-11-20T23:59:00+09:00"
  }

  assert {
    condition     = aws_lambda_function.this.environment[0].variables.ALWAYS_ON_UNTIL == "2026-11-20T23:59:00+09:00"
    error_message = "24시간 모드 끝 시각을 Lambda 가 받는다"
  }
}

run "always_on_is_off_by_default" {
  command = apply

  assert {
    condition     = aws_lambda_function.this.environment[0].variables.ALWAYS_ON_UNTIL == ""
    error_message = "모듈 기본은 24시간 모드가 아니다(안 쓰면 끈다)"
  }
}

run "rejects_malformed_always_on_until" {
  command = plan

  variables {
    always_on_until = "11/20"
  }

  expect_failures = [var.always_on_until]
}

run "rejects_too_short_idle_time" {
  command = plan

  variables {
    idle_minutes = 5
  }

  expect_failures = [var.idle_minutes]
}
