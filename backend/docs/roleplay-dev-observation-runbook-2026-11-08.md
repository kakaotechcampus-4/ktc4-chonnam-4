# AWS Dev 서버에서 직접 입력하며 관측하기

이 절차는 팀원이 테스트 계정으로 직접 입력하는 짧은 실험용이다.
로컬 자동 검사는 기록 기능과 오류 차단을 확인하고, 실제 모델의 판단·지연은 배포 후 이 절차로 확인한다.
현재 서버 설정 변경·push·PR·배포·실제 화면 입력은 수행하지 않았다.

## 1. 검증한 코드를 서버에 올릴 준비

1. 목적별 로컬 커밋과 검사 결과를 확인하고 사용자에게 fork push 승인을 받는다.
2. fork의 `refactor/evaluation-output-retry`를 push하고 팀 저장소 `develop` 대상 PR을 준비한다.
3. 팀 CI·리뷰를 통과하고 머지한 뒤 Deploy 결과를 확인한다. fork push만으로 배포되지 않는다.
   fork PR은 미리보기 배포도 지원하지 않는다. 현재 서버의 24시간 모드에서는 팀 내부 PR도 자동 미리보기가 생기지 않는다.
4. Actions 실행의 배포 커밋과 화면의 커밋 표시를 기록한다. 기존 서버가 떠 있다는 이유만으로 새 코드라고 판단하지 않는다.

최종 로컬 검사의 기준은 공식 develop `d6f7e06`을 반영한 작업본이다.
앞서 실제 AI에 실행한 고정 반례 두 건은 동기화 전 `9ffa124` 기반 미커밋 작업본의 결과이며, 새 서버 입력 결과와 구분한다.

## 2. 담당자가 Parameter Store 설정 확인

서버는 로컬 `.env.local`을 읽지 않는다. AWS Systems Manager → Parameter Store의
`/neuringo/dev/` 아래 설정을 `deploy/host/params.txt` 목록에 따라 배포 때 전달한다.
아래 이름은 이 접두사를 제외한 상대 경로다. 키·주소의 실제 값은 PR이나 공개 이슈에 붙이지 않는다.

| 상대 경로 | 이번 실험 설정/확인 |
| --- | --- |
| `ai/model` | `gpt-6-luna` 등 이번에 측정할 실제 API 모델 ID |
| `ai/reasoning-effort` | 비교 조건이 동일하면 `low` |
| `ai/timeout` | 기존 로컬 비교 조건 `30s` |
| `ai/max-retries` | `0`: 재시도는 기존 역할극 루프가 담당 |
| `ai/max-output-tokens` | 기존 값을 확인·기록. 이번 실험 때문에 임의로 새 상한을 설정하지 않음 |
| `ai/call-log` | `true` |
| `ai/diagnostics/enabled` | 제한된 실험 동안만 `true` |
| `ai/diagnostics/window` | `PT15M`: ISO 8601 형식, 앱의 진단 객체 시작부터 15분 |
| `ai/diagnostics/max-requests` | `10`: 대상 HTTP 입력 요청 수 |
| `ai/diagnostics/max-bytes` | `20971520`: 이전 파일 포함 총 20MiB |
| `ai/diagnostics/max-event-bytes` | `1048576`: 한 사건 최대 1MiB |

기존 `ai/base-url`·`ai/api-key`·`ai/chat-provider`가 실제 제공자에 맞고,
`ai/chat-provider=openai`, `roleplay/http/enabled=true`, `roleplay/text/enabled=true`인지 함께 확인한다.
실제 컨테이너의 활성 프로필은 `dev`여야 한다. `prod`를 함께 켜거나 호출 로그 없이 원문 진단을 켜면 기동 시 거절된다.
다른 목적별 모델 route가 있다면 기본 모델과 구분해 기록한다.

설정을 바꾼 뒤에는 승인된 배포 절차로 새 컨테이너를 띄운다.
Parameter Store만 바꿔도 실행 중 앱에 즉시 반영되는 것은 아니다.
같은 커밋 배포가 건너뛰어졌다면 담당자가 기존 배포 절차의 설정 재적용 방법을 확인한다.
부팅 시간도 수집 시간 창에 포함되므로, 관측할 사람이 준비된 뒤 시작한다.

## 3. 서버 설정과 화면 확인

AWS 콘솔 → EC2 → 연결 → Session Manager에서 아래 명령으로 상태와 최근 로그를 확인한다.
서버 환경 파일 내용을 `cat`하지 않는다.

```bash
sudo docker compose --env-file /opt/neuringo/state/dev.env -f /opt/neuringo/current/deploy/compose.dev.yml -p neuringo-dev ps
sudo docker compose --env-file /opt/neuringo/state/dev.env -f /opt/neuringo/current/deploy/compose.dev.yml -p neuringo-dev logs --since 15m backend
```

`AI_SETTINGS`에서 모델·추론 설정·timeout·출력 상한·재시도를 확인한다.
이 로그의 `sdk_retries`는 앱 제공자 설정이며, 역할극 기동 시 Spring AI 클라이언트 재시도도 0인지 별도로 검사한다.
시작 실패는 배포 결과와 비공개 앱 로그로 확인한다. `/actuator/health` 공개 설정은 변경하지 않는다.

## 4. 먼저 텍스트 입력 한 턴

1. 팀 테스트 계정으로 로그인하고 시나리오 진입 → 역할극 시작부터 진행한다.
   로컬 P1의 고정 목표·이력을 그대로 쓰는 테스트와 실제 세션 문맥은 다르다.
2. 브라우저 개발자 도구 → Network를 열고 실제 입력에 따른 `text-inputs` 요청을 찾는다.
3. 시나리오의 사건을 설명하는 문장을 직접 입력한다. 동시에 여러 요청을 보내지 않는다.
4. 응답의 `X-Trace-Id`와 본문의 `meta.traceId`를 확인한다. 같은 서버 생성 UUID여야 한다.
5. 화면 대사·최종 상태·소요 시간과 이 UUID를 접근이 제한된 실험 메모에 남긴다.

이번 기능은 API 응답에 원문을 추가하지 않는다. Network 헤더만으로 요청을 찾을 수 있어 프론트엔드 변경은 필수가 아니다.
추후 화면 안에 진단 ID를 표시하는 기능이 필요하면 프론트엔드 후속 과제로 정리한다.

## 5. 해당 요청의 원문 파일 읽기

다음 변수에 실제 Network 응답에서 확인한 UUID를 넣는다. 다른 요청의 폴더를 일괄 출력하지 않는다.

```bash
TRACE_ID='Network에서-확인한-서버-UUID'
sudo docker compose --env-file /opt/neuringo/state/dev.env -f /opt/neuringo/current/deploy/compose.dev.yml -p neuringo-dev exec -T backend cat "/tmp/neuringo-diagnostics/${TRACE_ID}/diagnostics.jsonl"
```

한 줄이 한 사건이다. 같은 `request_id`와 `sequence` 순서로 읽는다.

| 사건/로그 | 볼 내용과 해석 |
| --- | --- |
| `MODEL_REQUEST` | 단계·시도 번호·프롬프트/스키마 버전·실제 모델 메시지·요청한 구조 제약 |
| `MODEL_RESPONSE` | 파싱 전 출력 문자열·모델·시간·토큰·종료 이유. 잘못된 JSON도 보존 |
| `VALIDATION` | 서버 검증 결과와 이유. 유효한 평가에는 `decision`·`retry_target`·`failure_codes`도 기록 |
| `HTTP_RESULT` | HTTP 상태·완료 여부·역할극 최종 상태·checkpoint·replayed·요청 시간 |
| `LLM_CALL outcome=OK` | 제공자 호출 성공. 서버 검사나 검수 PASS까지 뜻하지 않음 |
| `LLM_VALIDATION outcome=VALID` | 서버가 출력을 유효하게 해석함. 유효한 REGENERATE/REANALYZE도 포함 |
| `ROLEPLAY_REQUEST` | 같은 UUID의 최종 기술 결과. HTTP 200만 보지 않고 `completion`·`result`도 확인 |
| `ROLEPLAY_STAGE` | 같은 요청의 구간별 시작 위치·포함 시간·자체 시간·완료 여부 |
| `ROLEPLAY_TIMING` | 요청 전체와 구간별 시간 집계·AI 호출 수·반복 호출·응답 시 미완료 구간 요약 |

원문 입력은 개인정보 마스킹 등 기존 처리를 거친 **실제 모델 입력**이다.
마스킹 전 사용자 입력·음성 파일·모델의 비공개 내부 추론은 수집하지 않는다.
제공자 실패에는 분류·메타데이터만 남기므로 원시 HTTP 오류 본문이 없는 것은 정상이다.

시간 단위도 구분한다. `capture_elapsed_ms`는 진단 수집 시작 이후의 위치이며 요청 시간이나 AI 응답 시간이 아니다.
호출 시간은 응답 메타데이터의 `elapsedMs`, 전체 HTTP 시간은 `HTTP_RESULT.data.elapsed_ms` 또는 `ROLEPLAY_REQUEST ms`다.
원문 파일 쓰기 자체의 부하도 있으므로 진단 활성 여부를 비교 조건에 남긴다.

### TTFB가 18.8초에서 35.6초로 늘어난 경우

두 Network 요청의 X-Trace-Id를 각각 기록하고 같은 ID의 ROLEPLAY_TIMING/ROLEPLAY_STAGE를 비교한다.
구간별 기술 시간은 ai/call-log=true이면 원문 진단을 끈 상태에서도 기록된다.
설정 적용·배포 이전 요청에 대해 새 구간별 시간을 소급해 얻을 수는 없다.

| 구간/필드 | 확인할 질문 |
| --- | --- |
| REQUEST_PREPARATION / UPLOAD_VALIDATION | 세션 조회·보존 게이트·음성 업로드 검사에서 지연됐는가? |
| WORKER_QUEUE | 제한된 worker에 들어가기 전 기다린 시간이 늘었는가? |
| TURN_PREPARATION / REQUEST_CLAIM / CHECKPOINT_LOOKUP | 입력 준비·처리권 획득·기존 처리 결과 조회에서 지연됐는가? |
| CONTEXT_PREPARATION | 대화·학습 문맥과 진행 상태 조회가 느린가? |
| INPUT_PROCESSING | 입력 판정/전처리에 걸린 시간은 얼마인가? 내부 AI 호출도 포함하므로 자체 시간을 함께 봄 |
| STT / TTS | 음성 인식 또는 응답 음성 생성이 느린가? 각 시도별로 기록 |
| LLM_CALL | operation별 입력 판정·분석·생성·검수 중 무엇이 느린가? |
| OUTPUT_VALIDATION | 모델 응답 후 서버 파싱·도메인 검증이 느린가? |
| RESULT_COMMIT | 결과 저장 호출 경계가 느린가? 개별 SQL/DB 연결 대기 시간과는 구분 |
| llm_calls_by_operation / repeated_llm_calls | 호출 자체가 느려졌는가, 같은 목적의 호출이 늘었는가? |

각 STAGE의 start_ms는 이 요청 측정 시작 후 위치이고 duration_ms는 해당 구간의 포함 시간이다.
구간 기록은 응답 직전에 모아서 출력하므로 파일에 추가된 행 순서만으로 실행 순서를 추정하지 않는다.
start_ms·span_id·parent_span_id로 읽는다. 밀리초 미만 시간은 버림 처리돼 0ms로 표시될 수 있다.
self_ms는 직계 자식 구간이 차지한 시간의 합집합을 제외한 시간이다.
HTTP_REQUEST·WORKER_EXECUTION·INPUT_PROCESSING 같은 부모와 내부 LLM_CALL을 더하면 중복 집계된다.
inclusive_ms_by_stage와 self_ms_by_stage는 같은 구간 종류의 요청 내 집계다.
동시에 실행된 구간은 자체 시간 집계도 겹칠 수 있으므로 모든 값을 더해 HTTP 시간과 같다고 가정하지 않는다.
unclassified_ms는 HTTP 루트의 자체 시간이다. worker 내부의 아직 분류되지 않은 작업은 WORKER_EXECUTION의 자체 시간에도 남는다.
이 나머지를 전부 DB나 네트워크 지연이라고 부르지 않는다.

repeated_llm_calls는 각 목적의 첫 호출을 제외한 추가 호출 수다. 재시도/재분석 흐름은 operation·attempt·시작 위치·검증 결과를 함께 읽는다.
outcome=COMPLETED는 해당 함수가 반환됐다는 기술 의미다. 최종 전달·저장 성공은 ROLEPLAY_REQUEST의 result·checkpoint·replayed와 재조회로 확인한다.
큐 거절/시작 전 취소는 REJECTED_BEFORE_START·TIMED_OUT_BEFORE_START·CANCELLED_BEFORE_START 등으로 표시한다.
응답 시 실행 중인 구간은 IN_PROGRESS_AT_RESPONSE이며, 응답 후 종료하면 STAGE_LATE/after_response=true로 따로 기록한다.
늦게 끝난 구간의 전체 시간을 이미 보낸 응답의 대기 시간에 더하지 않는다.
truncated=true이면 요청당 256개 구간 한도에 도달한 불완전한 기록이다.

ROLEPLAY_TIMING은 요청 측정 종료 시점의 스냅샷이다. 이후 시간 로그/진단 파일을 쓰는 부하가 ROLEPLAY_REQUEST ms에 추가될 수 있다.
result=null이면 정상 역할극 결과가 기록되지 않은 요청이므로 HTTP 상태·completion·오류 응답을 함께 확인한다.
브라우저 TTFB와 앱 시간은 네트워크·앞단·측정 경계가 달라 반드시 일치하지 않는다.
외부 AI 호출 시간이 늘어도 제공자 내부 대기·네트워크·추론을 이 기록만으로 분리하지 못한다.
문맥/저장 구간이 느린 근거가 생기면 해당 쿼리·연결 풀 등으로 다음 조사 범위를 좁힌다.

## 6. 무엇을 결론 내릴 수 있나

먼저 입력 한 건을 끝까지 연결한 뒤 사건 파악·감정 표현·도움 요청·오해 입력을 소수씩 관측한다.
입력과 세션 상태를 함께 기록한다. 최종 정상 응답이 나와도 원문 분석·후보·평가가 서로 타당한지 직접 읽는다.
새로고침 후 대화 조회에서도 승인된 응답이 보이는지 확인해 저장 검증을 별도로 남긴다.

- 3단계가 각각 한 번씩 실행됐다면 정상 경로 지연을 본다. 입력 판정이 켜진 실제 API는 별도 AI 호출이 더 있을 수 있다.
- 호출 수가 늘었다면 어느 단계가 왜 거절돼 반복됐는지 `VALIDATION`과 평가 원문을 잇는다.
- 제공자 OK → 형식 거절이라면 연결 문제와 구분해 구조·스키마·도메인 검사를 조사한다.
- 유효한 평가인데 판단이 틀렸다면 스키마 통과와 의미 정확성을 구분한다.
- `replayed=true`면 이전 처리 결과의 재전달일 수 있어 새 AI 처리 시간 표본으로 합치지 않는다.
- 음성은 텍스트 경로를 확인한 뒤 따로 관측한다. STT/TTS는 구간별로 확인하고 텍스트 응답의 TTS도 구분한다.

한두 건으로 성공률·p95·운영 timeout을 확정하지 않는다. 지금의 완료 기준은 실제 입력의 실행 경로와 실패 원인을 추적할 수 있는가다.

## 7. 파일이 없거나 중간에서 끊긴 경우

`AI_DIAGNOSTICS state=STARTED request=...`와 `state=SKIPPED reason=...`를 확인한다.
`WINDOW_EXPIRED`, `REQUEST_LIMIT`, `TOTAL_LIMIT`, `EVENT_LIMIT`, `STORAGE_UNAVAILABLE`을 구분한다.
크기 초과 사건은 원문 대신 `CAPTURE_SKIPPED`만 남길 수 있다. 수집 도중 시간 창/총량이 끝나면 후속 사건이나 `HTTP_RESULT`가 없을 수 있다.
이것을 전체 원문 수집 성공 또는 AI 미호출로 집계하지 않는다. 호출 자체는 기존 처리 정책대로 진행된다.
인증·입력 검사에서 일찍 거절됐다면 모델 사건이 없는 것이 정상일 수 있다.
대상 경로는 역할극 `text-inputs`·`voice-inputs` POST뿐이며 리포트·조회·CLI 호출은 수집하지 않는다.

## 8. 문제 수정과 실험 종료

문제가 생기면 입력·세션 상태·배포 SHA·trace ID·실패 단계·관련 원문을 비공개로 보존하고,
합성 재현 → 최소 수정 설계/승인 → 관련 검사 → 별도 커밋 → 승인 후 push/배포 → 같은 조건 재확인 순서로 처리한다.
[네이티브 구조화 출력](https://docs.spring.io/spring-ai/reference/api/structured-output/native.html),
[검증과 재호출](https://docs.spring.io/spring-ai/reference/api/structured-output/validation.html),
[관측](https://docs.spring.io/spring-ai/reference/observability/index.html)을 실제 Spring AI 2.0.1 API·Elice 지원과 대조한다.
기존 재시도 루프에 Advisor 재호출을 중첩하지 않는다.

종료 시 `ai/diagnostics/enabled=false`로 바꾸고 승인된 배포로 적용한다.
시간 창 만료는 **수집 중지**이며 파일 자동 삭제가 아니다. 프로세스/컨테이너 재시작도 파일 삭제를 보장하지 않는다.
컨테이너 재생성 시 쓰기 계층은 사라지지만, 그 전에 명시적 삭제가 필요하면 담당자가 아래 고정 경로만 정리한다.
필요한 비공개 근거를 확인한 뒤 수행하며, 이 문서를 작성하는 과정에서는 삭제를 실행하지 않았다.

```bash
sudo docker compose --env-file /opt/neuringo/state/dev.env -f /opt/neuringo/current/deploy/compose.dev.yml -p neuringo-dev exec -T backend sh -c 'rm -rf -- /tmp/neuringo-diagnostics'
```

원문을 공개 PR·이슈·공용 로그에 통째로 붙이지 않는다. 실험 메모에는 다음을 남긴다.

```text
배포 SHA / 모델 / 추론 설정 / 원문 진단 활성 여부:
시나리오·세션 상태 / 테스트 입력:
trace ID / HTTP 상태 / 최종 결과 / 저장 재조회 결과:
단계별 호출 횟수·시간·토큰 / 반복된 이유:
사람이 확인한 판단·대사 문제 / 다음에 재현할 조건:
```
