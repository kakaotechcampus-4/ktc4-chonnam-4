# 단일 백엔드의 종료·재전송 계약

S3-JIN-02 후속 보완, 2026-10-27. 작업 기준 develop `63e4314`.
인프라는 PR #63과 최신 develop `c253edd`를 대조했다. 두 develop 커밋 사이 변경은 FE 파일 2개로 인프라는 동일하다.

## 운영 전제

현재 저장소의 배포는 CloudFront → EC2 1대의 nginx → backend 1개 → PostgreSQL 구조다.
`deploy/host/deploy.sh`는 같은 Compose 프로젝트의 컨테이너를 교체하며 서버의 배포 잠금으로 직렬화한다.
PR 미리보기는 같은 자리를 교체하고 별도 DB를 사용한다. 배포 잠금은 음성 요청의 처리권을 대신하지 않는다.

같은 업무 DB에 여러 backend 복제본·별도 Compose 프로젝트를 동시에 연결하지 않는 것이 메모리 처리권의 전제다.
이 전제를 유지하는 MVP에서는 `InMemoryRoleplayRequestGuard`를 사용한다. 서버 증설·복제본 증가·구/신 서버
동시 운영 배포를 도입하기 전에는 공유 처리권과 장애 복구 정책을 구현해야 한다.
원본 Task의 DB 기반 PROCESSING 소유권 조건을 이미 구현했다고 보고하지 않는다.

## 정상 종료

| 설정 | 값 | 목적 |
|---|---|---|
| 턴 최대 처리 예산 | 60초 유지 | STT~LLM 재시도~TTS 및 체크포인트의 기존 제한 |
| Spring 종료 단계 대기 | 70초 | 턴 처리와 응답·정리를 위한 여유 |
| backend 컨테이너 종료 유예 | 90초 | Spring 종료와 나머지 프로세스 정리를 위한 여유 |

70/90초는 종료를 위한 구현 설정이며 요청 키 TTL이나 AI 실행 예산을 변경하는 정책이 아니다.
Graceful shutdown이 시작되면 내장 Tomcat이 신규 요청 수락을 중단하고 기존 HTTP 요청이 끝나기를 기다린다.
HTTP 요청은 기존 턴 예산 안에서 완료 또는 시간 초과 처리한다. 정상적으로 요청이 끝난 뒤 빈을 파괴하며,
그때 작업자 풀의 기존 `shutdownNow`가 호출된다. 작업자 풀의 종료 코드는 바꾸지 않았다.

이 설정은 정상 SIGTERM/애플리케이션 컨텍스트 종료에 대한 것이다. SIGKILL·OOM·호스트 장애·종료 시간 초과에는
완료를 보장하지 않는다. 단일 서버 교체 중 접속 중단이나 프록시 응답 유실까지 없애는 무중단 배포도 아니다.
실제 EC2 중지와 Docker 종료, 진행 중 multipart 업로드·블로킹 IO까지 포함한 종단 검증은 별도다.

## 재시작 후 재전송

1. 인증과 세션/활동 소유권을 먼저 확인한다. 같은 키만으로 권한을 우회하지 않는다.
2. DB에 확정 결과가 있으면 키와 지문을 비교해 같은 요청의 기존 후보·문구·체크포인트를 반환한다.
   AI·음성 생성/발행과 턴 저장을 다시 실행하지 않는다.
3. DB에 확정 결과가 없고 새 턴 진행이 가능한 경우에는 원래 요청을 다시 처리한다.
   이전 프로세스가 AI를 호출한 뒤 저장 전에 종료됐다면 외부 AI 호출은 반복될 수 있다.
4. 같은 키의 다른 입력은 409다. 이전 작업의 자동 취소·교체로 해석하지 않는다.

프런트엔드는 원래 키와 원래 음성을 유지해 재전송해야 한다. 응답이 불확실하다는 이유로 새 키를 자동 발급하지 않는다.
이번 변경은 FE를 수정하지 않았으며 관련 후속 요청은 별도 메모로 관리한다.

## 검증

- `RoleplayShutdownTest` 2개: 실제 설정의 `턴 예산 < Spring 대기 < 컨테이너 유예` 관계 검사와,
  로컬 내장 Tomcat의 HTTP 요청 처리 중 컨텍스트 종료를 시작해 요청이 완료된 뒤 작업자가 종료되는지 검사.
  실제 운영 작업자 팩토리와 `RoleplayTurnRunner`를 사용하며 별도의 테스트 servlet을 사용한다.
  60초 전체를 실제로 기다리거나 실제 역할극 컨트롤러·DB를 함께 종료하는 테스트는 아니다.
- 위 테스트와 `RoleplayHttpConfigurationTest` 6개, `RoleplayHttpIntegrationTest` 25개,
  `RoleplayScopedVoiceTurnIntegrationTest` 19개, `RoleplayTurnDeadlineTest` 13개, 총 65개 통과.
- 저장 후 새 메모리 처리권으로 재전송 시 AI 생략, 저장 실패 후 재시도 시 AI 재실행 가능성을 실제 PostgreSQL로 재검증.
- `spotlessCheck`, `git diff --check` 및 실제 Docker Compose `config` 해석 통과.
  Compose가 backend의 종료 유예를 `1m30s`로 해석했다. 실제 AWS 배포는 수행하지 않았다.

근거: [Spring graceful shutdown](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html),
[Docker stop_grace_period](https://docs.docker.com/reference/compose-file/services/#stop_grace_period).
