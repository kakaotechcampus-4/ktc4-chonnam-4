# AI 제공자 공통 호출 계약

## 목적

느링고의 애플리케이션 로직이 특정 LLM SDK에 직접 의존하지 않도록 공통 호출 경계를 정의한다. 현재 인프라 구현은 Spring AI의 OpenAI 호환 ChatModel을 사용하며, 실제 제공자 대신 MockServer로 HTTP 계약을 검증한다.

## 호출 계층

```text
역할극 애플리케이션
  → StructuredLlmExecutor
  → LlmProvider
  → SpringAiLlmProvider
  → OpenAI 호환 HTTP API
```

- `LlmProvider`는 제공자 중립 포트다.
- `SpringAiLlmProvider`만 Spring AI 타입을 사용한다.
- 제공자 계층은 생성 문자열을 `LlmCompletion`으로 반환한다.
- `StructuredLlmExecutor`가 호출 목적에 맞는 출력 객체로 변환한다.

## 호출 목적

`AiOperation`은 다음 호출을 구분한다.

- `INITIAL_DIFFICULTY_DECISION`
- `SCENARIO_GENERATION`
- `CAUSE_ANALYSIS`
- `RESPONSE_GENERATION`
- `RESPONSE_EVALUATION`
- `NEXT_DIFFICULTY_DECISION`

현재 구조화 출력 계약은 역할극의 원인 분석, 후보 응답 생성, 후보 평가를 우선 제공한다.

## 추적 문맥

`AiTraceContext`는 `requestId`, `activityId`, `classId`, `childId`, `scenarioId`, `scenarioVersion`, `sessionId`, `turnId`, `analysisId`, `candidateId`를 UUID 기반으로 연결한다. 호출 시점에 아직 만들어지지 않은 식별자는 비어 있을 수 있다.

추적 문맥은 내부 연결과 운영 지표를 위한 값이며 프롬프트에 자동으로 추가하지 않는다.

`AiAttemptContext`는 원인 분석, 후보 생성, 후보 평가 시도 횟수를 각각 관리한다. 실제 재시도 실행과 횟수 증가는 후속 오케스트레이션 계층의 책임이다.

## 출력 계약

- `AnalysisResult`: 학습 상태, 대표 부족 원인, 다음 전략, 분석 신뢰도
- `CandidateResponse`: 응답 문장, 사용 전략, 목표, 지원 수준, 이전 실패 후보
- `EvaluationResult`: 전달 안전성, 판정, 재시도 위치, 실패 코드, 수정 지시

후보는 다음 조건을 모두 만족해야 전달 가능하다.

```text
safeToSend == true
AND decision == PASS
AND criticalFailureCount == 0
```

이번 계약은 전달 가능 여부만 계산한다. 실제 아동 전달, 상태 갱신, DB 저장은 수행하지 않는다.

## 실패 분류

| 실패 유형 | 의미 | 재시도 가능 |
| --- | --- | --- |
| `TIMEOUT` | 호출 제한 시간 초과 | 예 |
| `NETWORK_ERROR` | 연결 실패 | 예 |
| `RATE_LIMITED` | HTTP 429 | 예 |
| `AUTHENTICATION_ERROR` | HTTP 401·403 | 아니요 |
| `PROVIDER_UNAVAILABLE` | HTTP 5xx | 예 |
| `PROVIDER_RESPONSE_ERROR` | 제공자 응답 본문을 해석할 수 없음 | 예 |
| `EMPTY_OUTPUT` | LLM 생성 결과가 비어 있음 | 예 |
| `INVALID_OUTPUT_FORMAT` | 생성 결과가 느링고 출력 계약과 맞지 않음 | 예 |
| `UNKNOWN` | OpenAI SDK 계열이지만 세부 유형을 분류할 수 없는 오류 | 아니요 |

Spring AI와 OpenAI SDK의 자동 재시도는 0회로 설정한다. 동일 요청 재호출 횟수와 복구 경로는 느링고 오케스트레이터가 관리한다.

제공자 오류는 구체 예외 타입, HTTP 상태 코드, Spring·JDK 네트워크 예외 타입, 예외 클래스명 fallback 순서로 분류한다. 알려진 제공자 오류와 이름 fallback은 실패 결과로 반환하지만, 어느 규칙으로도 제공자 오류라고 판단할 수 없는 런타임 예외는 내부 결함을 숨기지 않도록 호출자에게 다시 전파한다.

## 환경 설정

AI는 기본적으로 비활성화된다.

| 환경변수 | 용도 | 기본값 |
| --- | --- | --- |
| `AI_CHAT_PROVIDER` | Spring AI chat provider 활성화 | `none` |
| `AI_PROVIDER_NAME` | 운영 지표에 기록할 논리 제공자명 | `openai-compatible` |
| `AI_BASE_URL` | OpenAI 호환 API 주소 | `https://api.openai.com` |
| `AI_API_KEY` | 제공자 API 키 | `not-configured` |
| `AI_MODEL` | 모델 ID | `not-configured` |
| `AI_TIMEOUT` | 한 번의 요청 제한 시간 | `10s` |
| `AI_MAX_RETRIES` | SDK 내부 자동 재시도 | `0` |

실제 제공자를 사용할 때만 `AI_CHAT_PROVIDER=openai`로 설정한다.

## 개인정보와 로그 제한

- 원본 음성과 STT 원문을 `LlmRequest`에 넣지 않는다.
- 입력 처리와 안전 처리를 통과한 표준 발화만 프롬프트 입력으로 사용한다.
- 아동·강사의 실명, 이메일, 접근 코드를 프롬프트에 넣지 않는다.
- 전체 프롬프트와 completion을 운영 로그에 남기지 않는다.
- 제공자 오류 본문과 예외 메시지를 실패 결과에 포함하지 않는다.
- 운영 지표에는 내부 식별자, 호출 단계, 모델, 소요 시간, 토큰 수, 실패 코드만 사용한다.

이 계층은 음성, 발화, 위험 사건 또는 AI 결과를 DB에 저장하지 않는다.

## ERD 연결

후속 저장 Task에서는 다음 매핑을 사용한다.

| 계약 | 저장 대상 |
| --- | --- |
| `AnalysisResult` | `TURN_ANALYSIS.result_json` |
| 분석 시도 | `TURN_ANALYSIS.analysis_no` |
| `CandidateResponse` | `RESPONSE_CANDIDATE` |
| 생성 시도 | `RESPONSE_CANDIDATE.candidate_no` |
| `EvaluationResult` | `CANDIDATE_EVALUATION` |
| 평가 시도 | `CANDIDATE_EVALUATION.attempt_no` |
| 승인된 후보 | `CONVERSATION_TURN.delivered_candidate_id` |
| 시스템 재시도 합계 | `CONVERSATION_TURN.retry_count` |

현재 Task에는 Entity, Repository, Flyway Migration이 포함되지 않는다.

## 테스트

```bash
./gradlew test
```

MockServer 통합 테스트는 실제 외부 AI를 호출하지 않고 다음을 검증한다.

- OpenAI 호환 정상 응답과 token metadata
- 응답 지연에 대한 `TIMEOUT`
- HTTP 429에 대한 `RATE_LIMITED`
- HTTP 503에 대한 `PROVIDER_UNAVAILABLE`
- 손상된 제공자 응답에 대한 `PROVIDER_RESPONSE_ERROR`
- SDK 자동 재시도 없이 HTTP 요청이 정확히 한 번 발생하는지

구조화 출력 단위 테스트는 분석·후보 생성·평가 결과의 필수 필드와 전달 가능 조건을 검증한다.
