# 서버 배포 (dev)

팀 AWS 계정의 EC2 1대(t3.medium, 서울)에 백엔드와 DB 를 띄우고, 프론트는 S3 + CloudFront 로 낸다. 설계는 [docs/cd-architecture.md](../docs/cd-architecture.md).
22번·SSH 키·액세스 키 없이 **GitHub OIDC + SSM** 으로만 배포한다(운영진 OIDC 가이드).

| 파일 | 역할 |
|---|---|
| `backend/Dockerfile` | Temurin 21 LTS 멀티스테이지(JDK 로 빌드 → JRE 로 실행). root 가 아닌 사용자(uid 10001)로 돈다 |
| `deploy/compose.dev.yml` | 서버 구성: 백엔드 이미지 + `postgres:18.6`. 메모리 상한(backend 1.5 GiB·postgres 768 MiB), 로그 회전. DB 포트는 열지 않는다 |
| `deploy/host/params.txt` | 서버 `.env` 에 넣을 설정값 목록(Parameter Store 이름). 여기에 없는 값은 컨테이너에 들어가지 않는다 |
| `deploy/host/deploy.sh` | 서버에서: 설정값 → `.env`(600) → 배포 전 백업 → 새 이미지 → 상태 확인(`GET /api/v1/csrf` 200) → 안 되면 직전 이미지로 되돌림 → 옛 이미지 정리 |
| `deploy/host/backup.sh` · `restore-check.sh` | `pg_dump` → S3(14일), 그 백업을 서버의 임시 postgres 에 복구해 확인 |
| `deploy/host/setup.sh` | 서버 기본 설정: Docker·Compose·AWS CLI·swap 2 GiB·journald 상한·보안 업데이트·`/opt/neuringo` |
| `scripts/ssm-run.sh` | Actions 에서 서버로 스크립트를 보내고(SSM) 끝날 때까지 기다린다. 출력의 계정 ID·ARN·IP 를 가린다 |
| `scripts/release-bundle.sh` | 배포 묶음(compose + `deploy/host`)을 서버 스크립트 하나로 만든다. 서버는 `releases/<SHA>` 에 풀고 `current` 를 돌린다 |
| `scripts/web-deploy.sh` | 프론트 `dist` → S3(assets 1년 캐시·index.html 캐시 안 함) → CloudFront 캐시 비우기 |
| `scripts/infra-outputs.sh` | Terraform 이 Parameter Store(`/neuringo/dev/infra/…`)에 적은 ECR·버킷·CloudFront 값을 읽는다 |
| `.github/workflows/deploy.yml` | develop 에서 CI 가 통과한 커밋을 배포한다. 수동 실행으로 커밋 SHA 를 넣으면 되돌린다 |
| `.github/workflows/host-setup.yml` · `ops-backup.yml` | 서버 기본 설정(수동) · 매일 백업과 복구 확인(03:00 KST) |

서버 안 배치:

```text
/opt/neuringo/
  releases/<커밋 SHA>/   배포 묶음(compose·스크립트). 최근 5개
  current -> releases/…  마지막으로 성공한 묶음(매일 백업이 쓴다)
  state/dev.env          설정값(권한 600, 배포마다 다시 만든다)
  state/dev.current      지금 이미지 · dev.previous 직전 이미지
  backups/{daily,pre-deploy}/  pg_dump(서버에는 최근 3개, S3 에 14일)
  logs/                  docker 출력·실패한 배포의 앱 로그(공개 로그에는 싣지 않는다)
```

## 처음 한 번

1. 저장소 변수 `AWS_ACCOUNT_ID`(12자리). Settings → Secrets and variables → Actions → **Variables**
2. Actions → **AWS probe** 로 권한 확인(설계 0단계)
3. Actions → **Infra**(develop)로 인프라를 만든다(ECR·S3·CloudFront·보안 그룹 규칙·Parameter Store). 먼저 Environment `infra` 를 만든다([infra/README.md](../infra/README.md) "처음 한 번")
4. Actions → **Host setup** 으로 서버 기본 설정
5. 설정값을 Parameter Store `/neuringo/dev/…` 에 둔다(`deploy/host/params.txt` 의 이름). 비밀값(DB 비밀번호·`CHILD_ACCESS_HMAC_SECRET`)은 Terraform 이 만들고, 외부 AI 키는 담당자가 직접 넣는다
6. Actions → **Deploy** 수동 실행(또는 develop 에 머지)

## 되돌리기·확인

- 배포가 상태 확인을 통과하지 못하면 `deploy.sh` 가 **직전 상태로 자동으로 되돌린다**(Deploy 는 빨간색, Summary 에 "되돌렸다"). 이미지뿐 아니라 직전 `.env`(설정값)·직전 묶음의 compose 로 돌아가서, 새 설정값 탓에 죽어도 되돌릴 수 있다. 되돌리기도 안 되면 backend 를 멈춘다(계속 재시작하며 CPU 크레딧을 태우지 않게).
- 응답(200)만 보지 않고 실제로 새 이미지가 떠 있는지도 본다. DB 가 멈춰 있어도 배포 전 백업은 건너뛰지 않는다(DB 만 먼저 띄운다).
- 서버가 꺼져 있으면 백엔드 배포·매일 백업은 실패가 아니라 건너뜀(경고)이다. 이미지는 ECR 에 올려 두니 서버를 켠 뒤 Deploy 를 수동 실행한다. 서버를 끄고 켜는 순서는 [infra/README.md](../infra/README.md) "서버를 끄고 켤 때".
- 원하는 커밋으로 되돌리기: Actions → Deploy → Run workflow → `ref` 에 develop 의 커밋 SHA.
- DB 는 앞으로만 간다(Flyway). 되돌릴 수 있게 컬럼 추가 → 사용 전환 → 삭제를 서로 다른 릴리스로 나눈다. 스키마까지 되돌려야 하면 `backups/pre-deploy` 의 백업으로 복구한다.
- 서버 확인: AWS 콘솔 → EC2 → 연결 → Session Manager. `sudo ls /opt/neuringo/logs`, `sudo docker compose --env-file /opt/neuringo/state/dev.env -f /opt/neuringo/current/deploy/compose.dev.yml -p neuringo-dev ps`

## 로컬 확인

```bash
bash scripts/verify.sh docker      # 이미지 빌드 → root 아님 → compose 로 기동 → GET /api/v1/csrf 200
bash scripts/verify.sh workflows   # 워크플로·스크립트 린트 + 서버 스크립트 자체 검사(가짜 docker·aws·curl)
```

`/actuator/health` 는 지금 로그인해야 본다(공개 여부는 팀 결정 전). 그래서 상태 확인은 로그인 없이 여는 `GET /api/v1/csrf` 200 으로 한다.

## 아직 남은 것

1. 인프라는 코드(`infra/`)만 있고 아직 적용 전이다(AWS probe → Infra apply). 그 전까지 Deploy 는 건너뛰고 Summary 에 이유를 남긴다.
2. 프론트 API 주소가 코드에 고정이다(`frontend/src/features/instructor/api.ts`). `import.meta.env.VITE_API_BASE_URL` 로 바뀌기 전까지 프론트 배포는 건너뛴다.
3. DEC-018(배포·롤백 규칙)은 스프린트 8 전에 팀이 확정한다. 위 방식이 제안이다.
