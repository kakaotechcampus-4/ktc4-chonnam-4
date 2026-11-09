# 캐릭터 질문·재생성 정책 정합성 보완

## 문제와 승인 범위

[실제 P1 단건 비교](roleplay-latency-live-comparison-2026-11-09.md)에서 축소 형태는 최초 세 호출 19.428초였지만 재생성 두 번으로 총 46.720초를 사용했다. 첫 후보를 질문이 없다는 이유로 거절하고, 아동에게 감정을 묻는 문답으로 바꾼 것이 문제였다. 사용자 승인은 질문 필수 정책의 서버 연결, 재생성의 캐릭터 정책 보존, 실제 대사 고정 검사 세 가지다.

이번 작업은 속도를 다시 측정하는 작업이 아니다. 추가 실제 AI 호출·push·배포는 하지 않는다. 기존 승인 기획의 질문 없는 캐릭터 반응 허용, 캐릭터 역할 유지, S0~S3와 확정 도움 이력 보존을 따른다. 시도 상한·공유 60초·독립 검수·SDK 재시도 0은 유지한다.

## 서버가 검증하는 부분

1. 캐릭터의 `character_response_contract`에 `question_required=false`, `learner_quiz_allowed=false`를 보낸다. 내부 유형 `GUIDING_QUESTION`이라는 이름이 질문 필수를 뜻하지 않는다.
2. 캐릭터 요청별 native JSON Schema의 실패 코드에서 `MISSING_QUESTION`을 제외한다. 서버도 이 코드가 반환되면 같은 후보를 검수하는 `CHARACTER_QUESTION_CONFLICT`로 처리한다. 잘못된 검수를 PASS로 바꾸거나 후보를 즉시 재생성하지 않는다. 안내자 요청의 질문 규칙은 유지하며 요청별 스키마 사본을 사용해 서로 오염되지 않게 한다.
3. 독립 검수에서 `character_interaction_review={mode, quote}`를 받는다. mode는 `CHARACTER_REACTION`, `CHARACTER_QUESTION`, `LEARNER_QUIZ`, `ROLE_REVERSAL`이다. quote는 실제 후보 text의 연속 인용이어야 한다. 아동 원문·없는 대사를 인용하면 거절한다.
4. `LEARNER_QUIZ`·`ROLE_REVERSAL`로 검수했다면 `CHARACTER_BREAK`와 `conversation_coherent=false`가 함께 있어야 한다. 이 분류를 반환하면서 PASS하는 결과는 서버가 거절한다. 안전·입력 의미 확인 우선 경로는 이 검수를 null로 생략할 수 있다. 안내자 경로는 null이다.
5. 캐릭터 반응·자연스러운 질문으로 분류해도 단서·답변 가능성·지원·안전 등 기존 검사는 모두 유지한다. 단서 부족은 `ANALYSIS_RESPONSE_MISMATCH`, 이어 말하기 어려움은 `NOT_ANSWERABLE`로 거절할 수 있다. 질문 부재를 허용한다는 이유로 모든 반응을 승인하지 않는다.
6. 캐릭터 생성 요청은 `retry.instruction_scope=WITHIN_CHARACTER_CONTRACT`를 보낸다. 재생성 자료의 자유 텍스트가 서버 계약·역할·지원을 덮어쓰지 않도록 생성과 검수 지침을 맞춘다. 이미 금지한 `MISSING_QUESTION`을 담은 GenerationRetry가 생성 팩터리에 직접 들어오면 외부 호출 전에 거절한다.

발화 방식의 의미 분류와 자연어 수정 지시의 모든 충돌을 서버가 판별하는 것은 아니다. 모델이 교사식 문답을 자연스러운 질문으로 잘못 분류하면 그 의미 오류가 남을 수 있다. 단순 물음표·특정 한국어 표현 정규식으로 모든 질문을 금지하지 않는다. 이 경계는 실제 모델의 고정 후보 검수로 추가 확인해야 한다.

## 계약·버전

- 제공자 검수 스키마: `evaluation-output/v8`.
- 기본 축소 캐릭터 생성·검수 프롬프트: `roleplay-response-generation-character/v5`, `roleplay-response-evaluation-character/v12`.
- 기존 형태 캐릭터 생성·검수 프롬프트: `roleplay-response-generation-character/v4`, `roleplay-response-evaluation-character/v11`.
- 분석 프롬프트·모델 출력 계약은 그대로다. 내부 `evaluation-result/v2`와 HTTP·DB 구조는 유지한다. 프론트엔드 변경은 필요 없다.
- 새 검수 필드는 제공자 진단 원문에서 확인한다. 공개 응답·저장용 EvaluationResult에 노출하지 않는다.

## 고정 회귀 검사와 한계

실제 P1 분석·세 후보·첫 검수를 `src/test/resources/eval/character-interaction-p1-observed.json`에 고정했다. 이 자료는 합성 P1 실행 결과이며 키·접속 주소를 포함하지 않는다. 테스트는 승인 기준·원문이 같은 P1 입력을 사용한다.

- 실제 첫 검수의 `MISSING_QUESTION` 거절은 무효다.
- 독립 검수가 승인한 자연스러운 반응·캐릭터 질문은 전달 가능하지만 안전 등 다른 거절은 우회할 수 없다.
- 질문 없는 단서 부족 대사도 유효한 검수가 거절하면 재생성한다.
- 실제 최종 대사를 LEARNER_QUIZ로 검수했다면 PASS로 숨길 수 없으며, 없는 인용도 거절한다.
- 정책 충돌은 같은 후보 재검수로 이어진다. 재검수에서 유효한 단서 부족 거절을 받으면 그 거절을 유지하고 새로운 후보를 생성한다. 재생성 요청에는 실패 코드와 정책 범위가 전달된다.
- 계속 잘못된 검수를 반환하면 기존 동일 후보 검수 총 3회 상한에서 복구 경로로 종료한다. 새 후보나 재분석으로 예산을 우회하지 않는다.
- 캐릭터용 스키마를 만든 뒤에도 안내자 스키마의 질문 규칙이 유지되며 안전 우선 경로는 보존된다.

합성 제공자는 위 분류를 미리 지정한다. 이 검사는 서버가 잘못된 판정을 그대로 소비하거나 분류와 반대되는 PASS를 허용하지 않는다는 증거다. 실제 모델이 대사의 의미를 정확하게 분류하거나 응답 시간이 개선됐다는 증거는 아니다.

## 검증 상태와 다음 측정

- 최종 `./gradlew --no-daemon spotlessApply build` 통과(3분 2초). JUnit XML 합계 2,910개: 통과 2,904, 실패·오류 0, 실행 조건이 필요한 6개 제외.
- 새 P1 계약 회귀 검사 9개 전부 통과. 관련 검사와 HTTP/목표 관찰 통합 검사도 통과했다.
- 최초 전체 검사에서 과거 진단 실행기용 가짜 응답의 새 필수 검수 누락과 버전 기대값이 발견됐다. 운영 검증을 완화하지 않고 가짜 응답·메타데이터 기대값을 보완한 뒤 해당 진단 검사와 최종 전체 검사를 통과했다.
- 마이그레이션 가드(기존 최대 버전 43), 공개 금지 파일 검사, diff 공백 검사 통과. 소스·자료는 백엔드 범위다.
- 최종 로그는 로컬 `build/reports/character-interaction-build.log`, 집계는 `build/reports/character-interaction-validation/summary.json`에 보존한다. 두 경로는 Git 추적 대상이 아니다.

추가 실제 AI 호출은 수행하지 않았다.

다음 실측에서는 같은 `.env.local`과 P1로 축소 형태 한 턴을 먼저 확인한다. 신규 검수 계약의 수용, 첫 판단, 동일 후보 재검수·후보 재생성 횟수, 단계별 시간·토큰, 최종 대사의 역할을 함께 읽는다. 이전 단건과 차이가 있어도 안정적인 개선으로 확정하지 않는다. 실제 Dev 실패 입력 `도와줄까`와 잘못된 고정 후보의 거절은 별도 검증 항목이다.

## Spring AI 문서와 적용 범위

[Provider-Native Structured Output](https://docs.spring.io/spring-ai/reference/api/structured-output/native.html)은 API 수준 스키마 제약과 모델별 지원 차이를 설명한다. [Schema Validation & Self-Correction](https://docs.spring.io/spring-ai/reference/api/structured-output/validation.html)은 응답 검증과 오류를 전달하는 제한된 재시도를 설명한다.

공식 문서는 Spring AI 2.0.2, 프로젝트는 2.0.1 기준으로 확인했다. 기존 요청별 native 스키마·수신 검증·오케스트레이터의 재시도를 재사용했다. 새 Advisor나 중첩 자동 재시도, 라이브러리 업그레이드는 추가하지 않았다. 스키마와 일관성 검증이 모델의 교육적 의미 판단을 대신한다고 해석하지 않는다.
