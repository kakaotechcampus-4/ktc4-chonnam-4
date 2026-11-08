# 관찰별 근거 검증의 운영 평가 연결 설계

작성일: 2026-11-08\
상태: 승인 범위 구현 및 오프라인 전체 검증 완료\
이번 작업: 승인된 운영 평가 연결과 오프라인 검증. 이 구현 단계에서 추가 실 API 호출 없음.

## 목표와 적용 범위

기존 독립 평가 1회 안에서 관찰별 검수 결과를 먼저 받고 서버가 근거·참조·일관성을 검증한다.
활동 목표·분석/전략·후보 표현·안전 검수는 계속 수행한다.
정상 경로는 분석 1회 → 생성 1회 → 평가 1회이며 별도의 관찰 LLM 호출을 추가하지 않는다.
단계별 시도 상한, 기존 오케스트레이터, SDK 재시도 0회와 전체 60초 예산은 유지한다.

진단에서는 관찰만 검수했다. 운영에서는 다른 검수와 한 호출에 들어가므로 진단의 3/3 성공이 그대로 재현된다고 보장하지 않는다.
이번 설계는 관찰 결과의 구조화·서버 검증을 연결하는 단계이며 전체 역할극 성공을 미리 선언하지 않는다.

## 출력 계약 변경

| 대상 | 현재 | 변경 |
| --- | --- | --- |
| 제공자 출력 스키마 | evaluation-output/v1 | evaluation-output/v2 |
| 안내자·캐릭터 평가 프롬프트 | 각각 v4 | 각각 v5 |
| 관찰별 결과 | 없음 | observation_reviews 추가 |
| 모델의 observations_supported | Boolean 직접 반환 | 제거, 서버가 관찰별 결과로 계산 |
| 모델의 목표 검수 버전 | evaluation-result/v2 문자열 반환 | 제거, 서버가 내부 계약 버전 부여 |
| 내부 EvaluationResult·목표 검수 계약 | 기존 형태·evaluation-result/v2 | 유지 |
| 프론트엔드 공개 API | 기존 형태 | 변경 없음 |

모델 출력의 goal_observation_review는 analysis_id와 activity_goal_supported만 반환한다.
서버는 기존 GoalObservationOutput.Review를 내부 v2 버전·요청 분석 ID·계산한 관찰 승인·모델의 활동 판정 승인으로 만든다.
관찰 승인을 모델에게 두 번 판단시키지 않는다.

checks의 기존 12개 항목, decision, failure_codes, revision_instruction은 유지한다.
analysis_supported는 관찰만이 아니라 학습 상태·분석과 다음 전략의 정합성도 보는 기존 검사이므로 남긴다.
관찰 검수가 통과했다는 이유만으로 analysis_supported나 활동 판정·후보 검수를 true로 바꾸지 않는다.
처리 경로·safe_to_send·치명 실패 개수는 현재처럼 서버가 계산한다.

## observation_reviews 항목

- micro_goal_id: 검수한 현재 관찰의 목표.
- criterion_reference: 그 관찰의 승인된 기준 참조.
- evidence_quote: 이번 canonical_utterance의 정확한 연속 구절 또는 null.
- verdict: SUPPORTED / UNSUPPORTED / INSUFFICIENT_CONTEXT.
- reason_code: MATCHED_EXPRESSION / ABSENCE_CONFIRMED / NO_MATCHING_EXPRESSION / STATUS_MISMATCH / INSUFFICIENT_CONTEXT.
- explanation: 기준과 발화의 관계를 설명하는 한글 사유 한 문장, 최대 200자.

진단의 criterion_reference 단독 키는 운영에서는 목표 ID와 함께 사용한다.
현재 GoalObservationInput은 목표와 기준 참조의 조합을 식별자로 사용하므로, 기준 문자열이 같을 가능성을 임의로 배제하지 않는다.
현재 관찰은 모두 이번 턴에 속한다는 기존 분석 검증을 유지하며 모델에게 turn_id를 반복 출력하도록 추가하지 않는다.

## 관찰 종류별 근거 규칙

SUPPORTED는 아동 정답이 아니라 **분석이 해당 관찰을 올바르게 분류했다는 승인**이다.

| 분석이 제안한 관찰 | 해당 분류를 승인할 때 필요한 근거 |
| --- | --- |
| EXPLICIT·IMPLICIT | MATCHED_EXPRESSION + 실제 발화 구절 |
| MISUNDERSTOOD·CONTRADICTORY | MATCHED_EXPRESSION + 그 오해/모순 분류를 뒷받침하는 실제 발화 구절 |
| NOT_EXPRESSED | ABSENCE_CONFIRMED. 없는 표현을 인용하도록 강제하지 않으며 null 허용 |

ABSENCE_CONFIRMED는 NOT_EXPRESSED의 승인에만 허용한다.
UNSUPPORTED에는 NO_MATCHING_EXPRESSION 또는 STATUS_MISMATCH를 사용한다.
STATUS_MISMATCH에는 해석이 달라진 실제 구절이 필요하고, 표현이 없다는 거절은 null을 허용한다.
단, NOT_EXPRESSED 관찰을 거절한다면 실제로 표현됐다는 근거가 필요하므로 STATUS_MISMATCH와 실제 인용을 요구한다.
빈 인용만으로 “표현하지 않았다는 분석이 틀렸다”고 승인하지 않는다.
INSUFFICIENT_CONTEXT에는 같은 reason_code와 부족한 정보에 대한 짧은 사유를 요구한다.

실제 구절이라는 검사가 의미의 타당성까지 증명하지 않는다. 사유도 모델의 주장으로 취급한다.
이번 변경으로 EXPLICIT/IMPLICIT 등의 기획상 의미나 목표 달성 기준을 새로 정하지 않는다.

## 서버 검증 순서

1. native JSON Schema/strict와 같은 수신 스키마로 자료형·필수 값·허용 코드·추가 필드를 검사한다.
2. 기존 후보·분석 요청 연결과 기본 판정/실패 코드 검증을 수행한다.
3. 일반 평가에서는 검수 항목의 (목표 ID, 기준 참조) 집합이 이번 analysis.goal_observation.observations와 정확히 같은지 검사한다.
4. 승인 범위 밖의 목표·기준, 누락·중복·추가 항목, 다른 목표의 기준을 거부한다.
5. 인용이 있으면 비어 있지 않고 canonical_utterance의 정확한 연속 부분 문자열인지 검사한다. 모델이 고친 인용은 보정하지 않는다.
6. 관찰 종류·verdict·reason_code·필수 인용과 설명 길이의 조합을 검사한다.
7. 구조·근거 검사가 끝난 뒤 관찰 승인/거절/판단보류를 구분한다.
8. 내부 결과 변환 후 기존 활동·분석·후보·PASS 전달 게이트를 그대로 검사한다.

모든 현재 관찰이 SUPPORTED이면 observations_supported=true다.
현재 관찰이 없고 reviews도 비어 있으면 검수할 현재 관찰이 없다는 뜻으로 true를 구성한다.
이는 관찰이 생겼거나 학습 목표를 달성했다는 뜻이 아니다. 활동 달성의 기존 긍정 근거 검사는 별도로 유지한다.
리뷰가 빈 배열인 상황을 “모든 작은 목표 달성”으로 해석하거나 새로운 완료 조건을 만들지 않는다.

현재 관찰 중 UNSUPPORTED가 있으면 observations_supported=false다.
일반 판정은 analysis_supported=false, UNSUPPORTED_CAUSE_ANALYSIS 코드, REANALYZE와 일치해야 한다.
모델이 PASS나 후보 재생성을 함께 반환하면 일관성 위반으로 같은 후보를 재평가한다. 서버가 모델 판정을 임의로 고치지 않는다.
관찰은 전부 승인됐지만 활동·전략에 별도 문제가 있으면 기존 REANALYZE 판단이 여전히 가능하다.

## 판단보류와 재시도

확정적인 UNSUPPORTED가 없고 한 항목 이상 INSUFFICIENT_CONTEXT이면 판단보류로 처리한다.
새 내부 예외 IndeterminateLlmOutputException과 재시도 가능한 AiFailureType.INDETERMINATE_OUTPUT으로 구분한다.
StructuredLlmExecutor가 메타데이터·사용량을 보존해 이 결과를 전달하고, 기존 오케스트레이터가 같은 분석·후보를 재평가한다.
판단보류는 INVALID_OUTPUT_FORMAT 또는 제공자 실패로 집계하지 않는다. 측정 보고서에는 별도 평가 판단보류 횟수를 기록한다.

관찰 중 명시적인 UNSUPPORTED도 있다면 관찰 승인은 false로 처리하고, 기존 분석 복귀 조건을 검사한다.
정보 부족이 명시적인 관찰 거절을 덮어쓰지는 않는다.

| 결과 | 처리 |
| --- | --- |
| 누락·허구 인용·참조·상태/사유·판정 불일치 | 기존 INVALID_OUTPUT_FORMAT, 같은 분석·후보 재평가 |
| 판단보류 | 새 INDETERMINATE_OUTPUT, 같은 분석·후보 재평가 |
| 유효한 관찰 거절 + 일관된 REANALYZE | 기존 분석 복귀 |
| 관찰 승인 + 후보 문제 | 기존 REGENERATE/SAFETY_REGENERATE 조건 검사 |
| 모든 기존 검수와 새 근거 검수 통과 | 기존 PASS 게이트에서 전달 여부 결정 |

형식/근거 오류와 판단보류에는 각각 고정된 오류 코드·수정 조건만 다음 요청에 전달한다.
모델 원문·발화 인용·자유 서술 사유를 retry 지시에 복사하지 않는다.
정보가 부족하면 검사를 통과하려고 승인하지 말라는 조건을 유지한다.
평가 최대 3회에 도달하면 RESPONSE_EVALUATION의 SAFE_FALLBACK/LIMIT_REACHED다.
추가 LLM 검증 루프나 SDK 재시도를 넣지 않으며 생성·분석 카운터도 불필요하게 증가시키지 않는다.

## 안전·입력 확인 우선순위

유효한 SAFETY_ESCALATION/CONFIRM_INPUT은 기존 우선순위를 유지한다.
이 경우에는 observation_reviews=[]를 허용하고 학습 근거 검사를 위해 재평가를 강제하지 않는다.
관찰 승인은 내부에서 false로 두며 정상 전달·학습 달성에 사용하지 않는다.
기본 스키마·ID 연결·허용 코드 검증은 수행한다. 알 수 없는 코드나 깨진 출력까지 무조건 수용하는 예외는 아니다.

## 저장·개인정보·출력량

관찰 인용·설명은 모델 응답을 검증하는 동안에만 사용하고 내부 EvaluationResult로 변환할 때 버린다.
DB 컬럼·운영 일반 로그·공개 DTO·retry 피드백에 추가하지 않는다.
운영 원문 보관·삭제 정책을 변경하지 않는다. 합성 회귀 실행에서만 기존 진단 규칙에 맞춰 비교 산출물을 남긴다.

관찰 수만큼 출력이 늘어나므로 다중 기준·다중 관찰 사례와 잘린 JSON도 검증한다.
기존 출력 토큰 상한·deadline을 임의로 늘리지 않는다. 응답 잘림은 기존 출력 실패·복구 경로로 처리한다.

## 구현·검증 범위

구현 대상은 평가 wire DTO/스키마·관찰 근거 검증기·프롬프트 연결·서버 변환·고정 피드백이다.
판단보류 구분을 위해 내부 실패 유형/예외·StructuredLlmExecutor의 매핑과 회귀 측정 집계도 연결한다.
기존 오케스트레이터의 단계·시도 상한·전달 API·저장 스키마·프론트엔드·모델 선택은 바꾸지 않는다.
안내자와 캐릭터의 말투·역할 기준은 각각 유지한다.

관련 검사 후 전체 check를 한 번 실행한다. 새로운 실패가 없으면 같은 전체 검사를 반복하지 않는다.
기존에 전체 실행에서만 나타났던 CSRF 404는 발생 시 별도로 원인을 기록하고 전체 성공으로 숨기지 않는다.

필수 검증 사례:

- A 사건 승인, B의 동일 사건 승인·잘못된 감정 거절 및 내부 v2 변환.
- NOT_EXPRESSED 승인에 인용 강제하지 않음, 오해/모순 관찰의 분류 승인과 아동 정답을 구분.
- 빈 현재 관찰과 이전 긍정 근거 등 기존 경계 유지. 새 전체 완료 조건 없음.
- 참조 조합 오류·중복·누락·추가·허구 인용·사유 충돌을 같은 후보 재평가로 처리.
- 관찰 거절과 모델 PASS/재생성의 모순을 차단. 관찰 승인만으로 다른 검사 통과를 만들지 않음.
- 판단보류가 최대 3회, 같은 ID·메타데이터·예산 유지, 형식/제공자 실패와 별도 집계.
- 안전·입력 확인 우선순위, 기한 후 늦은 출력 차단, TTS·저장·공개 DTO 경계 유지.
- 예외/로그/retry/내부 EvaluationResult에 인용·설명이 새로 남지 않음.

## 승인받을 범위

이번 승인은 위 운영 코드 연결과 오프라인 검증까지다.
실 API 호출은 구현·검증 결과를 확인한 뒤 별도 범위를 정한다.
프론트엔드 변경·모델 교체·DB 마이그레이션·commit/push는 포함하지 않는다.

## 근거

- 진단 결과: build/eval/elice-p1-observation-evidence-01/summary.md
- 현재 변환: src/main/java/com/neuringo/neuringobe/ai/application/prompt/EvaluationOutput.java
- 현재 스키마: src/main/java/com/neuringo/neuringobe/ai/application/prompt/EvaluationOutputSchema.java
- 평가 생성: src/main/java/com/neuringo/neuringobe/ai/application/prompt/RoleplayPromptFactory.java
- 파싱/연결: src/main/java/com/neuringo/neuringobe/ai/application/prompt/RoleplayOutputParsers.java
- 재시도: src/main/java/com/neuringo/neuringobe/ai/application/roleplay/RetryingRoleplayTurnExecutor.java
- 구조/판단보류 매핑: src/main/java/com/neuringo/neuringobe/ai/application/structured/StructuredLlmExecutor.java

## 구현 및 검증 기록

관찰별 출력 스키마·검증기·내부 결과 변환, 평가 프롬프트 v5, 같은 후보 재평가용 고정 피드백을 연결했다.
판단보류는 INDETERMINATE_OUTPUT으로 분리하고 평가 보고서에 별도 횟수를 추가했다.
기존 보고서에 새 집계 필드가 없으면 0으로 읽는다. API·DB·프론트엔드 변경은 없다.

관련 검사와 형식 검사가 통과했다. 관련 테스트는 235개, 실패·오류·건너뜀은 모두 0개다.
검증 범위에는 관찰 근거 재현, 스키마·파서·프롬프트, 목표 관찰·지원 적용,
같은 후보의 재평가와 판단보류 집계, 실패 유형 계약이 포함된다.

2026-11-08 Docker 복구 후 `./gradlew --no-daemon check`가 5분 27초에 성공했다.
162개 테스트 스위트의 총 2,444개 중 2,438개 통과, 실패 0개, 오류 0개, 조건부 건너뜀 6개다.
건너뛴 항목은 `ML_API_REAL=true`일 때 실행하는 실 ML API 검사 1개와
`ROLEPLAY_MEDIA_REAL_TOOLS=true`일 때 실행하는 실제 ffmpeg 검사 5개다.
코드 형식 검사와 JaCoCo 보고서 생성도 완료했다.
이전에 전체 실행에서만 간헐적으로 실패했던 RoleplayServletUploadIntegrationTest의 2개도 이번 실행에서 통과했다.
이번 통과만으로 그 간헐적 실패의 원인이 해결됐다고 단정하지 않는다.

앞선 전체 실행에서 발견한 새 실패 유형의 계약 문서 누락은 수정했고, 위 전체 검사에 포함해 통과했다.
디스크 부족 및 Docker 연결 불가로 중단됐던 실행은 성공 결과에 포함하지 않는다.

이 검사는 실제 Elice 모델이 통합된 v2 평가에서 올바르게 판단하는지 입증하지 않는다.
실 모델의 통합 평가 결과·지연·토큰 사용량은 별도 승인 후 측정할 범위로 분리했다.
commit/push는 수행하지 않았다.

이후 별도 승인으로 수행한 통합 진단은 roleplay-integrated-evaluation-diagnostic-design-2026-11-08.md에 기록했다.
A의 사건 관찰과 스키마는 통과했지만 활동 검수·분석 체크의 모순으로 전체 평가를 수용하지 못해 1회에서 중단했다.
B는 미실행이며, 정상 역할극 성공을 입증한 결과가 아니다.
