# 개발 서버 배포 (준비 단계)

S1-JEONG-02 의 CD 준비물이다. 이미지와 배포 골격까지만 만들었고 **실제 배포는 G0 이후**다.

| 파일 | 역할 |
|---|---|
| `backend/Dockerfile` | Temurin 21 LTS 멀티스테이지(JDK 로 빌드 → JRE 로 실행). root 가 아닌 사용자(uid 10001)로 돈다 |
| `deploy/compose.dev.yml` | 서버에서 띄울 구성: 백엔드 이미지 + `postgres:18.6`. DB 포트는 열지 않고, 앱은 127.0.0.1 에만 연다 |
| `deploy/.env.example` | compose 가 읽는 값의 목록. 계정·비밀번호는 비워 둔다 |
| `.github/workflows/docker-build.yml` | 백엔드·배포 파일이 바뀐 PR·push 마다 이미지를 빌드하고 compose 로 띄워 요청을 받는지(`GET /api/v1/csrf` 200) 본다. 이미지를 올리지는 않는다 |
| `.github/workflows/deploy-dev.yml` | 수동 실행 전용. GHCR 에 올리고 SSH 로 서버에 배포한다. secret 이 하나라도 없으면 건너뛰고 Summary 에 이유를 남긴다 |

## 로컬 확인

```bash
bash scripts/verify.sh docker
```

이미지 빌드 → 실행 사용자가 root 가 아닌지 → `compose.dev.yml` 로 PostgreSQL 과 함께 띄워 `local` 프로필로 요청을 받는지(`GET /api/v1/csrf` 200) 확인한다. `/actuator/health` 는 지금 로그인해야 본다. 앱은 `127.0.0.1:18080` 에 뜨고 끝나면 지운다.

## 배포할 때 준비할 것 (운영진·정민서)

| 무엇 | 어디 | 비고 |
|---|---|---|
| Environment `dev` | Settings → Environments | 승인자를 지정하면 배포 전에 승인을 받는다 |
| `DEV_HOST`·`DEV_SSH_USER` | `dev` Environment secret | 배포 서버 주소·SSH 사용자 |
| `DEV_SSH_KEY` | `dev` Environment secret | 배포 전용 SSH 개인 키 |
| `DEV_SSH_KNOWN_HOSTS` | `dev` Environment secret | `ssh-keyscan <서버>` 결과. 처음 보는 호스트 키는 받아들이지 않는다 |
| `DEV_DB_NAME`·`DEV_DB_USERNAME`·`DEV_DB_PASSWORD` | `dev` Environment secret | 서버의 `.env`(권한 600)로만 들어간다 |
| GHCR 패키지 공개 범위 | Organization → Packages | 비공개면 서버가 이미지를 받을 때 로그인이 필요하다. 배포 워크플로는 실행 중에만 유효한 토큰으로 로그인하고 끝나면 로그아웃한다 |

노션 §7-1 의 목록(`DEV_HOST`·`DEV_SSH_KEY`·`DEV_DB_*`)에 SSH 사용자(`DEV_SSH_USER`)와 호스트 키 고정(`DEV_SSH_KNOWN_HOSTS`)을 더했다.

## 실배포를 막고 있는 것

1. `/actuator/health` 가 로그인해야 열린다(401). 배포 뒤 상태 확인·로드밸런서 health check 에 쓰려면 공개 여부를 정해야 한다(배재일·정민서).
2. 프론트가 API 주소를 `http://localhost:8080` 으로 하드코딩했고, CORS 는 `http://localhost:5173` 만 연다(배재일).
3. 배포 서버(EC2)·DB·secret·Environment·GHCR 공개 범위가 정해지지 않았다(정민서·운영진).
4. DEC-018(S8) 결정 전이다.
