# 역할극 리포트·다음 난이도·추천을 팀 앱에 붙이기

이 문서는 역할극이 끝난 뒤의 세 기능을 팀 앱(develop `ccfdff0`) 위에서 서버·화면까지 이은 방법을 설명한다. 세 기능은 강사가 보는 학습 리포트(03 VS-014), 다음 역할극 난이도(VS-016), 다음 수업 추천(VS-015)이다. 도메인 계산과 LLM 생성·평가 루프는 [learning-report-loop.md](learning-report-loop.md)에 있고, 여기서는 그 코드를 언제 부르고 어디에 저장하고 누구에게 보여 주는지를 다룬다. 작업 브랜치는 `feature/roleplay-report-integration`이고 develop에는 아직 합치지 않았다.

## 역할극이 끝나면 일어나는 일

역할극 완료는 팀 코드의 `RoleplayCompletionService.commitApprovedTurn` 한 트랜잭션에서 확정된다. 목표를 달성하거나 최대 턴에 닿으면 활동이 COMPLETED가 되고 학습 기록이 생기며, 같은 트랜잭션에서 표준 발화 원문을 지운다. 여기에 이벤트 하나를 더했다. 활동이 이번 턴으로 처음 완료되면 `RoleplayActivityCompleted`를 발행한다. `ActivityCompletionService.completeAfterRoleplay`가 처음 완료했는지를 boolean으로 돌려주게 바꾼 것도 이 때문이다. 리포트 쪽은 이 이벤트를 커밋 뒤(`AFTER_COMMIT`)에만 받으므로, 완료가 롤백되면 리포트도 생기지 않는다.

리포트는 두 단계로 만든다. 1단계(`ReportPreparationService`)는 LLM을 부르지 않고 DB 기록만 읽어 목표별 결과, 지표, 다음 난이도, 추천을 한 트랜잭션에 남긴다. 리포트는 PENDING 상태로 저장된다. 2단계(`ReportGenerationService`)는 생성 전용 스레드에서 AI 요약을 만들고 리포트를 COMPLETED나 FAILED로 바꾼다. 둘로 나눈 이유는 두 가지다. 완료 처리는 아동 턴 스레드에서 60초 예산 안에 돌아서 LLM을 기다릴 수 없다. 또 03 명세가 "AI가 실패해도 숫자는 기록에서 나와야 한다"고 정했다. 그래서 LLM이 없거나(`AI_CHAT_PROVIDER=none`) 실패해도 다음 난이도와 추천은 이미 저장돼 있다.

2단계 작업이 서버 재시작이나 대기열 초과로 멈추는 경우도 있다. 그래서 5분마다 도는 재처리가 10분 넘게 바뀌지 않은 PENDING 리포트를 다시 넣는다. 상태를 바꾸는 UPDATE는 모두 `status = 'PENDING'`을 조건으로 걸었다. 같은 리포트를 두 작업이 맡아도 먼저 끝난 결과 하나만 남는다. 한 리포트를 세 번 넘게 맡으면 `GENERATION_STALLED`로 끝낸다.

## 답 기록을 목표별 결과로 바꾸는 법

develop의 역할극은 답마다 아동이 실제로 답한 질문의 문맥(`roleplay_turn.input_micro_goal_id`·`input_support_level`·`input_answer_revealed`)과 학습 상태(`roleplay_goal_assessment.input_learning_state`), 기준별 관찰(`roleplay_goal_observation`)을 남긴다. `RoleplayAnswerReader`가 이 세 테이블을 JDBC로 읽고 `RoleplayAnswerMapper`가 지난 작업의 `RoleplayTurnRecord`로 바꾼다. 원문 칸(`canonical_utterance`·`response_text`)은 고르지 않는다.

관찰 기준은 DB에 문장이 아니라 해시로만 남는다. `criterion_reference`는 `evidence/v1:` 뒤에 sha256(작은 목표 ID + 줄바꿈 + 기준 문장)을 붙인 값이다. 그래서 매퍼는 승인 시나리오의 `required_evidence` 문장마다 같은 해시를 다시 계산해 문장을 찾는다. 역할극 문맥을 만드는 `JdbcRoleplayContextSource`와 같은 공식이다. 맞는 기준이 없는 관찰은 버리고 개수만 로그에 남긴다. 답을 알려 준 뒤의 답(`input_answer_revealed`)은 지원 단계 기록과 상관없이 S3에서 한 답으로 본다. 팀 진행 규칙의 ANSWER_REVEALED와 같은 기준이다. 응답 거부(`response_act`)는 DB에 남지 않아서, 거부로 빼는 목표는 없다.

## 다음 역할극 난이도와 역할극 단독 활동

다음 난이도는 역할극을 마친 활동마다 한 번 정하고 `follow_up_difficulty_decision`에 남긴다(V26). 연속 상향 후보 수는 이 아동의 가장 최근 결정에서 이어받는다. 결정 행에는 점수율(GoalScoreRate의 6배 정수), 이유, 정책 버전이 함께 있어서, 같은 입력과 같은 버전이면 같은 결과를 다시 낼 수 있다.

develop에서는 역할극 단독 활동(ROLEPLAY)을 422로 막고 있었다. 역할극은 최초 결합 안에서만 시작됐고, 그때 난이도는 퀴즈로 정한 최초 난이도였다. 그래서 다음 난이도를 정해도 쓸 곳이 없었다. 이번에 단독 활동을 열었다. 배정할 때 이 아동의 최신 결정의 레벨과 시작 지원(L1·S2, L2·S1, L3·S0)을 `roleplay_activity_difficulty`에 고정한다. 배정 뒤 다른 역할극이 끝나 결정이 바뀌어도 이미 배정한 활동은 바뀌지 않게 하려는 것이다. 마친 역할극이 없어 결정이 없으면 409 `ROLEPLAY_DIFFICULTY_NOT_READY`로 거절한다.

승인 시나리오는 강사가 배정할 때 고른다(2026-11-02, PR #90 리뷰). 처음에는 아동이 시작할 때 서버가 최초 결합과 같은 기준으로 붙였는데, 그러면 강사가 상황과 작은 목표를 보지 못한 채 역할극이 진행된다. 그래서 활동 만들기 확인 단계가 후보(`GET /api/v1/children/{childId}/roleplay-scenarios`, `RoleplayScenarioSelectionService`)를 보여 주고, 배정 요청의 `scenarioId`를 서버가 다시 확인한다. 후보는 같은 목표 내용·고정 레벨의 승인본 가운데 이 아동이 아직 쓰지 않은 것이고, 학급은 보지 않는다(팀 결정). 순서는 마지막으로 쓰인 지 오래된 것(안 쓰인 것이 맨 앞) → 최근 승인본(V27 `approved_at`)이다. 다른 아동이 쓴 승인본은 강사가 재사용을 승인해야(`reuseApproved`) 배정한다. 시작 입력은 퀴즈 근거 대신 고정 난이도를 같은 모양(`InitialDifficultyEvidence`, 문항 수 0)으로 옮겨 넘긴다. 그래서 세션을 만드는 팀 코드는 그대로 둘 수 있었다.

## 추천과 강사 결정

추천은 리포트 1단계에서 서버 규칙(`RECOMMENDATION_V1`)으로 최대 3개를 만든다. 보완·반복·적용은 지금 학습 목표를 이어받는 제안(`parentGoalId`)이다. 확장은 넓힐 목표를 강사가 써야 해서 제목을 비워 둔다. 유사 목표 검색(임베딩)은 운영에 붙이지 않았다. 이유 문장은 강사 화면 문구라 해요체로 쓴다.

강사는 채택, 고쳐서 채택, 보류, 거절 가운데 하나를 고른다. 결정은 `recommendation_decision`에 남기고 목표나 활동은 만들지 않는다. 자동 배정을 막는 03의 원칙이다. 화면은 채택 뒤 목표가 채워진 활동 만들기로 이동하고, 배정은 강사가 그 화면에서 한다. 결정 요청에는 `Idempotency-Key`가 필요하다. 같은 키에 같은 입력이면 앞 결과를 돌려주고, 다른 입력이면 409다. 채택·고쳐서 채택·거절은 확정이라 다시 바꿀 수 없고, 보류만 나중에 바꿀 수 있다.

## API와 화면

계약 `contracts/api/v1/reports-recommendations`의 다섯 API(API-REC-01~05)를 설계에서 구현으로 바꿨다. 다음 난이도 조회 API-REC-06(`GET /children/{childId}/roleplay-difficulty`)도 더했다. 모두 담당 강사 Bearer 전용이고, 남의 자원은 없는 자원과 같은 자기 404 코드로 숨긴다. 리포트는 AI 요약까지 끝난 것만 200이다. 계약에서 `Record<string, unknown>`로 열려 있던 칸은 서버 계산값으로 확정했다. 그 내용(`quiz`, `roleplay.goals[]`, `difficultyDecision`, `metrics`, `narrative`)을 계약 문서와 `examples/`에 맞췄다. API-REC-06의 `sourceRecordId`는 AI 요약이 실패해 리포트가 404여도 화면이 추천을 읽을 수 있게 둔 값이다.

강사 화면은 시안 자리에 그대로 붙였다.

- **활동 리포트:** 개요·역할극·난이도 근거·기술 기록 탭을 열었다. AI 요약 수정 탭은 계약에 강사 수정이 없어서 팀이 정할 때까지 닫아 둔다.
- **추천·다음목표:** 메뉴를 열었다. 학급을 고르면 아동마다 다음 난이도와 제안이 보인다.
- **학급 개요:** "최근 학습 요약" 칸에 아동별 한 줄과 확인할 제안 수를 보인다.
- **활동 만들기:** 역할극 카드를 열고 `goalTitle`·`goalCategory`·`parentGoalId` 주소 값을 읽어 목표를 채운다.
- **데이터를 읽는 법:** 학급 단위 API는 만들지 않았다. 아동마다 계약 API를 부르고, 학급 인원이 적다고 보았다.

## 호출 목적별 모델과 운영 설정

엘리스 ML API는 모델마다 주소가 다르다. 그래서 `neuringo.ai.routes.<목적>`(base-url·model·request-timeout·max-output-tokens·reasoning-effort)을 두었다. 경로가 있으면 `OperationRoutingLlmProvider`가 그 목적만 따로 만든 클라이언트로 보낸다. 지난 실측의 추천대로 리포트 초안은 Haiku 5.5(추론 none), 평가는 Gemini 4 Argon(low)으로 보낸다. 엘리스 게이트웨이는 비스트리밍 출력을 2,000토큰(추론 포함)까지만 받아서, 경로의 출력 상한 기본값도 2,000이다. `neuringo.ai.call-log=true`를 주면 호출마다 목적, 결과, 종료 이유, 토큰 수만 한 줄로 남긴다. 글 내용은 남기지 않는다.

| 설정 | 기본값 | 뜻 |
|---|---|---|
| `AI_REPORT_GENERATION_BASE_URL`·`_MODEL`·`_REASONING_EFFORT`·`_MAX_OUTPUT_TOKENS`·`_TIMEOUT` | 비움·2000·60s | 리포트 초안 경로. 비우면 기본 모델 |
| `AI_REPORT_REVIEW_*` | 비움·2000·90s | 리포트 평가 경로 |
| `AI_MAX_OUTPUT_TOKENS`·`AI_REASONING_EFFORT` | 비움 | 기본 경로(역할극 등)의 출력 상한·추론 강도 |
| `REPORT_WORKERS`·`REPORT_QUEUE_CAPACITY`·`REPORT_STALE_AFTER`·`REPORT_MAX_ATTEMPTS` | 1·50·10m·3 | 생성 스레드와 재처리 |
| `REPORT_UPGRADE_THRESHOLD_PERCENT`·`REPORT_FAST_UPGRADE_PERCENT` | 80·끔 | 팀 결정 대기 3번. 바꾸면 정책 버전 뒤에 값이 붙는다 |
| `REPORT_EXTEND_FROM_LOWEST_LEVEL` | true | 팀 결정 대기 4번. L1 만점에서도 확장을 낼지 |

## 팀 코드를 고친 곳과 이유

| 파일 | 바꾼 것 | 이유 |
|---|---|---|
| `roleplay/service/RoleplayCompletionService` | 처음 완료일 때 `RoleplayActivityCompleted` 발행, 생성자에 `ApplicationEventPublisher` | 리포트가 완료 커밋 뒤에만 돌게 |
| `activity/service/ActivityCompletionService` | `completeAfterRoleplay`가 boolean 반환 | 재전송 턴에서 이벤트가 두 번 나지 않게 |
| `assignment/service/ActivityAssignmentService` | ROLEPLAY 422 제거, 다음 난이도 고정·퀴즈 없이 배정 | 다음 난이도를 실제로 쓰려면 단독 역할극이 필요 |
| `activity/repository/ActivityRepository.attachScenarioIfEmpty` | 유형 조건에 ROLEPLAY 추가 | 단독 활동에도 시나리오를 한 번만 붙이게 |
| `roleplay/service/RoleplayStartService`·`InitialRoleplayStartQueryService` | 단독 활동이면 시작 때 시나리오를 붙이고 고정 난이도로 시작 | 퀴즈 근거가 없는 활동의 시작 입력 |
| `deletion/service/DeletionService` | 리포트·추천·난이도를 학습 기록보다 먼저 지움 | 아동 삭제(ADR D1) 순서 |
| `auth/security/SensitiveReadLog` | 대상 4개 추가 | 리포트·추천·난이도 조회도 민감 조회로 남김 |
| `ai/config/AiProviderConfiguration`·`SpeechProviderConfiguration` | 목적별 경로·호출 로그, `scripted` STT·`silent` TTS | 모델 분리, 로컬 끝까지 돌려 보기 |
| `ai/application/structured/StructuredLlmExecutor` | 출력 검사에 걸리면 DEBUG 로그 한 줄(목적·프롬프트 버전·시도·검사 문장) | 고정 안내로 끝난 턴의 원인이 남지 않았다. 검사 문장은 고정 문장이라 모델 출력·아동 발화가 들어가지 않는다 |
| `roleplay/config/LocalScriptInputConfiguration`(새 파일) | `roleplay.local-script-input=true`일 때만 입력 처리기·음성 전달 빈 | 역할극 HTTP API가 요구하는데 팀 코드에 운영 구현이 없다. 개인정보 거르기·안전 판단을 하지 않으므로 로컬 대본 전용이다 |
| 테스트·계약 | `ApiContractTest`·`apiContract.test.ts` PATCH 지원, `OwnershipPatrolIntegrationTest` 등록, ROLEPLAY 422 기대값을 409로 | 새 API와 바뀐 배정 규칙 |

## 확인한 것

백엔드 단위·통합 테스트로 아래를 확인했다. 통합 테스트는 Testcontainers PostgreSQL에서 돌렸다.

- **완료 이벤트에서 리포트까지:** 완료 이벤트를 받으면 지표·난이도·추천이 남는다. LLM이 없으면 리포트가 `AI_UNAVAILABLE`로 끝나 404이고, 추천과 난이도는 200이다.
- **소유권과 결정:** 남의 리포트·추천은 404다. 결정은 같은 키·같은 입력에서 앞 결과를 돌려주고, 확정 뒤에는 409다. 아동을 지우면 새 테이블도 비워진다.
- **역할극 단독 활동:** 고정 레벨의 승인본에만 붙고 시작 지원이 고정값이다. 맞는 승인본이 없으면 시작하지 않는다.
- **기존 테스트:** 팀의 역할극 완료, 진행 정책, 퀴즈 흐름, 배정, 계약, 소유권 순찰 테스트가 함께 통과한다.

프론트는 vitest 314개(MSW 계약 시나리오 포함)와 Playwright e2e 2개(실제 로컬 백엔드)가 통과했다. 실제 모델로 끝까지 돌린 기록은 [docs/roleplay-report-e2e-2026-11-01.md](../../docs/roleplay-report-e2e-2026-11-01.md)에 있다.

## 아직 남은 것

- **고정 안내 턴(팀 역할극 처리기):** "모르겠어"가 잦은 대본(`scripts/local/answers/unsure.json`)으로 다시 돌리자 6번 중 3번이 고정 안내로 끝났다. 출력 검사 로그를 보면 모델은 세 번 다 응답을 냈지만 서버 검사가 같은 이유로 세 번 거절했다. 이유는 80자 초과(`text is too long`) 한 턴과, 질문이 아닌 응답 유형인데 물음표를 쓴 것(`a non-question response must not ask a question`) 두 턴이다. `StructuredRoleplayTurnSteps.generationRetry()`는 평가 모델이 돌려보낸 경우에만 재시도 지시를 만들고, 출력 검사에 걸린 경우에는 아무 지시 없이 같은 입력을 다시 보낸다. 원인 판단 단계에는 같은 경우를 위한 지시(`invalidAnalysisOutput`)가 있다. 응답 생성에도 걸린 검사를 알려 주는 재시도 지시가 필요하다. 팀 역할극 처리기의 몫이라 이 브랜치에서는 고치지 않았다.
- **시나리오 재사용 규칙:** 재사용 시나리오의 강사 확인, 고르는 순서, 다음 후보는 팀 논의 중이다(2026-10-31). 지금은 강사 확인 없이 오래된 순으로 붙는다. 정해지면 `findFirstUnusedApproved`와 그것을 부르는 두 곳(최초 결합, 단독 역할극)을 함께 바꾼다.
- **운영 입력 처리기와 음성 전달:** 실제 아동 음성으로 역할극 HTTP API를 켜려면 팀이 운영 구현을 만들어야 한다. 로컬 설정은 대체물이 아니다.
- **팀 결정 1·2번:** S3 달성 뒤 다음 목표의 시작 지원과 판단 불가 답의 횟수 처리는 역할극 프롬프트 규칙에 있다. 서버 설정으로 열지 않았다. 이번 첫 실행에서도 앞 목표를 S1에서 이룬 뒤 다음 목표가 S0으로 내려가지 않고 S1에서 시작했다(리포트 개요의 목표별 도움 표). 지난 시뮬레이션에서 본 지원 고착이 실제 모델에서도 보인 것이다.
- **아동 역할극 화면:** 아직 mock이다. 실제 API(음성 녹음)에 붙이는 일은 팀 아동 UI 몫이다.
