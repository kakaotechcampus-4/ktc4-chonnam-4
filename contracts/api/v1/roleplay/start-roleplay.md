# API-RPL-01 · 역할극 시작·기존 세션 복원

> 구현 — 2026-10-28 / PR #80 + develop 통합 작업본. 공유 시작·복원 예제를 동기화했습니다. 운영 배포를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | POST |
| Path | `/api/v1/activities/{activityId}/roleplay-sessions` |
| 인증 | 본인 아동 HttpOnly 세션 + CSRF |
| 요청 키 | `Idempotency-Key: UUID` 필수 |
| 요청 본문 | 없음. `{}`와 임의 L/S 본문도 400으로 거절 |
| 성공 | 신규 세션 201, 기존 세션 복원 200 |
| 응답 | `ApiResponse<RoleplayStartData>` |
| 캐시 | `Cache-Control: no-store` |

## 2. 용도·중요 규칙

- 2026-10-28 사용자 결정: 같은 활동의 새 키 요청도 기존 세션을 복원합니다. 현재 S·작은 목표·마지막 확정 턴·상태를 초기화하지 않습니다.
- 세션이 없을 때만 확정된 최초 S와 연결된 승인 버전으로 생성합니다. 신규 생성은 현재 FIRST_COMBINED의 준비된 경로를 지원합니다.
- 첫 질문은 승인 시나리오의 저장된 지원 수준별 `opening_prompts`에서 읽습니다. 질문과 고정 메시지 ID를 세션·요청 키와 같은 트랜잭션으로 저장합니다. 문구 미준비나 저장 실패 시 신규 생성도 함께 롤백합니다.
- 승인 전에 `scenario_opening_focus`에 S별 작은 목표를 명시하면 신규 세션의 질문 초점도 함께 저장합니다. 목표 목록 순서를 선택 기준으로 사용하지 않습니다. 기존 텍스트 시작 계약은 유지하지만 목표 연결이 없는 세션은 후속 운영 문맥을 공급하지 않습니다. 연결 정보는 서버 승인 자료이며 요청 본문이나 공개 응답에 추가하지 않습니다.
- 학습 턴이 없으면 저장한 첫 질문을 같은 메시지 ID로 반환합니다. 기존 세션에 첫 질문 저장이 없으면 조건에 맞는 승인 문구를 확인해 한 번 저장합니다.
- 진행된 세션은 마지막 확정 턴의 서버 응답만 `purpose=RESUME`으로 반환합니다. 원문 발화·전체 이력·초기 질문을 대신 반환하지 않습니다. 메시지 ID는 그 턴의 ID입니다.
- 활동과 세션이 모두 IN_PROGRESS일 때만 메시지를 반환합니다. PAUSED·COMPLETED·RECOVERY_NEEDED 등 입력을 진행할 수 없는 상태는 현재 상태와 빈 메시지 배열을 반환합니다. 자동 재개·재시작하지 않습니다.
- 시작 질문은 학습 턴이 아니며 턴 수를 증가시키지 않습니다. 재전송·복원으로 현재 S·체크포인트·마지막 활동 시각을 갱신하지 않습니다.
- 응답에 상세 S·난이도 평가·점수·표준 발화를 포함하지 않습니다. 현재 구현은 텍스트만 제공하므로 `speech.available=false`, `url=null`입니다. AI·TTS 호출은 수행하지 않습니다.
- 이 API 성공은 조건부 음성 입력 기능이나 운영 RoleplayContextSource 연결 완료를 의미하지 않습니다. 이후 대화 처리는 별도 설정·운영 연결이 필요합니다.

## 3. 요청

`activityId`와 요청 키는 UUID 문자열입니다. Query와 Body는 없습니다.
쿠키는 `credentials: include`로 전송하고, `GET /api/v1/csrf`가 제공한 헤더 이름과 토큰을 사용합니다.
아동 요청에 강사 Bearer 토큰을 붙이지 않습니다. 응답 유실·네트워크 실패 시 원래 키를 유지해 재시도합니다.

## 4. 성공 응답

신규 생성 201 예시입니다. 복원도 같은 구조이며 200을 반환합니다.

~~~json
{
  "data": {
    "sessionId": "82000000-0000-4000-8000-000000000001",
    "activityId": "40000000-0000-4000-8000-000000000001",
    "scenarioId": "80000000-0000-4000-8000-000000000001",
    "scenarioVersion": 1,
    "status": "IN_PROGRESS",
    "activityStatus": "IN_PROGRESS",
    "lastDeliveredTurnId": null,
    "checkpointVersion": 0,
    "delivery": {
      "messages": [
        {
          "messageId": "83000000-0000-4000-8000-000000000001",
          "speakerRole": "DIALOGUE",
          "purpose": "QUESTION",
          "text": "친구는 어떤 기분일까?",
          "speech": { "available": false, "url": null }
        }
      ]
    }
  },
  "meta": { "traceId": "90000000-0000-4000-8000-000000000001" }
}
~~~

| 필드 | 의미 |
|---|---|
| `sessionId`, `activityId`, `scenarioId`, `scenarioVersion` | 본인 세션과 고정된 시나리오 버전 |
| `status` | 저장된 세션 상태 IN_PROGRESS / PAUSED / COMPLETED |
| `activityStatus` | 저장된 활동 상태. 세션 상태와 함께 다음 행동 판단 |
| `lastDeliveredTurnId` | 마지막 확정 학습 턴 ID, 아직 없으면 null |
| `checkpointVersion` | 현재 세션 버전. 마지막 턴 이후 상태 변경으로도 증가할 수 있음 |
| `delivery.messages` | 현재 표시 가능한 서버 문구. 중단 상태는 빈 배열 |
| `messageId` | 재전송·복원 시 중복 표시를 막을 안정된 식별자 |
| `speakerRole` | 현재 DIALOGUE |
| `purpose` | 첫 질문 QUESTION, 마지막 응답 복원 RESUME |
| `speech` | 현재 `{available:false,url:null}`. 오디오 재생 준비를 기다리지 않음 |

같은 키는 같은 세션을 가리키며 현재 진행 상태를 반환합니다. 진행 후의 응답을 최초 생성 응답의 고정 복사본으로 간주하지 않습니다.
완료 상태에서 빈 메시지 배열을 받으면 새 질문을 만들거나 자동으로 다시 시작하지 않습니다.

## 5. 오류·FE 처리

| HTTP | code | 처리 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID·필수 헤더·지원하지 않는 요청 본문 확인 |
| 401 | AUTHENTICATION_REQUIRED | 아동 재입장 필요 |
| 403 | ACCESS_DENIED | 강사 등 아동 외 역할은 시작 불가 |
| 403 | CSRF_TOKEN_INVALID | 토큰 재취득 후 원래 행동·키로 복구 판단 |
| 404 | ACTIVITY_NOT_FOUND | 없는 활동·타 아동 소유·비활성 아동을 숨김 |
| 409 | INITIAL_DIFFICULTY_NOT_READY | 최초 퀴즈 근거 미확정·불일치 |
| 409 | INITIAL_ROLEPLAY_NOT_READY | 신규 시작 상태·연결·승인 버전 미준비 |
| 409 | ROLEPLAY_START_DELIVERY_NOT_READY | 승인 시작 문구 또는 마지막 확정 응답 확인 불가. 임의 질문으로 대체하지 않음 |
| 409 | IDEMPOTENCY_KEY_REUSED | 다른 활동·아동의 시작 요청에 사용한 키 |
| 409 | ROLEPLAY_SESSION_AMBIGUOUS | 과거 같은 활동의 복수 세션. 임의 선택·새 생성 금지 |
| 409 | ROLEPLAY_SCENARIO_CONFLICT | 활동·세션 시나리오 또는 요청 연결 불일치 |
| 500 | INTERNAL_ERROR | 원문·예외 전문 비노출. 같은 키로 저장 결과 복구 |

네트워크·Abort·파싱 실패는 서버 미실행을 뜻하지 않습니다. [공통 연동 규칙](../integration-guide.md)에 따라 원래 키를 보존합니다.

## 6. 복사 가능한 TypeScript

공유 시작·복원 함수는 `examples/endpoints.ts`에 구현돼 있습니다. `draft-endpoints.ts`에서 가져오지 않습니다.
응답 타입은 `types.ts`의 `RoleplayStartData`이며 실제 프론트엔드 연동·운영 배포는 별도입니다.

### 요청·응답 타입

~~~ts
import type { UUID, ActivityStatus } from "./types.ts";

export interface RoleplayStartData {
  sessionId: UUID;
  activityId: UUID;
  scenarioId: UUID;
  scenarioVersion: number;
  status: 'IN_PROGRESS' | 'PAUSED' | 'COMPLETED';
  activityStatus: ActivityStatus;
  lastDeliveredTurnId: UUID | null;
  checkpointVersion: number;
  delivery: {
    messages: Array<{
      messageId: UUID;
      speakerRole: 'DIALOGUE';
      purpose: 'QUESTION' | 'RESUME';
      text: string;
      speech: { available: false; url: null };
    }>;
  };
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import type * as T from "./types.ts";

export interface StartRoleplayArgs {
  activityId: T.UUID;
  idempotencyKey: T.UUID;
}

export function startRoleplay(
  api: ApiClient,
  args: StartRoleplayArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.RoleplayStartData>> {
  return api.request<T.RoleplayStartData>("/api/v1/activities/" + encodeURIComponent(args.activityId) + "/roleplay-sessions", {
    method: "POST",
    auth: "child",
    expectedStatuses: [200, 201],
    signal: options.signal,
    idempotencyKey: args.idempotencyKey
  });
}
~~~

### 사용 예시

`api`는 공통 연동 규칙에 따라 생성한 클라이언트입니다. 신규 생성 201과 복원 200을 모두 처리하며 원래 요청 키를 보존합니다.

~~~ts
import { startRoleplay } from "./endpoints.ts";

const response = await startRoleplay(api, {
  activityId: "40000000-0000-4000-8000-000000000001",
  idempotencyKey: "89000000-0000-4000-8000-000000000001"
});
const data = response.data;
// messageId로 표시 중복을 제거하고 현재 상태·checkpointVersion을 사용합니다.
// 빈 messages에서 새 질문을 만들거나 완료된 세션을 다시 시작하지 않습니다.
~~~

## 7. cURL 계약 확인

환경별 origin과 실제 아동 쿠키·CSRF를 준비합니다. 시작 본문은 보내지 않습니다.

~~~bash
curl -i -X POST "$NEURINGO_API_ORIGIN/api/v1/activities/${activityId}/roleplay-sessions" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "$NEURINGO_CSRF_HEADER: $NEURINGO_CSRF_TOKEN" \
  -H "Idempotency-Key: $NEURINGO_REQUEST_KEY"
~~~

## 8. 연결 Task·검증 범위

S5-JIN-02 후속 연결이며 S6-JIN-01·S6-YOON-01의 세션·입력 UI와 연동합니다.
서버 Controller·생성/복원 트랜잭션·승인 문구·CSRF·본인 소유권·동시 요청을 합성 자료와 PostgreSQL로 검증합니다.
운영 AI·TTS·음성 대화 문맥·브라우저·배포 인수 완료는 별도입니다. 전체 완료 조건과 턴 상한은 이 API에서 새로 확정하지 않습니다.
