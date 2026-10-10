// CloudFront 배포 설정에서 "켜기(Enabled)"와 원본(app) 주소만 바꾼다. scripts/edge-toggle.sh 가 부른다.
//
//   node scripts/edge-toggle.mjs <get-distribution-config 출력.json> on <EC2 공인 DNS> <새 설정.json>
//   node scripts/edge-toggle.mjs <get-distribution-config 출력.json> off - <새 설정.json>
//
// 출력(표준 출력, KEY=값): CHANGED=0|1, ETAG=<버전 토큰>, ENABLED_BEFORE=true|false, ORIGIN_CHANGED=0|1
// 주소·배포 ID 는 찍지 않는다(공개 로그). 켜기·주소 말고 다른 값이 바뀌면 실패한다(Terraform 이 관리하는 구조는 건드리지 않는다).
import { readFileSync, writeFileSync } from "node:fs"
import { pathToFileURL } from "node:url"

export const ORIGIN_ID = "app"
// 구조 변경(원본 하나, 2026-10-07) 전의 API 원본 이름. 그 apply 전에 켜기를 눌러도 서버 주소를 넣을 수 있게 받는다.
const LEGACY_ORIGIN_ID = "api"
const originOf = (config) => {
  const items = config.Origins?.Items ?? []
  return items.find((o) => o.Id === ORIGIN_ID) ?? items.find((o) => o.Id === LEGACY_ORIGIN_ID)
}
const EC2_DNS = /^ec2-[0-9]{1,3}(-[0-9]{1,3}){3}\.[a-z0-9-]+\.compute\.amazonaws\.com$/

function withoutToggled(config) {
  const copy = structuredClone(config)
  delete copy.Enabled
  const origin = originOf(copy)
  if (origin) delete origin.DomainName
  return copy
}

/**
 * 켜거나 끈 설정을 돌려준다. 켤 때는 원본 주소도 새 서버 주소로 바꾼다(서버를 껐다 켜면 공인 주소가 바뀐다).
 * @returns {{ changed: boolean, originChanged: boolean, config: object }}
 */
export function toggle(config, action, originDomain) {
  if (action !== "on" && action !== "off") throw new Error("on 또는 off")
  const next = structuredClone(config)
  next.Enabled = action === "on"
  let originChanged = false
  if (action === "on") {
    if (!EC2_DNS.test(originDomain ?? "")) throw new Error("켤 때는 EC2 공인 DNS(ec2-…compute.amazonaws.com)가 필요하다")
    const origin = originOf(next)
    if (!origin) throw new Error(`원본 ${ORIGIN_ID} 가 없다(Terraform 구조를 확인한다)`)
    originChanged = origin.DomainName !== originDomain
    origin.DomainName = originDomain
  }
  if (JSON.stringify(withoutToggled(config)) !== JSON.stringify(withoutToggled(next))) {
    throw new Error("켜기·원본 주소 말고 다른 설정이 바뀌었다")
  }
  return { changed: config.Enabled !== next.Enabled || originChanged, originChanged, config: next }
}

function main([input, action, domain, output]) {
  if (!input || !action || !output) {
    console.error("사용법: node scripts/edge-toggle.mjs <get.json> on|off <EC2 공인 DNS|-> <새 설정.json>")
    process.exit(2)
  }
  const got = JSON.parse(readFileSync(input, "utf8"))
  if (!got.ETag || !got.DistributionConfig) throw new Error("get-distribution-config 출력이 아니다")
  const result = toggle(got.DistributionConfig, action, domain === "-" ? undefined : domain)
  writeFileSync(output, JSON.stringify(result.config))
  console.log(`CHANGED=${result.changed ? 1 : 0}`)
  console.log(`ETAG=${got.ETag}`)
  console.log(`ENABLED_BEFORE=${got.DistributionConfig.Enabled}`)
  console.log(`ORIGIN_CHANGED=${result.originChanged ? 1 : 0}`)
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main(process.argv.slice(2))
  } catch (error) {
    console.error(`edge-toggle: ${error.message}`)
    process.exit(1)
  }
}
