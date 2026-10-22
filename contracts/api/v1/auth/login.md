# API-IAM-03 · 강사 로그인

> 구현 — 2026-10-22 / develop `63e4314`. 현재 코드의 HTTP 계약. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | POST |
| Path | /api/v1/auth/sessions |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 공개 + CSRF |
| 성공 | 200 |
| 반환 | ApiResponse<LoginData> |
| FE 함수 | login |
| 구현·협업 | 배재일 |

## 2. 용도·중요 규칙

- data.accessToken을 sessionStorage/기존 강사 저장소에 저장한다. expiresAt까지 고정12시간, 자동 refresh 없음.
- 탭 종료 후 재로그인. 보호 API 만료401에서는 작성 입력도 버린다. password/토큰을 로그에 남기지 않는다.

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
| email | string | 예 | 아니오 | 빈 값 불가 |
| password | string | 예 | 아니오 | 빈 값 불가; 실패는 동일한 일반 안내 |

~~~json
{
  "email": "teacher@example.test",
  "password": "example-password"
}
~~~

## 4. 성공 응답

HTTP 200.

~~~json
{
  "data": {
    "accessToken": "example-opaque-token",
    "expiresAt": "2026-10-06T15:00:00Z",
    "user": {
      "userId": "20000000-0000-4000-8000-000000000001",
      "role": "INSTRUCTOR",
      "email": "teacher@example.test",
      "name": "테스트 강사",
      "orgName": null,
      "status": "ACTIVE"
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
| data.accessToken | string | 예 | 아니오 | 불투명 Bearer 토큰; JWT라고 가정하지 않음 |
| data.expiresAt | ISODateTime | 예 | 아니오 | 로그인+12시간, 연장 없음 |
| data.user | UserData | 예 | 아니오 | 강사 정보 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

현재 업무/공통 오류입니다. message 대신 HTTP status와 code로 분기합니다.

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 403 | CSRF_TOKEN_INVALID | 재취득; 변경 요청 자동 재전송 없음 |
| 401 | INVALID_CREDENTIALS | 일반 로그인 오류 |
| 422 | VALIDATION_FAILED | 필수값 누락 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |
| 400 | INVALID_REQUEST | UUID/필수 헤더·파트/Query 형식 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 403,
    "code": "CSRF_TOKEN_INVALID",
    "message": "요청을 확인해 주세요.",
    "path": "/api/v1/auth/sessions",
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
export interface LoginBody {
  /** 빈 값 불가 */
  email: string;
  /** 빈 값 불가; 실패는 동일한 일반 안내 */
  password: string;
}

export type ISODateTime = string;

export type UUID = string;

export type UserRole = 'INSTRUCTOR';

export type AccountStatus = 'ACTIVE';

export interface UserData {
  /** 강사 ID */
  userId: UUID;
  /** 현재 역할은 INSTRUCTOR만 사용 */
  role: UserRole;
  /** 로그인 이메일 */
  email: string;
  /** 강사 이름 */
  name: string;
  /** 선택 조직명, null 포함 */
  orgName: string | null;
  /** 현재 계정 상태는 ACTIVE만 사용 */
  status: AccountStatus;
}

export interface LoginData {
  /** 불투명 Bearer 토큰; JWT라고 가정하지 않음 */
  accessToken: string;
  /** 로그인+12시간, 연장 없음 */
  expiresAt: ISODateTime;
  /** 강사 정보 */
  user: UserData;
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface LoginArgs {
  body: T.LoginBody;
}

export function login(
  api: ApiClient,
  args: LoginArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.LoginData>> {
  return api.request<T.LoginData>("/api/v1/auth/sessions", {
    method: "POST",
    auth: "public",
    expectedStatuses: [200],
    signal: options.signal,
    body: args.body
  });
}
~~~

### 사용 예시

~~~ts
import { login } from "./endpoints.ts";

const response = await login(api, {
  "body": {
    "email": "teacher@example.test",
    "password": "example-password"
  }
});
const data = response.data;
// 개인정보·원본·토큰·응답 전문을 로그에 출력하지 않습니다.
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다. 실제 동작/삭제가 발생하는 요청이므로 테스트 데이터로 확인합니다.

~~~bash
curl -i -X POST "$NEURINGO_API_ORIGIN/api/v1/auth/sessions" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "$NEURINGO_CSRF_HEADER: $NEURINGO_CSRF_TOKEN" \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data '{"email":"teacher@example.test","password":"example-password"}'
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S3-BAE-01` · `S8-BAE-01`
- 코드 근거: [현재 Controller/DTO](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/blob/63e43144c92449ec5670390a6b99d20d2b2580a5/backend/src/main/java/com/neuringo/neuringobe/auth/controller/AuthController.java)
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
