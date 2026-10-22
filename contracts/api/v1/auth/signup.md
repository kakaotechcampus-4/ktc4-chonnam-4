# API-IAM-02 · 강사 회원가입

> 구현 — 2026-10-22 / develop `63e4314`. 현재 코드의 HTTP 계약. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | POST |
| Path | /api/v1/users |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 공개 + CSRF |
| 성공 | 200 |
| 반환 | ApiResponse<UserData> |
| FE 함수 | signup |
| 구현·협업 | 배재일 |

## 2. 용도·중요 규칙

- email/name/password와 termsAgreed=true, privacyAgreed=true가 필요합니다. 동의 누락/null/false는 422입니다. orgName은 선택입니다.
- 이메일 앞뒤 공백 제거·대소문자 정규화 후 전역 유일성 검사. 비밀번호는 8~72자이며 UTF-8 72바이트 이하입니다.
- 약관/방침 버전과 동의 시각은 서버가 저장합니다. 클라이언트가 버전/시각을 지정하는 필드는 없습니다. 현재 role=INSTRUCTOR, status=ACTIVE입니다.

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
| email | string | 예 | 아니오 | 정규화 후 유일; 현재 Email/최대254자 검증 |
| password | string | 예 | 아니오 | 8~72자, UTF-8 72바이트 이하 (한글·이모지는 바이트 수도 확인) |
| name | string | 예 | 아니오 | 실명; 현재 최대100자 |
| orgName | string \| null | 아니오 | 예 | 선택 레거시, 최대100자; 필수 수집 금지 |
| termsAgreed | true | 예 | 아니오 | 필수 동의. false/null/누락은 422 |
| privacyAgreed | true | 예 | 아니오 | 필수 동의. 버전과 동의 시각은 서버가 결정 |

~~~json
{
  "email": "teacher@example.test",
  "password": "example-password",
  "name": "테스트 강사",
  "termsAgreed": true,
  "privacyAgreed": true
}
~~~

## 4. 성공 응답

HTTP 200.

~~~json
{
  "data": {
    "userId": "20000000-0000-4000-8000-000000000001",
    "role": "INSTRUCTOR",
    "email": "teacher@example.test",
    "name": "테스트 강사",
    "orgName": null,
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
| data.userId | UUID | 예 | 아니오 | 강사 ID |
| data.role | UserRole | 예 | 아니오 | 현재 역할은 INSTRUCTOR만 사용 |
| data.email | string | 예 | 아니오 | 로그인 이메일 |
| data.name | string | 예 | 아니오 | 강사 이름 |
| data.orgName | string \| null | 예 | 예 | 선택 조직명, null 포함 |
| data.status | AccountStatus | 예 | 아니오 | 현재 계정 상태는 ACTIVE만 사용 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

현재 업무/공통 오류입니다. message 대신 HTTP status와 code로 분기합니다.

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 403 | CSRF_TOKEN_INVALID | 재취득; 변경 요청 자동 재전송 없음 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |
| 422 | VALIDATION_FAILED | 동의 누락/false, 길이·형식 검증 |
| 409 | DUPLICATE_RESOURCE | 정규화 이메일 중복 |
| 400 | INVALID_REQUEST | UUID/필수 헤더·파트/Query 형식 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 403,
    "code": "CSRF_TOKEN_INVALID",
    "message": "요청을 확인해 주세요.",
    "path": "/api/v1/users",
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
export interface SignupBody {
  /** 정규화 후 유일; 현재 Email/최대254자 검증 */
  email: string;
  /** 8~72자, UTF-8 72바이트 이하 (한글·이모지는 바이트 수도 확인) */
  password: string;
  /** 실명; 현재 최대100자 */
  name: string;
  /** 선택 레거시, 최대100자; 필수 수집 금지 */
  orgName?: string | null;
  /** 필수 동의. false/null/누락은 422 */
  termsAgreed: true;
  /** 필수 동의. 버전과 동의 시각은 서버가 결정 */
  privacyAgreed: true;
}

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
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface SignupArgs {
  body: T.SignupBody;
}

export function signup(
  api: ApiClient,
  args: SignupArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.UserData>> {
  return api.request<T.UserData>("/api/v1/users", {
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
import { signup } from "./endpoints.ts";

const response = await signup(api, {
  "body": {
    "email": "teacher@example.test",
    "password": "example-password",
    "name": "테스트 강사",
    "termsAgreed": true,
    "privacyAgreed": true
  }
});
const data = response.data;
// 개인정보·원본·토큰·응답 전문을 로그에 출력하지 않습니다.
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다. 실제 동작/삭제가 발생하는 요청이므로 테스트 데이터로 확인합니다.

~~~bash
curl -i -X POST "$NEURINGO_API_ORIGIN/api/v1/users" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "$NEURINGO_CSRF_HEADER: $NEURINGO_CSRF_TOKEN" \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data '{"email":"teacher@example.test","password":"example-password","name":"테스트 강사","termsAgreed":true,"privacyAgreed":true}'
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S3-BAE-01` · `S8-BAE-01`
- 코드 근거: [현재 Controller/DTO](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/blob/63e43144c92449ec5670390a6b99d20d2b2580a5/backend/src/main/java/com/neuringo/neuringobe/user/dto/SignupRequest.java)
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
