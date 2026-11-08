# 합성 P1과 서비스 입력 조립 경로 대조

검토일: 2026-11-08\
대상: 원격 develop 9ffa124 기반 현재 작업 코드, 캐릭터 P1, 분석 전용 진단 analysis-review-01\
수행 범위: 코드·저장된 입력·기획/후속 설계 문서 읽기와 본 문서 작성. 코드 수정·DB 조회·추가 API 호출 없음.

## 결론

확인된 차이는 “모델에 필요한 필드를 몇 개 더 넣으면 된다”는 수준이 아니다.
현재 회귀 실행기는 서비스의 승인 문맥·응답 이력·지원 정책 조립을 우회한다.
그 결과 합성 P1의 질문 횟수·최근 대화·빈 이력이 서비스 코드가 같은 시점에 만드는 조합과 맞지 않는다.
지원 정책과 학습 입력 순번도 서비스 경로와 다르다.

이 차이는 코드로 확인했지만, 기존 모델의 INSUFFICIENT_CONTEXT 응답을 유발한 원인인지는 확인하지 못했다.
또한 실제 운영 DB나 배포 환경을 조회한 것이 아니라 현재 코드의 입력 생성 규칙과 비교한 결과다.

## 두 입력 생성 경로

서비스 경로:

1. RoleplayContextAssembler가 활동·아동·세션·시나리오·체크포인트 범위로 조회한다.
2. JdbcRoleplayContextSource가 승인된 콘텐츠, 저장된 응답, 관찰·입력 상태를 같은 읽기 시점에서 모은다.
3. RoleplayGoalResponseHistory에서 질문 횟수·정답 제공 여부를 계산한다.
4. RoleplayGoalObservationHistory가 관찰 당시 지원과 이번 발화 이전의 목표별 노출을 복원한다.
5. JdbcRoleplaySupportHistorySource가 확정 입력 판정·근거를 복원하고 지원 감소 정책을 계산한다.
6. 학습 입력 순번·목표별 유도 순번과 대화를 조립한 뒤 프롬프트 팩토리로 전달한다.

현재 회귀 경로:

1. RegressionSet.toInput이 JSON의 상태·대화를 그대로 사용한다.
2. RoleplayEvalRunner가 RoleplayObservationTestPlans.inputForScenario를 호출한다.
3. 이 헬퍼는 승인 기준만 만들고 과거 관찰·응답 이력을 비우며 지원 정책은 unavailable로 둔다.
4. 프롬프트 팩토리는 받은 자료를 출력하므로, 서비스에서 유래한 일관성이 자동으로 보장되지 않는다.

## 확인된 차이

아래의 “서비스 첫 입력”은 **저장 응답이 0건인 첫 입력이고, 현재 코드의 기본 지원 정책을 사용하며,
진행 제한은 실험용 값으로 명시 설정한 경우**를 뜻한다. 실행 중인 운영 환경의 실측값이 아니다.

| 항목 | 합성 P1의 실제 요청 | 위 조건의 서비스 코드에서 유도되는 값 | 판단 |
| --- | --- | --- | --- |
| turn_count | 1 | 저장된 이전 턴 0건 + 1 = 1 | 일치 |
| current_micro_goal_prompt_count | 1 | 저장된 질문 응답이 없으므로 0 | 불일치 |
| recent_dialogue | 이전 AI 대사 1개 | roleplay_turn이 없으면 빈 배열 | 불일치 |
| 사전 질문/응답 이력 | question_count=0, last_response=null | 빈 응답 이력이면 같은 값 | 이 값 자체가 무조건 누락은 아님 |
| learning_turn_count | null | 빈 확정 이력의 첫 입력이면 1 | 서비스의 학습 순번 계산을 우회 |
| current_micro_goal_learning_support_turn | null | 같은 조건의 목표 첫 입력이면 1 | 서비스의 학습 유도 순번 계산을 우회 |
| support_adjustment.status | EVIDENCE_UNAVAILABLE | 기본 정책 + 빈 이력이면 NOT_ELIGIBLE | 상태 의미가 다름 |
| 지원 정책 | policy_version 등 모두 null | SUPPORT_DECREASE_V1 / 목표 2개 / RESET_SEQUENCE | 정책 계산 경로를 우회 |
| confirmed_observations·독립 검수 이력 | 빈 배열 | 첫 입력이면 빈 배열일 수 있음 | 허구의 이전 관찰로 채울 대상이 아님 |
| goal_evidence_so_far | 전부 NONE | 이전 긍정 관찰이 없으면 전부 NONE | 이번 발화의 관찰과 구분하면 정상 |
| 최대 턴·목표별 상한 | 회귀 JSON의 6 / 4 | 배포/테스트에서 명시한 값. 미설정 시 진행 조회를 허용하지 않음 | 실험값을 확정 기획으로 간주하지 않음 |

질문 횟수 1과 사전 노출 횟수 0이 항상 모순이라는 일반 규칙을 새로 주장하는 것은 아니다.
여기서는 두 값 모두 같은 저장 응답을 기준으로 계산하는 서비스 구현과, 그 경로를 사용하지 않은 이 P1을 비교했다.
또한 최근 대화가 역할극 시작 화면의 대사였을 수는 있지만, 현재 조회 코드는 opening_prompts를 최근 대화에 합치지 않는다.
시작 대사를 LLM 컨텍스트에 넣어야 하는지 여부는 별도 계약 검토 사항이며 이번 조사로 운영 버그라고 확정하지 않는다.

## 지원 정책에서 정정·보완할 이해

application.yml만 보면 진행 제한이 비어 있지만, 지원 감소 기본값은 별도 application.properties에 있다.
현재 저장소의 후속 설계 문서 roleplay-support-adjustment.md도 사용자 확정 기준을 명시한다.

- 서로 다른 목표 2개를 힌트 없이 연속 도달하면 다음 목표 지원 감소 자격을 얻는다.
- 부분 이해·도움 요청은 연속성을 초기화하고 판정 불가는 기존 연속성을 유지한다.
- 버전은 SUPPORT_DECREASE_V1이며 이 버전에서 목표 수·초기화 규칙을 바꾸면 거부한다.

따라서 지원 감소의 목표 수를 다시 임의로 정할 필요가 없다.
반면 회귀 입력의 EVIDENCE_UNAVAILABLE은 이 정책을 평가한 결과가 아니다.
GoalObservationInput의 기본 생성자가 넣는 unavailable 값이 그대로 전달된 것이다.
NOT_ELIGIBLE(정책은 있으나 아직 자격 없음), POLICY_REQUIRED(정책 필요), EVIDENCE_UNAVAILABLE(근거 부족)을 구분해야 한다.
배포 환경의 덮어쓰기 값은 조회하지 않았으므로 위 기본값을 모든 실제 환경의 설정이라고 단정하지 않는다.

## 활동 판정·다음 전략에 실제로 필요한 자료

| 검수 | 대조해야 할 자료 | 이번 진단에서의 상태 |
| --- | --- | --- |
| 사건 관찰 | 아동 발화, 승인된 required_evidence와 참조 | 제공됐고 모델도 SUPPORTED 반환 |
| 활동 목표 IN_PROGRESS 판정 | 승인된 learning_goal, 이번/과거 관찰, 정답·도움 노출과 판정 근거 | 목표·현재 관찰은 제공됐으나 합성 이력의 생성 경로가 서비스와 다름 |
| 다음 감정 목표 전략 | 현재 질문 초점, 이번 발화의 달성 근거, 다음 승인 목표, 그 목표의 관찰·지원 이력, 지원 정책·학습 순번 | 기본 정책·학습 순번이 서비스와 다르고 상태/대화/이력 조합도 불일치 |

MISSING_ACTIVITY_CONTEXT / MISSING_STRATEGY_CONTEXT는 모델이 고른 진단 사유다.
어떤 실제 필드가 필수인데 빠졌는지를 지목한 것은 아니다.
위 차이를 확인했다고 그 사유의 원인을 특정한 것으로 해석하지 않는다.
현재로서는 운영 API에 새 필수 필드를 추가할 근거도 충분하지 않다.

## 완료 기준의 문서·프롬프트 적용 차이

첨부 기획 06의 과거 미정 항목만으로 후속 결정을 전부 미정으로 되돌리면 안 된다.
현재 저장소의 roleplay-goal-observation-design.md에는 “활동 목표 달성 시 정상 완료”라는 사용자 확인이 있다.
roleplay-completion-integration.md는 명시적 최대 턴에 따른 마무리와 학습 목표 달성을 구분한다.

다만 구현 간 적용 범위를 따로 점검해야 한다.

- 후속 설계 문서와 서버 검증은 작은 목표의 고정 순서·전 항목 달성을 별도 필수 완료 조건으로 추가하지 않는다.
- 서버는 활동 목표 판정의 참조와 긍정 관찰 근거를 검사하되, 긍정 관찰 하나만으로 자동 완료하지 않는다.
- 캐릭터 분석 프롬프트에는 모든 작은 목표에 증거가 있으면 ACHIEVED/CLOSE_ROLEPLAY로 하라는 충분조건이 있다.
- 캐릭터 검수 프롬프트도 그 경우 IN_PROGRESS를 STATE_STRATEGY_MISMATCH로 돌린다.
- 이것을 “모든 목표가 있어야만 완료 가능”이라는 필요조건으로 읽으면 안 된다.
  다만 활동 목표의 독립적인 의미 검수와 자동 마무리 규칙 사이의 적용 차이는 별도 검토 대상이다.

이번 P1은 모든 목표의 증거가 모인 경우가 아니므로 이 차이를 이번 거절의 직접 원인으로 단정하지 않는다.
활동 완료 전체가 미정이라고 설명하는 것도 부정확하다. 확정된 완료 동작과 세부 의미 검수 기준을 구분해야 한다.
이번 작업에서 프롬프트·완료 정책·지원 정책은 바꾸지 않았다.

## 권고하는 다음 범위: 서비스 경로로 만든 별도 합성 입력

기존 P1과 진단 결과를 보존하고 서비스 조립 경로를 통과한 별도 합성 P1을 만든다.
추정한 지원 이력을 손으로 추가하거나 기존 파일의 결과를 덮어쓰지 않는다.

1. 격리된 테스트 DB에 같은 의미의 승인 시나리오·학습 기준·캐릭터·세션을 구성한다.
2. RoleplayContextAssembler/JdbcRoleplayContextSource로 첫 입력의 실제 스냅샷을 만든다.
   진행 상한 6/4는 이번 비교용 테스트 설정으로만 명시하고, 지원 감소는 현재 기본 정책을 사용한다.
3. 아동 발화와 분석 2의 의미 판단은 유지한다. DB에서 생긴 목표 ID/기준 참조가 다르면 동일 목표·기준과의 대응을 명시해 재연결한다.
   학습 상태·관찰 상태·활동 판정·다음 전략을 조용히 바꾸지 않는다.
4. 질문 수·대화·사전 이력·정책·학습 순번이 같은 스냅샷에서 유도됐는지 오프라인 검사한다.
5. 같은 분석 전용 진단 프롬프트/스키마 v1을 사용하고, 후보 대사는 계속 제외한다.
6. 검사 통과 후 승인된 경우에만 기존 모델에 물리 호출 1회, SDK 재시도 0회로 새 진단을 실행한다.
7. 이전 진단의 세 상태와 비교한다. 여러 컨텍스트 값이 함께 달라지므로 특정 필드 하나의 인과 효과로 해석하지 않는다.

다음 범위도 테스트·진단 코드로 제한한다. 운영 평가·DB 마이그레이션·완료 정책·FE 변경은 포함하지 않는다.
실제 모델의 정보 부족 응답이 계속되면 확인된 입력을 기준으로 검수 기준/모델 능력을 조사할 근거가 된다.
단건 승인 또는 단건 거부만으로 운영 전달 게이트를 완화하지 않는다.

## 근거 위치

### 승인 후 구현·검증 결과

ServiceContextP1FixtureTest를 추가해 Testcontainers PostgreSQL에 합성 승인 콘텐츠·세션을 만들고
실제 RoleplayRequestScopeService → RoleplayContextAssembler → JdbcRoleplayContextSource를 실행했다.
진행·지원 정책을 가짜로 대체하지 않았다. 기존 P1과 진단 파일은 보존했다.
테스트 내 6/4 제한과 세션의 현재 목표·S0는 비교용으로 명시한 상태다.
최초 퀴즈·난이도 배정·세션 시작까지 재현한 것은 아니다.

관찰·정책·학습 순번은 서비스 코드가 계산했다. 첫 입력의 질문 수 0, 최근 대화 없음,
학습 순번 1, NOT_ELIGIBLE/SUPPORT_DECREASE_V1이 실제 조립 결과로 확인됐다.
사건 목표의 사전 상태도 NOT_OBSERVED로 조회됐다. 이전 합성 P1의 IN_PROGRESS와 다른 추가 컨텍스트 차이다.
승인된 기준과 목표의 식별자는 명시적으로 재연결했고 분석의 의미·시나리오·아동 발화가 같은지 검사했다.
실제 호출 요청에서도 후보만 제외됐는지 확인했다.

실행 명령:

```bash
./gradlew spotlessApply test --tests '*ServiceContextP1FixtureTest' --tests '*AnalysisReviewDiagnosticTest'
./gradlew spotlessCheck
./gradlew analysisReviewDiagnostic --args="--service-context"
```

관련 22건과 형식 검사가 통과했다. 운영 코드가 아닌 진단·테스트 범위에 맞춰 관련 검사를 수행했으며
이전 전체 검사에서 간헐적으로 실패했던 CSRF 업로드 테스트까지 해소했다고 주장하지 않는다.

### 실 API 비교 결과

실행은 elice-p1-service-analysis-review-01, 물리 호출은 1회다. SDK 재시도는 0회이고 2.886초 걸렸다.
실제 모델 gpt-5.4-nano-2026-03-17과 분석 전용 프롬프트·출력 계약 v1은 이전 진단과 같다.
입력 3,130 / 출력 117토큰을 사용했고 단가가 없어 비용은 계산하지 않았다.

| 검수 | 기존 합성 입력 진단 | 서비스 조립 입력 진단 |
| --- | --- | --- |
| 사건 관찰 | SUPPORTED | UNSUPPORTED / OBSERVATION_NOT_GROUNDED |
| 활동 목표 판정 | INSUFFICIENT_CONTEXT | UNSUPPORTED / ACTIVITY_ASSESSMENT_NOT_GROUNDED |
| 다음 전략 | INSUFFICIENT_CONTEXT | INSUFFICIENT_CONTEXT / MISSING_STRATEGY_CONTEXT |

두 거절의 criterion_references는 빈 배열이었다. 현재 진단 스키마는 이를 허용해 구조·참조·상태/사유 검사는 통과했다.
따라서 VALID_DIAGNOSTIC_OUTPUT은 분석의 거절 근거가 옳다는 뜻이 아니다.
서비스 입력 조립의 차이를 정리해도 이번 요청에서 검수 거절·정보 부족이 해소되지는 않았다.
모델이 어떤 기준을 잘못됐다고 판단했는지도 아직 구체화하지 못했다.

입력의 여러 컨텍스트·식별자가 함께 바뀌었고 조건별 단건이므로 특정 필드의 효과나 모델의 재현성은 확정할 수 없다.
이번 결과를 분석 오류 확정·PASS 전환·서비스 전체 해결로 사용하지 않는다.
검증된 입력을 고정하고 명확히 올바른 관찰과 잘못된 관찰을 대조할 기준 사례가 다음 조사에 필요하다.
추가 호출이나 운영 평가 변경은 수행하지 않았다.

산출물은 build/eval/elice-p1-service-analysis-review-01의 summary.md·result.json·request.json·
source-fixture.json·comparison-checks.json에 있다. 재생성용 테스트 원본은 ServiceContextP1FixtureTest다.

- [서비스 문맥 조회와 입력 상태 조립](</Users/jinmina/IdeaProjects/neuringoBe/backend/src/main/java/com/neuringo/neuringobe/roleplay/service/JdbcRoleplayContextSource.java:274>)
- [서비스의 최근 대화 조회](</Users/jinmina/IdeaProjects/neuringoBe/backend/src/main/java/com/neuringo/neuringobe/roleplay/service/JdbcRoleplayContextSource.java:324>)
- [저장 응답 기반 질문 횟수](</Users/jinmina/IdeaProjects/neuringoBe/backend/src/main/java/com/neuringo/neuringobe/roleplay/application/RoleplayGoalResponseHistory.java:151>)
- [확정 사전 노출 복원](</Users/jinmina/IdeaProjects/neuringoBe/backend/src/main/java/com/neuringo/neuringobe/roleplay/application/RoleplayGoalObservationHistory.java:63>)
- [학습 입력 순번 계산](</Users/jinmina/IdeaProjects/neuringoBe/backend/src/main/java/com/neuringo/neuringobe/ai/application/prompt/RoleplayLearningSupportTurns.java:14>)
- [기본 지원 정책](</Users/jinmina/IdeaProjects/neuringoBe/backend/src/main/resources/application.properties:1>)
- [후속 확정 지원 정책 문서](</Users/jinmina/IdeaProjects/neuringoBe/backend/docs/roleplay-support-adjustment.md:5>)
- [지원 정책 계산](</Users/jinmina/IdeaProjects/neuringoBe/backend/src/main/java/com/neuringo/neuringobe/roleplay/application/RoleplaySupportAdjustmentPolicy.java:17>)
- [활동 목표 판정의 적용 기준](</Users/jinmina/IdeaProjects/neuringoBe/backend/docs/roleplay-goal-observation-design.md:21>)
- [완료·목표 달성의 구분](</Users/jinmina/IdeaProjects/neuringoBe/backend/docs/roleplay-completion-integration.md:1>)
- [회귀 실행의 빈 관찰 문맥 공급](</Users/jinmina/IdeaProjects/neuringoBe/backend/src/test/java/com/neuringo/neuringobe/ai/eval/RoleplayEvalRunner.java:111>)
- [실제 진단 입력](</Users/jinmina/IdeaProjects/neuringoBe/backend/build/eval/elice-p1-analysis-review-01/request.json:1>)
