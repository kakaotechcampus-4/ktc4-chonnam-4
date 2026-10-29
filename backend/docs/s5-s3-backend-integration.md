# S5·S3 백엔드 통합 기록

> 최종 상태(2026-10-28): 1~3번 백엔드 코드 결합 후 전체 빌드와 92개 묶음의 1,359개 테스트가 모두 통과했다. 운영 한도·실제 승인 자료·AI/음성·브라우저·운영 DB 전환은 별도 인수 항목이다. 아래 단계별 수치는 당시 변경 범위의 기록이며 최종 결과를 대신하지 않는다. 프론트엔드 후속은 [별도 인수 문서](./s5-s3-frontend-handoff.md)를 따른다.

## 기준과 범위

- 작업 폴더: `/private/tmp/neuringo-s5-s3-integration-1qgwnvtg`
- 브랜치: `codex/s5-s3-backend-integration`, 기준 develop `9f6ffa5`.
- 선행 코드: 최초 난이도 V2 `17e191b`, 활동 완료 `92bb7fd`, 시나리오 SQL `4a5550b`.
- S5·S3의 미커밋 백엔드 변경은 원본을 수정하지 않고 `.git/integration-inputs/`에 해시와 함께 복사한 자료로 결합했다. S5의 시작·복원·승인본 연결과 S3의 문맥·목표 관찰·확정/완료 연결을 포함한다.
- 실제 프론트엔드·공유 TypeScript 예제는 최신 develop과 동일하다. 커밋·원격 반영·운영 DB 변경은 하지 않았다.

## 결합한 실행 경로

`퀴즈 확정 → 난이도 V2 결과 저장 → 커밋 후 승인본 연결 → 시작 API → 세션·첫 문구 저장 → 저장 문맥 조회 → 검수된 턴·목표 관찰 확정 → 기존 세션 복원 → 활동 목표 달성 시 완료·보상·기록·표준 발화 삭제`

독립 퀴즈는 마지막 답안 확정 때 완료하고 최초 결합은 역할극 목표 달성을 기다린다. 최초 난이도는 처음 생성할 때만 적용하고 기존 세션의 S·턴·상태는 복원한다. 연결 실패는 퀴즈 결과를 되돌리지 않지만, 시작 문구 저장 실패는 신규 세션·시작 키를 함께 취소한다. 최종 턴·관찰·완료·보상·기록·표준 발화 삭제도 같은 짧은 트랜잭션으로 확정한다.

`JdbcRoleplayContextSource`는 버전 1 고정을 제거하고 실제 시나리오 버전·학급·목표 조건 해시를 확인한다. 승인 자료의 버전·범위도 자료 지문에 포함한다. 승인 이후 문구·증거 변경은 DB에서 막고, 잘못된 승인 자료는 문맥으로 공급하지 않는다. 기존 테스트의 별도 시나리오 DDL 실행을 제거하고 실제 Flyway 공급 구조를 사용한다.

## 통합 실험용 DB 이력

| 버전 | 변경 |
|---|---|
| V1~V12 | 공통 원문 유지 |
| V13 | 활동 완료 공급 브랜치의 학습 기록·과거 독립 퀴즈 보정 |
| V14·V15 | 공급 브랜치 원문의 목표 상황 길이·시나리오와 작은 목표 |
| V16~V18 | S3 원문의 관찰 근거·응답 종류·입력 당시 상태 |
| V19 | S5 원본 V13의 퀴즈 기본값 사유 SQL, 내용 유지 |
| V20 | S5 원본 V16의 승인 범위·버전 불변성, 내용 유지 |
| V21 | S5 원본 V17의 시작 요청 키, 내용 유지 |
| V22 | S5 원본 V18의 시작 문구. V16에 있는 세션·시나리오 복합 UNIQUE 중복 선언만 제거 |

이 배치는 별도 통합본의 실험용이다. 원본 S5·S3의 SQL과 적용 이력은 수정하지 않았다. 대상 운영 DB의 이력은 조회하지 않았으며 서로 다른 V13이 이미 적용된 DB의 배포 전환 방안을 확정한 것이 아니다. checksum 강제 변경이나 Flyway repair를 수행하지 않았다.

빈 DB 전체 적용, V12에서 V22까지 과거 퀴즈 자료를 보존한 업그레이드, V16→V17 및 V17→V18에서 과거 응답·입력 상태를 추측하지 않고 보존하는 검사를 포함한다. 목표 상황 입력 검증도 실제 V14에 맞춰 200자로 맞췄다.

## 검증 기록

선행 코드만 결합한 첫 실행은 직접 관련 58개 테스트와 Spotless 검사를 통과했다. 이 결과를 전체 통합본의 최종 검증으로 확대하지 않는다.

전체 코드 결합 후 시작·문맥·관찰 연결 테스트를 실행했다. 별도 정책 공급자는 테스트에서만 사용한다. 시작 세션의 초기 S, 첫 질문, 확정 턴 복원, 완료 후 빈 메시지, 한 번의 학습 기록, 표준 발화 삭제 및 재전송을 실제 DB에서 함께 확인했다. 승인 버전 3 조회와 다른 학급·목표 해시 차단, 승인 내용 직접 수정 차단도 확인한다.

최종 `./backend/gradlew -p backend build --no-daemon --console=plain` 성공. 89개 테스트 묶음의 **1,322개 테스트 통과**, 실패·오류·건너뛰기 0개다. Spotless·JAR 빌드·커버리지 보고서 생성을 포함한다. 최종 실행 로그는 `/private/tmp/neuringo-s5-s3-verified-build.log`다.

초기 전체 실행에서는 백엔드 컨테이너 종료 대기가 30초라 서버의 70초보다 짧은 설정 검사가 실패했다. S3의 백엔드 컨테이너 종료 대기 90초를 결합했다. 다음 실행에서 종료 시점 HTTP 연결 검사의 간헐 실패를 관찰해 테스트 클라이언트에 HTTP/1.1을 명시하고 응답 본문을 flush했다. 이 두 검사는 세 번 연속 통과했고 이후 전체 1,322개도 통과했다. 배포 실행이나 외부 AI 호출은 하지 않았다.

최종 빌드 입력 413개는 `.git/integration-inputs/verification-manifest.json`의 SHA-256과 같음을 확인했다. 검수 결과·승인 자료·진행 정책은 합성/시험 자료를 사용했다.

- [최근 테스트 보고서](/private/tmp/neuringo-s5-s3-integration-1qgwnvtg/backend/build/reports/tests/test/index.html) — 후속 실행 때 갱신된다. 1·2번 전체 검증의 결과 수치는 위 실행 로그와 `.git/integration-inputs/final-test-summary.json`에 보존했다.
- [커버리지 보고서](/private/tmp/neuringo-s5-s3-integration-1qgwnvtg/backend/build/reports/jacoco/test/html/index.html)
- `RoleplayContextSourceIntegrationTest` 87개, `QuizFlowIntegrationTest` 85개, `RoleplayCompletionIntegrationTest` 27개, 원자적 완료 검사 13개, 종료 검사 2개를 포함한다.

사용자가 함께 진행하도록 요청한 1번(시나리오 저장·승인 구조)과 2번(역할극 코드 통합)의 별도 통합본 구현·검증은 완료했다. 다음은 3번 운영 진행 상태 공급이며, 최종 제품 인수와 운영 DB 적용은 아래 의존성으로 남는다.

## 남은 작업과 프론트엔드 후속

- 운영 `RoleplayProgressSource`: 초기/현재 질문 초점·지원·목표 상태·명시적 제한 정책 공급은 이번 두 묶음에 포함하지 않았다. 임의 초기 목표·6/4턴 기본값을 추가하지 않았다.
- 실제 승인 콘텐츠 공급·AI/STT/TTS·브라우저·운영 DB 이력에 대한 검증은 별도다. HTTP의 시험 제공자 통과를 실서비스 종단 완료로 해석하지 않는다.
- 프론트엔드는 목표 상황 설명 200자 입력 검증, 퀴즈 완료 뒤 결과/준비 상태 조회, 시작 요청의 인증·CSRF·키·200/201 처리, 메시지 ID 중복 제거, 세션/활동 상태 처리, 완료 status 조회와 스탬프 표시를 연결해야 한다. 직접 수정하지 않았다.
- 활동 status와 시작 API의 Markdown은 실제 코드에 맞췄지만 공유 예제 함수·타입·fixture 동기화는 프론트엔드 후속이다. GET status는 구현 경로이며 기존 draft 함수는 아직 차단될 수 있다.
- 통합 중 원본 S3 폴더에서 지원·도달 판정 관련 추가 변경이 진행되는 것을 확인했다. 이 통합본은 처음 해시로 보존한 자료를 기준으로 고정했으며 이후 변경을 덮어쓰거나 부분 복사하지 않았다. 다음 정책 공급 작업에서 해당 변경을 다시 대조한다.

현재 두 묶음의 결합 결과는 이 문서가 기준이다. 함께 가져온 기존 S3·S5 인수 문서의 이전 범위/검증 수치는 각 원본 작업 시점의 기록이다.

## 3번 첫 동작 — 저장된 진행 상태 공급

`JdbcRoleplayProgressSource`를 실제 Spring 빈으로 추가했다. `JdbcRoleplayContextSource`의 읽기 트랜잭션에 참여해 동일 아동·활동·목표·세션·승인 버전·체크포인트의 현재 S·질문 초점·다음 턴 번호를 DB에서 복원한다. 학급·목표 조건과 진행 상태가 다르거나 저장된 지원 수준이 없으면 값을 만들어 반환하지 않는다. 쓰기·초기 S 재적용·별도 AI 호출은 없다.

`RoleplayProgressPolicy`는 저장값으로 결정할 수 없는 최초 질문 초점·목표별 상태·명시적 턴 제한을 공급할 계약이다. 정책은 저장된 S·턴 번호를 바꿀 수 없고, 이미 저장된 질문 초점의 변경도 거절한다. 승인 범위 밖 목표나 일부 목표의 상태 누락은 차단한다. 구체 운영 정책 빈은 아직 추가하지 않았으며, 정책이 없으면 새 문맥을 공급하지 않는 기존 차단 동작을 유지한다. 따라서 3번 전체 완료나 운영 대화 활성화를 뜻하지 않는다.

신규 실제 DB 검사 14개·기존 문맥 검사 87개·아키텍처 검사 11개, 총 **112개 통과**, 실패·오류·건너뛰기 0개다. Spotless와 커버리지 생성도 통과했다. 이전 1,322개 전체 검증 이후 추가한 변경이며 이번 단위에서는 전체 테스트를 재실행하지 않았다. 트랜잭션 밖 독립 조회·기한 만료·잘못된 범위·정책 누락과 기존 S1 복원이 최초 S2로 돌아가지 않는 사례를 포함한다.

- [이번 동작의 테스트 보고서](/private/tmp/neuringo-s5-s3-integration-1qgwnvtg/backend/build/reports/tests/stored-progress/index.html)
- 실행 로그: `/private/tmp/neuringo-s5-s3-stored-progress.log`
- 검증 입력 해시: `.git/integration-inputs/stored-progress-verification-manifest.json`

공개 API·DB 구조·프론트엔드는 바꾸지 않았다. 다음은 승인된 첫 질문의 작은 목표 연결과 목표 상태·제한 정책의 실제 공급이며, 초기 목표나 6/4턴 기본값을 임의로 추가하지 않는다.

## 3번 두 번째 동작 — 승인된 첫 질문의 목표 연결

V23 `scenario_opening_focus`는 시나리오·S별 첫 질문의 작은 목표를 명시한다. 같은 시나리오의 작은 목표만 FK로 허용하며 승인 후 삽입·수정·삭제는 기존 승인 불변성 트리거로 차단한다. 시나리오 공급 측은 초안 상태에서 시작 문구와 함께 연결을 준비해야 한다. 새 승인 API나 화면을 만든 것은 아니다.

`RoleplayStartService`는 신규 세션에서 승인 버전을 잠근 뒤 최초 S에 해당하는 목표를 읽어 S와 함께 저장한다. 기존 세션의 복원 분기는 그대로 유지한다. S2의 첫 질문이 목록 두 번째 목표에 연결돼 있어도 그 ID를 사용한다. 배열의 첫 요소나 order_no로 추측하지 않는다.

기존 승인 자료와 과거 세션은 자동 보정하지 않는다. 텍스트 시작·확정 응답 복원 계약은 유지하지만, `JdbcRoleplayProgressSource`는 최초 턴이어도 저장된 질문 초점이 없으면 정책을 호출하지 않고 새 문맥을 공급하지 않는다. 정책이 미확인 첫 질문의 목표를 임의로 대신 고르는 경로를 닫았다. 구체 운영 정책 빈은 아직 없으므로 이 변경만으로 음성 대화가 활성화되지 않는다.

직접 관련 4개 클래스의 **192개 검사 통과**, 실패·오류·건너뛰기 0개다. 저장 상태 19개·문맥/완료 연결 87개·퀴즈/시작 85개·V12→V23 업그레이드 1개를 포함한다. Spotless와 커버리지 생성도 통과했다. 전체 회귀는 반복하지 않았다.

- [첫 질문 목표 연결 검증](/private/tmp/neuringo-s5-s3-integration-1qgwnvtg/backend/build/reports/tests/opening-focus/index.html)
- 실행 로그: `/private/tmp/neuringo-s5-s3-opening-focus.log`
- 입력 해시: `.git/integration-inputs/opening-focus-verification-manifest.json`

신규 SQL은 별도 통합본의 V23이며 원본 S3·S5 폴더나 운영 DB에는 적용하지 않았다. 공개 요청·응답 필드와 프론트엔드는 변경하지 않았다. 전체 최대 턴과 목표별 유도 턴의 확정값은 사용자에게 질문했으며 답변 전에는 6/4 등의 값을 코드 기본값으로 채우지 않는다. 다음은 목표별 상태와 명시적인 제한 정책 공급이다.


## 3번 세 번째 동작 — 입력 목표의 검수 판정 보존

기존 분석·검수 결과의 `learningState`를 입력 당시 작은 목표에 연결해 저장한다. `RoleplayGoalObservationMapper`는 다음 질문의 후보 목표와 `command.inputContext().microGoalId()`를 구분하고, `GoalProgress`를 저장 변경안에 붙인다. 신규 AI 호출이나 목표 달성 추론은 추가하지 않았다. `GOAL_ACHIEVED` 같은 작은 목표의 판정과 활동 전체의 `decision`은 별도 필드다.

V24는 `roleplay_goal_assessment`에 `input_micro_goal_id`와 `input_learning_state`를 추가한다. 같은 확정 턴의 입력 목표를 FK로 참조하며 허용 코드·두 값의 동시 존재를 DB에서 검사한다. 원문·모델 자유 서술은 복사하지 않는다. 과거 값은 NULL로 남기며 NOT_OBSERVED나 미달성으로 바꾸지 않는다. 입력 상태가 없는 과거 내부 변경안도 미확인으로 보존한다.

`RoleplayCompletionService`는 변경안의 입력 목표와 서버 입력 상태가 일치하는지 확인한다. 판정·관찰·턴·체크포인트를 기존 동일 트랜잭션에 저장한다. 판정 저장 실패 시 모두 취소되고 같은 요청 재시도가 가능하다. 이미 확정된 요청은 기존 결과를 재사용하며 판정 행을 덮어쓰지 않는다. 표준 발화를 삭제해도 enum 판정과 목표 참조는 남는다.

직접 관련 5개 클래스 총 **179개 통과**, 실패·오류·건너뛰기 0개다. 문맥 DB 90개·완료 DB 27개·매핑 31개·저장 변경안 30개·V12→V24 업그레이드 1개를 포함한다. Spotless와 커버리지 생성도 통과했다. 입력 목표/다음 질문 목표 분리, 목표 참조 변조 차단, 판정 저장 실패·전체 롤백·동일 요청 복구, 재전송·원문 삭제 후 보존을 확인했다. 전체 회귀는 반복하지 않았다.

- [입력 목표 판정 검증](/private/tmp/neuringo-s5-s3-integration-1qgwnvtg/backend/build/reports/tests/goal-judgment/index.html)
- 실행 로그: `/private/tmp/neuringo-s5-s3-goal-judgment.log`
- 입력 해시: `.git/integration-inputs/goal-judgment-verification-manifest.json`

운영 목표 상태 조회·정책 공급은 아직 연결하지 않았다. 다음 동작은 저장된 판정을 실제 목표 상태 공급으로 이어주는 것이다. 전체·목표별 턴 제한은 여전히 미설정이며 확정되지 않은 값을 기본값으로 넣지 않았다. 공개 API·프론트엔드·원본 S3/S5 코드·운영 DB는 변경하지 않았고 커밋·push도 하지 않았다.


## 3번 네 번째 동작 — 실제 목표 상태·설정 정책 공급

`JdbcRoleplayProgressPolicy`를 실제 빈으로 연결했다. `JdbcRoleplayProgressSource`가 확인한 저장 버전과 같은 트랜잭션에서 확정 턴·입력 목표 판정·목표 관찰·정답 제공 이력을 조회한다. 입력 당시 목표와 다음 질문 목표를 구분하고, 판정의 시나리오/버전·저장 버전·계약 버전·턴 순서·누락을 검사한다. 원문·서버 응답 전문은 읽지 않는다.

목표 상태는 다음과 같이 복원한다. 이 값은 현재 진행 상태이며 독립 달성 점수나 활동 전체 완료 판정이 아니다.

- 유효 판정이나 관찰이 없는 목표는 `NOT_OBSERVED`다. 다른 초점의 발화에서 관찰한 목표는 `IN_PROGRESS`로만 표시하고 도달을 추측하지 않는다.
- 현재 입력 목표의 검수 결과가 `GOAL_ACHIEVED`이고 같은 턴에 승인 범위의 긍정 관찰이 있으면 `ACHIEVED`로 표시한다. 다른 학습 판정은 `IN_PROGRESS`다.
- `NOT_ASSESSABLE`은 그 목표의 이전 상태를 유지한다. 첫 판정이 불가인 경우도 임의의 도달·미달성 판정으로 바꾸지 않는다.
- 실제 `ANSWER_REVEAL` 응답 이력은 `ANSWER_REVEALED`로 유지한다. 이후 저장된 도달 문구나 표준 발화 삭제로 정답 제공 사실을 없애지 않는다.
- 과거 판정 누락·미확인 이력은 NOT_OBSERVED로 채우지 않고 새 문맥 공급을 차단한다. 이미 확정한 응답의 재전송·시작 복원은 계속 가능하다.

`RoleplayProgressProperties`는 `roleplay.progress.maximum-turn-count`와 `roleplay.progress.maximum-support-turns-per-micro-goal`을 함께 받는다. 환경변수는 각각 `ROLEPLAY_MAXIMUM_TURN_COUNT`, `ROLEPLAY_MAXIMUM_SUPPORT_TURNS_PER_MICRO_GOAL`이다. 두 값 모두 없으면 빈 정책으로 두고 새 대화 문맥을 공급하지 않는다. 한쪽만 있거나 0 이하면 시작 시 설정 오류로 처리한다. 코드·YAML에 숫자 기본값을 추가하지 않았으며 테스트의 13/5·17/3은 합성 설정값이다. 한도는 기존 분석 입력에 전달하고 활동 목표 달성이나 보상 판정으로 바꾸지 않는다.

직접 관련 4개 클래스 총 **124개 통과**, 실패·오류·건너뛰기 0개다. 실제 진행 정책 DB 검사 9개·설정 바인딩 6개·저장 진행 19개·문맥/완료 DB 90개를 포함한다. 진행 공급자를 대체하지 않고 실제 시작→정책→문맥을 확인했다. 검수 결과 자체는 합성 자료이며 실제 외부 AI·음성·브라우저 실행은 아니다. Spotless와 커버리지 생성도 통과했고 전체 회귀는 반복하지 않았다.

- [운영 진행 정책 검증](/private/tmp/neuringo-s5-s3-integration-1qgwnvtg/backend/build/reports/tests/runtime-policy/index.html)
- 실행 로그: `/private/tmp/neuringo-s5-s3-runtime-policy.log`
- 입력 해시: `.git/integration-inputs/runtime-policy-verification-manifest.json`

3번의 저장 상태·첫 질문 목표·검수 판정·정책 공급 코드는 연결했다. 운영 턴 제한은 아직 미설정이므로 운영 새 대화 활성화 완료로 보고하지 않는다. 승인 자료 공급, AI·STT·TTS 및 전달 설정, 실제 DB 적용 이력과 브라우저 종단 검증도 별도다. 다음은 현재 통합본의 최종 검증과 프론트엔드 후속 정리다. 프론트엔드·공개 API·DB 마이그레이션을 이번 단위에서 변경하지 않았고 커밋·push·배포도 하지 않았다.


## 4번 최종 검증·인수 정리

2026-10-28 JDK 21·Testcontainers PostgreSQL에서 `./backend/gradlew -p backend build --no-daemon --console=plain` 성공. **92개 클래스 1,359개 테스트 통과, 실패·오류·건너뛰기 0개**다. Spotless·JAR 빌드·커버리지 생성을 포함하며 최신 저장 상태·진행 정책 빈·V1~V24를 포함한 통합본 전체 결과다.

- [최종 전체 테스트 보고서](/private/tmp/neuringo-s5-s3-integration-1qgwnvtg/backend/build/reports/tests/final-integration/index.html)
- 실행 로그: `/private/tmp/neuringo-s5-s3-final-verification.log`
- 검증 입력 해시: `.git/integration-inputs/final-integration-verification.json`
- [프론트엔드 인수 문서](./s5-s3-frontend-handoff.md)

예상과 실제 결과를 확인한 대표 사례는 신규 생성 201/같은 키·새 키 복원 200, 최초 S 유지가 아닌 현재 S 복원, 문구 저장 실패 시 신규 세션 롤백, 목표 판정 저장 실패 시 턴·상태 롤백, 작은 목표 도달과 활동 전체 완료의 구분, 정답 제공 이력 보존, 완료·보상·기록 1회, 표준 발화 삭제 후 재전송, 설정/판정 미확인 상태의 새 문맥 차단이다.

현재 프론트엔드는 실제 입장·종료 외의 활동·퀴즈·역할극·완료가 모의 응답이다. 시작의 delivery.messages/messageId와 음성의 messages/checkpointVersion/replayed를 구분하고, 실제 완료 POST 대신 status GET과 스탬프 화면을 연결해야 한다. 실제 입력 화면은 text mock이고 제공 서버 경로는 조건부 audio multipart이므로 텍스트 API·힌트 캐릭터·장소 계약을 제공된 것으로 가정하지 않는다.

백엔드 구현 검증과 운영 인수는 구분한다. 운영 최대 턴 2개 값은 미설정이고 승인 콘텐츠/초기 목표 공급, 실제 제공자·입력 안전 처리·음성 전달 설정, 원본 운영 DB 이력 대조 및 브라우저 검증이 남아 있다. 이 미검증을 완료로 바꿔 push 단계로 넘어가지 않았다. origin은 원본 S5 폴더를 가리키는 로컬 remote이며 사용자의 fork가 아니다. fork 동기화·커밋·push·PR 생성·배포는 수행하지 않았다.

사용자가 설명해볼 핵심은 최초 S와 현재 S의 차이, 입력 목표 판정과 다음 질문 초점의 차이, 퀴즈 커밋 후 연결 실패와 같은 트랜잭션의 턴/완료 실패가 각각 어떤 데이터를 남기는지다.


최종 보존본: `/Users/jinmina/Documents/ChatGPT/서비스 기획/느링고_분석/S5-S3_통합_최종_2026-10-28`. `backend-integration.patch`는 기준 `9f6ffa5` 대비 백엔드·백엔드 계약 Markdown·백엔드 컨테이너 종료 설정만 포함하며, 원본 코드와 실제 index를 변경하지 않는 별도 index로 생성했다. 패치와 최종 검증 입력의 해시는 `verification.json`에 있다.


## 최종 범위 정정 — 배포 변경 제외

2026-10-28 사용자 요청으로 배포 관련 작업을 범위에서 제외했다. `deploy/compose.dev.yml` 종료 대기 시간 변경과 이후 검토한 Parameter Store 전달 항목·환경 예시·배포 테스트 변경을 모두 철회했다. 앞선 문단의 배포 설정 포함 패치는 과거 보존본이다. 최신 전달본은 최종 보존 폴더의 `배포변경_제외_최종본/`이며 백엔드와 백엔드 계약 Markdown만 포함한다. 검증 입력 423개 중 compose 1개만 기준 상태로 원복했고 나머지 422개는 동일하다. 실제 입력 안전·음성 전달 구현은 별도 의존성으로 남기고 S5 구현 범위를 확장하지 않는다. [S5 최종 정리](./s5-jin-02-final-summary.md)에 완료 범위와 미검증 의존성을 구분했다.
