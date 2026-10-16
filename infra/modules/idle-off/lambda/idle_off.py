"""Dev server 안 쓰면 끄기. EventBridge 가 10분마다 부른다(infra/modules/idle-off).

GitHub Actions 예약(schedule)은 이 레포에서 몇 시간씩 밀리거나 건너뛰어서(2026-10-11 실측: 20분마다가 1시간 동안 0번)
끄는 판단을 AWS 안으로 옮겼다. 끄는 순서는 dev-server.yml 의 끄기와 같다:
  대문(CloudFront) 닫기 → 다 닫힌 것 확인 → 배포·백업 잠금이 비길 기다림 → 서버 중지
고정 IP 가 없어서 대문을 먼저 닫는다. 꺼진 서버의 옛 IP 는 다른 AWS 고객에게 갈 수 있다(scripts/edge-toggle.sh).

끄는 때: 서버가 켜져 있고, 미리보기 자리가 비었고, 아래 중 가장 늦은 것에서 IDLE_MINUTES 가 지났을 때.
  - 서버를 켠 시각(EC2 LaunchTime, status.sh 의 STATUS_BOOTED_AT)
  - 마지막 배포(STATUS_AT)
  - 화면(nginx)에 마지막 요청이 온 시각(STATUS_LAST_REQUEST)
  - GitHub Actions 가 서버에 보낸 마지막 SSM 명령(미리보기를 빌드하는 몇 분 동안은 자리가 아직 없다)
서버 상태를 읽지 못하면 끄지 않고 실패한다(Lambda 오류 경보가 메일로 알린다).
로그에는 서버 ID·주소·배포 ID 를 찍지 않는다(경보 메일·콘솔에 남는다).
"""

import json
import os
import time
from datetime import datetime, timedelta, timezone

OWN_COMMENT = "neuringo-idle-off"
STATUS_SCRIPT = 'f=/opt/neuringo/current/deploy/host/status.sh; if [ -f "$f" ]; then bash "$f"; else echo LEASE_ACTIVE=0; fi'
LOCK_SECONDS = 240
LOCK_SCRIPT = (
    "mkdir -p /opt/neuringo/state && "
    f"flock -w {LOCK_SECONDS} /opt/neuringo/state/deploy.lock true && echo LOCK_FREE=1"
)
DONE = {"Success", "Failed", "Cancelled", "TimedOut", "Cancelling", "Undeliverable", "Terminated", "InvalidPlatform", "AccessDenied"}


def log(**fields):
    print(json.dumps(fields, ensure_ascii=False, default=str))


def error_code(err):
    return getattr(err, "response", {}).get("Error", {}).get("Code", "")


def parse_time(value):
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=timezone.utc)
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value).strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def parse_kv(text):
    values = {}
    for line in (text or "").splitlines():
        key, sep, value = line.partition("=")
        if sep and key.strip():
            values[key.strip()] = value.strip()
    return values


def kst(at):
    return at.astimezone(timezone(timedelta(hours=9))).strftime("%H:%M") if at else "?"


def instance_state(ec2, instance_id):
    found = ec2.describe_instances(InstanceIds=[instance_id])
    instances = [i for r in found.get("Reservations", []) for i in r.get("Instances", [])]
    if len(instances) != 1:
        raise RuntimeError(f"서버를 찾지 못했다({len(instances)}대)")
    return instances[0]["State"]["Name"], parse_time(instances[0].get("LaunchTime"))


def run_shell(ssm, instance_id, script, timeout, sleep, poll=3):
    """서버에서 셸을 돌리고 (상태, 표준 출력)을 돌려준다."""
    sent = ssm.send_command(
        InstanceIds=[instance_id],
        DocumentName="AWS-RunShellScript",
        Comment=OWN_COMMENT,
        TimeoutSeconds=60,
        Parameters={"commands": [script], "executionTimeout": [str(timeout)]},
    )
    command_id = sent["Command"]["CommandId"]
    waited = 0
    while waited <= timeout + 60:
        sleep(poll)
        waited += poll
        try:
            got = ssm.get_command_invocation(CommandId=command_id, InstanceId=instance_id)
        except Exception as err:  # 보낸 직전에는 InvocationDoesNotExist 가 온다
            if error_code(err) == "InvocationDoesNotExist":
                continue
            raise
        if got.get("Status") in DONE:
            return got["Status"], got.get("StandardOutputContent", "")
    return "TimedOut", ""


def last_ci_command(ssm, instance_id, since):
    """since 뒤에 이 Lambda 말고(GitHub Actions 등) 서버에 보낸 마지막 SSM 명령 시각."""
    latest = None
    token = None
    while True:
        args = {
            "InstanceId": instance_id,
            "Filters": [{"key": "InvokedAfter", "value": since.strftime("%Y-%m-%dT%H:%M:%SZ")}],
        }
        if token:
            args["NextToken"] = token
        page = ssm.list_commands(**args)
        for command in page.get("Commands", []):
            if command.get("Comment") == OWN_COMMENT:
                continue
            at = parse_time(command.get("RequestedDateTime"))
            if at and (latest is None or at > latest):
                latest = at
        token = page.get("NextToken")
        if not token:
            return latest


def decide(status, launch_time, last_command, now, idle_minutes):
    """(끈다?, 까닭, 마지막 사용) — 상태를 읽지 못한 경우는 handler 가 먼저 거른다."""
    if status.get("LEASE_ACTIVE") == "1":
        until = parse_time(status.get("LEASE_UNTIL"))
        return False, f"PR #{status.get('LEASE_PR', '?')} 미리보기가 {kst(until)} (KST) 까지 자리를 쓰는 중", None
    candidates = [
        launch_time,
        parse_time(status.get("STATUS_BOOTED_AT")),
        parse_time(status.get("STATUS_AT")),
        parse_time(status.get("STATUS_LAST_REQUEST")),
        last_command,
    ]
    last = max((c for c in candidates if c), default=None)
    if last is None:
        return True, "자리가 비었고 마지막 사용 기록이 없다", None
    idle = int((now - last).total_seconds() // 60)
    if idle < idle_minutes:
        return False, f"마지막 사용 {kst(last)} (KST), {idle}분 전. {idle_minutes}분 동안 안 쓰면 끈다", last
    return True, f"자리가 비었고 마지막 사용 {kst(last)} (KST) 뒤 {idle}분 동안 안 썼다", last


def edge_set(cloudfront, distribution_id, enabled):
    """대문을 열거나 닫는다. 바꿨으면 True. 그사이 다른 실행이 바꿨으면(ETag) 다시 읽어 한 번 더."""
    for _ in range(3):
        got = cloudfront.get_distribution_config(Id=distribution_id)
        config = got["DistributionConfig"]
        if config.get("Enabled") is enabled:
            return False
        config["Enabled"] = enabled
        try:
            cloudfront.update_distribution(Id=distribution_id, IfMatch=got["ETag"], DistributionConfig=config)
            return True
        except Exception as err:
            if error_code(err) != "PreconditionFailed":
                raise
    raise RuntimeError("그사이 다른 실행이 CloudFront 를 계속 바꾼다")


def edge_closed(cloudfront, distribution_id, seconds, sleep, poll=10):
    """다 퍼져서(Deployed) 정말 닫힌 것을 볼 때까지 기다린다."""
    waited = 0
    while True:
        dist = cloudfront.get_distribution(Id=distribution_id)["Distribution"]
        if dist.get("Status") == "Deployed" and dist.get("DistributionConfig", {}).get("Enabled") is False:
            return True
        if waited >= seconds:
            return False
        sleep(poll)
        waited += poll


def clients():
    import boto3  # Lambda 런타임에 들어 있다. 테스트는 가짜를 넘긴다

    region = os.environ.get("AWS_REGION", "ap-northeast-2")
    return {
        "ec2": boto3.client("ec2", region_name=region),
        "ssm": boto3.client("ssm", region_name=region),
        "cloudfront": boto3.client("cloudfront"),
    }


def remaining_seconds(context):
    getter = getattr(context, "get_remaining_time_in_millis", None)
    return getter() / 1000 if getter else 900


def handler(event, context, aws=None, sleep=time.sleep, clock=None):
    aws = aws or clients()
    now = (clock or (lambda: datetime.now(timezone.utc)))()
    instance_id = os.environ["INSTANCE_ID"]
    distribution_id = os.environ["DISTRIBUTION_ID"]
    idle_minutes = int(os.environ.get("IDLE_MINUTES", "30"))
    ec2, ssm, cloudfront = aws["ec2"], aws["ssm"], aws["cloudfront"]

    def done(result, reason, **extra):
        log(result=result, reason=reason, **extra)
        return {"result": result, "reason": reason}

    state, launch_time = instance_state(ec2, instance_id)
    if state != "running":
        return done("skip", f"서버가 켜져 있지 않다({state})")

    command_status, out = run_shell(ssm, instance_id, STATUS_SCRIPT, 60, sleep)
    status = parse_kv(out)
    if command_status != "Success" or "LEASE_ACTIVE" not in status:
        log(result="error", reason="서버 상태를 읽지 못했다", ssm=command_status)
        raise RuntimeError(f"서버 상태를 읽지 못했다(SSM {command_status}). 끄지 않았다")

    last_command = last_ci_command(ssm, instance_id, now - timedelta(minutes=idle_minutes + 5))
    go, reason, _ = decide(status, launch_time, last_command, now, idle_minutes)
    if not go:
        return done("keep", reason)

    log(result="closing", reason=reason)
    changed = edge_set(cloudfront, distribution_id, False)
    # 서버 잠금(240초)·끄기·여유를 남기고 기다린다. Lambda 제한 시간은 900초다.
    if not edge_closed(cloudfront, distribution_id, max(60, remaining_seconds(context) - LOCK_SECONDS - 180), sleep):
        raise RuntimeError("CloudFront 가 제한 시간 안에 다 닫히지 않았다. 서버를 끄지 않았다(다음 실행이 이어 한다)")

    # 닫는 동안 누가 켜기·미리보기·배포를 시작했으면 다시 열고 그만둔다.
    newer = last_ci_command(ssm, instance_id, now)
    if newer:
        edge_set(cloudfront, distribution_id, True)
        return done("abort", f"닫는 사이 {kst(newer)} (KST) 에 새 작업이 와서 대문을 다시 열었다")

    lock_status, lock_out = run_shell(ssm, instance_id, LOCK_SCRIPT, LOCK_SECONDS, sleep)
    if parse_kv(lock_out).get("LOCK_FREE") != "1":
        edge_set(cloudfront, distribution_id, True)
        return done("abort", f"배포·백업이 {LOCK_SECONDS}초 안에 끝나지 않아 대문을 다시 열었다(SSM {lock_status})")

    ec2.stop_instances(InstanceIds=[instance_id])
    return done("stopped", reason, edge="닫았다" if changed else "이미 닫혀 있었다")
