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
- `SPEECH_TRANSCRIPTION` (STT, `LlmRequest` 로는 보낼 수 없음)
- `SPEECH_SYNTHESIS` (TTS, `LlmRequest` 로는 보낼 수 없음)

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
| `TIMEOUT` | 호출 제한 시간 초과, HTTP 408 | 예 |
| `NETWORK_ERROR` | 연결 실패 | 예 |
| `RATE_LIMITED` | HTTP 429 | 예 |
| `AUTHENTICATION_ERROR` | HTTP 401·403 | 아니요 |
| `PROVIDER_UNAVAILABLE` | HTTP 5xx | 예 |
| `PROVIDER_REQUEST_REJECTED` | 제공자가 요청을 거부함 (HTTP 400·404·422 등 401·403·408·429를 제외한 4xx) | 아니요 |
| `PROVIDER_RESPONSE_ERROR` | 제공자 응답 본문을 해석할 수 없음 | 예 |
| `EMPTY_OUTPUT` | LLM 생성 결과가 비어 있음 | 예 |
| `INVALID_OUTPUT_FORMAT` | 생성 결과가 느링고 출력 계약과 맞지 않음 | 예 |
| `UNKNOWN` | OpenAI SDK 계열이지만 세부 유형을 분류할 수 없는 오류 | 아니요 |

재시도 가능 여부는 `AiFailureType`이 정하며 `AiFailure.retryable()`은 실패 유형의 값을 따른다. 이 표는 `AiFailureTypeTest`가 enum과 대조한다.

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

## 음성 입출력 (STT·TTS)

LLM 과 같은 방식으로 제공자 중립 포트를 두고, 결과는 `AiCallResult` 로 돌려준다. 실패 분류(`AiFailureType`)와 자동 재시도 0회 원칙도 같다.

```text
역할극 애플리케이션
  → SpeechToTextProvider  → OpenAiSpeechToTextProvider   → POST {STT_BASE_URL}/v1/audio/transcriptions
  → TextToSpeechProvider  → TypecastTextToSpeechProvider → POST {TTS_BASE_URL}/v1/text-to-speech
```

| 구분 | 제공자 | 기본 모델 | 입력 | 출력 |
| --- | --- | --- | --- | --- |
| STT | OpenAI 호환 음성 전사 API | `gpt-4o-mini-transcribe` | `SpeechTranscriptionRequest` (webm·ogg·wav·mp3·m4a, 최대 25MB) | `SpeechTranscription` (전사문, 신뢰도, 토큰) |
| TTS | 타입캐스트 | `ssfm-v30` | `SpeechSynthesisRequest` (최대 2000자, 목소리, 감정) | `SynthesizedSpeech` (mp3 또는 wav) |

### STT 결과 해석

- 무음이면 제공자는 빈 전사문을 돌려준다. 이는 제공자 실패가 아니라 `Success` 이며 `hasSpeech() == false` 다. 무음·저신뢰 판정과 재입력 안내는 RPL 입력 처리가 맡는다.
- `confidence` 는 토큰 logprob 평균의 지수(0~1)다. `STT_INCLUDE_LOGPROBS=false` 이거나 모델이 logprob 을 주지 않으면 비어 있다. 저신뢰 기준(0.40 이하)은 이 값에 적용하되, 실제 녹음으로 보정하기 전까지는 임시 기준이다.
- 응답 본문에 `text` 가 없거나 JSON 이 아니면 `PROVIDER_RESPONSE_ERROR` 다.
- STT 전사문은 입력 처리·안전 처리 전의 원문이므로 그대로 `LlmRequest` 에 넣지 않는다.

### TTS 결과 해석

- 응답 본문이 음성 바이너리다. 비어 있으면 `EMPTY_OUTPUT` 이다.
- 형식은 응답 `Content-Type` 을 따르고, 알 수 없으면 설정한 `TTS_AUDIO_FORMAT` 으로 본다.
- 402(크레딧 부족)·422(검증 오류)는 `PROVIDER_REQUEST_REJECTED`(재시도 안 함)다.
- `ssfm-v30` 은 `prompt.emotion_type=preset` 을 함께 보낸다. `ssfm-v21` 은 이 필드를 보내지 않는다.
- 검증을 통과해 전달이 확정된 문장(`EvaluationResult.canDeliver()`)과 고정 안내 문구만 TTS 로 보낸다.

### 개인정보

- 원본 음성은 STT 요청에만 쓰고 저장·로그에 남기지 않는다. 요청·결과 객체의 `toString` 은 음성·전사문·문장을 포함하지 않는다.
- 제공자 오류 본문은 실패 결과에 넣지 않는다(`providerErrorCode` 는 예외 타입 이름).

### 환경 설정

STT·TTS 는 LLM(`AI_CHAT_PROVIDER`)과 따로 켜며 기본값은 꺼짐이다. 켰는데 키나 목소리가 없으면 애플리케이션이 시작되지 않는다.

| 환경변수 | 용도 | 기본값 |
| --- | --- | --- |
| `STT_PROVIDER` | `openai` 로 켬 | `none` |
| `STT_PROVIDER_NAME` | 운영 지표의 제공자명 | `openai` |
| `STT_BASE_URL` | OpenAI 호환 API 주소 | `https://api.openai.com` |
| `STT_API_KEY` | API 키 (켜면 필수) | 없음 |
| `STT_MODEL` | 전사 모델 | `gpt-4o-mini-transcribe` |
| `STT_LANGUAGE` | ISO-639-1 언어 | `ko` |
| `STT_TIMEOUT` | 요청 제한 시간 | `15s` |
| `STT_INCLUDE_LOGPROBS` | logprob 요청(신뢰도 계산) | `true` |
| `TTS_PROVIDER` | `typecast` 로 켬 | `none` |
| `TTS_PROVIDER_NAME` | 운영 지표의 제공자명 | `typecast` |
| `TTS_BASE_URL` | 타입캐스트 API 주소 | `https://api.typecast.ai` |
| `TTS_API_KEY` | API 키 (켜면 필수) | 없음 |
| `TTS_MODEL` | 음성 모델 | `ssfm-v30` |
| `TTS_VOICE_ID` | 기본 목소리 ID (켜면 필수) | 없음 |
| `TTS_LANGUAGE` | ISO-639-3 언어 | `kor` |
| `TTS_AUDIO_FORMAT` | `mp3` 또는 `wav` | `mp3` |
| `TTS_TIMEOUT` | 요청 제한 시간 | `15s` |

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
- HTTP 400에 대한 `PROVIDER_REQUEST_REJECTED`
- 손상된 제공자 응답에 대한 `PROVIDER_RESPONSE_ERROR`
- SDK 자동 재시도 없이 HTTP 요청이 정확히 한 번 발생하는지

구조화 출력 단위 테스트는 분석·후보 생성·평가 결과의 필수 필드와 전달 가능 조건을 검증한다.

STT·TTS 어댑터 테스트(`OpenAiSpeechToTextProviderTest`, `TypecastTextToSpeechProviderTest`)는 Docker 없이 JDK 내장 HTTP 서버로 실제 HTTP 요청을 검증한다: 요청 형식(multipart·JSON·인증 헤더), 정상 응답 변환, HTTP 오류 분류, 제한 시간, 연결 실패, 손상·빈 응답, 재시도 없이 한 번만 호출하는지.
