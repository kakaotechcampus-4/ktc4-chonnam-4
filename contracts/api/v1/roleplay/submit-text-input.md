# API-RPL-05 · 역할극 텍스트 입력 제출

> 설계 — 2026-10-22 / develop `63e4314`. 설계·미구현. 경로/필드/상태는 제안이며 allowDrafts 기본 차단입니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | POST |
| Path | /api/v1/roleplay-sessions/{sessionId}/text-inputs |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 본인 아동 HttpOnly 세션 + CSRF + Idempotency-Key |
| 성공 | 202 — 설계 예시 |
| 반환 | ApiResponse<DraftInputAccepted> |
| FE 함수 | submitTextInput |
| 구현·협업 | 진미나·윤상현 |

## 2. 용도·중요 규칙

- 서버최소정제/개인정보마스킹/의미보존후표준발화하나만분석.
- 새입력은새키,유실재전송은같은키. 현재202접수/조회방식은미정.

## 3. 요청

### Path parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| sessionId | UUID string | 예 | 경로 식별자 |

### Query parameters

없음. 지원되지 않는 page/size/sort를 보내도 적용된다고 가정하지 않습니다.

### Headers·쿠키

| 이름 | 값/타입 | 필수 | 주의 |
|---|---|---|---|
| Accept | application/json | 예 | 공통 envelope |
| Cookie | credentials: include | 예 | 브라우저 관리; HttpOnly 쿠키 직접 읽기 금지 |
| CSRF Header | GET /api/v1/csrf의 headerName:token | 예 | 쿠키와 함께 전송 |
| Idempotency-Key | UUID string | 예 | 동일 행동 재전송은 같은 키/같은 입력 |
| Content-Type | application/json; charset=utf-8 | 예 | JSON body |

### Request Body

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| clientInputId | UUID | 예 | 아니오 | 한 새 입력, 재전송 유지 |
| text | string | 예 | 아니오 | 원문 입력; 길이/세부 검증 미정 |

~~~json
{
  "clientInputId": "84000000-0000-4000-8000-000000000001",
  "text": "친구가 아프고 속상할 것 같아요."
}
~~~

## 4. 성공 응답

HTTP 202 — 설계 예시.

~~~json
{
  "data": {
    "inputId": "84000000-0000-4000-8000-000000000001",
    "turnId": "85000000-0000-4000-8000-000000000001",
    "status": "PROCESSING"
  },
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| data.inputId | UUID | 예 | 아니오 | 입력 ID |
| data.turnId | UUID | 예 | 아니오 | 조회할 턴 |
| data.status | 'PROCESSING' | 예 | 아니오 | 접수 제안, 전달/완료 성공 아님 |

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
| 422 | VALIDATION_FAILED | 검증 코드 제안 |
| 409 | IDEMPOTENCY_KEY_REUSED | 범용 키 충돌 제안 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 400,
    "code": "INVALID_REQUEST",
    "message": "요청을 확인해 주세요.",
    "path": "/api/v1/roleplay-sessions/{sessionId}/text-inputs",
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

export interface DraftTextBody {
  /** 한 새 입력, 재전송 유지 */
  clientInputId: UUID;
  /** 원문 입력; 길이/세부 검증 미정 */
  text: string;
}

export interface DraftInputAccepted {
  /** 입력 ID */
  inputId: UUID;
  /** 조회할 턴 */
  turnId: UUID;
  /** 접수 제안, 전달/완료 성공 아님 */
  status: 'PROCESSING';
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface SubmitTextInputArgs {
  sessionId: T.UUID;
  body: T.DraftTextBody;
  idempotencyKey: T.UUID;
}

export function submitTextInput(
  api: ApiClient,
  args: SubmitTextInputArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftInputAccepted>> {
  return api.request<T.DraftInputAccepted>("/api/v1/roleplay-sessions/" + encodeURIComponent(args.sessionId) + "/text-inputs", {
    method: "POST",
    auth: "child",
    expectedStatuses: [202],
    signal: options.signal,
    draft: true,
    idempotencyKey: args.idempotencyKey,
    body: args.body
  });
}
~~~

### 사용 예시

~~~ts
import { submitTextInput } from "./draft-endpoints.ts";

const response = await submitTextInput(api, {
  "sessionId": "82000000-0000-4000-8000-000000000001",
  "body": {
    "clientInputId": "84000000-0000-4000-8000-000000000001",
    "text": "친구가 아프고 속상할 것 같아요."
  },
  "idempotencyKey": "89000000-0000-4000-8000-000000000001"
});
const data = response.data;
// 개인정보·원본·토큰·응답 전문을 로그에 출력하지 않습니다.
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다. 설계 경로는 실제 서버로 호출하지 않고 Mock에서 확인합니다.

~~~bash
curl -i -X POST "$NEURINGO_API_ORIGIN/api/v1/roleplay-sessions/${sessionId}/text-inputs" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "$NEURINGO_CSRF_HEADER: $NEURINGO_CSRF_TOKEN" \
  -H "Idempotency-Key: $NEURINGO_REQUEST_KEY" \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data '{"clientInputId":"84000000-0000-4000-8000-000000000001","text":"친구가 아프고 속상할 것 같아요."}'
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S6-JIN-01` · `S6-YOON-01`
- 현재 서버 Controller가 없는 설계 계약입니다. 구현 PR에서 경로·권한·DTO를 확정한 뒤 연결합니다.
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
