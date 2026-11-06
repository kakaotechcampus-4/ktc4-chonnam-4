# API v1 변경 이력

## 2026-11-06 — 리포트·추천·다음 역할극 난이도(PR #90), 역할극 단독도 PR #101 검토 흐름으로

- 기준은 develop `f017637` 위 `feature/roleplay-report-integration` 작업 브랜치(PR #90)입니다. 운영 배포·제품 인수 완료를 뜻하지 않습니다.
- API 목차는 59개 ID 중 구현 42개·조건부 구현 2개·설계 13개·미제공 2개입니다. [API-REC-01~05](./reports-recommendations/get-learning-report.md)를 설계에서 구현으로 바꾸고 다음 역할극 난이도 조회 [API-REC-06](./reports-recommendations/get-roleplay-difficulty.md)(`GET /api/v1/children/{childId}/roleplay-difficulty`)을 더했습니다.
- 리포트의 `quiz`·`roleplay`·`difficultyDecision`은 서버가 기록에서 센 값이고, `metrics`(분자·분모)와 `narrative`(AI 항목과 근거 ID)가 있습니다. 추천 유형은 REINFORCE·REPEAT·APPLY·EXTEND, 충분성은 SUFFICIENT·LIMITED·INSUFFICIENT, 거절 이유는 NOT_NOW·NOT_SUITABLE·ALREADY_PLANNED·OTHER입니다. 채택해도 서버는 목표·활동을 만들지 않아 `createdGoalId`는 늘 null입니다.
- 추천 결정(API-REC-05)은 같은 키·같은 입력이면 앞 결과를 200으로, 같은 키·다른 입력이면 409 `IDEMPOTENCY_KEY_REUSED`, 확정 뒤 다시 결정하면 409 `RECOMMENDATION_ALREADY_DECIDED`입니다.
- [API-ACT-01](./activities/create-activity.md): ROLEPLAY(역할극 단독)를 배정할 수 있습니다. 최초 결합 완료와 다음 역할극 난이도가 있어야 하고(없으면 409 `ROLEPLAY_DIFFICULTY_NOT_READY`), 배정 때 레벨·시작 지원을 활동에 고정합니다. 요청에 시나리오를 받지 않고 응답의 `scenarioId`는 null입니다.
- 역할극 단독의 시나리오는 최초 결합과 같은 [API-SCN-08](./scenarios/list-scenario-approvals.md) 승인 대기함 → [API-SCN-01](./scenarios/prepare-scenario.md) 준비·다음 후보 → [API-SCN-04](./scenarios/review-scenario.md) 확정으로 붙습니다. 승인 대기함의 `scenarioLevel`·`waitingSince`는 퀴즈 결과 대신 활동에 고정한 난이도·고정 시각입니다.
- 검토 입력 지문에 역할극 단독은 고정 난이도의 결정 ID(`source_decision_id`)·고정 지원 수준을 더 넣습니다. 그 활동의 고정값이 바뀌면 이전 검토 확정은 409 `SCENARIO_REVIEW_INPUT_CHANGED`이고, 다른 역할극이 끝나 새 결정이 생긴 것만으로는 바뀌지 않습니다. 최초 결합의 지문은 그대로입니다.
- 작업 브랜치에만 있던 역할극 단독 후보 조회(`GET /api/v1/children/{childId}/roleplay-scenarios`), 배정 요청의 `scenarioId`·`reuseApproved`, 422 `SCENARIO_REQUIRED`·`SCENARIO_NOT_MATCHED`·`SCENARIO_REUSE_NOT_APPROVED`·`SCENARIO_NOT_ALLOWED`는 PR #101 회의 결정에 따라 지웠습니다. develop에 들어간 적 없는 계약입니다.
- [API-ACT-02](./activities/list-child-activities.md)·[API-ACT-03](./activities/get-activity.md)의 `nextAction`은 역할극 단독도 고정 난이도를 근거로 판정합니다(확정 전 `WAIT_FOR_SCENARIO`).

## 2026-11-06 — 아동용 활동 목록 응답 분리

- develop e09bbbf 기반 refactor/child-activity-response 작업본입니다. PR #99 리뷰 r4480037266 및 FE 답변 r4488412186을 반영합니다. 운영 배포 완료를 뜻하지 않습니다.
- ACT-02는 검증된 아동 세션에 activityId/activityType/status/completedAt/nextAction 5개만 반환합니다. 지원 수준·기본값 여부·목표/시나리오 ID·내부 차단 사유 등은 JSON에서 제외합니다.
- 담당 강사 응답, 활동 생성·상세·status 응답, 내부 준비 상태 계산·권한·페이지·읽기 전용 동작은 유지합니다. 새 Endpoint나 DB 마이그레이션은 없습니다.
- 동봉 TypeScript 타입·fixture·함수 및 실제 FE 코드는 직접 변경하지 않았습니다. 아동 FE의 현행 5개 필드 매핑은 유지할 수 있으며 주석·공유 계약 타입 동기화와 실제 브라우저 확인은 후속입니다.
- 퀴즈 결과 API의 아동 평가 정보 응답은 이번 목록 분리와 별도 검토 항목입니다.


## 2026-11-06 — 시나리오 승인 대기 활동 목록

- 기준은 develop `d1a7b0e`(PR #100·#101·#103 병합) 위의 `feature/scenario-review-screens` 작업 브랜치입니다. 운영 배포·제품 인수 완료를 뜻하지 않습니다.
- API 목차는 58개 ID 중 구현 36개·조건부 구현 2개·설계 18개·미제공 2개입니다.
- 추가: [API-SCN-08](./scenarios/list-scenario-approvals.md) 승인 대기 활동 목록(콘텐츠·승인 → 승인 대기함). 퀴즈로 난이도가 정해졌고 시나리오가 없는 내 학급의 최초 결합 활동을 기다린 순서대로 주고, `state`는 `WAITING`(검토 전)·`IN_REVIEW`(서버에 남은 검토 있음)입니다.

## 2026-11-05 — 저장 시각 마이크로초 정규화(#104)

- 기준은 `fix/instant-micros-precision` 작업 브랜치입니다. 원격 병합·운영 배포를 뜻하지 않습니다.
- 필드 이름·타입은 그대로입니다. 서버가 시각을 저장하기 전에 마이크로초 아래 자리를 버려, 처음 응답(메모리 값)과 재전송·재조회 응답(DB 값)의 시각이 같아집니다. 전에는 처음 응답만 나노초(예: `.512881776`)였고 재전송 응답은 DB가 반올림한 값(`.512882`)이었습니다.
- 영향 API: [API-ACT-01](./activities/create-activity.md)·[API-ACT-02](./activities/list-child-activities.md)·[API-ACT-03](./activities/get-activity.md)의 `assignedAt`, [API-IAM-07](./auth/issue-child-code.md)의 `expiresAt`, [API-IAM-03](./auth/login.md)의 `expiresAt`, [API-GOAL-01](./learning-goals/create-learning-goal.md)·[API-GOAL-03](./learning-goals/get-learning-goal.md)의 `createdAt`, [API-QUIZ-04](./quizzes/put-quiz-attempt.md)·[API-QUIZ-05](./quizzes/get-quiz-attempt.md)의 `respondedAt`.
- [API-QUIZ-04](./quizzes/put-quiz-attempt.md)·[API-QUIZ-05](./quizzes/get-quiz-attempt.md)의 `respondedAt`은 PR #103에서 같은 방식(마이크로초 절삭)으로 먼저 맞췄고, 이번 변경은 나머지 저장 시각으로 넓히고 공통 함수 하나로 묶습니다.
- 공통 규칙은 [integration-guide.md](./integration-guide.md) 4절에 적었습니다. DB 컬럼·기존 데이터는 바꾸지 않습니다.

## 2026-11-05 — develop `7376e91`(PR #101·#103) 반영

- 공유 예제: [API-SCN-01](./scenarios/prepare-scenario.md) prepareScenario(POST `/activities/{activityId}/scenario-review`), [API-SCN-04](./scenarios/review-scenario.md) reviewScenario(PUT `/activities/{activityId}/scenario`), [API-SCN-06](./scenarios/delete-scenario.md) deleteScenario(DELETE `/activities/{activityId}/scenario-review`)를 실제 계약으로 바꿔 `examples/endpoints.ts`로 옮겼습니다. 목차는 구현 35개·조건부 2개·설계 18개·미제공 2개이며, 공유 예제 검사가 다시 통과합니다.
- 프론트엔드 아동 화면은 [API-ACT-02](./activities/list-child-activities.md)의 `nextAction`으로 갈 곳을 정합니다. QUIZ → 퀴즈, ROLEPLAY → 역할극, WAIT_FOR_SCENARIO → 선생님 확정 대기 안내, BLOCKED → 이어 갈 수 없음 안내. 확정 전에는 역할극 시작 요청을 보내지 않습니다.

## 2026-11-05 — S4-JIN-02 준비 상태·역할극 재개

- feat/activity-readiness 작업본에서 ACT-02의 data[]와 ACT-03의 data.activity에 nextAction/blockedReason을 추가했습니다. 필드·값·단계별 판정은 개별 명세를 따릅니다.
- 새 Endpoint나 준비 상태 DB 컬럼을 추가하지 않습니다. 페이지 ID 범위의 근거를 일괄 조회하고 기존 승인 호환 검증을 사용합니다.
- ACT-01 생성 응답과 ACT-04 status 전용 응답, 아동/강사 권한 구분은 유지합니다. 사용할 수 없는 과거 퀴즈 문항은 상세에서 비우되 현재 단계의 준비 상태를 제공합니다.
- RPL-01은 중단된 활동·세션을 저장 근거 검사 후 재개합니다. 현재 작은 목표·지원 수준·마지막 확정 턴은 보존하고 세션 버전은 갱신합니다. 상태 전환·새 키 연결·복원 문구 준비가 한 트랜잭션으로 처리됩니다. 검사 실패는 409 ROLEPLAY_RESUME_NOT_READY이며 완료 세션은 재시작하지 않습니다.
- QUIZ-04/06은 새 답변·새 힌트 저장에서 중단된 퀴즈를 검사 후 재개합니다. 동일 답변·동일 힌트 키의 재전송과 GET은 상태를 변경하지 않습니다. 재개 불가·완료 활동의 새 쓰기는 409 QUIZ_ACTIVITY_NOT_READY입니다.
- 2026-11-05 Java 21/PostgreSQL Testcontainers에서 `./gradlew --offline spotlessCheck build --no-daemon`을 통과했습니다. JUnit 125개 클래스, 2,068개 중 2,063개 통과·실패/오류 0·건너뜀 5입니다. 건너뛴 5개는 ROLEPLAY_MEDIA_REAL_TOOLS 환경변수로 활성화하는 기존 실제 FFmpeg 검사입니다.
- 마이그레이션 가드(S4-JIN-01 f8e3dfa 기준, 최대 V39)와 공개 파일 검사는 통과했습니다. 이번 Task는 운영 마이그레이션·준비 상태 컬럼을 추가하지 않습니다.
- 공유 예제 검사는 API-SCN-01 명세 상태에서 실패하며 기준 커밋 f8e3dfa에서도 같은 실패를 재현했습니다. 공유 TypeScript 타입·fixture·검사에는 준비 상태 필드와 재개 정책이 아직 반영되지 않았습니다. 이 검사를 최신 계약 검증 통과로 간주하지 않습니다.
- 실제 프론트엔드/공유 TypeScript 예제 반영, 브라우저 연동·부하 측정·운영 배포는 미검증입니다.

## 2026-11-04 — develop `bab136d` 기준 재대조

- 기준은 develop [`bab136d`](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/tree/bab136d86b0642fd8446b3299fe09de3e9eb4a4e)(PR #78 병합)입니다. 구현·조건부 API 34개를 Controller/DTO/Service와 다시 대조했고, 문서 상단 기준과 코드 근거 링크를 이 커밋으로 옮겼습니다. 운영 배포·제품 인수 완료를 뜻하지 않습니다.
- API 목차는 그대로 57개 ID 중 구현 32개·조건부 구현 2개·설계 21개·미제공 2개입니다. [API-RPL-01](./roleplay/start-roleplay.md)의 "작업 브랜치" 표시를 지웠습니다.
- 호환성 영향이 있는 응답 변경
  - [API-RPL-04](./roleplay/submit-voice-input.md)·[API-RPL-05](./roleplay/submit-text-input.md): `messages[].speakerRole`(`DIALOGUE` 캐릭터 대사·`HINT` 마스코트 힌트/고정 안내)이 생겼습니다. 마스코트 힌트가 붙은 DELIVERED는 `[HINT, DIALOGUE]` 2개, 재입력·재시도·중단 안내는 HINT 1개입니다. 공유 `RoleplayDeliveryAction`에 `SUGGEST_TEXT_KEEP_VOICE`를 더했습니다.
  - [API-RPL-05](./roleplay/submit-text-input.md): 공유 예제를 초안(`clientInputId`·202 접수)에서 실제 계약(`{text}` 본문, 200/202, 음성과 같은 응답)으로 바꾸고 `examples/endpoints.ts`의 조건부 함수로 옮겼습니다.
- 오류 코드 정정
  - [API-GOAL-02](./learning-goals/list-learning-goals.md)의 404는 `CHILD_NOT_FOUND`, [API-GOAL-03](./learning-goals/get-learning-goal.md)의 404는 담당 범위 밖도 `GOAL_NOT_FOUND`입니다.
  - [API-IAM-08](./auth/enter-child.md): `accessCode` 누락/null은 422가 아니라 401 `INVALID_ACCESS_CODE`입니다.
  - [API-ACT-03](./activities/get-activity.md)·[API-QUIZ-03](./quizzes/list-assigned-quiz-items.md)·[API-QUIZ-04](./quizzes/put-quiz-attempt.md)·[API-QUIZ-06](./quizzes/issue-quiz-hint.md): 404 `QUIZ_ITEM_NOT_FOUND`·409 `ITEM_VERSION_CONFLICT`를 더했습니다. 겹쳐 적힌 `ACTIVITY_QUIZ_NOT_FOUND` 행을 하나로 합쳤습니다.
  - [API-IAM-04](./auth/get-instructor-session.md) 404 `USER_NOT_FOUND`(탈퇴 경합), [API-IAM-07](./auth/issue-child-code.md) 다른 강사의 같은 키도 409, 역할극 입력의 400·415·404 경우를 적었습니다.
- 요청 규칙 보완
  - [API-ACT-01](./activities/create-activity.md): 같은 Idempotency-Key 재전송은 `parentGoalId`까지 같아야 200입니다.
  - [API-QUIZ-04](./quizzes/put-quiz-attempt.md): `firstResponse`·`expressionMatchResult`·`finalResponse`·`technicalFailureReason`의 유형별 필수·허용 규칙, NOT_ANALYZABLE·기술 실패는 첫 PUT에서 바로 채점 제외 확정, 201/200 구분을 적었습니다.
  - 이름 칸(API-IAM-02·CLS-01·CLS-06)의 보이는 글자·제어 문자 규칙, 등장인물 id 공백만 422·role 실명 미검사(API-GOAL-01), 역할극 기능 켜기에 필요한 필수 설정을 적었습니다.
- 예시 값 정정: 퀴즈 결과 `overallAccuracy`는 소수 6자리(`0.666667`), 힌트 `type`은 `OBSERVABLE_FACE_CUE`·`OBSERVABLE_BODY_CUE`·`CHOICE_REDUCTION` 중 하나입니다.
- 프론트엔드: 강사·아동 요청이 명세의 공통 헤더(`Accept: application/json`, `Content-Type: application/json; charset=utf-8`)와 경로 인코딩(`encodeURIComponent`)을 따르게 했습니다. MSW 가짜 서버도 위 오류 코드 정정에 맞췄습니다.
- 프론트엔드 아동 화면의 화면 내 가짜 응답을 실제 API로 바꿨습니다.
  - 표정 퀴즈: 문항 [API-QUIZ-03](./quizzes/list-assigned-quiz-items.md), 답 [API-QUIZ-04](./quizzes/put-quiz-attempt.md), 이어 풀기 [API-QUIZ-05](./quizzes/get-quiz-attempt.md), 힌트 [API-QUIZ-06](./quizzes/issue-quiz-hint.md). 첫 답 → (틀리면) 힌트 최대 2개 → 최종 답 1번 흐름입니다. 내 감정 문항은 카메라를 아직 켜지 않아 `expressionMatchResult=NOT_ANALYZABLE`, `cameraModelVersion=camera-not-connected`로 보내며 서버가 점수에서 뺍니다.
  - 완료: 화면이 완료를 요청하지 않고 [API-ACT-04](./activities/get-activity-status.md)로 COMPLETED를 확인한 뒤 스탬프 화면으로 갑니다(독립 퀴즈는 마지막 답 뒤, 역할극은 대화가 이어질 때마다).
  - 역할극: 시작·이어 하기 [API-RPL-01](./roleplay/start-roleplay.md), 글자 답 [API-RPL-05](./roleplay/submit-text-input.md). `speakerRole`로 거북이(DIALOGUE)·깡총이(HINT) 말풍선을 나눕니다. 말로 답하기(API-RPL-04)는 아직 연결하지 않았습니다.

## 2026-11-02 — 학급·아동 이름 수정과 목표 조건 정리·모순 거절

- 기준은 `feature/goal-lineage-edit` 작업 브랜치입니다. 원격 병합·운영 배포·제품 인수 완료를 뜻하지 않습니다.
- API 목차는 57개 ID 중 구현 32개·조건부 구현 2개·설계 21개·미제공 2개입니다.
- 구현으로 변경: [API-CLS-04](./classrooms-children/update-classroom.md) 학급명 수정, [API-CLS-09](./classrooms-children/update-child.md) 아동 이름 수정. 이름만 받고, 공유 예제 함수는 `examples/endpoints.ts`로 옮겼습니다.
- 호환성 변경([API-GOAL-01](./learning-goals/create-learning-goal.md), [API-ACT-01](./activities/create-activity.md)의 goal)
  - `characters`는 `{id, role}` 객체 배열입니다. role은 인물 설명(최대50자)이고 실제 이름을 넣지 않습니다. id는 영문 대문자로 시작하는 관계 종류이며 비우면 `OTHER`, 겹치면 `FRIEND_2`처럼 번호가 붙습니다. `LEARNER`는 거부합니다. role이 없거나 id 형식이 다르면 422 `VALIDATION_FAILED`입니다.
  - 저장 전에 문자열 공백을 정리하고 목록 중복을 지웁니다. `situationType`·`category`가 공백뿐이면 빈 문자열이 아니라 null로 저장합니다.
  - 피할 표현이 필수 요소나 등장인물 role 안에 들어 있으면 422 `INVALID_GOAL_CONDITIONS`이고 `fieldErrors`가 그 피할 표현을 가리킵니다(ACT-01은 `goal.` 접두).
  - `contentHash`는 목록을 정렬해 계산합니다. 칩 순서만 다른 목표는 같은 해시입니다. 이미 저장된 목표는 다시 계산하지 않습니다.
- `situationType`은 상황 설명 문장(최대200자)으로 씁니다. 공유 예제 `types.ts`에 `GoalCharacter`·`GoalCharacterData`를 더했습니다.

## S6-JIN-01 — 음성 입력 복구와 텍스트 입력

- 음성 입력 실패를 재입력 응답으로 안내하고 세 번째 실패부터 텍스트를 권한다. 음성 선택은 유지하며 같은 요청의 재전송을 중복 집계하지 않는다.
- 실제 음성 형식·길이·크기와 검사 도구 장애를 확인한다. 실패 기록은 활동 완료 즉시, 미완료 시 마지막 활동 24시간 후 삭제한다.
- 텍스트 입력은 `text` JSON 본문과 음성 입력의 공통 응답·멱등 계약을 사용한다. 두 입력 API 모두 기능 설정과 운영 제공자 연결이 필요한 조건부 구현이다.
- 공유 TypeScript 예제와 프론트엔드 코드는 이번 백엔드 브랜치에서 변경하지 않았다.

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


## 2026-11-04 · S4-JIN-01 작업본

- SCN-01을 POST /activities/{activityId}/scenario-review의 준비·복원·NEXT 계약으로 변경했다. 한 후보와 조건·DB UUID 목표 연결을 반환하고 후보 소진 시 같은 요청에서 동기 생성한다.
- SCN-04를 PUT /activities/{activityId}/scenario의 선택적 수정·명시적 확정·배정으로 통합했다. 수정본은 새 UUID/버전이며 원본은 유지한다.
- SCN-06을 DELETE /activities/{activityId}/scenario-review의 본문 있는 취소, 200 종료 응답으로 변경했다.
- SCN-03/05/07은 이번 경로의 응답·확정 처리로 통합된 과거 제안으로 표시했다. 전체 카탈로그 SCN-02는 미구현 별도 설계다.
- 결과 GET 자동 연결 설명을 폐기하고 활동/역할극의 명시적 승인 선행 조건을 반영했다.
- contracts/api-v1.json은 기존 FE/BE 공유 전송 검사 픽스처로 유지한다. 새 세 API의 HTTP 계약은 ScenarioReviewHttpIntegrationTest가 검증한다. FE 예제·MSW·화면은 수정하지 않았다. develop 병합·운영 배포는 미확인이다.


## 2026-11-05 · 공통 풀 전환 작업본

- SCN-01/04/06의 재사용 범위를 본인 승인본에서 전체 공통 승인 풀로 변경했다. HTTP 경로·본문·revision 구조는 유지한다.
- 제시 순서는 강사별이며 거절은 이번 검토에만 적용한다. 공통 제공 중지 API는 추가하지 않았다.
- 활동별 승인자와 원 콘텐츠 최초 승인자를 분리하고, 원 승인자 탈퇴 후에도 후보 조회·확정을 지원한다. V39의 삭제 보존 변경을 포함한다.
- 선택 진단 로그는 ruleVersion=2/pool로 변경했다. pool은 공통 승인 기록 수, eligible은 조건 통과 수다. CONFIRM 목적은 해당 후보만 검사하며 전체 풀 집계와 섞지 않는다.
- 공통 후보 조회·타 강사 확정·탈퇴 후 재사용·개별 순환·검토 거절·수정 새 공통 후보를 HTTP로 검사했다. 전체 회귀와 실제 FE/배포/부하 측정은 아직 진행 전이다.
