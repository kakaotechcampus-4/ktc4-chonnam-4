# 관찰 거절 사유·인용 조합의 네이티브 제약 설계

상태: 승인 후 구현·관련 검사·실제 반례 두 건 재검증 완료. 의도한 거절 2/2. 서버 관측 준비 단계로 복귀.

## 근거와 배포 판단

기존 회귀 검사 133개 및 반례 실행기 관련 검사 17개는 통과했다.
실제 gpt-6-luna/low 두 호출에서 잘못된 후보는 유효하게 거절했지만,
잘못된 감정 관찰의 거절은 STATUS_MISMATCH + null 인용 때문에 도메인 검사에서 차단됐다.
원본: build/eval/negative-evaluation-gpt-6-luna-8672047733794451832.
계획한 최소 반례 검증이 미완료이므로 배포 준비·최종 커밋/push 단계로 진행하지 않는다.

## 원인과 최소 수정

현재 ScopedAnalysisContext.createSchema는 각 관찰에 동일한 개별 필드 enum/타입을 사용한다.
요청별 원본 관찰 status를 알고 있어도 verdict·reason_code·evidence_quote 사이의 허용 조합을 제공자에 표현하지 않는다.
수신 후 ObservationReviewValidator는 이 조합을 검사하므로, 제공자 스키마 통과와 도메인 검증이 어긋난다.

관찰 키별 스키마 속성 내부 anyOf로 현재 검증기가 이미 허용하는 조합을 표현한다.
최상위 스키마는 object로 유지하고 실제 인용 필드를 계속 사용한다.

| 원본 status | verdict | reason_code | quote 타입 |
| --- | --- | --- | --- |
| NOT_EXPRESSED | SUPPORTED | ABSENCE_CONFIRMED | string 또는 null |
| NOT_EXPRESSED 외 | SUPPORTED | MATCHED_EXPRESSION | string |
| 모든 status | UNSUPPORTED | STATUS_MISMATCH | string |
| NOT_EXPRESSED 외 | UNSUPPORTED | NO_MATCHING_EXPRESSION | string 또는 null |
| 모든 status | INSUFFICIENT_CONTEXT | INSUFFICIENT_CONTEXT | string 또는 null |

이는 현재 ObservationReviewValidator의 허용 조건을 옮기는 것이며, 거절 사유를 서버가 보정하는 변경이 아니다.
string의 비어 있지 않음·실제 아동 발화 부분 문자열 여부, 설명, 참조 등은 기존 수신 검증에서 계속 검사한다.
원문 전체 참조 자동 연결·필드 보충·강제 PASS·검증 완화는 하지 않는다.
현재 사례의 STATUS_MISMATCH/null은 제공자에 보낸 스키마와 동일한 수신 스키마에서 차단돼야 한다.
NO_MATCHING_EXPRESSION/null은 서버가 발명하는 답이 아니라 모델이 선택할 수 있는 기존 유효한 대안이다.

공식 근거:
- https://docs.spring.io/spring-ai/reference/api/structured-output/native.html
- https://docs.spring.io/spring-ai/reference/api/structured-output/validation.html

제공자별 스키마 제약이 다르므로 Elice의 실제 수용 여부를 확인한다.
프로젝트 Spring AI 2.0.1과 기존 OpenAiChatOptions response_format 연결을 유지한다.
ChatClient/Advisor 전환이나 재시도 계층 추가는 하지 않는다. 기존 전체 60초·평가 최대 3회·SDK 재시도 0회 유지.

## 검증과 승인받을 범위

운영 관찰 스키마·버전 메타데이터와 직접 관련된 검사만 변경한다.
운영 wire는 새 버전으로 식별하고 이전 진단의 스키마/프롬프트/원본은 그대로 보존한다.
과거 v4를 재현하는 진단은 저장된 v4 스키마 또는 명시적 과거 계약으로 검증하며 과거 출력을 새 스키마에 맞춰 고치지 않는다.
기존 프롬프트는 거절 사유를 이미 설명하므로 이번 수정은 스키마 제약을 중심으로 한다.

오프라인에서는 다섯 원본 status·세 verdict의 허용/금지 조합과 실제 이번 실패 원문을 확인한다.
특히 STATUS_MISMATCH/null 거절, NO_MATCHING_EXPRESSION/null 수용,
잘못된 원문 인용 차단, 판단보류·안전 우선순위·재시도 경로를 회귀 검증한다.

관련 검사 통과 후 실제 호출은 같은 두 반례를 각 한 번, 최대 두 번만 재검증하는 범위를 제안한다.
모델·추론·timeout은 현재 환경 설정을 기록한다. 공유 예산 60초·재시도 0회다.
제공자·스키마·시간 오류 시 중단하고 별도 호환성 호출·추가 재시도를 하지 않는다.
이 두 추가 호출은 기존 승인 두 회에 포함되지 않으므로 별도 승인을 받는다.
두 반례가 유효한 이유로 수용되면 원래 계획의 서버 관측 준비와 최종 검증 단계로 복귀한다.


## 승인 후 결과

ScopedAnalysisContext.evaluationSchema()에 관찰별 anyOf 조합을 추가하고 운영 EvaluationOutputSchema를 v5로 구분했다.
기존 schema()는 과거 분석 전용 진단용으로 유지해 저장된 스키마·원문·승인 기록의 계약이 바뀌지 않게 했다.
프롬프트·서버 인용/참조 검사·내부 결과 계약·재시도 상한·60초 예산은 변경하지 않았다.
5개 status·3개 verdict·5개 reason·인용 유무의 150개 조합, 실제 실패 조각, 인용 출처 검사 유지 등을 포함해 관련 검사 340개가 통과했다.
형식 검사와 git diff --check도 통과했다.

실제 재검증은 승인한 두 번만 수행했다. 모델 gpt-6-luna, reasoning=low, timeout=30초, 공유 예산 60초, 재시도 0회다.
A는 감정 관찰만 UNSUPPORTED/NO_MATCHING_EXPRESSION/null로 거절했고 나머지 분석 범위는 승인해 유효한 REANALYZE가 됐다.
B는 정상 분석을 유지하고 금지 의도 후보를 SCENARIO_CONTRADICTION·PROHIBITED_INFERENCE 등으로 거절해 유효한 REGENERATE가 됐다.
둘 모두 safe_to_send=false다. 실제 재분석·재생성·사용자 전달·저장은 실행하지 않았고 그 연결 경로는 자동 회귀 검사로 확인했다.
평가 시간은 A 11.502초, B 10.412초다. 입력 20,330·출력 2,005토큰, 금액 미계산이다.

원본과 요약은 build/eval/negative-evaluation-gpt-6-luna-18232480630842464421에 보존했다.
이전 실패 실행도 그대로 유지했다. 두 단발 반례의 개선 결과이며 모델 전반의 정확도나 안정성을 입증하는 것은 아니다.
서버 관측 보완·공식 develop `d6f7e06` 동기화 후 최종 build를 완료했다.
총 2,855개 중 2,849개 통과, opt-in 제외 6개, 실패 0개다. 실제 AWS 입력 관측은 아직 수행하지 않았다.
