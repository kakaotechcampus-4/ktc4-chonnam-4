# 인프라 (Terraform)

팀 AWS 계정(dev)의 리소스를 코드로 둔다. 설계는 [docs/cd-architecture.md](../docs/cd-architecture.md) 2·4·7절, 서버 배포는 [deploy/README.md](../deploy/README.md).

- **콘솔에서 만들거나 고치지 않는다.** 바꾸는 길은 PR(plan) → develop 머지 → 승인 → apply 하나뿐이다.
- 운영진 소유는 읽기만 한다(data source): EC2(켜진 1대), VPC·보안 그룹(규칙만 더한다), `ktc-github-deploy`, 서버 역할.
- 버전: Terraform `1.16.4`(이미지 `hashicorp/terraform:1.16.4`), `hashicorp/aws` `6.67.0`, `hashicorp/random` `3.9.1`. 디렉터리마다 `.terraform.lock.hcl` 을 커밋한다.

## 구조

```text
infra/
  live/dev/          dev 환경. 아래 모듈을 잇는다. backend "s3"(값은 infra.yml 이 넘긴다)
  modules/
    registry/        ECR — 태그 불변, 푸시할 때 스캔, 최근 이미지 10개
    storage/         DB 백업 버킷 — 공개 차단, 소유자 강제, 암호화, HTTPS 만, 14일 뒤 삭제
    edge/            CloudFront(PriceClass_200) + 웹 버킷(OAC) + SPA 주소 함수 + 보안 그룹 80번 규칙(CloudFront 접두사 목록만)
    config/          Parameter Store — 워크플로가 읽는 인프라 값 5개, 서버 .env 값 4개(비밀값 2개는 쓰기 전용)
    observability/   로그 그룹(30일), SNS 경보 주제·메일 구독, CPU 크레딧 과금·상태 검사 경보
    cost/            월 예산(실제 80%·예측 100% 알림), 이상 탐지(⚑4 확인 뒤 켠다)
  */tests/*.tftest.hcl   terraform test(mock provider, AWS 호출 없음)
```

| 파일 | 역할 |
|---|---|
| `.github/workflows/infra.yml` | PR: 검사만(자격증명 없음). develop: plan 표 → 승인 → apply. 매일: drift |
| `scripts/tf-run.sh` | 고정 이미지로 Terraform 을 돌린다. Actions 에서는 오류 출력의 계정 ID·ARN·IP·ID·메일을 가린다 |
| `scripts/tf-state.sh` | state 버킷 `neuringo-tfstate-<계정 ID>` 확인·생성(버전 관리·암호화·공개 차단·HTTPS 만·옛 버전 30일) |
| `scripts/tf-plan-guard.mjs` | plan JSON → 리소스·동작 표(값 없음) + 비용·안전 가드. `--config` 는 plan 전에 위험한 구성을 막는다 |

## 로컬 검사

Terraform 을 설치하지 않는다. Docker 로 같은 이미지를 쓴다. 자격증명 없이 돌고 AWS 를 부르지 않는다.

```bash
bash scripts/verify.sh infra
```

- 디렉터리마다 `init -backend=false -lockfile=readonly` → `validate` → `test`, 그리고 `fmt -check -recursive`·구성 검사(provisioner·외부 모듈·외부 provider·비밀값 data 금지).
- 처음 한 번은 프로바이더를 받는다(docker 볼륨 `neuringo-tf-plugins` 에 캐시).
- 한 곳만: `bash scripts/tf-run.sh infra/modules/edge test`. 줄 맞춤 고치기: `bash scripts/tf-run.sh infra fmt -recursive`.
- 실제 state 로 plan 하는 건 Actions(OIDC) 에서만 한다.

## 바꾸는 길

1. 브랜치에서 `infra/` 를 고치고 `bash scripts/verify.sh infra`.
2. PR → **Infra** 워크플로의 check: fmt·validate·test·구성 검사(모든 PR, 포크 포함).
   - PR 에는 AWS 자격증명을 주지 않는다(배포 역할에 쓰기 권한이 있다). 읽기 전용 plan 역할이 생기면 PR plan 을 켠다.
3. develop 머지 → plan(표) → Environment `infra` 승인 → apply
   - 승인 전에 plan 잡 Summary 의 표를 본다. 원문은 찍지 않는다. plan 잡은 읽기만 한다.
   - apply 잡은 plan 을 다시 만들어 가드를 다시 돌리고, 리소스·동작 목록이 승인한 것과 다르면 멈춘다.
4. 매일 09:00 KST drift 검사. 코드와 실제가 다르면 빨간불이다. 원인을 찾아 PR 로 맞춘다. 서버가 꺼져 있으면 건너뛴다.

손으로 만든 게 생기면 `import` 블록으로 코드에 가져온다.

### plan 가드

| 막는 것 | 풀어 주는 법 |
|---|---|
| 허용 목록 밖 리소스(NAT Gateway·ALB·EC2·RDS 등) | 비용을 검토하고 `ALLOWED_TYPES` 에 더하는 PR |
| 버킷·ECR·CloudFront 배포·비밀값 지우기·다시 만들기 | Actions → Infra → Run workflow(develop) → `allow_destroy` 체크 |
| 보안 그룹을 CIDR 로 열기(쪼갠 0.0.0.0/1 포함), 22번, 모든 프로토콜(-1) | 없다(접두사 목록만, 22번 대신 SSM) |
| 버킷 공개 차단 끄기·지우기, 누구에게나(`Principal "*"`) 허용하는 버킷 정책 | 없다 |
| (plan 전) provisioner·external/http 데이터 소스·null_resource·레포 밖 모듈·aws/random 밖 provider·`data "aws_ssm_parameter"` | 없다 |
| CloudFront `PriceClass_All` | 없다 |

## 비밀값 교체

비밀값(DB 비밀번호·아동 입장 코드 HMAC 키)은 Terraform 이 만들지만 값은 plan·state 에 남지 않는다(`ephemeral` + `value_wo`). 바꾸려면 `infra/live/dev/main.tf` 의 버전 기본값을 1 올린다.

- **HMAC 키** `hmac_secret_version`: PR → apply → Actions → Deploy 수동 실행(서버 `.env` 를 다시 만든다). 이미 발급한 입장 코드는 다시 발급해야 한다.
- **DB 비밀번호** `db_password_version`: postgres 는 볼륨을 처음 만들 때만 비밀번호를 쓴다. Parameter Store 만 바꾸면 앱이 DB 에 접속하지 못한다. 아래 순서를 이어서 한다.
  1. PR → apply(Parameter Store 에 새 값).
  2. AWS 콘솔 → EC2 → 연결 → Session Manager 에서 DB 안의 비밀번호를 새 값으로 바꾼다. 비밀번호는 명령줄에 싣지 않고 파이프로 넘긴다.

     ```bash
     sudo -i
     cd /opt/neuringo
     new=$(aws ssm get-parameter --name /neuringo/dev/db/password --with-decryption --query Parameter.Value --output text)
     printf "ALTER USER neuringo PASSWORD '%s';\n" "$new" |
       docker compose --env-file state/dev.env -f current/deploy/compose.dev.yml -p neuringo-dev exec -T postgres psql -q -U neuringo -d neuringo_dev
     unset new
     ```

  3. 곧바로 Actions → Deploy → Run workflow(`backend`). 새 `.env` 로 다시 뜬다. 2와 3 사이에는 새 DB 연결이 실패한다.

## 처음 한 번

1. 저장소 변수 `AWS_ACCOUNT_ID`(12자리). Settings → Secrets and variables → Actions → **Variables**.
2. Environment `infra`: Settings → Environments → New environment → **Required reviewers** 에 승인할 사람. 먼저 만들어 둔다. 없으면 GitHub 이 승인 없는 환경을 자동으로 만들어 apply 가 바로 돈다.
3. secret `ALERT_EMAILS`: JSON 배열(예: `["a@example.com"]`). 각 주소로 오는 AWS 구독 확인 메일의 링크를 눌러야 경보를 받는다. 없으면 구독 없이 만든다.
4. develop 에 들어간 뒤 Actions → **AWS probe** → ⚑1~5 확인. 특히 ⚑1(state 버킷·apply 권한), ⚑3(CloudFront), ⚑4(Budgets).
5. Actions → **Infra** → Run workflow(develop). state 버킷을 만들고 plan 표를 낸다 → 승인 → apply.
6. Actions → **Host setup** → **Deploy**([deploy/README.md](../deploy/README.md)).

켜진 EC2 가 정확히 1대여야 plan 이 돈다. 서버가 꺼져 있으면 plan 을 건너뛴다(Summary 에 이유).

## 서버를 끄고 켤 때 (Elastic IP 받기 전)

서버를 껐다 켜면 공인 IP 가 바뀐다. 옛 IP 는 다른 AWS 고객에게 갈 수 있어, 그동안 CloudFront 가 로그인·토큰을 그 IP 로 보낼 수 있다.

1. 끄기 전: Settings → Variables 에 `EDGE_ENABLED` = `false` → Actions → **Infra** → Run workflow(develop) → 승인 → CloudFront 가 꺼진다 → 그다음 서버를 끈다.
2. 켠 뒤: 서버를 켠다 → `EDGE_ENABLED` 를 `true` 로 바꾸거나 지운다 → Infra → 승인 → 새 주소로 CloudFront 가 켜진다.
3. 깜빡하면: 서버가 꺼질 때 경보 메일이 오고, 매일 drift 가 주소가 바뀐 것을 빨간불로 알린다.

Elastic IP 를 받으면 이 절차는 필요 없다(docs/cd-architecture.md 12절 요청 초안).

## 그 밖의 안전장치

- 운영진 보안 그룹에 인터넷 전체에서 80번(또는 모든 포트)으로 들어오는 규칙이 있으면 plan 이 멈춘다. 백엔드가 CloudFront 없이 평문으로 열리기 때문이다. 팀원이 연 규칙인지 확인해 지운다.
- CloudFront 는 한국에서만 연다(`allowed_countries`). 사용자 테스트 주소는 공개 로그에 찍지 않는다. 콘솔(CloudFront)이나 Parameter Store `/neuringo/dev/infra/cloudfront-domain` 에서 보고 팀에만 알린다.

## state

- S3 `neuringo-tfstate-<계정 ID>`, key `dev/terraform.tfstate`, 잠금은 S3 잠금 파일(`use_lockfile`, DynamoDB 없음).
- Terraform 이 자기 state 를 둘 곳이라 Terraform 밖(`scripts/tf-state.sh`)에서 만든다. develop 의 plan 이 실행마다 설정을 다시 건다.
- 비밀값은 없지만 리소스 ID·주소가 있다. 공개 로그에 찍지 않는다.
