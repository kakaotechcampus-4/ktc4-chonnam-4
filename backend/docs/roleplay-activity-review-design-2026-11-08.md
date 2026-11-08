# 활동 판정 근거 검수와 전체 분석 승인 계산 설계

작성일: 2026-11-08\
상태: 승인 범위 구현·오프라인 전체 검증·실 API 진단 완료. 실제 평가 문제는 미해결.

## 확인된 문제와 해결 목표

elice-p1-integrated-evaluation-01의 A는 사건 관찰을 승인했지만
activity_goal_supported=false와 checks.analysis_supported=true를 동시에 반환했다.
JSON 스키마는 통과했고 서버의 의미 일관성 검사에서 거절됐다.
활동을 왜 거절했는지는 Boolean만으로 확인할 수 없다.

이번 변경은 두 가지를 함께 해결한다.

1. 활동 판정에 대한 검수 대상·판정·사유·근거 참조를 받아 활동 미달성과 판정 거절을 구분한다.
2. 모델이 전체 분석을 다시 요약 승인하지 않도록 하고, 서버가 세 검수 결과의 논리곱을 계산한다.

중복 값만 제거해 잘못된 REANALYZE를 받아들이는 것을 해결 완료로 보지 않는다.
모델의 의미 판단 정확성은 별도로 측정한다. 활동 판정을 서버가 자동 승인하는 변경도 아니다.

## 유지할 확정 사항

- 활동 목표 달성이 정상 완료 기준이다. 작은 목표의 고정 순서나 모든 작은 목표 달성을 새 필수 조건으로 만들지 않는다.
- 관찰 분류 승인, 현재 질문 목표의 GOAL_ACHIEVED, 활동 전체의 ACHIEVED는 서로 다른 정보다.
- 활동 판정 ACHIEVED의 기존 참조·긍정 근거 검사와 저장 전 검사, 독립 검수는 유지한다.
- 현재/이전 관찰과 당시 지원·정답 제공 이력을 구분한다. 없는 이력을 채우지 않는다.
- 안전·입력 확인 우선순위, 분석·생성·평가 상한과 공유 60초 예산은 유지한다.
- 정상 경로는 분석→생성→평가 세 호출이다. 새 LLM 단계를 추가하지 않는다.

## 제공자 출력 v3

제공자 스키마는 evaluation-output/v3, 안내자·캐릭터 평가 프롬프트는 각각 v6로 변경한다.
내부 EvaluationResult와 GoalObservationOutput.Review(evaluation-result/v2), 공개 API·DB는 유지한다.

| 기존 모델 출력 | 변경 |
| --- | --- |
| observation_reviews | 유지. 현재 관찰의 발화 근거·분류만 검수 |
| goal_observation_review.activity_goal_supported | 제거하고 같은 위치에 activity_review 객체 추가 |
| checks.analysis_supported | 제거. 전체 승인 값은 서버가 계산 |
| 없음 | 최상위 analysis_logic_review 추가. 관찰·활동 판정을 제외한 분석의 논리 검수 |
| failure_codes의 분석 실패 코드 | 모델 대신 서버가 아래 검수 결과에서 생성 |
| decision·나머지 checks·후보 수정 지시 | 유지. 서버 계산과 충돌하면 거절하며 임의 보정하지 않음 |

goal_observation_review.analysis_id는 계속 요청 분석 ID와 일치해야 한다.

### 활동 판정 검수

activity_review의 필수 필드:

- reviewed_decision: ACHIEVED / IN_PROGRESS / UNAVAILABLE. 요청 분석의 활동 판정과 정확히 일치해야 한다.
- verdict: SUPPORTED / UNSUPPORTED / INSUFFICIENT_CONTEXT.
- reason_code: 아래 조합만 허용한다.
- evidence_references: 검수에 사용한 관찰의 (turn_id, micro_goal_id, criterion_reference) 배열.
- explanation: 판단을 확인할 짧은 한글 사유, 공백 불가·200자 이하.

| verdict | reason_code | 조건 |
| --- | --- | --- |
| SUPPORTED | ASSESSMENT_GROUNDED | 입력의 해당 활동 판정이 근거에 맞음 |
| UNSUPPORTED | ACHIEVEMENT_NOT_GROUNDED | reviewed_decision=ACHIEVED일 때만 |
| UNSUPPORTED | CONTINUATION_NOT_GROUNDED | reviewed_decision=IN_PROGRESS일 때만 |
| UNSUPPORTED | UNAVAILABLE_NOT_GROUNDED | reviewed_decision=UNAVAILABLE일 때만 |
| INSUFFICIENT_CONTEXT | INSUFFICIENT_CONTEXT | 검수할 정보 부족. 승인/거절로 바꾸지 않음 |

참조는 이번 분석 관찰 또는 이미 확정된 관찰에 존재해야 하며, 승인 목표·기준과 일치하고 중복이 없어야 한다.
ACHIEVED를 승인할 때는 분석의 activity_goal.basis에 있는 긍정 관찰 참조를 하나 이상 검수 근거로 포함해야 한다.
이는 기존 최소 근거 조건의 검수 연결이며, 긍정 관찰 한 개로 활동 달성을 자동 승인하는 규칙이 아니다.
IN_PROGRESS/UNAVAILABLE에는 긍정 인용·참조를 무조건 요구하지 않는다. 설명과 해당 입력 맥락으로 검수한다.
활동을 달성하지 않았다는 이유만으로 IN_PROGRESS 판정의 검수를 거절하지 않는다.

### 나머지 분석 논리 검수

analysis_logic_review는 verdict, reason_code, explanation(공백 불가·한글 200자 이하)을 가진다.
기존 analysis_supported가 검사하던 response_act·relevance·learning_state·primary_gap·next_strategy의
발화 해석과 상호 일관성, 지원 이력에 맞는 전략을 계속 검사한다.
관찰 분류·활동 판정의 승인 여부는 여기서 중복 요약하지 않고 각각의 전용 검수에 맡긴다.

- SUPPORTED: LOGIC_GROUNDED.
- UNSUPPORTED: RESPONSE_ACT_MISMATCH / RELEVANCE_MISMATCH / LEARNING_STATE_MISMATCH /
  PRIMARY_GAP_MISMATCH / STATE_STRATEGY_MISMATCH / SUPPORT_LEVEL_MISMATCH 중 주된 원인 하나.
- INSUFFICIENT_CONTEXT: INSUFFICIENT_CONTEXT.

이 항목을 후보 문장의 품질로 거절하지 않는다. 분석과 후보의 연결은 기존 analysis_response_consistent 등에서 별도로 검사한다.
새 reason_code는 검수 객체 내부의 닫힌 값이며 기존 failure_codes에 그대로 넣는 새 오류 코드가 아니다.

## 서버 계산과 분기

기본 스키마·ID 연결, 각 검수의 필드·사유·참조 조합을 먼저 검증한다.
검수 통과 후에만 다음 값을 만든다.

```text
observations_supported = 기존 관찰 검증기의 결과
activity_goal_supported = activity_review.verdict가 SUPPORTED
analysis_logic_supported = analysis_logic_review.verdict가 SUPPORTED
analysis_supported = 위 세 값이 모두 true
```

활동·분석 논리 검수의 INSUFFICIENT_CONTEXT를 false로 변환하지 않는다.
기존 INDETERMINATE_OUTPUT으로 같은 분석·후보를 재평가하며 판단보류 집계와 상한을 유지한다.
관찰 배열 내부의 명시적 거절/판단보류 혼합 처리 규칙은 기존 구현을 유지한다.

일반 경로에서 analysis_supported=false이면 서버가 UNSUPPORTED_CAUSE_ANALYSIS를 결과 코드에 추가한다.
논리 검수의 주된 사유가 STATE_STRATEGY_MISMATCH이면 해당 기존 실패 코드도 추가한다.
이 두 분석 코드는 모델의 failure_codes 허용 목록에서 제외해 두 곳에서 모순되게 출력하지 않도록 한다.
나머지 후보·안전 코드의 허용 목록과 checks 간 검증은 유지하며, 최종 내부 허용 코드는 기존 목록 그대로다.

계산된 분석 거절이면 유효한 REANALYZE여야 한다. PASS/후보만 재생성은 거절한다.
반대로 세 분석 검수가 모두 승인됐다면 일반 경로의 REANALYZE도 모순으로 거절한다.
다른 checks가 실패하면 후보 재생성 등의 기존 유효 분기를 요구한다.
서버는 모델의 decision을 PASS로 고치거나 활동 판정을 ACHIEVED로 고치지 않는다.
생성 재요청에 필요한 revision_instruction도 기존 조건대로 필수다.

유효한 SAFETY_ESCALATION/CONFIRM_INPUT은 학습 검수보다 우선한다.
이때 activity_review와 analysis_logic_review는 null을 허용하고 observation_reviews=[]도 허용한다.
일반 경로에서는 두 객체의 null을 허용하지 않는다. 스키마·ID·허용 코드 검사는 우선 경로에도 적용한다.
내부 관찰/활동 승인은 false로 두고 학습 완료·정상 전달에 사용하지 않는다.

## 개인정보·변경 범위

참조·설명은 평가 검증에만 사용하고 내부 EvaluationResult로 변환할 때 버린다.
자유 서술 사유·인용을 DB·공개 DTO·일반 로그·retry에 추가하지 않는다.
재평가에는 고정 코드·고정 한글 수정 조건만 전달한다.
기존 오류 피드백에서 제거된 checks.analysis_supported 등을 요구하는 지시도 함께 수정한다.

구현은 평가 스키마/DTO·검증/변환·평가 프롬프트·관련 고정 피드백과 테스트/진단 fixture로 한정한다.
분석/생성 출력 계약·오케스트레이터·완료 정책·DB·프론트엔드·모델 선택은 바꾸지 않는다.
실제 v2 실패 원문은 보존하고 v3 정답 사례로 몰래 바꾸지 않는다.

## 검증과 승인 요청 범위

관련 검사 후 전체 check를 한 번 수행한다. 새 실패나 새 코드 수정이 없다면 전체 검사를 반복하지 않는다.

필수 검증:

- IN_PROGRESS를 근거 있게 승인해도 내부 activity_goal_supported=true이며 활동 ACHIEVED로 바뀌지 않음.
- 활동 거절·논리 승인 조합에서는 전체 분석 승인이 false로 계산되고 REANALYZE/실패 코드로 연결됨.
  이 조합의 수용은 의미 판단이 정답이라는 증거가 아니므로 PASS 성공으로 집계하지 않음.
- 거절 사유와 reviewed_decision 불일치·다른 분석 ID·허구/중복 참조·의미 체크 모순을 차단함.
- 관찰만 승인돼도 활동·논리 검수가 거절/판단보류면 PASS하지 않음.
- 기존 분석 해석·학습 상태·전략·지원 검사와 후보·안전·입력 확인 우선순위를 유지함.
- 같은 후보 재평가 상한·공유 예산·판단보류와 형식 실패의 분리·구형 산출물 읽기를 유지함.

오프라인 검증을 통과한 뒤 별도 모델/입력 변경 없이 같은 고정 A/B를 최대 두 번 실제 평가한다.
공유 60초, SDK/진단 재시도 0회, 첫 기술/출력 실패 시 중단한다.
결과 폴더는 build/eval/elice-p1-integrated-evaluation-v3-01이며 기존 결과를 덮어쓰지 않는다.
관찰 기대값은 A 사건 승인, B 사건 승인·감정 거절이다.
활동·논리·후보 검수의 사유와 서버 수용을 별도로 보고하고 B 미실행은 미검증으로 표시한다.
P1의 사건 관찰만으로 활동 목표 ‘친구의 감정을 이해하고 공감하기’의 달성을 자동 확정하지 않는다.
전체 턴 재시도·음성·저장 성공률을 측정한 실험으로 확대 해석하지 않는다.

이번 승인 요청은 위 백엔드 구현·오프라인 검증·조건부 실제 평가 최대 2회까지다.
실패해도 운영 코드를 자동 추가 수정하거나 API를 더 호출하지 않는다. commit/push는 포함하지 않는다.

## 근거

- 실제 출력: build/eval/elice-p1-integrated-evaluation-01/A/result.json
- 현재 평가 변환: src/main/java/com/neuringo/neuringobe/ai/application/prompt/EvaluationOutput.java
- 현재 게이트: src/main/java/com/neuringo/neuringobe/ai/application/prompt/RoleplayOutputParsers.java
- 관찰/활동 내부 계약: src/main/java/com/neuringo/neuringobe/ai/application/structured/output/GoalObservationOutput.java
- 확정 완료·관찰 원칙: docs/roleplay-goal-observation-design.md의 적용 기준과 2026-10-30 달성 근거 검증
- 확정 기획: 개발 명세의 06_착수기준_완료기준_결정사항.md, DEC-053 및 전체 완료 조건의 임의 추가 금지

## 구현 및 검증 결과

활동/분석 논리 검수 DTO·사유/참조 검증과 서버의 전체 분석 승인·분석 실패 코드 계산을 연결했다.
평가 wire v3, 안내자·캐릭터 평가 프롬프트 v6를 적용하고, 제거된 필드를 요구하던 재평가 피드백을 갱신했다.
기본 구조와 각 검수의 사유·참조 조합을 검증한 뒤 승인/거절/판단보류를 처리한다.
관련 합성 제공자와 진단 테스트는 현재 계약으로 갱신했고 이전 실제 결과 파일은 보존했다.
정상 경로의 호출 수·오케스트레이터·활동 완료 정책·내부 결과·공개 API·DB·프론트엔드는 바꾸지 않았다.

- 관련 검사 258개: 실패·오류·건너뜀 0개.
- 전체 `./gradlew --no-daemon check`: 4분 43초에 성공.
- 전체 164개 스위트, 총 2,479개: 통과 2,473개, 실패·오류 0개, opt-in 건너뜀 6개.
- 제외 항목은 실제 ML API 1개와 실제 ffmpeg 검사 5개다.
- 코드 형식·차이 검사와 JaCoCo 보고서 생성 통과.

### 실제 Elice 진단

실행: `AI_MODEL=gpt-5.4-nano ./gradlew --no-daemon integratedEvaluationDiagnostic`

결과 폴더는 build/eval/elice-p1-integrated-evaluation-v3-01이다.
A 1회 뒤 INVALID_OUTPUT_FORMAT으로 중단했고 B는 호출하지 않았다. 재시도·추가 호출은 없다.
모델 반환 버전은 기존과 같은 gpt-5.4-nano-2026-03-17이다.
v2 진단과 v3 진단의 A user 입력은 바이트 단위로 동일하며, 프롬프트와 출력 계약이 변경됐다.

| 항목 | v2의 A | v3의 A |
| --- | --- | --- |
| 진단 시간 | 4.384초 | 7.833초 |
| 제공자 호출 시간 | 4.357초 | 7.810초 |
| 입력/출력 토큰 | 6,279 / 329 | 7,254 / 786 |
| JSON 스키마 | 통과 | 통과 |
| 서버 수용 | 실패 | 실패 |
| 첫 거절 사유 | 활동 거절과 분석 승인 모순 | 아동 발화에 없는 관찰 인용 |

각각 단건이고 프롬프트와 출력 구조를 함께 바꿨으므로, 지연·토큰 차이를 특정 요소의 인과 효과로 단정하지 않는다.
이번 작업을 속도나 성공률 개선으로 보고하지 않는다.

v3 모델 응답의 구체적인 문제:

1. A의 현재 관찰은 사건 하나인데 observation_reviews에 사건·감정·연결 세 항목을 반환했다.
   승인된 모든 목표가 이번 검수 대상인 것은 아니다. 추가 두 항목도 기존 관찰 집합 검증에 맞지 않는다.
2. 사건 인용 ‘응… 발표하다가 멈췄어.’와 감정 인용 ‘그때 좀 멍해졌어.’는
   canonical_utterance에 없고 candidate_response.text에 있는 구절이다. 서버는 첫 인용에서 차단했다.
3. analysis_logic_review는 RESPONSE_ACT_MISMATCH로 거절했지만 설명은 후보가 앉았다는 표현을 하지 않았다는 내용이다.
   발화 해석·분석 논리 검수에 후보 문장 판단이 섞인 출력이다.
4. activity_review는 IN_PROGRESS를 CONTINUATION_NOT_GROUNDED로 거절하며
   ‘현재 목표의 필수 근거가 후보에서 충분히 드러나지 않는다’고 설명했다.
   활동 판정 근거의 검수에도 후보 문장 기준이 들어갔다.

이는 모델이 반환한 인용·짧은 검수 설명에서 확인되는 혼동이다. 모델 내부 사고 과정을 관측한 결과는 아니다.
이번 출력에는 이전 중복 Boolean 모순을 표현할 필드가 없지만, 그 제거로 올바른 의미 판단까지 확보되지는 않았다.
서버가 잘못된 근거를 차단한 것은 확인했고, 전체 평가의 정상 수용은 실행한 A에서 0/1이다.

진단의 observation_verdicts_match=true는 기대한 사건 항목의 verdict만 일치했다는 뜻이다.
이 점수는 추가 리뷰와 인용 출처를 평가하지 않으므로 관찰 검수 통과로 해석하면 안 된다.
출력 스키마 통과·기대 verdict 일치·근거/참조 검증 통과·전체 서버 수용은 각각 구분한다.
B 미실행이므로 A/B 전체 구분 능력은 미검증이다.

추가 실험이나 운영 코드 재수정·commit/push는 수행하지 않았다.
다음 설계에서는 후보를 검수하는 작업과 아동 발화/분석 근거를 검수하는 작업의 입력 경계를 검토해야 한다.
현재 결과만으로 추가 프롬프트·모델 교체·호출 분리가 해결책이라고 확정하지 않는다.
