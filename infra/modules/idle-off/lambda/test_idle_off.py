"""idle_off.py 자체 검사(AWS 없이 가짜 클라이언트로). python3 -m unittest discover -s infra/modules/idle-off/lambda"""

import os
import unittest
from datetime import datetime, timedelta, timezone

import idle_off

NOW = datetime(2026, 10, 11, 4, 30, tzinfo=timezone.utc)  # 13:30 KST
DNS = "ec2-203-0-113-20.ap-northeast-2.compute.amazonaws.com"
OLD_DNS = "ec2-198-51-100-7.ap-northeast-2.compute.amazonaws.com"


def at(minutes_ago):
    return NOW - timedelta(minutes=minutes_ago)


def iso(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


class AwsError(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.response = {"Error": {"Code": code}}


class FakeEC2:
    def __init__(self, state="running", launched=None, dns=DNS):
        self.state, self.launched, self.dns, self.stopped = state, launched or at(300), dns, 0
        self.edge_when_stopped, self.edge = None, None  # 끌 때 대문 상태(run_handler 가 이어 준다)

    def describe_instances(self, InstanceIds):
        one = {"State": {"Name": self.state}, "LaunchTime": self.launched, "PublicDnsName": self.dns}
        return {"Reservations": [{"Instances": [one]}]}

    def stop_instances(self, InstanceIds):
        self.stopped += 1
        self.edge_when_stopped = (self.edge.status, self.edge.enabled)


class FakeSSM:
    def __init__(self, status="", lock="LOCK_FREE=1", commands=(), status_result="Success", later_commands=()):
        self.outputs = {"status.sh": (status_result, status), "deploy.lock": ("Success", lock)}
        self.commands = list(commands)
        self.later_commands = list(later_commands)  # 대문을 닫는 사이에 새로 온 명령
        self.sent, self.pending = [], {}
        self.not_yet = True

    def send_command(self, **kwargs):
        self.sent.append(kwargs)
        script = kwargs["Parameters"]["commands"][0]
        key = "status.sh" if "status.sh" in script else "deploy.lock"
        command_id = f"c{len(self.sent)}"
        self.pending[command_id] = self.outputs[key]
        return {"Command": {"CommandId": command_id}}

    def get_command_invocation(self, CommandId, InstanceId):
        if self.not_yet:  # 보낸 직후에는 아직 없다고 온다
            self.not_yet = False
            raise AwsError("InvocationDoesNotExist")
        status, out = self.pending[CommandId]
        return {"Status": status, "StandardOutputContent": out}

    def list_commands(self, InstanceId, Filters, NextToken=None):
        since = idle_off.parse_time(Filters[0]["value"])
        pool = self.commands + (self.later_commands if since >= NOW else [])
        found = [c for c in pool if c["RequestedDateTime"] > since]
        if NextToken is None and len(found) > 1:  # 쪽 나눔도 따라가는지
            return {"Commands": found[:1], "NextToken": "next"}
        return {"Commands": found[1:] if NextToken else found}


class FakeCloudFront:
    def __init__(self, enabled=True, in_progress_polls=2, conflicts=0, origin=DNS):
        self.enabled, self.status, self.origin = enabled, "Deployed", origin
        self.in_progress_polls, self.conflicts = in_progress_polls, conflicts
        self.updates, self.left = [], 0

    def get_distribution_config(self, Id):
        origins = {"Quantity": 1, "Items": [{"Id": "app", "DomainName": self.origin}]}
        config = {"Enabled": self.enabled, "Comment": "그대로", "Origins": origins}
        return {"ETag": f"E{len(self.updates)}", "DistributionConfig": config}

    def update_distribution(self, Id, IfMatch, DistributionConfig):
        if self.conflicts:
            self.conflicts -= 1
            raise AwsError("PreconditionFailed")
        assert DistributionConfig["Comment"] == "그대로", "켜기·원본 주소 말고는 바꾸지 않는다"
        self.updates.append(DistributionConfig["Enabled"])
        self.origin = DistributionConfig["Origins"]["Items"][0]["DomainName"]
        self.enabled, self.status, self.left = DistributionConfig["Enabled"], "InProgress", self.in_progress_polls

    def get_distribution(self, Id):
        if self.left:
            self.left -= 1
        else:
            self.status = "Deployed"
        return {"Distribution": {"Status": self.status, "DistributionConfig": {"Enabled": self.enabled}}}


class Context:
    def get_remaining_time_in_millis(self):
        return 890_000


def status(**over):
    values = {"LEASE_ACTIVE": "0", "STATUS_BOOTED_AT": iso(at(300)), "STATUS_AT": iso(at(200)), "STATUS_LAST_REQUEST": iso(at(90))}
    values.update(over)
    return "\n".join(f"{k}={v}" for k, v in values.items() if v is not None)


def ci(minutes_ago, comment="neuringo"):
    return {"RequestedDateTime": at(minutes_ago), "Comment": comment}


class IdleOffTest(unittest.TestCase):
    real_log = staticmethod(idle_off.log)

    def setUp(self):
        os.environ.update(INSTANCE_ID="i-0123456789abcdef0", DISTRIBUTION_ID="E2EXAMPLE", IDLE_MINUTES="30")
        os.environ.pop("ALWAYS_ON_UNTIL", None)
        self.sleeps, self.logs = [], []
        self.addCleanup(setattr, idle_off, "log", idle_off.log)
        idle_off.log = lambda **fields: self.logs.append(fields)

    def run_handler(self, ec2=None, ssm=None, cf=None):
        self.ec2, self.ssm, self.cf = ec2 or FakeEC2(), ssm or FakeSSM(status()), cf or FakeCloudFront()
        self.ec2.edge = self.cf
        aws = {"ec2": self.ec2, "ssm": self.ssm, "cloudfront": self.cf}
        return idle_off.handler({}, Context(), aws=aws, sleep=self.sleeps.append, clock=lambda: NOW)

    def test_server_off_does_nothing(self):
        result = self.run_handler(ec2=FakeEC2(state="stopped", dns=""), cf=FakeCloudFront(enabled=False))
        self.assertEqual(result["result"], "skip")
        self.assertEqual(self.ssm.sent, [])
        self.assertEqual(self.cf.updates, [])

    def test_server_off_with_open_edge_closes_edge(self):
        result = self.run_handler(ec2=FakeEC2(state="stopped", dns=""))
        self.assertEqual(result["result"], "skip")
        self.assertEqual(self.cf.updates, [False], "콘솔·AWS 점검으로 꺼진 서버의 옛 IP 를 가리키지 않게 닫는다")
        self.assertEqual(self.cf.origin, DNS, "원본 주소는 그대로 둔다")
        self.assertEqual(self.ssm.sent, [])

    def test_starting_or_stopping_server_leaves_edge_to_dev_server(self):
        for state in ("pending", "stopping"):
            self.run_handler(ec2=FakeEC2(state=state), cf=FakeCloudFront(origin=OLD_DNS))
            self.assertEqual(self.cf.updates, [], state)

    def test_running_server_with_old_origin_fixes_origin(self):
        cf = FakeCloudFront(origin=OLD_DNS)
        result = self.run_handler(ssm=FakeSSM(status(STATUS_LAST_REQUEST=iso(at(3)))), cf=cf)
        self.assertEqual(result["result"], "keep")
        self.assertEqual((cf.updates, cf.origin), ([True], DNS), "열린 채로 원본만 지금 주소로")
        self.assertIn("고쳤다", self.logs[-1]["edge"])

    def test_closed_edge_is_not_reopened(self):
        cf = FakeCloudFront(enabled=False, origin=OLD_DNS)
        self.run_handler(ssm=FakeSSM(status(STATUS_LAST_REQUEST=iso(at(3)))), cf=cf)
        self.assertEqual(cf.updates, [], "닫힌 대문은 Dev server 켜기·끄기가 다룬다")

    def test_always_on_keeps_idle_server_without_asking_it(self):
        os.environ["ALWAYS_ON_UNTIL"] = "2026-11-20T23:59:00+09:00"
        result = self.run_handler()
        self.assertEqual(result["result"], "keep")
        self.assertIn("24시간 모드", result["reason"])
        self.assertIn("11/20 23:59", result["reason"])
        self.assertEqual((self.ec2.stopped, self.cf.updates, self.ssm.sent), (0, [], []))

    def test_always_on_still_fixes_edge_after_reboot(self):
        os.environ["ALWAYS_ON_UNTIL"] = "2026-11-20T23:59:00+09:00"
        result = self.run_handler(cf=FakeCloudFront(origin=OLD_DNS))
        self.assertEqual(result["result"], "keep")
        self.assertEqual((self.cf.updates, self.cf.origin), ([True], DNS))

    def test_always_on_does_not_start_stopped_server(self):
        os.environ["ALWAYS_ON_UNTIL"] = "2026-11-20T23:59:00+09:00"
        result = self.run_handler(ec2=FakeEC2(state="stopped", dns=""), cf=FakeCloudFront(enabled=False))
        self.assertEqual(result["result"], "skip", "끄기를 누른 사람이 이긴다(켜기 권한도 없다)")

    def test_always_on_after_its_end_turns_off_as_usual(self):
        os.environ["ALWAYS_ON_UNTIL"] = iso(at(1))
        self.assertEqual(self.run_handler()["result"], "stopped")

    def test_idle_for_30_minutes_closes_edge_first_then_stops(self):
        result = self.run_handler()
        self.assertEqual(result["result"], "stopped", result)
        self.assertEqual(self.cf.updates, [False])
        self.assertEqual(self.ec2.stopped, 1)
        self.assertEqual(self.ec2.edge_when_stopped, ("Deployed", False), "대문이 다 닫힌 것을 본 뒤에만 끈다(옛 IP)")
        self.assertIn("12:00 (KST) 뒤 90분", result["reason"])
        self.assertTrue(all(c["Comment"] == idle_off.OWN_COMMENT for c in self.ssm.sent), "제 명령은 표시해 둔다(활동으로 세지 않게)")
        self.assertIn("deploy.lock", self.ssm.sent[-1]["Parameters"]["commands"][0], "끄기 직전에 배포·백업 잠금을 기다린다")

    def test_recent_request_keeps_server(self):
        result = self.run_handler(ssm=FakeSSM(status(STATUS_LAST_REQUEST=iso(at(29)))))
        self.assertEqual(result["result"], "keep")
        self.assertIn("29분 전", result["reason"])
        self.assertEqual(self.ec2.stopped, 0)
        self.assertEqual(self.cf.updates, [])

    def test_just_booted_or_deployed_keeps_server(self):
        self.assertEqual(self.run_handler(ec2=FakeEC2(launched=at(10)))["result"], "keep")
        self.assertEqual(self.run_handler(ssm=FakeSSM(status(STATUS_AT=iso(at(20)))))["result"], "keep")

    def test_preview_lease_keeps_server(self):
        result = self.run_handler(ssm=FakeSSM(status(LEASE_ACTIVE="1", LEASE_PR="56", LEASE_UNTIL=iso(at(-20)))))
        self.assertEqual(result["result"], "keep")
        self.assertIn("PR #56", result["reason"])
        self.assertIn("13:50", result["reason"])

    def test_github_command_during_build_keeps_server(self):
        result = self.run_handler(ssm=FakeSSM(status(), commands=[ci(120), ci(4)]))
        self.assertEqual(result["result"], "keep", "미리보기 빌드 중(자리 확인 SSM 명령 뒤)에는 끄지 않는다")

    def test_own_commands_do_not_count_as_use(self):
        result = self.run_handler(ssm=FakeSSM(status(), commands=[ci(5, comment=idle_off.OWN_COMMENT)]))
        self.assertEqual(result["result"], "stopped")

    def test_unreadable_status_fails_without_stopping(self):
        for ssm in (FakeSSM("", status_result="Failed"), FakeSSM("garbage")):
            with self.assertRaises(RuntimeError):
                self.run_handler(ssm=ssm)
            self.assertEqual(self.ec2.stopped, 0)
            self.assertEqual(self.cf.updates, [])

    def test_missing_status_script_uses_boot_time(self):
        result = self.run_handler(ssm=FakeSSM("LEASE_ACTIVE=0"))
        self.assertEqual(result["result"], "stopped")

    def test_edge_not_closed_in_time_keeps_server_on(self):
        with self.assertRaises(RuntimeError):
            self.run_handler(cf=FakeCloudFront(in_progress_polls=10_000))
        self.assertEqual(self.ec2.stopped, 0)

    def test_edge_conflict_rereads_and_retries(self):
        result = self.run_handler(cf=FakeCloudFront(conflicts=1))
        self.assertEqual(result["result"], "stopped")
        self.assertEqual(self.cf.updates, [False])

    def test_already_closed_edge_still_stops(self):
        result = self.run_handler(cf=FakeCloudFront(enabled=False))
        self.assertEqual(result["result"], "stopped")
        self.assertEqual(self.cf.updates, [])

    def test_new_work_while_closing_reopens_edge_and_aborts(self):
        later = {"RequestedDateTime": NOW + timedelta(minutes=2), "Comment": "neuringo"}
        result = self.run_handler(ssm=FakeSSM(status(), later_commands=[later]))
        self.assertEqual(result["result"], "abort")
        self.assertEqual(self.cf.updates, [False, True])
        self.assertEqual(self.ec2.stopped, 0)

    def test_busy_deploy_lock_reopens_edge_and_aborts(self):
        result = self.run_handler(ssm=FakeSSM(status(), lock=""))
        self.assertEqual(result["result"], "abort")
        self.assertEqual(self.cf.updates, [False, True])
        self.assertEqual(self.ec2.stopped, 0)

    def test_logs_do_not_print_ids(self):
        import contextlib
        import io

        idle_off.log = self.real_log  # 이 검사만 진짜 로그(표준 출력)를 본다
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            self.run_handler()
        self.assertNotIn("i-0123456789abcdef0", buf.getvalue())
        self.assertNotIn("E2EXAMPLE", buf.getvalue())
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            self.run_handler(cf=FakeCloudFront(origin=OLD_DNS))
        self.assertNotIn("compute.amazonaws.com", buf.getvalue(), "서버 주소도 찍지 않는다")


if __name__ == "__main__":
    unittest.main()
