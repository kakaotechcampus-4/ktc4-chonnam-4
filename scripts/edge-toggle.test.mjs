// edge-toggle.mjs 자체 검사(node:test). bash scripts/verify.sh workflows 에 포함.
import assert from "node:assert/strict"
import { test } from "node:test"
import { toggle } from "./edge-toggle.mjs"

const OLD = "ec2-203-0-113-10.ap-northeast-2.compute.amazonaws.com"
const NEW = "ec2-198-51-100-7.ap-northeast-2.compute.amazonaws.com"

function config(enabled, domain = OLD) {
  return {
    CallerReference: "terraform-1",
    Comment: "neuringo dev",
    Enabled: enabled,
    Origins: {
      Quantity: 1,
      Items: [{ Id: "app", DomainName: domain, CustomOriginConfig: { HTTPPort: 80, OriginReadTimeout: 60 } }],
    },
    DefaultCacheBehavior: { TargetOriginId: "app", ViewerProtocolPolicy: "redirect-to-https" },
    Restrictions: { GeoRestriction: { RestrictionType: "whitelist", Quantity: 1, Items: ["KR"] } },
  }
}

test("켜면 새 서버 주소로 바꾸고 켠다", () => {
  const r = toggle(config(false), "on", NEW)
  assert.equal(r.changed, true)
  assert.equal(r.originChanged, true)
  assert.equal(r.config.Enabled, true)
  assert.equal(r.config.Origins.Items[0].DomainName, NEW)
})

test("끄면 주소는 그대로 두고 끈다", () => {
  const r = toggle(config(true), "off")
  assert.equal(r.changed, true)
  assert.equal(r.config.Enabled, false)
  assert.equal(r.config.Origins.Items[0].DomainName, OLD)
})

test("이미 원하는 상태면 바꿀 것이 없다", () => {
  assert.equal(toggle(config(true, NEW), "on", NEW).changed, false)
  assert.equal(toggle(config(false), "off").changed, false)
})

test("켜져 있어도 서버 주소가 바뀌었으면 고친다", () => {
  const r = toggle(config(true, OLD), "on", NEW)
  assert.equal(r.changed, true)
  assert.equal(r.originChanged, true)
})

test("나머지 설정(캐시·나라 제한 등)은 그대로다", () => {
  const before = config(false)
  const r = toggle(before, "on", NEW)
  assert.deepEqual(r.config.Restrictions, before.Restrictions)
  assert.deepEqual(r.config.DefaultCacheBehavior, before.DefaultCacheBehavior)
  assert.equal(r.config.CallerReference, before.CallerReference)
  assert.equal(before.Enabled, false, "받은 설정은 고치지 않는다")
})

test("EC2 공인 DNS 가 아니면 켜지 않는다(엉뚱한 곳으로 보내지 않게)", () => {
  assert.throws(() => toggle(config(false), "on", "evil.example.com"), /EC2 공인 DNS/)
  assert.throws(() => toggle(config(false), "on", ""), /EC2 공인 DNS/)
  assert.throws(() => toggle(config(false), "on", `${NEW}.evil.example.com`), /EC2 공인 DNS/)
})

test("구조 변경 전(S3 화면 + api 원본)이면 api 원본의 주소만 바꾼다", () => {
  const old = config(false)
  old.Origins.Quantity = 2
  old.Origins.Items = [{ Id: "web", DomainName: "neuringo-dev-web.s3.ap-northeast-2.amazonaws.com" }, { ...old.Origins.Items[0], Id: "api" }]
  const r = toggle(old, "on", NEW)
  assert.equal(r.config.Origins.Items[1].DomainName, NEW)
  assert.equal(r.config.Origins.Items[0].DomainName, "neuringo-dev-web.s3.ap-northeast-2.amazonaws.com")
})

test("서버 원본이 없으면 켜지 않는다", () => {
  const odd = config(false)
  odd.Origins.Items[0].Id = "something"
  assert.throws(() => toggle(odd, "on", NEW), /원본 app/)
})

test("on·off 말고는 받지 않는다", () => {
  assert.throws(() => toggle(config(true), "restart"), /on 또는 off/)
})
