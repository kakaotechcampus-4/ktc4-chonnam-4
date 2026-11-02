# API v1 변경 이력

## 2026-11-02 — 역할극 단독의 시나리오는 강사가 배정 때 고른다(PR #90 리뷰)

- 기준은 `feature/roleplay-report-integration` 작업 브랜치입니다. develop 병합·운영 배포를 뜻하지 않습니다.
- API-ACT-01: ROLEPLAY 배정에 `scenarioId`(필수)·`reuseApproved`를 더했습니다. 서버가 승인 상태·목표 내용·고정 레벨을 확인하고 422 `SCENARIO_REQUIRED`·`UNAPPROVED_SCENARIO`·`SCENARIO_NOT_MATCHED`·`SCENARIO_REUSE_NOT_APPROVED`, 409 `SCENARIO_ALREADY_USED`로 막습니다. 다른 유형의 `scenarioId`는 422 `SCENARIO_NOT_ALLOWED`입니다. 응답 `scenarioSource`는 `INSTRUCTOR_SELECTED`입니다.
- API-RPL-01: 역할극 단독 활동은 시작할 때 서버가 시나리오를 고르지 않고, 배정 때 고른 승인본만 씁니다.
- API-SCN-08(`GET /api/v1/children/{childId}/roleplay-scenarios`)을 더했습니다. 순서는 마지막으로 쓰인 지 오래된 것(안 쓰인 것이 맨 앞) → 최근 승인본입니다. 승인 시각은 V27 `scenario.approved_at`(승인 순간 트리거가 채움, 그 전 승인본은 만든 시각)입니다. 목차는 59개 ID 중 구현 37개입니다.
- 학급은 보지 않습니다(팀 결정). 후보·배정 검사와 역할극 시작·진행의 승인본 확인(`findApprovedOpening`·`JdbcRoleplayContextSource`·`JdbcRoleplayProgressSource`)에서 `class_id` 조건을 뺐습니다. 최초 결합이 퀴즈를 마칠 때 자동으로 고르는 쿼리(`findFirstUnusedApproved`)는 아직 같은 학급 조건이 남아 있습니다.
- `contracts/api-v1.json`에 시나리오 3개(다른 유형의 scenarioId 422, 후보 404 두 가지)를 더했습니다.

## 2026-10-31 — 리포트·추천·다음 역할극 난이도 구현(작업 브랜치)

- 기준은 develop `ccfdff0` 위 `feature/roleplay-report-integration` 작업 브랜치입니다. develop 병합·운영 배포를 뜻하지 않습니다.
- API-REC-01~05를 설계에서 구현으로 바꾸고, 다음 역할극 난이도 조회 API-REC-06(`GET /api/v1/children/{childId}/roleplay-difficulty`)을 더했습니다. 목차는 58개 ID 중 구현 36개·조건부 구현 1개·설계 19개·미제공 2개입니다.
- 열려 있던 필드를 확정했습니다. 리포트의 `quiz`·`roleplay`·`difficultyDecision`은 서버가 기록에서 센 값이고, `metrics`(분자·분모)와 `narrative`(AI 항목과 근거 ID)를 더했습니다. 역할극 단독 활동은 `quiz`가 null입니다.
- 추천 유형은 REINFORCE·REPEAT·APPLY·EXTEND, 충분성은 SUFFICIENT·LIMITED·INSUFFICIENT, 거절 이유는 NOT_NOW·NOT_SUITABLE·ALREADY_PLANNED·OTHER입니다. 채택해도 서버는 목표·활동을 만들지 않아 `createdGoalId`는 늘 null입니다.
- 결정은 같은 키·같은 입력이면 앞 결과를 200으로, 같은 키·다른 입력이면 409 `IDEMPOTENCY_KEY_REUSED`, 확정 뒤 다시 결정하면 409 `RECOMMENDATION_ALREADY_DECIDED`입니다.
- `contracts/api-v1.json`에 리포트·추천·난이도 시나리오 10개와 자리표시자 `unknownId`를 더했고, 백엔드·프론트 계약 실행기가 PATCH를 보냅니다.
- 문서·전송 모의 검사 111개 통과(Node 26). 실제 서버·외부 AI 동작은 백엔드 통합 테스트에서 따로 확인합니다.

## 2026-10-28 — PR #80과 develop 충돌 해결

- 기준은 PR #80 `c306bd1`과 develop `565beb6`의 통합 작업본입니다. 아래는 코드·문서 통합 내역이며 원격 병합·운영 배포·제품 인수 완료를 뜻하지 않습니다.
- API 목차는 57개 ID 중 구현 30개·조건부 구현 1개·설계 24개·미제공 2개입니다. API-RPL-01 시작·복원 구현과 API-ACT-04 status 조회를 함께 반영했습니다.
- API-ACT-04의 공유 조회 함수는 develop에서 `examples/endpoints.ts`로 이동한 구현을 보존했습니다. 상태 GET은 완료·보상을 다시 실행하지 않습니다. API-RPL-01도 `endpoints.ts`로 이동하고 응답 타입·모의 데이터를 동기화했습니다. 본문 없는 아동 요청, 200/201 및 현재 상태 복원을 검증합니다.
- 퀴즈의 최초 난이도·시나리오 연결 이벤트, 독립 퀴즈 완료·보상·학습 기록 저장, 커밋 후 기본값 로그를 모두 보존했습니다. 최초 결합은 퀴즈만으로 완료되지 않습니다.
- 완료·보상과 학습 목표 달성을 분리합니다. 목표 달성 또는 최대 턴에 의한 정상 마무리는 활동 완료·스탬프 지급 대상이지만, 최대 턴 내 미도달을 달성으로 바꾸거나 난이도 상향의 근거로 쓰지 않습니다. 후속 난이도는 기존 목표별 수행 근거의 유지·하향·상향 후보 규칙을 보존합니다.
- 현재 코드에서 최대 턴 종료의 완료·보상 연결과 후속 난이도 계산·다음 시나리오 적용은 아직 남아 있습니다. 이 정책 문서화가 해당 구현·검증의 완료를 뜻하지 않습니다.
- 서버 종료 테스트의 HTTP/1.1 명시·응답 flush와 응답 유형·전략 보존/재전송 테스트를 보존했습니다. 과거 16개·65개 등의 검증 수치는 각 기준의 이력으로 구분하며 이번 통합 검증에 합산하지 않습니다.
- 아래 S5·S3 기록은 단계별 작업 이력입니다. 최신 API 개수·공유 예제·실제 SQL 버전과 차이가 있는 표현은 현재 파일 기준으로 정리했습니다.

### 이번 통합 작업본의 실제 검증 결과

- JDK 21·Testcontainers PostgreSQL에서 `./gradlew --offline build` 성공. 94개 클래스·1,384개 테스트 통과, 실패·오류·건너뜀 0. 컴파일·Spotless·JAR·커버리지 생성 포함.
- 공유 호출 예제 동기화 후 문서·전송 모의 검사 115개 통과. 실제 서버 요청은 하지 않는 검사입니다.
- 공통 TypeScript 4개 파일과 역할극 시작·활동 status 문서의 코드 블록 6개를 TypeScript 5.9.3의 strict·exactOptionalPropertyTypes·noEmit으로 검사해 통과했습니다. 모든 문서의 코드 블록을 타입 검사한 결과로 확대하지 않습니다.
- 충돌 표시·미해결 인덱스·공백 검사를 통과했습니다. 자동 병합에서 중복된 동일 HTTP 테스트 1개를 제거하고 기존 검증 내용은 보존했습니다.
- 외부 AI·브라우저·운영 배포 및 최대 턴 완료·후속 난이도 미구현 항목의 동작 검증을 완료했다는 의미는 아닙니다.

## 2026-10-28 — S5·S3 백엔드 통합본

- 최초 난이도 V2·독립 퀴즈 완료·역할극 시작·목표 관찰·완료 연결을 별도 통합본에 결합했습니다. 운영 진행 정책 공급 및 외부 제공자·브라우저 연동 완료를 뜻하지 않습니다.
- 실제 V14·V15 시나리오 구조를 사용하며 상황 설명 `situationType` 요청 길이를 200자로 맞췄습니다. 후속 문맥은 세션의 실제 승인 버전·학급·목표 조건 해시를 확인합니다.
- 현재 SQL 이력은 V13 학습 기록, V14·V15 공급 SQL, V16~V18 관찰·응답·입력 상태, V19 기본값 사유, V20 승인 범위·불변성, V21 시작 키, V22 시작 문구, V23 첫 질문 목표, V24 입력 목표 판정입니다. 운영 DB의 실제 적용 이력 대조와 변경 절차는 별도입니다.
- 이 단계의 #80 작업에서는 실제 프론트엔드와 공유 TypeScript 예제를 수정하지 않았습니다. 이후 충돌 해결에서 활동 status 및 역할극 시작·복원의 공유 예제를 동기화했습니다. 실제 프론트엔드 연동은 별도입니다.

## 2026-10-28 — S5-JIN-02 역할극 시작·복원 API

- 작업 브랜치에서 `POST /activities/{activityId}/roleplay-sessions`를 구현했습니다. 본인 아동 세션·CSRF·UUID 요청 키가 필요하며 본문은 보내지 않습니다. 신규 생성 201, 기존 세션 복원 200입니다.
- 사용자 결정으로 새 키도 기존 세션을 복원합니다. 현재 S·턴·상태를 초기화하지 않으며 완료된 세션은 재시작하지 않습니다.
- 첫 질문은 승인된 지원별 문구와 메시지 ID를 저장해 재사용합니다. 진행된 세션은 마지막 확정 서버 응답만 복원하고, 중단 상태는 빈 메시지 배열을 반환합니다. 상세 S와 원문 발화는 응답하지 않습니다.
- 시작 문구 미준비·저장 실패는 신규 세션과 요청 키를 함께 롤백합니다. 텍스트만 제공하며 음성 입력·운영 대화 문맥의 활성화는 별도입니다.
- 이 단계에서는 공유 TypeScript 예제와 실제 프론트엔드를 직접 수정하지 않았습니다. 이후 충돌 해결에서 공유 시작·복원 함수를 `endpoints.ts`로 옮겨 200/201·messageId·activityStatus·checkpointVersion을 동기화했습니다. 실제 프론트엔드가 메시지 중복 제거·현재 상태 복원을 처리하는 연동과 운영 배포는 별도입니다.

## 2026-10-28 — S5-JIN-01 계산·저장 연결

- 현재 작업 브랜치 구현 기준이며 병합·운영 배포 완료를 뜻하지 않습니다.
- 정책 INITIAL_QUIZ_V2: 정상 집계의 유효0개만 L1/S1 기본값. 집계 장애는 답안 확정 시500 QUIZ_RESULT_CALCULATION_FAILED로 반환하고 트랜잭션을 롤백합니다.
- 퀴즈 결과 응답에 nullable difficultyFallbackReason을 추가합니다. NO_VALID_QUESTIONS 또는 LEGACY_UNSPECIFIED이며 기본값 미사용은null입니다.
- 현재 기본값 사유 마이그레이션은 V19이며, 기존 건수/정책을 보존하고 과거 기본값 사유는 추정하지 않습니다. 로그는 최소 상태만 남기며 기본값 완료 로그는 커밋 뒤에 기록합니다.
- FE 소스·연동 예제 타입/fixture는 수정하지 않았습니다. 최종 연동 후속 항목으로 관리합니다.

## 2026-10-27 — 활동 완료를 서버가 처리, 상태 조회 구현 (PR #76)

| API | 변경 | 호환성 영향 |
|---|---|---|
| ACT-04 | 설계 → 구현, 함수를 endpoints.ts로 이동. 없는 활동·다른 아동·담당 아닌 강사는 모두 404 ACTIVITY_NOT_FOUND. 강사 조회는 민감 조회 기록 | allowDrafts 없이 호출 가능. 응답 `{ status }`는 설계와 같음 |
| QUIZ-04 | 독립 퀴즈 마지막 문항 FINALIZED 때 활동 COMPLETED, 스탬프·학습 기록 한 번 | 응답 모양 그대로. FE가 따로 보내는 완료 요청 없음 |
| ACT-02/03 | completedAt·rewardIssuedAt이 독립 퀴즈 완료 때 채워짐 | 필드 모양 그대로 |

## 2026-10-27 — 강사 Bearer 요청의 세션 쿠키 제거 (PR #75)

| API | 변경 | 호환성 영향 |
|---|---|---|
| 강사 인증 공통 | Bearer 요청에 서버 세션(JSESSIONID)을 만들지 않음. 쿠키만 보낸 강사 API 요청은 401 | 요청·응답 모양은 그대로. 강사 화면이 쿠키에 기대던 곳이 없어 FE 변경 없음 |

## 2026-10-22 — develop 63e4314 반영

기준 커밋: [`63e43144c92449ec5670390a6b99d20d2b2580a5`](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/tree/63e43144c92449ec5670390a6b99d20d2b2580a5). 기존 2026-10-06/c644dc2 명세에서 현재 코드의 HTTP 계약으로 갱신했습니다. 이 변경은 로컬 v1 문서/연동 예제만 수정하며 GitHub 푸시·서버·DB·제품 정책 승인을 수행하지 않습니다.

| API | 변경 | 호환성 영향 |
|---|---|---|
| IAM-02 | termsAgreed/privacyAgreed 필수true, 서버 동의 증빙 | 예전 가입 body는422 |
| IAM-02/03/04 공통 | UserRole=INSTRUCTOR, AccountStatus=ACTIVE | 레거시 운영자/계정상태를 현행 DTO로 쓰지 않음 |
| IAM-06 | DELETE /users/me → POST /users/me/withdrawal + password,204 | 경로/Method/body 변경.403 PASSWORD_MISMATCH 추가 |
| CLS-05/08/10 | 삭제/단건조회 Controller 반영, 구현 함수로 이동 | draft차단 해제,204/404와 삭제범위 확인 |
| GOAL-01/02/03 | category 최대30자 선택 요청/nullable응답 | 타입·해시/표시 계약 확인 |
| ACT-01 | childId/goalId → childId/activityType/goal, 원자 저장 | 필수멱등키·신규201/재전송200·유형/선행/목표 오류 반영 |
| ACT-02/03 | activityType, nullable goalId, 상세 중첩 응답 | data.activity 접근, 독립퀴즈 목표조회 분기 |
| QUIZ-01/02 | 문항풀 조회 API 제거 | 기존ID/파일을 미제공으로 남기고 함수/예시는 제거 |
| QUIZ-03 | 최초결합3/독립퀴즈10, 부족 시 배정거부 | 고정3문항 가정 제거 |
| QUIZ-07 | FIRST_COMBINED 결과만 최초난이도 실제사용 | 독립퀴즈 계산값으로 역할극 난이도를 바꾸지 않음 |
| RPL-04 | 조건부구현, audio+키,200결과/202처리중 | inputId/turnId 비동기 접수 DTO 제거, 실제 messages/action 등 반영 |
| RPL-06 | 현재 Controller없는 설계로 유지 | 음성202 후 자동조회 경로로 연결하지 않음 |

## 2026-10-22 — 저장소 문서 명명 방식 반영

- 기존 한글·번호 접두사 디렉터리를 `auth`, `classrooms-children`, `learning-goals`, `activities`, `quizzes`, `scenarios`, `roleplay`, `reports-recommendations`로 변경했습니다.
- `API-ACT-01.md` 형식 대신 `create-activity.md`처럼 동작을 설명하는 파일명을 사용합니다. API ID와 계약 내용은 유지합니다.
- 공통 문서는 `integration-guide.md`·`changelog.md`, 예제 폴더는 `examples/`로 통일했습니다. 목차·상대 링크·실행 명령·문서 검증도 변경된 경로에 맞췄습니다.

## 문서 운영 규칙

- 기존 57개 API ID는 유지하고 경로는 영문 소문자 kebab-case로 정리합니다. 미제공 ID를 재사용하지 않습니다.
- 현재 구현30/조건부1/설계24/미제공2를 구분하고, 조건부 활성화와 설계 개발을 별도 옵션으로 차단합니다. 역할극 시작·복원은 `endpoints.ts`의 구현 함수를 사용합니다.
- README·개별MD·types/함수/fixtures/모의 검사를 함께 갱신합니다. Swagger를 별도 수동 원본으로 병행하지 않고 Markdown의 변경을 코드 계약과 같은 PR에서 검토합니다.
- 이 폴더 밖의 로컬/첨부문서/ZIP 경로를 필수 링크로 사용하지 않습니다. 소스 근거는 검토 커밋 고정 GitHub 링크, 문서 링크는 폴더 내 상대 링크입니다.
- 원래 제품 정책과 다른 구현(탈퇴 비밀번호 등)은 현재 HTTP 동작으로 명시하되 기능 정책까지 자동 승인된 것으로 해석하지 않습니다.

## 2026-10-22 문서 갱신 당시 확인 결과

다음 수치는 당시 기준 커밋의 과거 기록이며, 이번 충돌 해결 후의 신규 검사 결과가 아닙니다.

- 문서·전송 모의 검사114개 통과: 57개ID/상태·JSON·상대링크·영문 kebab-case 경로·현재/설계/조건부 차단·멱등성·모든 허용 성공상태·제거API 호출 제외.
- 문서 내 TypeScript 블록165개와 공통 TypeScript 파일4개를 strict/exactOptionalPropertyTypes/noEmit으로 검사했습니다.
- 현행/조건부29개 경로를 기준 커밋의 Controller와 대조했고 미제공2개 경로는 없는 것을 확인했습니다. ACT-03은 현재 강사만 허용하는 보안필터를 반영했습니다.
- 실제 서버·DB·AI·브라우저·배포 인수는 실행하지 않았습니다. 위 결과는 문서/연동 예제의 검증입니다.
