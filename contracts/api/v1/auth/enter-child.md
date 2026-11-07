# API-IAM-08 · 아동 코드 입장

> 구현 — 2026-11-04 / develop `bab136d`. 429 시도 제한은 2026-11-06 추가. 현재 코드의 HTTP 계약. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | POST |
| Path | /api/v1/child-access-sessions |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 공개 + CSRF |
| 성공 | 201 |
| 반환 | ApiResponse<ChildEntryData> |
| FE 함수 | enterChild |
| 구현·협업 | 윤상현 |

## 2. 용도·중요 규칙

- childName이 아니라 displayName이며 숫자 앞자리0을 유지한다.
- 서버가 HttpOnly JSESSIONID를 설정한다. 코드24시간과 아동 인증 세션 자동만료 없음은 별개다.
- 다른 코드 입장은 이전 브라우저 세션을 교체한다. 응답 후 아동 화면 캐시도 대상ID 기준으로 정리한다.
- 접속 IP 별 시도 제한이 두 겹이다. 둘 다 429 이며 `Retry-After`(초)를 붙인다. FE 는 code 대신 429 상태로 분기하고, `Retry-After` 동안 입력을 막는다(헤더가 없으면 60초).
  - nginx 요청 수 제한: 입장 요청(맞은 코드 포함)을 분당 60번, 한꺼번에 60번까지 받는다. 넘치면 `TOO_MANY_REQUESTS`, `Retry-After: 60`.
  - 앱 틀린 코드 횟수 제한: `INVALID_ACCESS_CODE`·`ACCESS_CODE_EXPIRED` 만 센다(맞은 입장·422 는 세지 않는다). 첫 실패부터 10분 안에 30번째로 틀리면 그 IP 의 입장을 10분 동안 막는다(맞는 코드도). `ACCESS_CODE_ATTEMPTS_EXCEEDED`, `Retry-After` 는 남은 초. 값은 `neuringo.child-access.attempt-limit`.
  - 학교처럼 여러 아동이 IP 하나(NAT)를 함께 쓰면 함께 막힌다. 서버 메모리에만 세므로 배포·재시작 때 지워진다.

## 3. 요청

### Path parameters

없음.

### Query parameters

없음. 지원되지 않는 page/size/sort를 보내도 적용된다고 가정하지 않습니다.

### Headers·쿠키

| 이름 | 값/타입 | 필수 | 주의 |
|---|---|---|---|
| Accept | application/json | 예 | 공통 envelope |
| Cookie | credentials: include | 예 | 브라우저 관리; HttpOnly 쿠키 직접 읽기 금지 |
| CSRF Header | GET /api/v1/csrf의 headerName:token | 예 | 쿠키와 함께 전송 |
| Content-Type | application/json; charset=utf-8 | 예 | JSON body |

### Request Body

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| accessCode | string | 예 | 아니오 | 숫자4자리, 정수로 변환하지 않음. 누락/null은 422가 아니라 401 INVALID_ACCESS_CODE |

~~~json
{
  "accessCode": "0427"
}
~~~

## 4. 성공 응답

HTTP 201.

~~~json
{
  "data": {
    "childId": "10000000-0000-4000-8000-000000000001",
    "displayName": "테스트 아동"
  },
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| data.childId | UUID | 예 | 아니오 | 현재 아동 ID |
| data.displayName | string | 예 | 아니오 | 실명; childName 아님 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

현재 업무/공통 오류입니다. message 대신 HTTP status와 code로 분기합니다.

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 403 | CSRF_TOKEN_INVALID | 재취득; 변경 요청 자동 재전송 없음 |
| 401 | INVALID_ACCESS_CODE | 코드 불일치/삭제 대상, accessCode 누락/null |
| 401 | ACCESS_CODE_EXPIRED | 24시간 코드 만료;410 아님 |
| 422 | VALIDATION_FAILED | 값이 있는데 숫자4자리 형식이 아님(빈 문자열 포함) |
| 429 | ACCESS_CODE_ATTEMPTS_EXCEEDED | 같은 IP 가 10분 안에 코드를 30번 틀림. `Retry-After`(남은 초, 최대 600) 동안 입력을 막고 자동 재전송 없음 |
| 429 | TOO_MANY_REQUESTS | 앞단 nginx 의 IP 별 요청 수 제한(분당 60번 넘음). `Retry-After: 60`. 몸통은 같은 오류 envelope |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |
| 400 | INVALID_REQUEST | UUID/필수 헤더·파트/Query 형식 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 403,
    "code": "CSRF_TOKEN_INVALID",
    "message": "요청을 확인해 주세요.",
    "path": "/api/v1/child-access-sessions",
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
export interface ChildEntryBody {
  /** 숫자4자리, 정수로 변환하지 않음 */
  accessCode: string;
}

export type UUID = string;

export interface ChildEntryData {
  /** 현재 아동 ID */
  childId: UUID;
  /** 실명; childName 아님 */
  displayName: string;
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface EnterChildArgs {
  body: T.ChildEntryBody;
}

export function enterChild(
  api: ApiClient,
  args: EnterChildArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ChildEntryData>> {
  return api.request<T.ChildEntryData>("/api/v1/child-access-sessions", {
    method: "POST",
    auth: "public",
    expectedStatuses: [201],
    signal: options.signal,
    body: args.body
  });
}
~~~

### 사용 예시

~~~ts
import { enterChild } from "./endpoints.ts";

const response = await enterChild(api, {
  "body": {
    "accessCode": "0427"
  }
});
const data = response.data;
// 개인정보·원본·토큰·응답 전문을 로그에 출력하지 않습니다.
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다. 실제 동작/삭제가 발생하는 요청이므로 테스트 데이터로 확인합니다.

~~~bash
curl -i -X POST "$NEURINGO_API_ORIGIN/api/v1/child-access-sessions" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "$NEURINGO_CSRF_HEADER: $NEURINGO_CSRF_TOKEN" \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data '{"accessCode":"0427"}'
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S4-YOON-01` · `S8-YOON-01`
- 코드 근거: [현재 Controller/DTO](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/blob/bab136d86b0642fd8446b3299fe09de3e9eb4a4e/backend/src/main/java/com/neuringo/neuringobe/child/controller/ChildAccessSessionController.java)
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
