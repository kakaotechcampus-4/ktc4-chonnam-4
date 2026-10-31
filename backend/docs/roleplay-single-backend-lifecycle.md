# 단일 백엔드의 종료·재전송 계약

현재 통합 기준: PR #80 `c306bd1`과 develop `565beb6`의 충돌 해결 작업본, 2026-10-28.
최초 설명·검증은 2026-10-27 develop `63e4314` 및 인프라 PR #63·`c253edd` 대조를 기준으로 작성됐다.
아래 설정은 현재 파일에서 다시 확인했으며, 과거 테스트 결과와 운영 배포 검증은 구분한다.

이 문서의 ‘정상 종료’는 백엔드 프로세스가 요청을 마친 뒤 종료하는 동작이다.
학습 활동의 목표 달성·최대 턴 종료·스탬프 지급은 [완료·보상 연동 계약](roleplay-completion-integration.md)을 따른다.
서버 종료 대기를 늘리는 설정이 학습 활동을 완료하거나 스탬프를 지급하는 조건은 아니다.

## 운영 전제

현재 저장소의 배포는 CloudFront → EC2 1대의 nginx → backend 1개 → PostgreSQL 구조다.
`deploy/host/deploy.sh`는 같은 Compose 프로젝트의 컨테이너를 교체하며 서버의 배포 잠금으로 직렬화한다.
PR 미리보기는 같은 자리를 교체하고 별도 DB를 사용한다. 배포 잠금은 음성 요청의 처리권을 대신하지 않는다.

같은 업무 DB에 여러 backend 복제본·별도 Compose 프로젝트를 동시에 연결하지 않는 것이 메모리 처리권의 전제다.
이 전제를 유지하는 MVP에서는 `InMemoryRoleplayRequestGuard`를 사용한다. 서버 증설·복제본 증가·구/신 서버
동시 운영 배포를 도입하기 전에는 공유 처리권과 장애 복구 정책을 구현해야 한다.
원본 Task의 DB 기반 PROCESSING 소유권 조건을 이미 구현했다고 보고하지 않는다.

## 백엔드 프로세스의 정상 종료

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
4. 같은 요청이 현재 실행 중이면 같은 세션·키·음성 바이트에는 202 `PROCESSING`을 반환한다. 같은 키의 다른 입력은 409 `ROLEPLAY_CONFLICT`다.
   기존 작업을 자동 취소·교체하거나 중복 AI 호출을 시작하지 않는다.
5. 응답 확정·재전송 성공과 활동 전체 완료는 별개다. 활동 완료·스탬프 표시는 서버의 status 조회로 확인한다.

프런트엔드는 원래 키와 원래 음성을 유지해 재전송해야 한다. 응답이 불확실하다는 이유로 새 키를 자동 발급하지 않는다.
이번 문서 충돌 해결에서 FE를 추가 수정하지 않았다. 기존 후속 요청은 별도 메모로 관리한다.

## 현재 설정 확인과 과거 검증 범위

현재 코드에서 `RoleplayTurnDeadline.MAX_DURATION=60초`, `application.yml`의 Spring 종료 대기 `70s`,
`deploy/compose.dev.yml`의 backend 종료 유예 `90s`를 확인했다. 설정을 읽어 확인한 것이며 실제 종료 테스트의 신규 실행 결과는 아니다.
`deploy/compose.origin.yml`에는 같은 종료 유예 설정이 없으므로 모든 배포 환경에 90초가 적용된다고 확대하지 않는다.

### PR #80에 보존된 2026-10-27 검증 이력

다음은 당시의 테스트 결과이며, 이번 통합 작업본에서 다시 실행한 결과가 아니다.


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

### develop에 보존된 2026-10-28 선별 검증 이력

기준 develop `9f6ffa5`에서 완료·보상 연동 전 역할극 기본 처리를 배정·음성·원자 저장과 함께 선별 검증했고,
총 16개 통과로 기록돼 있다. 위 65개 결과와 기준·선별 범위가 다르므로 단순 합산하지 않는다.
`RoleplayShutdownTest` 2개는 60 < 70 < 90 설정 관계와 내장 Tomcat의 요청 처리·작업자 종료 순서를 확인했다.
테스트 servlet과 실제 작업자 팩토리·runner를 사용한 범위이며 실제 EC2·Docker 종료나 전체 역할극 HTTP 종료 검증은 아니다.
전체 회귀와 운영 배포를 실행한 결과로 해석하지 않는다.

### 이번 충돌 해결 후 확인할 항목

승인한 종료 테스트의 HTTP/1.1 명시·응답 flush를 보존했다. 남은 충돌 해결 후 실제 테스트 결과를 새로 기록한다.
서버 강제 종료·진행 중 업로드·프록시 응답 유실·운영 배포의 검증을 이번 문서 정리만으로 완료 처리하지 않는다.
