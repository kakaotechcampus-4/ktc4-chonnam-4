# API-OPS-01~03 · 에이전트 워크플로우 실행 기록

> 구현 — 2026-11-10 / 브랜치 `feature/agent-workflow-dashboard`(develop `8c717ab` 위). 현재 코드의 HTTP 계약입니다. 서버에서 켜기 전에는 세 주소 모두 없는 주소(404)입니다.

## 1. Endpoint

| ID | Method | Path | 용도 | FE 함수 |
|---|---|---|---|---|
| API-OPS-01 | GET | `/api/v1/ops/agent-runs/overview?hours=&workflow=` | 기간 요약(대시보드 첫 화면) | getAgentRunOverview |
| API-OPS-02 | GET | `/api/v1/ops/agent-runs?hours=&workflow=&path=&limit=` | 최근 순 실행 목록 | listAgentRuns |
| API-OPS-03 | GET | `/api/v1/ops/agent-runs/{runId}` | 실행 한 건의 단계 흐름·시간 구간 | getAgentRun |

| 항목 | 값 |
|---|---|
| 인증 | 강사 Bearer. 그 가운데 `neuringo.agent-runs.operators` 에 있는 계정만. `neuringo.agent-runs.public-read=true` 면 로그인 없이 누구나 |
| 성공 | 200 |
| 화면 | `/ops/agent-runs`(강사 메뉴에는 없음) |
| 서버 | `backend/src/main/java/com/neuringo/neuringobe/ops/` |

## 2. 무엇을 기록하나

역할극 한 턴(`ROLEPLAY_TURN`), 학습 리포트 생성(`LEARNING_REPORT`), 시나리오 생성(`SCENARIO_GENERATION`)이 한 번 돌 때마다 실행 기록 한 건을 남깁니다.

- **단계(step)**: AI·음성 호출 하나(`CALL`), 코드가 내린 판정 하나(`CHECK`), 최종 결과(`RESULT`)입니다. 단계마다 판정 코드(`verdict`)와 사유 코드(`codes`)를 남깁니다. 판정이 지목한 다음 단계는 `declaredNext`, 실제로 이어진 다음 단계는 `ledTo`에 남습니다. 예를 들어 응답 검수가 `REGENERATE`를 내면 `ledTo`는 `RESPONSE_GENERATION`입니다.
- **흐름 종류(path)**: `NORMAL`은 거절·실패 없이 정상 결과로 끝난 실행입니다. `RETRIED`는 거절·실패 뒤 다시 돌아 정상 결과로 끝난 실행입니다. `GUIDED`는 입력 품질·안전 판정 같은 설계된 안내로 끝난 실행이고, `FAILED`는 호출 실패·재시도 한도·시간 초과·서버 오류로 끝난 실행입니다.
- **원인(endedBy·reason)**: `NORMAL`이 아니면 원인 단계와 그 판정·사유 코드를 남깁니다. `RETRIED`는 처음 거절된 단계, 그 밖에는 마지막으로 통과하지 못한 단계입니다.
- **시간 구간(spans)**: 역할극 턴은 `RoleplayTiming` 구간 트리(작업 대기·문맥 준비·AI 호출·출력 검사·음성 합성·결과 저장)를 함께 남깁니다.

남기지 않는 것: 발화, 프롬프트, 모델 출력 글, 안내 문구 글, 아동·세션·활동·학급 번호. 문자열은 코드 형식(영문·숫자·`_.:/@-` 64자 이내)만 저장하고, 그 밖의 값은 버리거나 `OTHER`로 바꿉니다. 역할극 턴의 `runId`는 그 요청의 응답 헤더 `X-Trace-Id`와 같아서, 브라우저 개발자 도구에서 본 번호로 바로 찾을 수 있습니다.

## 3. 요청

| 이름 | 위치 | 타입 | 기본 | 설명 |
|---|---|---|---|---|
| hours | query | number | 24 | 1~336. 지금부터 거꾸로 이 시간만큼 |
| workflow | query | string | 전체 | `ROLEPLAY_TURN`·`LEARNING_REPORT`·`SCENARIO_GENERATION` |
| path | query | string | 전체 | API-OPS-02 만. `NORMAL`·`RETRIED`·`GUIDED`·`FAILED` |
| outcome | query | string | 전체 | API-OPS-02 만. `SUCCESS`(NORMAL·RETRIED)·`FAILURE`(GUIDED·FAILED). path 와 같이 주면 path 가 그 묶음에 속해야 합니다 |
| limit | query | number | 50 | API-OPS-02 만. 1~200 |
| runId | path | UUID | — | API-OPS-03 만 |

## 4. 성공 응답

### API-OPS-01 요약

~~~json
{
  "data": {
    "window": { "from": "2026-11-09T06:31:00Z", "to": "2026-11-10T06:31:00Z", "hours": 24, "bucketMinutes": 60 },
    "totals": { "runs": 85, "normal": 61, "retried": 15, "guided": 8, "failed": 1, "p50Ms": 26200, "p95Ms": 48100, "aiCalls": 364, "inputTokens": 703000, "outputTokens": 85000 },
    "series": [{ "at": "2026-11-09T06:31:00Z", "normal": 5, "retried": 2, "guided": 1, "failed": 0, "p50Ms": 24800, "p95Ms": 41200 }],
    "workflows": [{ "workflow": "ROLEPLAY_TURN", "runs": 71, "normal": 51, "retried": 11, "guided": 8, "failed": 1, "p50Ms": 26100, "p95Ms": 39500 }],
    "steps": [{ "workflow": "ROLEPLAY_TURN", "step": "RESPONSE_EVALUATION", "calls": 82, "passed": 71, "rejected": 8, "failed": 3, "avgMs": 8100, "p95Ms": 10000, "inputTokens": 245000, "outputTokens": 20500 }],
    "transitions": [{ "workflow": "ROLEPLAY_TURN", "from": "RESPONSE_EVALUATION", "status": "REJECTED", "verdict": "REGENERATE", "to": "RESPONSE_GENERATION", "count": 8 }],
    "codes": [{ "workflow": "ROLEPLAY_TURN", "step": "RESPONSE_EVALUATION", "code": "ANSWER_TOO_DIRECT", "count": 8 }],
    "endings": [{ "workflow": "ROLEPLAY_TURN", "path": "GUIDED", "result": "REINPUT_REQUIRED", "endedBy": "SPEECH_TRANSCRIPTION", "reason": "LOW_CONFIDENCE", "count": 6, "recentRunIds": ["7c1f3a52-0d6b-4f0e-9a51-2b8f6f3c9d11"] }],
    "stages": [{ "stage": "LLM_CALL", "avgMs": 21400, "p95Ms": 30200 }],
    "flows": [{
      "workflow": "ROLEPLAY_TURN", "outcome": "SUCCESS", "path": "RETRIED", "result": "DELIVERED",
      "steps": [
        { "step": "RESPONSE_EVALUATION", "status": "REJECTED", "verdict": "REGENERATE" },
        { "step": "RESPONSE_GENERATION", "status": "PASSED", "verdict": "VALID" }
      ],
      "count": 9, "p50Ms": 33700,
      "recentRunIds": ["7c1f3a52-0d6b-4f0e-9a51-2b8f6f3c9d10"]
    }],
    "diagnosis": {
      "previousFrom": "2026-11-08T06:31:00Z", "previousTo": "2026-11-09T06:31:00Z", "previousRuns": 85,
      "failureRate": 0.2, "previousFailureRate": 0.21,
      "findings": [{
        "kind": "STEP_BLOCKED", "severity": "WARNING", "workflow": "ROLEPLAY_TURN",
        "step": "RESPONSE_EVALUATION", "code": "TIMEOUT", "current": 0.35, "previous": 0.35,
        "threshold": 0.2, "unit": "RATE", "sampleRunIds": ["7c1f3a52-0d6b-4f0e-9a51-2b8f6f3c9d12"]
      }]
    }
  },
  "meta": { "traceId": "90000000-0000-4000-8000-000000000001" }
}
~~~

- `series`는 실행이 없던 구간도 0 으로 채워 옵니다. 구간 길이(`bucketMinutes`)는 기간에 따라 5·15·60·180·360·720분입니다.
- `transitions`는 많이 일어난 순서로 최대 200개, `codes`·`endings`는 최대 30개입니다.
- `stages`는 역할극 턴의 구간별 자체 시간(안쪽 구간을 뺀 시간)입니다.
- `diagnosis`는 이번 기간과 바로 앞 같은 길이 기간(`previousFrom`~`previousTo`)을 비교한 자동 진단입니다. AI 를 부르지 않고 집계 값에 규칙을 적용합니다. 실행 20건 미만이면 판단하지 않고, 앞 기간이 20건 미만이면 비교하는 규칙(새 실패 흐름·원인 급증)은 건너뜁니다. `findings`는 심각한 순서로 최대 8개이고, 문장은 화면이 `kind`·`step`·`code`로 만듭니다.

  | kind | 걸리는 조건 | severity |
  |---|---|---|
  | `FAILURE_RATE` | 실패 비율 30% 이상 또는 앞 기간보다 5%p 이상 오름 | 50% 이상·15%p 이상이면 CRITICAL, 아니면 WARNING |
  | `LATENCY` | 역할극 턴 p95 가 턴 제한 시간(`roleplay.http.turn-budget`)의 80% 이상, 또는 앞 기간의 1.5배 이상이면서 제한 시간의 50% 이상 | 제한 시간 이상이면 CRITICAL |
  | `STEP_BLOCKED` | 호출 10번 이상인 단계의 거절·실패 비율 20% 이상. `code`는 가장 많은 판정 | 40% 이상이면 CRITICAL |
  | `NEW_FAILURE_FLOW` | 앞 기간에 없던 실패 흐름 패턴(최대 3개). `step`·`code`는 처음 통과하지 못한 단계와 판정 | 오류면 WARNING, 안내로 종료면 INFO |
  | `REASON_SURGE` | 같은 원인 단계·사유로 끝난 실행이 5건 이상이고 실행 대비 비율이 앞 기간의 2배 이상(앞 기간 0건 포함). `STEP_BLOCKED`가 짚은 것은 빼고 냅니다 | 실패 흐름이 섞이면 WARNING, 아니면 INFO |

- `flows`는 워크플로우·흐름·결과와 최종 결과 앞 단계들의 "단계·상태·판정" 순서가 같은 실행끼리 묶은 흐름 패턴입니다. 많이 일어난 순서로 최대 60개이고, `recentRunIds`는 그 흐름으로 끝난 최근 실행 번호 최대 3개입니다. `outcome`은 `SUCCESS`(정상 결과로 끝남) 또는 `FAILURE`입니다.

### API-OPS-02 목록

`data`는 API-OPS-03 과 같은 모양의 배열입니다. 목록에서는 `steps`·`spans`가 빈 배열이고 `stageMs`가 빈 객체입니다.

### API-OPS-03 상세

~~~json
{
  "data": {
    "runId": "7c1f3a52-0d6b-4f0e-9a51-2b8f6f3c9d10",
    "workflow": "ROLEPLAY_TURN",
    "path": "RETRIED",
    "result": "DELIVERED",
    "endedBy": "RESPONSE_EVALUATION",
    "reason": "REGENERATE",
    "inputType": "TEXT",
    "httpStatus": 200,
    "replayed": false,
    "startedAt": "2026-11-10T06:12:03Z",
    "durationMs": 34820,
    "aiCalls": 6,
    "repeatedCalls": 2,
    "inputTokens": 14800,
    "outputTokens": 1240,
    "truncated": false,
    "stageMs": { "LLM_CALL": 33900, "WORKER_QUEUE": 30 },
    "spans": [{ "id": 1, "parent": 0, "stage": "HTTP_REQUEST", "operation": null, "attempt": 0, "startMs": 0, "durationMs": 34820, "selfMs": 70, "outcome": "COMPLETED" }],
    "steps": [
      { "seq": 4, "kind": "CALL", "step": "RESPONSE_EVALUATION", "attempt": 1, "status": "REJECTED", "verdict": "REGENERATE", "codes": ["ANSWER_TOO_DIRECT"], "declaredNext": "RESPONSE_GENERATION", "ledTo": "RESPONSE_GENERATION", "atMs": 12840, "durationMs": 8900, "model": "gpt-5.4-nano", "promptVersion": "roleplay-response-evaluation/v3", "finishReason": "stop", "inputTokens": 3100, "outputTokens": 260 },
      { "seq": 8, "kind": "RESULT", "step": "RESULT", "attempt": 0, "status": "PASSED", "verdict": "DELIVERED", "codes": [], "declaredNext": null, "ledTo": null, "atMs": 34820, "durationMs": 0, "model": null, "promptVersion": null, "finishReason": null, "inputTokens": null, "outputTokens": null }
    ]
  },
  "meta": { "traceId": "90000000-0000-4000-8000-000000000002" }
}
~~~

`attempt`는 이 실행 안에서 같은 단계가 몇 번째로 돌았는지입니다. 코드 판정(`CHECK`)은 리포트 바퀴 번호이고, 없으면 0 입니다.

## 5. 오류·FE 처리

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_AGENT_RUN_FILTER | hours·workflow·path 값이 범위 밖 |
| 400 | INVALID_REQUEST | runId 가 UUID 가 아님 |
| 401 | AUTHENTICATION_REQUIRED·INVALID_TOKEN | 로그인 화면으로(공개가 꺼져 있을 때 로그인하지 않은 요청, 또는 만료된 토큰) |
| 404 | RESOURCE_NOT_FOUND | 기능이 꺼져 있거나 허용 목록에 없는 계정. 화면이 있다는 사실도 알리지 않습니다 |
| 404 | AGENT_RUN_NOT_FOUND | 없는 실행 번호(보관 기간이 지났거나 기록을 켜기 전 요청) |

## 6. 켜는 법(서버 설정)

| 환경변수 | Parameter Store(`/neuringo/<환경>/`) | 기본 | 설명 |
|---|---|---|---|
| NEURINGO_AGENT_RUNS_ENABLED | `ops/agent-runs/enabled` | false | `true` 면 기록을 저장하고 조회 API 를 엽니다 |
| NEURINGO_AGENT_RUNS_OPERATORS | `ops/agent-runs/operators` | 비어 있음 | 볼 수 있는 강사 계정 이메일을 쉼표로 적습니다. `*` 는 로그인한 강사 모두 |
| NEURINGO_AGENT_RUNS_RETENTION | `ops/agent-runs/retention` | 0(지우지 않음) | `14d` 처럼 기간을 주면 그보다 오래된 기록을 한 시간마다 지웁니다. `0` 이면 지우지 않습니다 |
| NEURINGO_AGENT_RUNS_PUBLIC | `ops/agent-runs/public` | false | `true` 면 로그인 없이 누구나 조회합니다(시연·외부 평가용). 조회 GET 만 열리고, 저장 값은 2절대로 고정 코드·횟수·시간·토큰 수뿐입니다. 시연이 끝나면 `false` 로 돌립니다 |

값을 넣은 뒤 Actions → Deploy(force)로 다시 배포합니다. 기존 호출 로그(`NEURINGO_AI_CALL_LOG`)는 켜지 않아도 됩니다. 기록은 같은 서버의 Postgres(`agent_run`, `agent_run_step`, V44)에 저장되며 새 AWS 서비스는 쓰지 않습니다.

## 7. 연결 Task·검증

- 서버 기록: `RoleplayRequestObservationFilter`(역할극 턴), `ReportGenerationService`·`ScenarioGenerationService`(`AgentRunTrace`), 판정 사건은 `StructuredLlmExecutor`·`RoleplayTurnOutcomeResolver`·`RetryingLearningReportGenerator`가 `RoleplayTiming.note`로 넘깁니다.
- 검증: `AgentRunAssemblerTest`(흐름 조립), `AgentRunDiagnosisTest`(자동 진단 규칙), `RoleplayTurnResolutionNoteTest`(턴 결과 판정), `RoleplayAgentRunHttpIntegrationTest`(실제 HTTP·DB, 정상·재시도·안내·오류 흐름과 조회 API), `AgentRunAccessIntegrationTest`(허용 목록), `AgentRunDisabledIntegrationTest`(기본 꺼짐), 프론트 `AgentRunsPage.test.tsx`.
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md)
