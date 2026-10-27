# API-RPL-01 · 역할극 시작·동일 시작 요청 복원

> 설계 — 2026-10-22 / develop `63e4314`. 설계·미구현. 경로/필드/상태는 제안이며 allowDrafts 기본 차단입니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | POST |
| Path | /api/v1/activities/{activityId}/roleplay-sessions |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 본인 아동 HttpOnly 세션 + CSRF + Idempotency-Key |
| 성공 | 201 — 설계 예시 |
| 반환 | ApiResponse<DraftRoleplaySession> |
| FE 함수 | startRoleplay |
| 구현·협업 | 진미나·윤상현 |

## 2. 용도·중요 규칙

- 본인배정·승인버전·동의/준비Gate확인. 같은키동일세션/검증초기응답.
- 새키중복세션반환vs409합의필요. 전체완료조건/턴상한을임의고정하지않는다.

## 3. 요청

### Path parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| activityId | UUID string | 예 | 경로 식별자 |

### Query parameters

없음. 지원되지 않는 page/size/sort를 보내도 적용된다고 가정하지 않습니다.

### Headers·쿠키

| 이름 | 값/타입 | 필수 | 주의 |
|---|---|---|---|
| Accept | application/json | 예 | 공통 envelope |
| Cookie | credentials: include | 예 | 브라우저 관리; HttpOnly 쿠키 직접 읽기 금지 |
| CSRF Header | GET /api/v1/csrf의 headerName:token | 예 | 쿠키와 함께 전송 |
| Idempotency-Key | UUID string | 예 | 동일 행동 재전송은 같은 키/같은 입력 |

### Request Body

없음. 빈 JSON 객체를 추가하지 않습니다.

## 4. 성공 응답

HTTP 201 — 설계 예시.

~~~json
{
  "data": {
    "sessionId": "82000000-0000-4000-8000-000000000001",
    "activityId": "40000000-0000-4000-8000-000000000001",
    "scenarioId": "80000000-0000-4000-8000-000000000001",
    "scenarioVersion": 1,
    "status": "IN_PROGRESS",
    "lastDeliveredTurnId": null,
    "delivery": {
      "messages": [
        {
          "messageId": "83000000-0000-4000-8000-000000000001",
          "speakerRole": "DIALOGUE",
          "purpose": "QUESTION",
          "text": "친구는 어떤 기분일까?",
          "speech": {
            "available": false,
            "url": null
          }
        }
      ]
    }
  },
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| data.sessionId | UUID | 예 | 아니오 | 세션 |
| data.activityId | UUID | 예 | 아니오 | 본인 활동 |
| data.scenarioId | UUID | 예 | 아니오 | 승인 콘텐츠 |
| data.scenarioVersion | number | 예 | 아니오 | 승인 버전 |
| data.status | string | 예 | 아니오 | IN_PROGRESS/PAUSED 등 제안 |
| data.lastDeliveredTurnId | UUID \| null | 예 | 예 | 마지막 확정 턴 |
| data.delivery | DraftDelivery | 예 | 아니오 | 공개 검증 출력 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

아래 업무 오류도 설계 예시이며 구현 전 합의가 필요합니다.

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID/Query 형식 |
| 401 | AUTHENTICATION_REQUIRED | 인증/입장 필요 |
| 403 | ACCESS_DENIED | 역할 권한 |
| 403 | CSRF_TOKEN_INVALID | 재취득; 변경 요청 자동 재전송 없음 |
| 404 | RESOURCE_NOT_FOUND | 리소스/소유권 코드 제안 |
| 409 | IDEMPOTENCY_KEY_REUSED | 범용 키 충돌 제안 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 400,
    "code": "INVALID_REQUEST",
    "message": "요청을 확인해 주세요.",
    "path": "/api/v1/activities/{activityId}/roleplay-sessions",
    "traceId": "90000000-0000-4000-8000-000000000001",
    "fieldErrors": []
  }
}
~~~

네트워크·Abort·성공 응답 파싱 오류는 서버 미실행을 뜻하지 않습니다. 변경 요청을 새 키로 자동 재전송하지 않습니다. CSRF 오류는 토큰을 다시 받고 원래 행동/키로 복구를 판단합니다. 강사 보호 요청401은 토큰·입력·캐시 정리, 아동401은 재입장 안내입니다. refresh 로그인 API는 없습니다. 세부 동작은 [공통 연동 규칙](../integration-guide.md)을 따릅니다.

## 6. 복사 가능한 TypeScript

공통 [api-client.ts](../examples/api-client.ts), [types.ts](../examples/types.ts), [draft-endpoints.ts](../examples/draft-endpoints.ts)를 같은 폴더 구조로 복사합니다. allowDrafts는 Mock에서만 켭니다.

### 요청·응답 타입

~~~ts
export type UUID = string;

export interface DraftSpeech {
  /** TTS 준비 여부 제안 */
  available: boolean;
  /** URL 반환/수명/권한 미정 */
  url: string | null;
}

export interface DraftMessage {
  /** 화면 출력 ID */
  messageId: UUID;
  /** UI 표시 주체 제안; 에이전트 분리 아님 */
  speakerRole: 'DIALOGUE' | 'HINT';
  /** QUESTION/HINT/FEEDBACK 등 제안 */
  purpose: string;
  /** 검증된 출력만 */
  text: string;
  /** 전달방식/TTL 미정 */
  speech: DraftSpeech;
}

export interface DraftDelivery {
  /** 메시지 배열 제안; 제공 트리거/순서 미정 */
  messages: DraftMessage[];
}

export interface DraftRoleplaySession {
  /** 세션 */
  sessionId: UUID;
  /** 본인 활동 */
  activityId: UUID;
  /** 승인 콘텐츠 */
  scenarioId: UUID;
  /** 승인 버전 */
  scenarioVersion: number;
  /** IN_PROGRESS/PAUSED 등 제안 */
  status: string;
  /** 마지막 확정 턴 */
  lastDeliveredTurnId: UUID | null;
  /** 공개 검증 출력 */
  delivery: DraftDelivery;
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface StartRoleplayArgs {
  activityId: T.UUID;
  idempotencyKey: T.UUID;
}

export function startRoleplay(
  api: ApiClient,
  args: StartRoleplayArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftRoleplaySession>> {
  return api.request<T.DraftRoleplaySession>("/api/v1/activities/" + encodeURIComponent(args.activityId) + "/roleplay-sessions", {
    method: "POST",
    auth: "child",
    expectedStatuses: [201],
    signal: options.signal,
    draft: true,
    idempotencyKey: args.idempotencyKey
  });
}
~~~

### 사용 예시

~~~ts
import { startRoleplay } from "./draft-endpoints.ts";

const response = await startRoleplay(api, {
  "activityId": "40000000-0000-4000-8000-000000000001",
  "idempotencyKey": "89000000-0000-4000-8000-000000000001"
});
const data = response.data;
// 개인정보·원본·토큰·응답 전문을 로그에 출력하지 않습니다.
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다. 설계 경로는 실제 서버로 호출하지 않고 Mock에서 확인합니다.

~~~bash
curl -i -X POST "$NEURINGO_API_ORIGIN/api/v1/activities/${activityId}/roleplay-sessions" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "$NEURINGO_CSRF_HEADER: $NEURINGO_CSRF_TOKEN" \
  -H "Idempotency-Key: $NEURINGO_REQUEST_KEY"
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S6-JIN-01` · `S6-YOON-01`
- 현재 서버 Controller가 없는 설계 계약입니다. 구현 PR에서 경로·권한·DTO를 확정한 뒤 연결합니다.
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
