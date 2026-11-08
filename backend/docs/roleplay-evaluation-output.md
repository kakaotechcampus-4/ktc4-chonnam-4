# 역할극 평가 출력 계약

평가 호출은 `response_format=json_schema`, `strict=true`로 출력 구조를 제한한다.
모델은 검수 판단과 근거 코드를 반환하고, 서버는 처리 경로와 전달 조건을 결정한다.

현재 운영 스키마는 v5다. 관찰별 원본 status에 따라 verdict·reason_code·evidence_quote의 허용 조합을 네이티브 스키마에 표현한다.
프롬프트는 v7, 내부 evaluation-result/v2는 유지한다. 인용의 실제 출처와 설명·참조 검증도 유지한다.
관련 검사 340개가 통과했고, gpt-6-luna/low 반례 재평가 두 건 모두 의도한 이유로 유효하게 거절됐다.
잘못된 감정 관찰은 NO_MATCHING_EXPRESSION/null로 REANALYZE, 금지 의도 후보는 REGENERATE로 수용됐다.
이는 두 고정 반례의 단발 결과이며 일반적인 성공률·전체 서비스 검증을 뜻하지 않는다.
과거 분석 전용 진단은 기존 schema() 계약을 유지하고 운영 평가만 evaluationSchema()의 제약을 사용한다.
공식 develop d6f7e06 동기화 후 최종 build 성공: 2,855개 중 2,849개 통과, opt-in 제외 6개, 실패 0개.
요청별 기술 관측과 dev 한정 원문 진단을 구현했다. 실제 AWS 서버 입력 관측은 미수행이다.
서버 실행 절차는 [Dev 서버 관측 runbook](roleplay-dev-observation-runbook-2026-11-08.md)에 있다.
상세 결과: build/eval/negative-evaluation-gpt-6-luna-18232480630842464421/summary.md.

## 이전 실험 기록

직전 v4 운영 연결과 전체 오프라인 검증은 통과했다(총 2,517개, 통과 2,511개, opt-in 제외 6개).
실제 통합 A/B는 모두 서버에서 거절됐다. A는 필수 인용 누락, B는 활동 근거 참조 오류와 후보 대사 인용이 확인됐다.
정상 평가 수용은 0/2이며 실제 문제는 아직 해결되지 않았다.
상세 결과는 roleplay-scoped-review-integration-design-2026-11-08.md와 build/eval/elice-p1-scoped-integrated-01/summary.md에 있다.

후속 테스트 전용 근거 선택 진단은 기계적 검증 2/2를 통과했지만, B에서 후보 대사를 아동의 감정 근거로 혼동했다.
원문 전체 참조를 연결하면 이 혼동을 기존 인용 검사로 검출하지 못해 운영 계약으로 채택하지 않았다.
운영 v4는 유지하며 상세 결과는 roleplay-evidence-selection-design-2026-11-08.md에 기록했다.

이어진 테스트 전용 호출 분리 진단(elice-p1-split-review-01)에서는 후보 없는 분석 검수가 A/B를 기대대로 구별했다.
별도 A 후보 검수는 REGENERATE였으며 캐릭터/타인 감정 혼동과 코드/check 모순이 남았다.
분석 전용 성공을 전체 서비스 해결로 해석하지 않으며 운영에는 미적용이다.
조건·52개 관련 검사·실제 3회 결과는 roleplay-split-review-design-2026-11-08.md에 기록했다.

후속 테스트 전용 발화자·코드 정합성 진단(elice-p1-candidate-role-01)은 과거 코드/check 모순을 검출했다.
실제 두 후보는 새 서버 검증을 통과했지만 역할 기대값은 A만 일치했다.
C는 제3자 역할 이탈을 놓쳤고 물음표 없는 대사에 MULTIPLE_QUESTIONS를 출력했다.
코드와 check가 함께 잘못되는 의미 오류는 남았으며 운영 미적용이다.
73개 관련 검사와 상세 결과는 roleplay-candidate-role-consistency-design-2026-11-08.md에 기록했다.

이전 v3의 오프라인 검증은 통과했다. 실제 모델 진단에서는 A가 후보 문장을 아동 발화 근거로 인용해
서버가 거절했고 B는 미실행이다. 구조화 출력 적용을 실제 역할극 문제 해결로 간주하지 않는다.
상세 기록은 roleplay-activity-review-design-2026-11-08.md와 build/eval/elice-p1-integrated-evaluation-v3-01/summary.md에 있다.

후속 테스트용 범위 고정 진단(elice-p1-scoped-review-01)은 A/B의 사전 기대값과 일치했다.
고정 키와 현재/활동 목표 분리를 적용한 분석 전용 결과이며, 당시 운영 v3 계약에 연결한 결과는 아니었다.
조건·결과·적용 한계는 roleplay-scoped-review-design-2026-11-08.md에 기록했다.


## 모델과 서버의 책임

| 모델 출력 | 서버 처리 |
| --- | --- |
| candidate_id, analysis_id | 요청의 후보·분석에 일치하는지 검사 |
| observation_reviews | 이번 키만 허용하고 원본 목표·기준 참조를 복원해 인용·분류·사유를 검증 |
| current_goal_review | 현재 질문 목표 ID·학습 상태에 대한 검수만 허용 |
| activity_review | 활동 판정과 실제 근거 참조 검수 |
| interpretation_strategy_review | 발화 해석·격차·다음 전략·지원 논리 검수 |
| checks 11개, decision, failure_codes, revision_instruction | 후보 품질·최종 판정·코드·수정 지시의 기존 정합성 검사 |

전체 분석 승인은 관찰·현재 목표·활동·해석/전략 승인의 논리곱으로 서버가 계산한다.
모델은 checks.analysis_supported, observations_supported, activity_goal_supported와 내부 버전을 출력하지 않는다.
retry_target·safe_to_send·critical_failure_count도 서버가 결정하며 모델이 공급하면 추가 필드로 거절한다.
UNSUPPORTED_CAUSE_ANALYSIS·STATE_STRATEGY_MISMATCH는 분석 검수 결과에서 서버가 생성한다.
나머지 실패 코드 enum은 기존 RoleplayCodes.FAILURE_CODES에서 위 서버 전용 코드를 제외해 만든다.

관찰 분류의 승인은 아동의 정답·목표 달성과 다르다. NOT_EXPRESSED가 타당하면 ABSENCE_CONFIRMED·null 인용이 가능하다.
현재 관찰이 없으면 observation_reviews={}이며 관찰 검수 대상이 없다는 뜻으로 true다. 활동 달성으로 바꾸지 않는다.
유효한 안전/입력 확인에서는 학습 검수 null을 허용하고 내부 관찰/활동 승인은 false로 구성한다.
일반 경로에서는 네 학습 검수 객체가 필수다.

## 버전과 적용 범위

- 안내자·캐릭터 평가 프롬프트: roleplay-response-evaluation/v7, roleplay-response-evaluation-character/v7
- 제공자 출력 스키마: evaluation-output/v5, 요청별 동적 스키마
- 내부 목표 검수 계약: 기존 evaluation-result/v2 유지
- 내부 EvaluationResult·공개 API·DB·분석/생성 출력 계약은 유지한다.
- 정상 경로는 분석→생성→평가 3회이며 별도 검수 호출은 추가하지 않는다.

ScopedAnalysisContext는 분석 입력을 범위별로 구성하고 키와 원본 관찰을 같은 요청에 묶는다.
EvaluationOutputSchema는 그 바인딩과 후보 ID로 스키마를 만들며 제공자 제약과 수신 검증에 동일하게 사용한다.
다른 요청의 키·상태·ID를 추측 복원하거나 잘못된 관찰을 삭제해 수용하지 않는다.
새 스키마·원문·바인딩은 전역 캐시에 보관하지 않는다.

## 재시도와 예산

재시도는 기존 `RetryingRoleplayTurnExecutor`가 담당한다. SDK 재시도와 별도의 검증 Advisor 루프를 추가하지 않는다.
스키마 또는 일관성 오류에는 서버가 정한 오류 코드와 수정 조건만 다음 평가에 전달한다.
모델 원문·예외 전문은 재요청에 복사하지 않는다. 새 후보에는 이전 평가 오류를 전달하지 않는다.
기존 단계별 시도 상한과 60초 턴 예산을 유지한다.

유효한 REANALYZE는 형식 실패가 아니며 기존 재분석 경로로 연결한다.
평가 재호출 뒤 통과한 턴은 첫 평가 PASS로 집계하지 않는다.

### 평가 항목 간 일관성

스키마 검증 뒤 서버가 다음 관계를 검사한다. 값이나 판정을 자동으로 고치지 않는다.

| 조건 | 필요한 값 |
| --- | --- |
| 관찰·현재 목표·활동·해석/전략 중 하나라도 거부 | 서버가 전체 분석 거절과 UNSUPPORTED_CAUSE_ANALYSIS 계산 |
| `GOAL_SKIPPED` 코드 | `checks.goal_aligned=false` |
| 논리 검수의 STATE_STRATEGY_MISMATCH 사유 | 서버가 기존 STATE_STRATEGY_MISMATCH 코드도 추가 |
| `ANALYSIS_RESPONSE_MISMATCH` 코드 | `checks.analysis_response_consistent=false` |
| `REGENERATE` 또는 `SAFETY_REGENERATE` | 관찰·현재 목표·활동·해석/전략 모두 승인 |

`CONFIRM_INPUT`, `SAFETY_ESCALATION`은 위 학습 일관성 검사보다 우선한다.
이 경우에도 스키마·식별자·허용 코드·실패 코드 존재 등 기존 검증은 수행하며 정상 후보로 전달하지 않는다.
일반 경로에서 REANALYZE는 계산된 분석 거절과 일치해야 한다. 발화 해석·학습 상태·전략 등 기존 분석 검사는 current_goal_review와 interpretation_strategy_review에서 유지한다.
활동/논리 검수의 정보 부족은 false로 변환하지 않고 INDETERMINATE_OUTPUT으로 같은 후보를 재평가한다.

모순은 분석 오류로 확정하지 않고 잘못된 평가 출력으로 분류한다.
`REVIEW_ANALYSIS_CONFLICT`, `FAILURE_CHECK_CONFLICT`, `REGENERATION_ANALYSIS_CONFLICT` 중 해당 오류와
고정된 수정 조건을 보내 같은 분석·후보를 재평가한다. 평가 시도는 최대 3회이며 60초 턴 예산을 공유한다.
평가가 계속 모순되면 평가 단계의 `SAFE_FALLBACK/LIMIT_REACHED`로 종료한다.
일관된 REANALYZE가 도착한 경우에만 기존 재분석 경로로 넘어간다.

## 검증

승인된 합성 P1 한 번의 실제 입력·출력 진단에는 `--diagnostics`를 사용한다.
`--cases P1 --repeats 1`과 기본 회귀 세트 또는 `eval/roleplay-regression-character-p1-v1.json`에서만 허용하며 `diagnostics.jsonl`을 추가한다.
다른 케이스·임의의 사용자 지정 세트·다회 반복·음성 왕복에는 진단 모드를 사용할 수 없다.

- `EvaluationOutputSchemaTest`: 허용 코드, 필수 값, Boolean 자료형, 추가 필드, PASS 및 평가 항목 간 모순, 서버 계산, 실제 평가 3건 재생
- `StructuredRoleplayTurnStepsTest`: 실제 실패 원문 6건, 실제 모순 평가 재생, 재평가·재분석, 요청 사이 오류 격리, 시도 상한
- `SpringAiLlmProviderTest`: 실제 HTTP 요청의 JSON Schema·strict 설정, 단일 호출, 사용량 보존
- `RoleplayGoalObservationPromptTest`: 기존 목표 관찰·지원·식별자 검증 유지
- `RoleplayTurnDeadlineTest`: 기존 기한·취소 동작 유지

실 API 반복 측정 예시(실제 호출 사용량이 발생한다):

```bash
./gradlew roleplayEval --args="--provider openai --cases P1 --repeats 3 --budget 60s --run-id elice-p1-native-01"
```

네이티브 스키마는 출력 구조를 제한한다. 학습 판단의 정확성, 제공자 거절·출력 잘림까지 보장하지 않는다.
전달 성공률과 함께 검수 판단, 재시도 횟수, 기대값, 형식 실패와 지연을 확인한다.

## 참고

- [Spring AI의 네이티브 구조화 출력](https://docs.spring.io/spring-ai/reference/api/structured-output/native.html)
- [Spring AI의 스키마 검증과 재시도](https://docs.spring.io/spring-ai/reference/api/structured-output/validation.html)

## 최초 네이티브 v1 적용 당시 검증 결과

기준 브랜치는 원격 develop의 `9ffa124`에서 만든 `refactor/evaluation-output-retry`다.
다음 기록은 커밋 전 변경을 적용한 상태의 결과다.

- `./gradlew spotlessApply check`: 성공. 전체 2,338건 중 2,332건 통과, 조건부 테스트 6건 제외.
  - 실제 ML API opt-in 테스트 1건과 실제 ffmpeg 검사 opt-in 테스트 5건은 실행 조건에 따라 제외됐다.
- Elice 소규모 호환성 요청: HTTP 200, `strict=true`인 스키마의 enum·정수형·추가 필드 제한을 만족했다.
  - 결과: `elice-schema-probe-01`
- 실제 GPT-5.4 Nano P1 순차 3회: `elice-p1-native-01`

| 실행 | 시간 | 호출 수 | 평가 판단 | 최종 결과 |
| --- | ---: | ---: | --- | --- |
| 1회 | 29.698초 | 10 | REANALYZE 3회 | RECOVERY |
| 2회 | 24.567초 | 9 | REANALYZE 3회 | RECOVERY |
| 3회 | 22.308초 | 9 | REANALYZE 3회 | RECOVERY |

당시 v1 평가 9회 모두 당시 스키마와 서버 검증을 통과했다. 평가 출력 구조 오류는 0/9다.
전체 호출 중 출력 검사 실패는 생성 단계에서 질문을 두 개 만든 1건(1/28)이었다.
세 턴 모두 `INPUT_CONFIRMATION/CAUSE_ANALYSIS/LIMIT_REACHED`로 종료됐고 시간 초과는 없었다.

분석 요약의 학습 상태·전략 기대값은 6/6 일치했지만, 검수 모델이 분석·후보에
`ANALYSIS_RESPONSE_MISMATCH`, `GOAL_SKIPPED`, `STATE_STRATEGY_MISMATCH` 등의 코드를 반환했다.
따라서 정상 후보 확보는 0/3이며, 역할극 전체의 안정성이 해결됐다고 볼 수 없다.
스키마 통과와 학습 판단의 타당성은 구분해야 한다.
다음 내용 검토에는 합성 입력의 전체 분석·후보·검수 결과를 함께 비교할 진단 근거가 필요하다.

기존 `elice-p1-02`는 `e09bbbf` 기준이므로 두 실행은 동일 코드 기준의 통제 비교가 아니다.
지연이나 성공률 변화를 이번 변경 하나의 효과로 단정하지 않는다.


## 운영 관찰 근거 v2 연결

- 원본: 관찰 전용 진단에서 같은 Nano 모델이 A 사건·B 사건을 수용하고 B의 잘못된 감정 관찰을 거절한 단건 결과.
- 검증: 현재 분석의 (micro_goal_id, criterion_reference) 집합과 리뷰가 정확히 같아야 한다.
  인용이 있으면 이번 canonical_utterance의 정확한 연속 부분 문자열이어야 한다.
- EXPLICIT·IMPLICIT·MISUNDERSTOOD·CONTRADICTORY 승인에는 MATCHED_EXPRESSION과 인용이 필요하다.
  NOT_EXPRESSED 승인에는 ABSENCE_CONFIRMED를 쓰고, 이를 거절할 때는 STATUS_MISMATCH와 실제 인용이 필요하다.
- INSUFFICIENT_CONTEXT만 남으면 내부 INDETERMINATE_OUTPUT으로 같은 분석·후보를 재평가한다.
  명시적 UNSUPPORTED가 있으면 관찰 승인을 false로 두고 기존 재분석 일관성 조건을 검사한다.
- 판단보류는 형식/제공자 실패와 구분해 turns.jsonl·summary.json의 indeterminate_evaluations와 Markdown 보고서에 집계한다.
  이전 결과 파일에 이 항목이 없으면 0으로 읽는다.
- SAFETY_ESCALATION/CONFIRM_INPUT은 학습 근거 검수보다 우선하며 빈 리뷰를 허용한다. 기본 출력·요청 연결 검사는 유지한다.
- 인용·설명은 검증 후 내부 EvaluationResult로 전달하지 않고 DB·공개 DTO·일반 로그·retry 피드백에도 추가하지 않는다.
- 기본 경로의 분석·생성·평가 호출 수와 재시도·전체 60초 예산은 유지한다. 관찰만의 추가 모델 호출은 없다.

기존 진단 성공을 이 통합 평가의 실제 모델 성공으로 간주하지 않는다.
운영 코드 연결 승인 범위는 오프라인 검증까지였다. 이후 별도 승인으로 아래 통합 실 API 진단을 수행했다.
설계와 검증 결과는 roleplay-observation-evidence-integration-design-2026-11-08.md에 기록한다.

2026-11-08 오프라인 전체 검증: `./gradlew --no-daemon check` 성공(5분 27초).
총 2,444개 중 2,438개 통과, 실패·오류 0개, 실 ML API 1개·실 ffmpeg 5개는 opt-in 조건에 따라 건너뛰었다.
실제 모델의 v2 통합 평가 성공률·지연·토큰 사용량은 이 결과로 판단할 수 없다.

후속 고정 A/B 운영 평가 진단은 elice-p1-integrated-evaluation-01이다.
A 1회에서 사건 관찰은 SUPPORTED, 스키마는 통과했으나 analysis_supported=true와
activity_goal_supported=false가 모순돼 INVALID_OUTPUT_FORMAT으로 중단했다. B는 호출하지 않았다.
A는 4.384초, 입력/출력 6,279/329토큰이다. 관찰 분류의 단건 일치와 전체 평가 거절을 구분해야 한다.
상세 조건·결과·한계는 roleplay-integrated-evaluation-diagnostic-design-2026-11-08.md에 기록했다.
