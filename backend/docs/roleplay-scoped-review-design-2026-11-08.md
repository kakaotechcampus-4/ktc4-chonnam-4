# 검수 대상과 목표 범위를 구조에 고정하는 진단 설계

작성일: 2026-11-08\
상태: 승인 범위의 테스트용 구현·관련 검사·실제 두 사례 진단 완료. 두 사례 모두 사전 기대값과 일치.

## 확인한 문제

후보 입력 유무 비교에서는 두 조건 모두 현재 사건 관찰 1개에 대해 3개의 리뷰를 반환했다.
현재 사건 목표의 GOAL_ACHIEVED도 감정·이유 목표의 근거가 없다는 이유로 거절했다.
현재 스키마는 관찰 리뷰를 일반 배열로 정의하고 목표 ID·기준 참조도 일반 문자열로 받는다.
따라서 승인 기준에 있는 다른 목표를 추가해도 JSON 구조는 유효하고 서버의 집합 검사에서 뒤늦게 거절된다.

이번에는 검수 대상의 개수·연결을 요청별 스키마에 고정하고, 현재 목표와 활동 전체의 판정을 별도 구조로 전달한다.
의미를 정확하게 판단하리라는 보장은 없으므로 운영 재구현 전에 테스트용 진단으로 확인한다.

## 1. 관찰 배열을 고정 키 객체로 변경

서버가 이번 분석의 관찰마다 o0, o1 등의 키를 부여한다. 키는 식별자이며 진행 순서나 선행 달성 조건이 아니다.
기존 A는 o0(사건)만, 기존 B는 o0(사건)와 o1(잘못 추가한 감정 관찰)을 가진다.
각 키와 원본 (turn_id, micro_goal_id, criterion_reference, status)의 연결을 로컬 원본과 요청에 보존한다.

모델 출력의 observation_reviews는 배열 대신 다음과 같은 객체다.

```json
{
  "observation_reviews": {
    "o0": {
      "evidence_quote": "아동 발화에서 복사한 구절",
      "verdict": "SUPPORTED",
      "reason_code": "MATCHED_EXPRESSION",
      "explanation": "검수 사유"
    }
  }
}
```

이는 형식 예시이며 실제 요청에 정답 값이나 예시 인용을 넣지 않는다.
동적 JSON Schema의 properties에는 그 요청의 키만 넣고 전부 required로 지정하며 additionalProperties=false로 한다.
A의 모델은 감정·연결에 해당하는 새 리뷰 키를 만들 수 없고 B는 o1을 생략할 수 없다.
출력에서 목표 ID·기준 참조를 다시 생성시키지 않는다. 서버가 고정 키로 원본 참조를 복원한다.
그 뒤 기존 ObservationReviewValidator를 적용해 인용 출처·분류/사유·참조 집합을 확인한다.
서버는 리뷰 항목을 삭제·추가하거나 verdict를 고치지 않는다. 잘못된 키·누락은 그대로 실패다.

배열 길이나 복잡한 조건부 스키마 대신 기본 object/properties/required/additionalProperties와 enum을 사용한다.
OpenAI 문서는 이 필수 필드·추가 키 금지 방식의 구조 제약을 설명한다.
다만 Elice 호환 API에서 이번 동적 스키마가 수용되는지는 승인된 실제 진단에서 확인한다. 별도 호환성 호출은 추가하지 않는다.

## 2. 분석 입력을 범위별로 재구성

기존 고정 입력의 분석 정보를 다음 객체로 옮긴다. 값을 바꾸거나 확정되지 않은 이력을 채우지 않는다.

| 입력 객체 | 원본에서 옮길 값 | 검수 범위 |
| --- | --- | --- |
| observation_tasks | 현재 분석 관찰과 해당 승인 기준·목표 설명을 o0/o1로 연결 | 이번에 제안한 관찰만 검수 |
| current_goal_assessment | state.current_micro_goal_id의 승인 설명·required_evidence, 분석 learning_state | 이번 질문 초점의 학습 상태 |
| activity_assessment | 승인 scenario.learning_goal, 분석 activity_goal의 decision·basis | 활동 전체의 판정 |
| interpretation_strategy | 분석 response_act·relevance·primary_gap·confidence·next_strategy 및 서버 지원 보정(있을 때) | 발화 해석과 다음 전략/지원의 논리 |

기존 최상위 analysis_result를 그대로 중복 추가하지 않고 위 객체로 변환한다.
시나리오 사실·금지 추론·승인 기준·사전 상태·발화·최근 대화·확정 관찰·지원 정책/이력 등 나머지 맥락은 유지한다.
시나리오에 남는 승인 목록은 전체 기준이고 observation_tasks만 이번 리뷰 대상이라는 구조를 사용한다.
후보 대사는 넣지 않는다. 후보 검수·재생성·최종 전달 판정도 요구하지 않는다.

예를 들어 A는 current_goal_assessment가 사건/GOAL_ACHIEVED,
activity_assessment가 ‘친구의 감정을 이해하고 공감하기’/IN_PROGRESS다.
이 두 값을 다른 이름과 대상 정보가 붙은 독립 객체로 전달한다.
현재 질문 목표를 다음 질문의 next_strategy.target_micro_goal_id로 바꾸지 않는다.
오프라인 검사에서는 변환 전후 의미 필드의 완전한 대응을 확인하고, 빠진 분석 필드가 있으면 호출 전에 중단한다.

## 3. 출력도 현재 목표·활동·나머지 논리로 구분

observation_reviews 외 출력 필드:

- analysis_id: 요청 분석 ID 하나만 enum으로 허용한다.
- current_goal_review: reviewed_micro_goal_id, reviewed_learning_state, verdict, reason_code, explanation.
  두 reviewed 값은 각각 이번 현재 목표 ID와 분석 학습 상태 하나만 enum으로 허용한다.
  사유는 LOGIC_GROUNDED / LEARNING_STATE_MISMATCH / INSUFFICIENT_CONTEXT만 허용한다.
- activity_review: 기존 활동 검수와 같은 reviewed_decision·verdict·reason_code·evidence_references·explanation.
  reviewed_decision은 이번 분석의 활동 판정 하나로 제한한다. 나머지 규칙은 기존 AnalysisReview.activity를 재사용한다.
- interpretation_strategy_review: verdict·reason_code·explanation.
  기존 분석 논리 사유 중 LEARNING_STATE_MISMATCH를 제외해 현재 목표의 상태 검수와 구분한다.

current_goal_review의 verdict/사유는 기존 AnalysisReview.logic 규칙으로 검증한다.
서버가 reviewed 대상의 일치와 허용 사유를 확인한 뒤 나머지 해석/전략 검수에도 기존 논리 검증기를 적용한다.
정보 부족은 판단보류로 보존하며, 표현한 목표 외의 감정·이유가 없다는 사실을 현재 사건 목표의 실패로 보정하지 않는다.

모든 verdict는 SUPPORTED/UNSUPPORTED/INSUFFICIENT_CONTEXT 선택이 가능하다.
서버가 목표 도달을 자동 계산하거나 모델 승인 값을 true로 고정하지 않는다.
정확한 대상 ID를 반환하더라도 설명이 다른 목표를 근거로 삼을 수 있으므로, 그 의미적 타당성은 사람이 결과를 읽어 확인한다.
새 구조가 잘못된 의미 판단까지 구조적으로 불가능하게 만드는 설계라고 주장하지 않는다.

## 4. 기존 확정 정책과 유지 범위

- 현재 질문 목표의 GOAL_ACHIEVED와 활동 전체 ACHIEVED를 분리한다.
- 활동 목표 달성이 정상 완료 기준이다. 모든 작은 목표나 정해진 순서의 통과를 새 조건으로 추가하지 않는다.
- 관찰 분류 승인만으로 아동 정답·독립 도달·활동 완료를 확정하지 않는다.
- 현재/이전 발화와 당시 지원·정답 제공 이력을 보존한다. 이번 후보를 사전 이력으로 사용하지 않는다.
- 운영 wire v3·평가 v6, 정상 3회 호출·기존 재시도·60초 예산·저장 전 검증은 이번에 바꾸지 않는다.
- 이 진단 결과를 운영 PASS/스탬프/DB 저장으로 연결하지 않는다.

## 5. 검증·실제 호출 계획

테스트용 빌더·동적 스키마·어댑터·실행 작업을 추가한다.
버전은 roleplay-scoped-analysis-diagnostic/v1, 스키마 scoped-analysis-diagnostic/v1이다.
기존 고정 A/B를 사용하며 입력 재구성·출력 제약이 동시에 달라지는 새 설계 검증이다.
이전 후보 유무 실험과 비교해 특정 필드 하나의 인과 효과를 주장하지 않는다.

오프라인 관련 검증:

- 원본 분석/상태/승인 기준의 변환 대응과 현재 목표·다음 목표·활동 목표 구분.
- A의 키가 o0 하나, B의 키가 o0/o1이고 추가·누락·다른 ID·잘못된 상태가 스키마/연결 검사에 실패함.
- 키로 복원한 관찰에 기존 검증기를 적용해 허구 인용·사유 충돌을 그대로 차단함.
- 승인·거절·판단보류를 구분하고 설명을 검사 통과용 정답으로 바꾸지 않음.
- 실제 호출 수 상한·공유 예산·중단 조건·결과 보존을 확인함.

운영 코드가 바뀌지 않으면 관련 테스트와 spotlessCheck만 수행하고 전체 check는 반복하지 않는다.
실제 Elice gpt-5.4-nano로 A → B 각각 1회, 최대 2회, 공유 60초, SDK/진단 재시도 0회다.
기존 모델·timeout·출력 토큰 상한을 바꾸지 않는다.
제공자·시간·스키마 실패 시 중단하고 의미/근거 실패나 거절·판단보류는 기록 후 두 조건을 완료한다.
실패를 고치려고 추가 호출하거나 새 키를 무시/추측해 수용하지 않는다.

사전 기대값:

| 항목 | A | B |
| --- | --- | --- |
| 사건 관찰 | SUPPORTED·실제 발화 인용 | 동일 |
| 추가 감정 관찰 | 검수 대상 아님·리뷰 키 없음 | UNSUPPORTED |
| 현재 사건 목표 상태 | GOAL_ACHIEVED 판정 승인 | 동일 |
| 활동 IN_PROGRESS | 판정 승인 | 동일, activity basis는 사건으로 고정 |
| 발화 해석/다음 전략 | 승인 | 동일, 관찰 자체의 오류는 관찰 리뷰에서 구분 |

정답은 모델 요청에 넣지 않고 사람의 결과 판독 기준으로만 쓴다.
B의 전체 분석은 감정 관찰 거절 때문에 승인될 수 없으며 다른 항목 승인을 전체 통과로 해석하지 않는다.

결과는 build/eval/elice-p1-scoped-review-01에 새로 저장한다. 기존 폴더가 있으면 호출 전에 중단한다.
변환 원본·키 매핑·요청·프롬프트·스키마·응답·검증 결과·시간/토큰·버전/해시를 보존한다.
동적 스키마 수용, 정확한 키 집합, 근거 검증, 목표 범위별 의미 판단을 각각 보고한다.
단건 두 사례로 운영 안정성·p95·전체 턴 성공률을 판단하지 않는다.

## 승인 요청 범위

테스트용 구조 변경·관련 오프라인 검사·위 실제 호출 최대 2회까지다.
운영 코드·프론트엔드·DB·완료 정책·모델 교체·추가 호출·commit/push는 포함하지 않는다.
실제로 목표 범위와 근거를 올바르게 구분하는지 확인한 뒤 운영 연결 여부를 별도 설계한다.

## 근거

- 기존 실패: build/eval/elice-p1-review-isolation-01 및 elice-p1-integrated-evaluation-v3-01
- 현재 서버 검증: ObservationReviewValidator, AnalysisReview, RoleplayOutputParsers, RoleplaySupportDecisionRules
- [OpenAI Structured Outputs의 필수 필드와 추가 키 제한](https://developers.openai.com/api/docs/guides/structured-outputs)
- [Spring AI의 제공자 네이티브 스키마 제약과 모델별 지원 확인](https://docs.spring.io/spring-ai/reference/api/structured-output/native.html)

공식 문서는 구조 제약의 근거다. 현재 프로젝트의 Spring AI 버전/호출 방식을 바꾸거나 Elice의 새 스키마 호환성을 확인한 결과는 아니다.

## 구현 및 오프라인 검증

테스트용 ScopedAnalysisValidation, ScopedReviewDiagnostic 및 scopedReviewDiagnostic 작업을 추가했다.
새 입력에서 원본 분석 전체를 복원해 원본과 비교하며, 나머지 맥락은 후보/분석 필드 외에 바뀌지 않는지 검사했다.
원본 관찰 키를 목표/기준 참조로 복원한 뒤 기존 관찰·활동·논리 검증기를 사용한다.
서버가 잘못된 키를 삭제하거나 정답 verdict로 교체하지 않는다.

관련 73개 검사와 spotlessCheck가 실패 없이 통과했다.
추가/누락 키, 다른 분석·목표 ID, 다른 학습/활동 상태, 범위를 벗어난 사유,
허구 인용·참조·사유 충돌, 거절/판단보류, 최대 호출 수와 공유 예산을 확인했다.
운영 코드를 바꾸지 않아 이전 전체 check를 반복하지 않았다.

## 실제 Elice 결과

실행: `AI_MODEL=gpt-5.4-nano ./gradlew --no-daemon spotlessCheck scopedReviewDiagnostic`

요청 2회, 재시도 0회, 중도 중단 없음. 요청 모델명은 gpt-5.4-nano,
반환 버전은 기존과 같은 gpt-5.4-nano-2026-03-17이다.
실제 두 요청에서 새 동적 스키마를 수용했고 두 응답 모두 해당 스키마와 서버 근거 검증을 통과했다.

| 항목 | A | B |
| --- | --- | --- |
| 진단 시간 | 6.880초 | 4.465초 |
| 제공자 호출 시간 | 6.854초 | 4.459초 |
| 입력/출력 토큰 | 3,620 / 427 | 3,863 / 466 |
| 요청/스키마/응답 관찰 키 | 모두 o0 | 모두 o0, o1 |
| 사건 관찰 | SUPPORTED, 실제 아동 발화 인용 | 동일 |
| 잘못된 감정 관찰 | 대상 아님·추가되지 않음 | UNSUPPORTED, NO_MATCHING_EXPRESSION, null 인용 |
| 현재 사건 목표의 GOAL_ACHIEVED | 판정 승인 | 판정 승인 |
| 활동 전체의 IN_PROGRESS | 판정 승인 | 판정 승인 |
| 발화 해석/다음 전략 | 승인 | 승인 |
| 전체 관찰 승인 | true | false |
| 진단 분류 | VALID_REVIEW | VALID_REVIEW |

합계 11.345초, 입력/출력 7,483/893토큰이다. 전체 역할극 처리 시간이나 실제 금액이 아니다.
두 사례 모두 사전 기대값 전체와 일치했다. B의 VALID_REVIEW는 올바르게 감정 관찰을 거절한 검수가 유효하다는 뜻이다.
잘못된 분석 B를 전체 승인했거나 후보를 전달했다는 뜻이 아니다.

짧은 설명에서도 현재 사건 근거의 충족과 활동 전체의 추가 학습 필요를 구분했다.
A는 사건을 알아차렸어도 감정 이해·공감은 아직이므로 활동 IN_PROGRESS가 타당하다고 설명했다.
B는 같은 사건을 승인하고 발화에 없는 감정만 거절했으며, 현재 사건 목표의 달성은 유지했다.
이번 두 응답에서는 이전 추가 리뷰와 현재/전체 목표 혼동이 재현되지 않았다.

## 적용 한계와 다음 범위

이는 분석 전용 단건 A/B 진단의 성공 근거다. 운영 wire v3·평가 v6는 이 변경을 적용하지 않은 상태다.
후보 대사·후보 checks·최종 decision을 같은 호출에 다시 넣었을 때도 유지되는지는 검증하지 않았다.
또한 다중 기준, 다른 학습 상태, 과거 관찰/도움 이력, 음성·저장·전체 재시도 경로의 실제 모델 결과는 미검증이다.

입력 구조·출력 제약·프롬프트를 함께 바꿨으므로 어떤 요소 하나의 효과나 일반 정확도 향상을 확정할 수 없다.
이전과 실험 작업/사례 구성이 달라 지연·토큰 개선이나 p95를 주장하지 않는다.
서버의 범위 제약이 의미 판단 전체를 보장하지 않는 한계도 그대로다.

다음은 이 근거를 운영 평가에 연결할 방식과 추가 호출 여부를 별도 설계하는 단계다.
이번 범위에서 운영 코드·프론트엔드·DB·완료 정책·모델 변경·추가 호출·commit/push는 수행하지 않았다.
모든 원본·매핑·요청·응답·해시는 build/eval/elice-p1-scoped-review-01에 보존했다.
