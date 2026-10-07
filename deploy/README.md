# 서버 배포 (dev)

팀 AWS 계정의 EC2 1대(t3.medium, 서울)에 화면(nginx)·백엔드·DB 를 띄우고, CloudFront 가 그 서버 하나를 HTTPS 로 낸다. 설계는 [docs/cd-architecture.md](../docs/cd-architecture.md).
팀원은 **Actions → Dev server** 하나로 서버를 켜고 끄고, PR 을 머지 전에 띄워 본다(아래 "Dev server").
22번·SSH 키·액세스 키 없이 **GitHub OIDC + SSM** 으로만 배포한다(운영진 OIDC 가이드).

| 파일 | 역할 |
|---|---|
| `backend/Dockerfile` | Temurin 21 LTS 멀티스테이지(JDK 로 빌드 → JRE 로 실행). root 가 아닌 사용자(uid 10001)로 돈다 |
| `frontend/Dockerfile` · `frontend/nginx.conf` | 화면 이미지: Node 24 로 `vite build`(`VITE_API_BASE_URL=/api/v1`) → nginx-unprivileged(uid 101, 8080). `/api/` 는 backend 로 넘기고, 화면 주소는 index.html, 응답 헤더 `X-Neuringo-Release` 에 커밋 |
| `deploy/compose.dev.yml` | 서버 구성: web + backend + `postgres:18.6`. 밖으로 여는 것은 web 하나. 메모리 상한(backend 1.5 GiB·postgres 768 MiB·web 64 MiB), 로그 회전. 미리보기는 `APP_DB_*` 로 미리보기 DB 에 붙는다 |
| `deploy/host/params.txt` | 서버 `.env` 에 넣을 설정값 목록(Parameter Store 이름). 여기에 없는 값은 컨테이너에 들어가지 않는다 |
| `deploy/host/deploy.sh` | 서버에서: 자리 확인 → 설정값 → `.env`(600) → develop 은 배포 전 백업 / 미리보기는 미리보기 DB 새로 → 새 이미지 2개 → 상태 확인(API 200·화면 200·화면 헤더의 커밋) → 안 되면 직전 상태(미리보기가 끼어 있으면 미리보기 전 develop)로 되돌림 → 옛 이미지 정리 |
| `deploy/host/status.sh` | 서버 상태(올라간 것·자리·컨테이너·여유)와, 서버에서 CloudFront 주소로 화면·API·커밋을 불러 본 결과(KEY=값) |
| `deploy/host/backup.sh` · `restore-check.sh` | `pg_dump` → S3(14일), 그 백업을 서버의 임시 postgres 에 복구해 확인 |
| `deploy/host/setup.sh` | 서버 기본 설정: Docker·Compose·AWS CLI·swap 2 GiB·journald 상한·보안 업데이트·`/opt/neuringo`·컨테이너의 인스턴스 메타데이터(IMDS) 차단 |
| `scripts/ssm-run.sh` | Actions 에서 서버로 스크립트를 보내고(SSM) 끝날 때까지 기다린다. 출력의 계정 ID·ARN·IP 를 가린다 |
| `scripts/release-bundle.sh` | 배포 묶음(compose + `deploy/host`)을 서버 스크립트 하나로 만든다. 서버는 `releases/<SHA>` 에 풀고 `current` 를 돌린다 |
| `scripts/server-power.sh` | 서버(EC2) 켜기·끄기·상태·권한(DryRun). 끄기 권한이 없으면 "OS 를 끄면 중지"일 때만 서버 안에서 끈다 |
| `scripts/edge-toggle.sh` · `edge-toggle.mjs` | CloudFront 켜기(새 서버 주소를 원본에)·끄기(다 꺼질 때까지 확인). Enabled·원본 주소만 바꾸고, 읽은 버전(ETag)을 붙여 덮어쓰지 않는다 |
| `scripts/infra-outputs.sh` | Terraform 이 Parameter Store(`/neuringo/dev/infra/…`)에 적은 ECR·백업 버킷·CloudFront 값을 읽는다 |
| `.github/workflows/release.yml` | (재사용) 이미지 2개 빌드(AWS 권한 없음) → ECR → SSM 으로 서버 반영 → 서버에서 CloudFront 로 확인 → 결과 보고서 |
| `.github/workflows/deploy.yml` | develop 에서 두 CI 가 통과한 커밋을 배포한다. 수동 실행으로 커밋 SHA 를 넣으면 되돌린다 |
| `.github/workflows/dev-server.yml` | 팀원용 버튼: 상태 보기·켜기·PR 미리보기 올리기·미리보기 끝내기·끄기 |
| `.github/workflows/host-setup.yml` · `ops-backup.yml` | 서버 기본 설정(수동) · 매일 백업과 복구 확인(03:00 KST) |

서버 안 배치:

```text
/opt/neuringo/
  releases/<커밋 SHA>/   배포 묶음(compose·스크립트). 최근 5개
  current -> releases/…  마지막으로 성공한 묶음(매일 백업이 쓴다)
  state/dev.env          설정값(권한 600, 배포마다 다시 만든다)
  state/dev.release      지금 올라간 것(커밋·develop/preview·PR·누가·언제)
  state/preview.lease    미리보기 자리(PR·누가·끝나는 시각)
  state/dev.base.*       미리보기 중일 때만: 미리보기 전 develop 상태(되돌릴 곳)
  backups/{daily,pre-deploy}/  pg_dump(서버에는 최근 3개, S3 에 14일)
  logs/                  docker 출력·실패한 배포의 앱 로그(공개 로그에는 싣지 않는다)
```

## 처음 한 번

1. 저장소 변수 `AWS_ACCOUNT_ID`(12자리). Settings → Secrets and variables → Actions → **Variables**
2. Actions → **AWS probe** 로 권한 확인(설계 0단계)
3. Actions → **Infra**(develop)로 인프라를 만든다(ECR·백업 S3·CloudFront·보안 그룹 규칙·Parameter Store). 먼저 Environment `infra` 를 만든다([infra/README.md](../infra/README.md) "처음 한 번")
4. Actions → **Host setup** 으로 서버 기본 설정(IMDS 차단 포함 — 없으면 PR 미리보기가 멈춘다)
5. 설정값을 Parameter Store `/neuringo/dev/…` 에 둔다(`deploy/host/params.txt` 의 이름). 비밀값(DB 비밀번호·`CHILD_ACCESS_HMAC_SECRET`)은 Terraform 이 만들고, 외부 AI 키는 담당자가 직접 넣는다
6. Actions → **Dev server → 켜기**(develop 최신으로 맞춘다), 또는 **Deploy** 수동 실행

## Dev server (팀원용)

PR 미리보기는 **PR 에 `preview` 라벨**, 켜기·끄기·상태는 Actions → **Dev server** → Run workflow. 결과 보고서는 그 실행의 Summary 다. 테스트 주소는 디스코드 고정 메시지.

| 어디서 | 할 일 | 하는 일 |
|---|---|---|
| PR | `preview` 라벨 붙이기 | 이 PR 을 develop 에 합친 코드(워크플로·스크립트 포함)로 화면·API 를 띄운다(꺼져 있으면 켜기부터, 미리보기 DB 는 매번 새로, 자리 1시간) |
| PR | 라벨이 있는 PR 에 push | 다시 올린다(서버가 켜져 있고 자리가 비었거나 이 PR 것일 때만) |
| PR | 라벨 떼기·닫기·머지 | 이 PR 의 미리보기를 끝낸다(develop 최신으로) |
| PR | `dev-off` 라벨 | 끈다 |
| Run workflow | 상태 보기 · 켜기 · 미리보기 끝내기 · 끄기 | 서버·대문·올라간 것·자리 / 켜고 develop 최신으로 / 끝내기 / 대문을 닫고 다 닫힌 것을 확인한 뒤 서버 끄기 |

- 한 번에 PR 하나. 다른 PR 이 자리를 쓰는 중이면 누가·언제까지인지 알려 주고 멈춘다(끄기도 멈춘다).
- 같은 레포 PR(→ develop)만. 포크 PR 은 GitHub 이 자격증명을 주지 않는다. 충돌이 있는 PR 은 실행이 생기지 않는다.
- 미리보기 ↔ develop 을 바꾸면 화면에서 다시 로그인한다(DB 가 바뀐다).

## 되돌리기·확인

- 배포가 상태 확인을 통과하지 못하면 `deploy.sh` 가 **직전 상태로 자동으로 되돌린다**(Deploy 는 빨간색, Summary 에 "되돌렸다"). 이미지뿐 아니라 직전 `.env`(설정값)·직전 묶음의 compose 로 돌아가서, 새 설정값 탓에 죽어도 되돌릴 수 있다. 되돌리기도 안 되면 backend 를 멈춘다(계속 재시작하며 CPU 크레딧을 태우지 않게).
- 응답(200)만 보지 않고 실제로 새 이미지 2개가 떠 있는지, 화면 헤더가 이 커밋인지도 본다. DB 가 멈춰 있어도 배포 전 백업은 건너뛰지 않는다(DB 만 먼저 띄운다).
- 서버가 꺼져 있으면 develop 배포·매일 백업은 실패가 아니라 건너뜀(경고)이다. 이미지는 ECR 에 올려 두니 **Dev server → 켜기**가 develop 최신으로 맞춘다. PR 미리보기가 자리를 쓰는 중이어도 develop 배포는 건너뛴다(끝내기·시간이 지난 뒤 develop 으로).
- 서버는 콘솔이 아니라 **Dev server → 끄기**로 끈다(고정 IP 가 없어 CloudFront 를 먼저 닫아야 한다, [infra/README.md](../infra/README.md) "서버를 끄고 켤 때").
- 원하는 커밋으로 되돌리기: Actions → Deploy → Run workflow → `ref` 에 develop 의 커밋 SHA.
- DB 는 앞으로만 간다(Flyway). 되돌릴 수 있게 컬럼 추가 → 사용 전환 → 삭제를 서로 다른 릴리스로 나눈다. 스키마까지 되돌려야 하면 `backups/pre-deploy` 의 백업으로 복구한다.
- 서버 확인: AWS 콘솔 → EC2 → 연결 → Session Manager. `sudo ls /opt/neuringo/logs`, `sudo docker compose --env-file /opt/neuringo/state/dev.env -f /opt/neuringo/current/deploy/compose.dev.yml -p neuringo-dev ps`

## 로컬 확인

```bash
bash scripts/verify.sh docker      # 이미지 2개 빌드 → root 아님 → compose 로 기동 → 화면을 거쳐 API·화면·SPA 주소·커밋 헤더
bash scripts/verify.sh workflows   # 워크플로·스크립트 린트 + 서버 스크립트·켜기·끄기·대문 여닫기 자체 검사(가짜 docker·aws·curl)
```

`/actuator/health` 는 지금 로그인해야 본다(공개 여부는 팀 결정 전). 그래서 상태 확인은 로그인 없이 여는 `GET /api/v1/csrf` 200 으로 한다.

## 아직 남은 것

1. DEC-018(배포·롤백 규칙)은 스프린트 8 전에 팀이 확정한다. 위 방식이 제안이다.
2. 미리보기 DB 는 비어 있다(가입부터). 테스트용 강사·반·아동을 넣는 시드는 아직 없다.
