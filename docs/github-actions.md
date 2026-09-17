# GitHub Actions 도입 정리 (백엔드)

`feature/minseo-backend-init` 브랜치에서 시범 적용한 백엔드 CI/정적분석/로컬 DB 자동화 내용 정리. develop/main에는 아직 반영 안 됨 ([PR #5](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/pull/5) 리뷰 대기).

## 배경

기존 `.github/workflows/`에는 멘토 배정, PR 컨벤션 안내, 디스코드 알림만 있고, **PR마다 실제로 빌드·테스트가 자동으로 도는 워크플로가 없었음.** 개발 커뮤니티 트렌드 조사 후, 비용 대비 효과가 큰 항목부터 추가함.

## 추가한 것

### 1. Backend CI — `.github/workflows/backend-ci.yml`

- **트리거**: `backend/**` 변경이 있는 `push`/`pull_request` (대상: `main`, `develop`)
- **동작**: JDK 21 세팅 → GitHub Actions `services` 블록으로 Postgres 17 컨테이너 기동(`neuringo_local` / `neuringo` / `neuringo`) → `./gradlew build` (테스트 포함)
- **PR #5 첫 실행 결과**: [성공](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/actions/runs/35173248166) — `build-and-test` job 전체 success

### 2. CodeQL — `.github/workflows/codeql.yml`

- **트리거**: `push`/`pull_request` (`main`, `develop`) + 매주 월요일 03:30 UTC 정기 스캔
- **동작**: `java-kotlin` 언어로 정적분석, GitHub Security 탭에 결과 표시
- **비용**: public repo라 무료
- **PR #5 실행**: [Actions 탭](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/actions/runs/35173248143)에서 확인 가능

### 3. 로컬 Postgres 자동 기동 — `spring-boot-docker-compose` + `backend/compose.yaml`

- `backend/build.gradle`에 `developmentOnly 'org.springframework.boot:spring-boot-docker-compose'` 추가
- `backend/compose.yaml`에 Postgres 17 서비스 정의 (`neuringo_local` / `neuringo` / `neuringo`, 포트 5432)
- 프로필 지정 없이 `./gradlew bootRun`(또는 IDE 실행)하면 Spring Boot가 `compose.yaml`을 읽어 컨테이너를 자동으로 띄우고 연결까지 자동 설정
- 로컬 검증 로그: 컨테이너 생성 → `Healthy` → 앱 기동 → `/actuator/health` → `{"status":"UP"}` 확인
- 기존처럼 로컬에 Postgres를 직접 설치해서 쓰고 싶다면, `SPRING_PROFILES_ACTIVE=local` + `.env.example` 참고해서 `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`를 지정하는 기존 방식도 그대로 유지됨 (두 방식 공존).

## 설계 메모 — 왜 CI는 Docker Compose를 안 쓰는가

`spring-boot-docker-compose`는 **developmentOnly** 스코프라 `test` 태스크의 클래스패스에는 포함되지 않는다. 처음엔 CI도 `compose.yaml` 자동 기동에 맡기려 했지만, 실제로는 `DataSource` 빈 생성 자체가 실패했다 (`Failed to determine a suitable driver class`). 그래서 CI에서는 GitHub Actions의 `services` 블록으로 Postgres를 별도로 띄우고, `local` 프로필(환경변수 기반 설정)로 테스트를 돌리는 쪽으로 분리했다. 즉:

- **로컬 개발(bootRun/IDE, 무프로필)** → Docker Compose 자동 기동
- **CI 테스트(`local` 프로필)** → Actions `services` 블록

## 참고

- PR: [#5 chore: 백엔드 CI/CodeQL/로컬 Docker Compose 환경 추가](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/pull/5)
- 옵션 검토 전체 목록(제외한 항목 포함): [Notion — 백엔드 개발환경 셋업 옵션 정리](https://app.notion.com/p/3de4706aa16381bdba3acc722a19d9a7)
- [backend/README.md](../backend/README.md) — 아키텍처 다이어그램, 스택별 공식 문서 링크, 로컬 환경설정 절차
