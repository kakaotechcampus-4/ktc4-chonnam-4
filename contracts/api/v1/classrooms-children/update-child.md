# API-CLS-09 · 아동 실명 수정

> 설계 — 2026-10-22 / develop `63e4314`. 설계·미구현. 경로/필드/상태는 제안이며 allowDrafts 기본 차단입니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | PATCH |
| Path | /api/v1/children/{childId} |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 담당 강사 Bearer + CSRF |
| 성공 | 200 — 설계 예시 |
| 반환 | ApiResponse<ChildData> |
| FE 함수 | updateChild |
| 구현·협업 | 배재일 |

## 2. 용도·중요 규칙

- displayName만 수정 제안; 동명이인을 UUID로 구분한다. 실명을 AI/로그에 넣지 않는다.

## 3. 요청

### Path parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| childId | UUID string | 예 | 경로 식별자 |

### Query parameters

없음. 지원되지 않는 page/size/sort를 보내도 적용된다고 가정하지 않습니다.

### Headers·쿠키

| 이름 | 값/타입 | 필수 | 주의 |
|---|---|---|---|
| Accept | application/json | 예 | 공통 envelope |
| Cookie | credentials: include | 예 | 브라우저 관리; HttpOnly 쿠키 직접 읽기 금지 |
| Authorization | Bearer <accessToken> | 예 | 아동/public에 강사 토큰을 붙이지 않음 |
| CSRF Header | GET /api/v1/csrf의 headerName:token | 예 | 쿠키와 함께 전송 |
| Content-Type | application/json; charset=utf-8 | 예 | JSON body |

### Request Body

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| displayName | string | 예 | 아니오 | 실명만; 현재 최대100자, 동명이인 허용 |

~~~json
{
  "displayName": "테스트 아동"
}
~~~

## 4. 성공 응답

HTTP 200 — 설계 예시.

~~~json
{
  "data": {
    "childId": "10000000-0000-4000-8000-000000000001",
    "classId": "11000000-0000-4000-8000-000000000001",
    "displayName": "테스트 아동",
    "status": "ACTIVE"
  },
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| data.childId | UUID | 예 | 아니오 | 아동 식별자 |
| data.classId | UUID | 예 | 아니오 | 소속 학급 |
| data.displayName | string | 예 | 아니오 | 아동 실명 |
| data.status | ChildStatus | 예 | 아니오 | 현재 ACTIVE; REMOVED 보관으로 삭제를 대체하지 않음 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

아래 업무 오류도 설계 예시이며 구현 전 합의가 필요합니다.

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID/Query 형식 |
| 401 | AUTHENTICATION_REQUIRED | 인증/입장 필요 |
| 401 | INVALID_TOKEN | 강사 토큰 만료/위조; 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 역할 권한 |
| 403 | CSRF_TOKEN_INVALID | 재취득; 변경 요청 자동 재전송 없음 |
| 404 | CHILD_NOT_FOUND | 리소스/소유권 코드 제안 |
| 422 | VALIDATION_FAILED | 검증 코드 제안 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 400,
    "code": "INVALID_REQUEST",
    "message": "요청을 확인해 주세요.",
    "path": "/api/v1/children/{childId}",
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
export interface ChildBody {
  /** 실명만; 현재 최대100자, 동명이인 허용 */
  displayName: string;
}

export type UUID = string;

export type ChildStatus = 'ACTIVE' | 'PAUSED' | 'REMOVED';

export interface ChildData {
  /** 아동 식별자 */
  childId: UUID;
  /** 소속 학급 */
  classId: UUID;
  /** 아동 실명 */
  displayName: string;
  /** 현재 ACTIVE; REMOVED 보관으로 삭제를 대체하지 않음 */
  status: ChildStatus;
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface UpdateChildArgs {
  childId: T.UUID;
  body: T.ChildBody;
}

export function updateChild(
  api: ApiClient,
  args: UpdateChildArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ChildData>> {
  return api.request<T.ChildData>("/api/v1/children/" + encodeURIComponent(args.childId), {
    method: "PATCH",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true,
    body: args.body
  });
}
~~~

### 사용 예시

~~~ts
import { updateChild } from "./draft-endpoints.ts";

const response = await updateChild(api, {
  "childId": "10000000-0000-4000-8000-000000000001",
  "body": {
    "displayName": "테스트 아동"
  }
});
const data = response.data;
// 개인정보·원본·토큰·응답 전문을 로그에 출력하지 않습니다.
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다. 설계 경로는 실제 서버로 호출하지 않고 Mock에서 확인합니다.

~~~bash
curl -i -X PATCH "$NEURINGO_API_ORIGIN/api/v1/children/${childId}" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN" \
  -H "$NEURINGO_CSRF_HEADER: $NEURINGO_CSRF_TOKEN" \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data '{"displayName":"테스트 아동"}'
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S4-BAE-01`
- 현재 서버 Controller가 없는 설계 계약입니다. 구현 PR에서 경로·권한·DTO를 확정한 뒤 연결합니다.
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
