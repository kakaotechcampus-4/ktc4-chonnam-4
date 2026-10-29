# API-IAM-07 · 아동 코드 발급·재발급

> 구현 — 2026-10-22 / develop `63e4314`. 현재 코드의 HTTP 계약. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | POST |
| Path | /api/v1/children/{childId}/access-codes |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 담당 강사 Bearer + CSRF + Idempotency-Key |
| 성공 | 201 |
| 반환 | ApiResponse<AccessCodeData> |
| FE 함수 | issueChildCode |
| 구현·협업 | 윤상현·배재일 |

## 2. 용도·중요 규칙

- 동일 행동의 Idempotency-Key는 유지한다. 재전송은 원래 코드/원래 만료이며 새24시간을 부여하지 않는다.
- 활성 숫자4자리 코드 다른 아동과 중복 불가. 새 키 발급은 이전 코드를 폐기한다. Location 상세GET은 현재 없음.

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
| Idempotency-Key | UUID string | 예 | 동일 행동 재전송은 같은 키/같은 입력 |

### Request Body

없음. 빈 JSON 객체를 추가하지 않습니다.

## 4. 성공 응답

HTTP 201.

~~~json
{
  "data": {
    "accessCode": "0427",
    "expiresAt": "2026-10-07T12:00:00+09:00"
  },
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| data.accessCode | string | 예 | 아니오 | 정확히 숫자4자리 문자열; 앞자리0 유지 |
| data.expiresAt | ISODateTime | 예 | 아니오 | 24시간 만료; 재전송은 원래 만료 유지 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

현재 업무/공통 오류입니다. message 대신 HTTP status와 code로 분기합니다.

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID/Query 형식 |
| 401 | AUTHENTICATION_REQUIRED | 인증/입장 필요 |
| 401 | INVALID_TOKEN | 강사 토큰 만료/위조; 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 역할 권한 |
| 403 | CSRF_TOKEN_INVALID | 재취득; 변경 요청 자동 재전송 없음 |
| 404 | CHILD_NOT_FOUND | 없거나 권한 밖 |
| 409 | IDEMPOTENCY_KEY_REUSED | 다른 아동 요청에 같은 키 |
| 409 | ACCESS_CODE_SPACE_EXHAUSTED | 유효 코드 공간 부족 |
| 409 | CHILD_NOT_ACTIVE | 비활성 아동 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 400,
    "code": "INVALID_REQUEST",
    "message": "요청을 확인해 주세요.",
    "path": "/api/v1/children/{childId}/access-codes",
    "traceId": "90000000-0000-4000-8000-000000000001",
    "fieldErrors": []
  }
}
~~~

네트워크·Abort·성공 응답 파싱 오류는 서버 미실행을 뜻하지 않습니다. 변경 요청을 새 키로 자동 재전송하지 않습니다. CSRF 오류는 토큰을 다시 받고 원래 행동/키로 복구를 판단합니다. 강사 보호 요청401은 토큰·입력·캐시 정리, 아동401은 재입장 안내입니다. refresh 로그인 API는 없습니다. 세부 동작은 [공통 연동 규칙](../integration-guide.md)을 따릅니다.

## 6. 복사 가능한 TypeScript

공통 [api-client.ts](../examples/api-client.ts), [types.ts](../examples/types.ts), [endpoints.ts](../examples/endpoints.ts)를 같은 폴더 구조로 복사합니다. 실서버의 origin·쿠키·권한 설정은 별도로 확인합니다.

### 요청·응답 타입

~~~ts
export type ISODateTime = string;

export interface AccessCodeData {
  /** 정확히 숫자4자리 문자열; 앞자리0 유지 */
  accessCode: string;
  /** 24시간 만료; 재전송은 원래 만료 유지 */
  expiresAt: ISODateTime;
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface IssueChildCodeArgs {
  childId: T.UUID;
  idempotencyKey: T.UUID;
}

export function issueChildCode(
  api: ApiClient,
  args: IssueChildCodeArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.AccessCodeData>> {
  return api.request<T.AccessCodeData>("/api/v1/children/" + encodeURIComponent(args.childId) + "/access-codes", {
    method: "POST",
    auth: "instructor",
    expectedStatuses: [201],
    signal: options.signal,
    idempotencyKey: args.idempotencyKey
  });
}
~~~

### 사용 예시

~~~ts
import { issueChildCode } from "./endpoints.ts";

const response = await issueChildCode(api, {
  "childId": "10000000-0000-4000-8000-000000000001",
  "idempotencyKey": "89000000-0000-4000-8000-000000000001"
});
const data = response.data;
// 개인정보·원본·토큰·응답 전문을 로그에 출력하지 않습니다.
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다. 실제 동작/삭제가 발생하는 요청이므로 테스트 데이터로 확인합니다.

~~~bash
curl -i -X POST "$NEURINGO_API_ORIGIN/api/v1/children/${childId}/access-codes" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN" \
  -H "$NEURINGO_CSRF_HEADER: $NEURINGO_CSRF_TOKEN" \
  -H "Idempotency-Key: $NEURINGO_REQUEST_KEY"
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S4-YOON-01` · `S8-BAE-01`
- 코드 근거: [현재 Controller/DTO](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/blob/63e43144c92449ec5670390a6b99d20d2b2580a5/backend/src/main/java/com/neuringo/neuringobe/child/controller/ChildAccessCodeController.java)
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
