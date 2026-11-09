# 고정 입력의 관찰 대조쌍 진단 설계

작성일: 2026-11-08\
상태: 설계 승인 후 진단 구현·오프라인 검증·실 API 최대 2회 실행 완료\
이번 작업: 기존 입력·기획/프롬프트 기준 확인과 본 문서 작성. 코드·테스트 추가, 추가 API 호출 없음.

## 목적과 판정 범위

같은 아동 발화에 대해 근거 있는 관찰과 존재하지 않는 감정을 만들어 낸 관찰을 구분하는지 확인한다.
분석 전체의 정확도, 활동 완료 정책, 다음 전략·지원 수준의 타당성을 한 번에 검증하지 않는다.
세 진단 출력 중 observation_review만 사전 기대값을 정한다.
activity_review·strategy_review는 함께 기록하되 이번 대조쌍의 통과 기준에 넣지 않는다.

기준 입력은 서비스 경로로 생성해 실제로 호출했던
build/eval/elice-p1-service-analysis-review-01/source-fixture.json 및 request.json을 고정한다.
테스트 DB에서 새로 생성하면 ID가 바뀔 수 있으므로 이번 비교를 위해 DB 스냅샷을 다시 만들지 않는다.
기존 합성 P1·실측 결과·최초 분석 전용 진단 결과도 보존한다.

## 고정된 사실

아동 발화:

> 너 발표하다가 말을 못 찾아서 중간에 멈추고 그냥 앉았지?

승인 기준:

- 사건 목표: 발표 중에 멈췄거나 말을 못 하고 앉았다는 것을 말한다.
- 감정 목표: 부끄러움·불안·속상함처럼 상황에 맞는 감정을 말한다.
- 연결 목표: 말이 안 나와서 부끄럽거나 불안했다고 이유와 함께 말한다.

현재 캐릭터 분석 기준은 아동이 해당 내용을 직접 말하면 EXPLICIT로 기록한다.
확인하며 묻는 말도 알아차린 근거일 수 있다. 현재 발화는 사건 내용을 직접 언급하지만 감정을 직접 언급하지 않는다.
다른 목표를 유추해낼 수 있다는 것과 해당 감정을 직접 말한 것은 구분한다.

## 대조 사례

| 사례 | 분석의 observations | observation_review 기대값 | 근거 |
| --- | --- | --- | --- |
| A: 기존 사건 관찰 | 사건 목표 EXPLICIT 1개 | SUPPORTED / NONE | 아동이 사건 기준의 내용을 직접 언급함 |
| B: 감정 관찰 추가 | A의 사건 관찰 + 감정 목표 EXPLICIT 1개 | UNSUPPORTED / OBSERVATION_NOT_GROUNDED | 아동이 직접 말하지 않은 감정을 직접 관찰했다고 주장함 |

B의 추가 관찰은 다음 참조로만 구성한다.

- turn_id: A와 같은 이번 발화 ID
- micro_goal_id: 기존 입력에 이미 승인된 감정 목표 ID
- criterion_reference: 기존 approved_criteria에 있는 감정 기준 참조
- status: EXPLICIT

B에 새로운 목표·새 기준·다른 발화·알 수 없는 ID를 넣지 않는다.
활동 판정의 basis는 기존 사건 관찰을 계속 가리킨다. 기존 사건 관찰을 지우지 않는다.
따라서 JSON/참조 위반이 아니라 학습 증거의 의미를 검수하는 대조가 된다.

B에서 늘어나는 것은 observations의 원소 하나뿐이다.
scenario·state·learner_turn·최근 대화·사전 지원·승인 기준·학습 순번·지원 정책은 고정한다.
learning_state·primary_gap·next_strategy·activity_goal·analysis_id도 변경하지 않는다.
B 때문에 다음 전략 검수 결과까지 달라질 수 있지만 이번 기대값의 대상은 관찰 검수뿐이다.

## 프롬프트·스키마·실행 통제

- 모델 요청명: 기존 설정의 gpt-5.4-nano. 실제 응답 모델명도 기록한다.
- 프롬프트: 기존 roleplay-analysis-review-diagnostic/v1의 내용 그대로 사용한다.
- 출력 계약: 기존 analysis-review-diagnostic/v1 유지. 같은 analysis_id·승인 기준이므로 실제 JSON Schema도 A/B가 같아야 한다.
- 후보 대사는 두 요청 모두 제외한다.
- A/B 명칭과 사전 기대값은 결과 보고서에만 사용하고 모델 입력에 넣지 않는다.
- 각 요청은 독립적인 System/User 메시지로 호출한다. 이전 모델 응답을 다음 요청에 넣지 않는다.
- A → B 순서로 각각 한 번, 전체 최대 2회 물리 호출이다. 각 사례의 SDK/검증 재시도는 0회다.
- 두 요청은 전체 60초 예산을 공유한다. 제공자 오류·잘못된 출력·기한 초과가 발생하면 남은 호출을 중단한다.
- 유효한 INSUFFICIENT_CONTEXT는 기술 오류가 아니므로 다음 사례를 실행하되 성공으로 집계하지 않는다.
- 실행 폴더가 이미 있으면 호출 전에 중단하며 과거 결과를 덮어쓰지 않는다.

## 오프라인 완료 기준

1. 고정된 실제 요청을 합성 회귀 입력으로 보존하고 생성 출처를 기록한다.
2. A의 모델 입력이 기존 request.json과 같은지 검사한다.
3. B와 A의 차이가 감정 EXPLICIT 관찰 한 개 추가뿐인지 검사한다.
4. 추가된 관찰의 목표·기준·턴이 기존 입력의 승인 범위와 맞는지 검사한다.
5. 두 요청의 시스템 프롬프트와 JSON Schema가 같은지, 기대값·사례명이 모델 입력에 없는지 검사한다.
6. 가짜 제공자로 최대 2회, 오류 시 중단, 자동 재시도 없음, 공통 예산 사용을 검사한다.
7. 비교 점수가 올바른 긍정/부정 판정과 정보 부족·형식 오류를 구분하는지 검사한다.

운영 평가·DB 스키마·프론트엔드는 수정하지 않는다. 전체 사용자 흐름의 E2E 검사도 아니다.
새 테스트용 대조쌍 실행기와 고정 합성 자료·관련 검사만 추가한다.

## 결과의 읽는 법

| A 결과 | B 결과 | 해석 |
| --- | --- | --- |
| SUPPORTED | UNSUPPORTED + OBSERVATION_NOT_GROUNDED | 이번 대조쌍에서 올바른 관찰과 조작한 관찰을 구분함 |
| UNSUPPORTED | UNSUPPORTED | 잘못된 관찰도 거절했지만 올바른 A까지 거절함 |
| SUPPORTED | SUPPORTED | A는 수용했지만 잘못된 감정 관찰을 놓침 |
| UNSUPPORTED | SUPPORTED | 두 기대값을 반대로 판단함 |
| 어느 한쪽 INSUFFICIENT_CONTEXT | 기타 | 구분에 성공했다고 볼 수 없음. 정보 부족과 오판을 별도로 기록 |
| 제공자/구조/기한 오류 | 미실행 또는 오류 | 의미 검수의 성능을 판정할 수 없음 |

주요 점수는 observation_review의 status와 reason_code가 두 기대값에 모두 맞는지다.
운영 PASS라는 이름을 붙이거나 전달 게이트에 연결하지 않는다.

criterion_references도 함께 기록한다. A에서 사건 기준을, B에서 추가한 감정 기준을 참조하는지는
판단 근거를 확인하는 보조 정보다. 기존 계약상 “판단에 사용한 참조” 목록이지
“거절한 참조만”을 뜻하지 않으므로 B에 감정 기준 외의 참조도 있다고 무조건 실패시키지 않는다.
빈 참조는 근거 제시가 부족한 것으로 표시하되 기존 출력 계약 위반으로 새롭게 바꾸지 않는다.
참조가 맞다는 것만으로 의미 판단이 정확하다고 간주하지도 않는다.

각 사례를 한 번만 실행하므로 성공하더라도 안정적인 검수 정확도나 재현성을 입증하지 않는다.
실패 결과만으로 특정 모델이 서비스 전체에 부적합하다고 단정하지 않는다.
이번 진단은 최소한의 의미 구분 여부를 확인하는 근거로 사용한다.

## 승인 범위

이 설계의 승인 범위는 테스트용 고정 대조쌍·실행기 구현, 오프라인 검증,
기존 모델의 A/B 각각 1회(최대 총 2회) 실 API 호출과 결과 정리다.
추가 반복·모델 변경·운영 판정 변경·push는 포함하지 않는다.

## 근거

### 승인 후 실행 결과

ObservationPairDiagnostic와 고정 합성 입력·관련 검사를 추가했다.
기존 분석 전용 실행기는 공통 기한을 받을 수 있도록 테스트 코드 안에서만 확장했다.
운영 Java/프롬프트·DB·FE는 변경하지 않았다.
오프라인 관련 33건과 spotlessCheck가 통과했으며 전체 check는 다시 실행하지 않았다.
이전 전체 검사에서 간헐적으로 실패한 CSRF 업로드 검사까지 해소됐다는 의미는 아니다.

```bash
./gradlew spotlessApply test --tests '*ObservationPairDiagnosticTest' --tests '*AnalysisReviewDiagnosticTest'
./gradlew spotlessCheck
./gradlew observationPairDiagnostic
```

실행 ID는 elice-p1-observation-pair-01이다. A/B 각각 1회, 총 2회 호출했으며 재시도·오류·기한 초과는 없었다.
실제 모델은 두 요청 모두 gpt-5.4-nano-2026-03-17이었다.
A의 요청 내용이 기존 서비스 입력 진단과 바이트 단위로 같고, B는 감정 관찰 하나만 추가됐는지 대조했다.
두 요청의 시스템 프롬프트·실제 JSON Schema 문자열도 같았다.

| 사례 | 기대값 | 실제 관찰 검수 | 판정 일치 | 관련 기준 참조 |
| --- | --- | --- | --- | --- |
| A: 사건 관찰 | SUPPORTED / NONE | UNSUPPORTED / OBSERVATION_NOT_GROUNDED | 아니오 | 빈 배열 |
| B: 잘못된 감정 관찰 추가 | UNSUPPORTED / OBSERVATION_NOT_GROUNDED | UNSUPPORTED / OBSERVATION_NOT_GROUNDED | 예 | 사건 기준·감정 기준 |

두 사례를 모두 거절했으므로 대조쌍 구분 성공은 false다.
개별 관찰 기대값은 1/2 일치했지만 서비스나 모델의 일반 정확도 50%라는 뜻은 아니다.
A의 거절은 현재 사전 정의한 관찰 기준에서 거짓 거절이다. 거절 이유를 특정할 기준 참조도 제시되지 않았다.
B는 추가한 감정 기준을 참조했으나 목록은 사용한 기준이지 거절 기준만을 의미하지 않으므로 상세 추론까지 입증하지는 않는다.

A 2.783초, B 2.692초로 진단 시간 합계는 5.475초다. 전체 역할극 시간과 구분한다.
입력 토큰 합계는 6,351, 출력은 354이며 단가가 없어 비용을 계산하지 않았다.

관찰 대조에서 잘못된 감정 관찰은 거절했지만 정상 사건 관찰까지 거절하는 문제가 나타났다.
이전 같은 서비스 입력 진단의 A 거절과도 같은 결과다.
각 사례 단건이므로 일반 성능이나 모델 전체 부적합을 단정하지 않는다.
이제 입력/출력 계약의 유효성과 의미 검수 정확성을 별도로 평가할 고정 기준이 생긴 상태다.
다른 검수 모델이나 근거 계약을 비교할 때도 이 자료를 보존하고 조건별로 평가해야 한다.
운영 PASS 강제·판정 완화·추가 API 호출·push는 수행하지 않았다.

결과는 build/eval/elice-p1-observation-pair-01의 summary.md·result.json·comparison-checks.json과
A/B별 request.json·schema.json·system-prompt.md·result.json·source-fixture.json에 보존했다.

- [실제 서비스 입력과 이전 결과](</Users/jinmina/IdeaProjects/neuringoBe/backend/build/eval/elice-p1-service-analysis-review-01/summary.md:1>)
- [고정할 실제 API 요청](</Users/jinmina/IdeaProjects/neuringoBe/backend/build/eval/elice-p1-service-analysis-review-01/request.json:1>)
- [기존 분석 전용 진단 기준](</Users/jinmina/IdeaProjects/neuringoBe/backend/src/test/resources/eval/analysis-review-prompt-v1.md:1>)
- [캐릭터 관찰의 EXPLICIT 기준](</Users/jinmina/IdeaProjects/neuringoBe/backend/src/main/resources/prompts/roleplay/v2/cause-analysis-character.md:6>)
- [서비스 입력 대조 기록](</Users/jinmina/IdeaProjects/neuringoBe/backend/docs/roleplay-p1-context-gap-audit-2026-11-08.md:1>)
