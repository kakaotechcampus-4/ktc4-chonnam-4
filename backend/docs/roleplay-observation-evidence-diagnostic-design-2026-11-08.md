# 같은 모델의 관찰별 근거 진단 설계

작성일: 2026-11-08\
상태: 설계 승인 후 테스트용 구현·오프라인 검증·실 API 2회 실행 완료\
이번 작업: 기존 A/B 요청·응답과 검수 기준 확인, 본 설계 작성. 코드 수정·추가 API 호출 없음.

## 목적

모델 교체를 해결책으로 가정하지 않는다.
현재 Nano가 정상 사건 관찰을 왜 거절하는지 사람이 기준·발화와 대조할 수 있는 짧은 판정 근거를 받는다.
기존 출력의 관찰 전체 SUPPORTED/UNSUPPORTED 한 개와 빈 참조만으로는 어느 관찰을 문제 삼았는지 알 수 없었다.

이번에는 관찰마다 결과를 받고, 수용한 관찰에는 실제 발화의 근거 구절을 요구한다.
이것은 모델의 숨은 내부 추론을 복원하는 실험이 아니다. 모델이 제시한 근거 자체가 사실과 기준에 맞는지 검토하는 진단이다.

## 고정할 조건

- 모델 요청명 gpt-5.4-nano 유지. 응답 모델명 기록.
- elice-p1-observation-pair-01의 A/B 실제 요청 내용 그대로 사용.
- A는 사건 EXPLICIT 관찰 1개, B는 같은 사건 관찰과 잘못 추가한 감정 EXPLICIT 관찰 1개.
- 시나리오·발화·상태·정책·대화·기준·식별자를 변경하지 않음.
- 후보 대사는 계속 제외. 기대값과 A/B 명칭은 모델 입력에 넣지 않음.
- 활동 판정·다음 전략은 입력의 기존 데이터로 보존하지만 이번 검수 대상에서 제외.

아동 발화는 “너 발표하다가 말을 못 찾아서 중간에 멈추고 그냥 앉았지?”다.
사건을 직접 언급했고 감정을 직접 말하지 않았다는 기존 사전 기준을 유지한다.

## 바꿀 부분

진단 전용 지시와 출력 계약을 새 버전으로 만든다.
기존 운영 프롬프트, 분석 전용 v1 진단, 이전 결과는 유지한다.

새 진단 프롬프트: roleplay-observation-evidence-diagnostic/v1\
새 출력 스키마: observation-evidence-diagnostic/v1

원인 분석 전체를 한꺼번에 판정하도록 요청하지 않고,
analysis_result.goal_observation.observations의 각 항목만 승인 기준·발화와 대조한다.
활동 완료 여부나 후보 표현 때문에 관찰 판정을 바꾸지 않도록 검수 대상을 명시한다.

## 출력 계약

루트는 analysis_id와 reviews 배열만 반환한다. 각 reviews 항목은 다음 필드다.

| 필드 | 내용 |
| --- | --- |
| criterion_reference | 검수한 현재 관찰의 승인 기준 참조 |
| verdict | SUPPORTED / UNSUPPORTED / INSUFFICIENT_CONTEXT |
| reason_code | MATCHED_EXPRESSION / NO_MATCHING_EXPRESSION / STATUS_MISMATCH / INSUFFICIENT_CONTEXT |
| evidence_quote | 이번 canonical_utterance에서 그대로 복사한 구절 또는 null |
| explanation | 기준과 발화의 관계를 설명하는 짧은 한글 사유. 최대 200자 |

모델에 장황한 단계별 사고 과정이나 내적 추론을 요구하지 않는다.
예를 들어 “발화는 사건만 언급하며 감정 표현은 없다”처럼 확인할 수 있는 판단 이유만 받는다.

SUPPORTED에는 MATCHED_EXPRESSION과 비어 있지 않은 evidence_quote가 필요하다.
UNSUPPORTED에는 NO_MATCHING_EXPRESSION 또는 STATUS_MISMATCH를 사용한다.
STATUS_MISMATCH에는 어떤 표현의 해석이 달랐는지 확인할 근거 구절이 필요하다.
표현이 없다는 거절에는 evidence_quote=null을 허용한다. 없는 감정 표현을 인용하도록 강제하지 않는다.
INSUFFICIENT_CONTEXT는 같은 reason_code를 쓰고 어떤 정보가 부족한지 짧게 설명한다.

## 서버 검증

1. 제공자 요청에 native JSON Schema/strict를 적용하고 수신 후 같은 스키마로 검사한다.
2. analysis_id가 요청과 같은지 검사한다.
3. reviews가 이번 입력의 관찰을 정확히 한 번씩 포함하는지 검사한다. 누락·중복·추가 관찰을 거부한다.
4. criterion_reference는 승인된 기준 중에서도 이번 관찰에 실제로 사용한 참조여야 한다.
5. 상태와 reason_code 조합, 필수 인용, 비어 있지 않은 설명·길이 제한을 검사한다.
6. 인용이 있으면 canonical_utterance의 정확한 연속 부분 문자열인지 검사한다. 모델이 고쳐 쓴 인용은 거부하고 자동 보정하지 않는다.

JSON Schema는 A/B에 같은 승인 기준 enum을 사용한다.
A 1개·B 2개의 실제 관찰 개수와 정확한 참조 집합은 서버가 입력과 대조한다.
따라서 요청마다 스키마를 다르게 만들지 않고도 관찰 누락을 막는다.

실제 발화에 있는 구절이라는 검사만으로 의미상 적절한 근거임을 증명할 수는 없다.
사건 구절을 감정의 근거로 잘못 제시할 수도 있으므로 사람의 대조와 사전 기대값 검사는 계속 필요하다.
설명도 모델의 주장이지 실제 내부 원인이 확인된 것으로 취급하지 않는다.

## 기대값과 기록

| 사례 | 관찰 | 기대 결과 |
| --- | --- | --- |
| A | 사건 EXPLICIT | SUPPORTED, 실제 사건 구절 인용 |
| B | 같은 사건 EXPLICIT | SUPPORTED, 실제 사건 구절 인용 |
| B | 추가한 감정 EXPLICIT | UNSUPPORTED. 존재하지 않는 감정 인용은 요구하지 않음 |

A/B의 사건 판정이 같은지도 별도로 기록한다.
A는 수용하면서 B의 같은 사건을 거절하면 다른 관찰의 추가가 판정에 영향을 준 단서가 된다.
이것만으로 그 원인을 확정하지는 않는다.

JSON/근거 인용 검증 실패와 의미 판정 오답, 정보 부족을 구분해 집계한다.
검증 오류를 아동의 오답이나 정상 거절로 세지 않는다.
출력 계약 유효성과 세 관찰의 기대값 일치도 별도로 기록한다. 운영 PASS로 변환하지 않는다.

## 구현·실행 범위

- 테스트용 근거 진단 실행기·출력 검증·프롬프트와 관련 검사만 추가.
- 기존 A/B 고정 자료를 재사용하고 새 DB 스냅샷이나 모델 선택 기능을 추가하지 않음.
- 운영 평가·DB 마이그레이션·프론트엔드 변경 없음.
- 오프라인 검사 후 A/B 각각 1회, 물리 호출 최대 총 2회, SDK/검증 재시도 0회.
- 두 요청이 60초 예산을 공유. 제공자·출력 계약·기한 오류 발생 시 남은 호출을 중단.
- 실행 결과 덮어쓰기 방지. 별도 elice-p1-observation-evidence-01 실행 폴더 사용.
- 원문 인용·설명은 승인된 합성 P1의 진단 산출물로만 보관. 실제 아동 로그에 연결하지 않음.

## 오프라인 완료 기준

- A/B의 사용자 입력이 기존 실제 A/B 요청과 같은지, 두 요청의 새 지시·스키마가 같은지 검사.
- 정확한 현재 관찰 집합, 다른 분석 ID, 누락/중복/추가 참조를 검사.
- 존재하지 않는 인용, 필수 인용 누락, 빈/과도한 설명, 상태·사유 모순을 거부.
- 기대값이 모델에 전달되지 않으며 모델 호출이 최대 2회이고 오류에 자동 반복하지 않음을 검사.
- 구조 오류·정보 부족과 의미상 오답을 구분해 결과를 작성하는지 검사.

## 해석의 한계

프롬프트의 검수 범위와 출력 계약을 함께 바꾸므로 개선돼도 특정 변경 하나의 효과로 단정하지 않는다.
다만 같은 모델·입력에서도 더 구체적인 검수에서 판단이 달라지는지 확인할 수 있다.
문제가 계속되면 구체적인 근거를 보존한 상태로 지시 해석·기대값·모델 능력을 재검토한다.
단건 쌍으로 모델 전체의 적합성이나 재현성을 확정하지 않는다.

## 근거

### 승인 후 실행 결과

관찰별 검증기 ObservationEvidenceReview와 실행기 ObservationEvidenceDiagnostic, 새 진단 프롬프트 및 관련 검사를 추가했다.
기존 테스트용 AnalysisReviewDiagnostic의 단일 호출·기한 처리만 공통 함수로 추출해 재사용했다.
운영 평가 코드는 변경하지 않았다.

```bash
./gradlew spotlessApply test --tests '*ObservationEvidenceDiagnosticTest' --tests '*ObservationPairDiagnosticTest' --tests '*AnalysisReviewDiagnosticTest'
./gradlew spotlessCheck
./gradlew observationEvidenceDiagnostic
```

관련 58건과 형식 검사가 통과했다. 전체 check는 재실행하지 않았으며,
이전 전체 검사에서 간헐 실패했던 CSRF 업로드 검사까지 해결된 것으로 보고하지 않는다.

실행 ID는 elice-p1-observation-evidence-01이다. 실제 모델은 같은 gpt-5.4-nano-2026-03-17이었다.
기존 A/B 실제 요청 내용이 그대로인지 바이트 단위로 확인했고 두 요청의 새 시스템 프롬프트와 스키마도 같았다.
호출은 각각 1회, 총 2회이며 SDK·검증 재시도와 오류·기한 초과는 없었다.

| 사례 | 관찰 | 실제 판정 | 근거 |
| --- | --- | --- | --- |
| A | 사건 EXPLICIT | SUPPORTED / MATCHED_EXPRESSION | 실제 아동 발화 인용, 사건을 직접 말함 |
| B | 같은 사건 EXPLICIT | SUPPORTED / MATCHED_EXPRESSION | 같은 실제 발화 인용, 사건을 직접 말함 |
| B | 추가한 감정 EXPLICIT | UNSUPPORTED / NO_MATCHING_EXPRESSION | 인용 null, 감정 표현이 없음 |

관찰 기대값은 3/3 일치했고 대조쌍 구분 결과는 true다.
두 사건 근거는 실제 canonical_utterance의 정확한 연속 부분 문자열 검사를 통과했다.
모델의 사유는 “발표 중 말을 못 찾아 중간에 멈추고 앉았다는 사건을 직접 말함”,
“부끄러움·불안·속상함 같은 감정을 말하지 않음”처럼 기준과 대조할 수 있었다.
확인한 현재 관찰의 참조 집합도 A 1개·B 2개로 빠짐없고 중복되지 않았다.

A 2.803초, B 2.503초로 진단 시간 합계는 5.306초다.
입력 토큰 합계는 5,339, 출력은 390이며 비용은 단가가 없어 계산하지 않았다.
이 시간은 관찰 검수 두 번의 합으로 전체 역할극 지연과 구분한다.

같은 모델이 이 작업을 구분할 수 있었으므로 이전 실패를 모델 능력만의 문제로 단정할 수 없다.
다만 검수 범위·프롬프트·스키마·출력 순서를 함께 바꿨으므로 어느 요소가 원인인지는 분리해 입증하지 못했다.
이전 거절의 숨은 내부 이유를 알아낸 것도 아니다.
각 사례 단건이며 활동·전략·후보 품질을 검사하지 않았으므로 일반 정확도나 역할극 전체 해결을 선언하지 않는다.

운영에 적용하려면 관찰·활동·전략·후보 검수의 책임과 근거 검증·재시도·60초 예산 연결을 별도 설계해야 한다.
운영 PASS 강제나 다른 평가 생략은 수행하지 않았다.
산출물은 build/eval/elice-p1-observation-evidence-01의 summary.md·result.json·comparison-checks.json 및 A/B별 요청·출력이다.

- 이전 대조쌍: build/eval/elice-p1-observation-pair-01/summary.md
- 고정 자료: src/test/resources/eval/analysis-review-observation-pair-p1-v1.json
- 기존 진단 지시: src/test/resources/eval/analysis-review-prompt-v1.md
- 캐릭터 관찰 기준: src/main/resources/prompts/roleplay/v2/cause-analysis-character.md
