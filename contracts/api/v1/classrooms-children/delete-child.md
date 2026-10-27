# API-CLS-10 · 아동 즉시 영구 삭제

> 구현 — 2026-10-22 / develop `63e4314`. 현재 코드의 HTTP 계약. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | DELETE |
| Path | /api/v1/children/{childId} |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 담당 강사 Bearer + CSRF |
| 성공 | 204 |
| 반환 | 본문 없음 (204) |
| FE 함수 | deleteChild |
| 구현·협업 | 배재일·정민서 |

## 2. 용도·중요 규칙

- 담당 아동과 종속 목표·활동·퀴즈·역할극 저장자료·접근 코드를 영구 삭제합니다. 성공204, 없는/권한 밖/이미 삭제한 대상은404입니다.
- 상태 REMOVED로 보관하는 방식이 아닙니다. 아동 서버 접근은 이후 DB 소유권/존재 검사에서 거부되며 모든 브라우저의 인증 쿠키·캐시를 이 DELETE가 직접 지운다고 가정하지 않습니다.

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

### Request Body

없음. 빈 JSON 객체를 추가하지 않습니다.

## 4. 성공 응답

HTTP 204.

본문 없음. HTTP 204이며 JSON을 파싱하지 않습니다.

### response schema

없음.

## 5. 오류·FE 처리

현재 업무/공통 오류입니다. message 대신 HTTP status와 code로 분기합니다.

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID/Query 형식 |
| 401 | AUTHENTICATION_REQUIRED | 인증/입장 필요 |
| 401 | INVALID_TOKEN | 강사 토큰 만료/위조; 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 역할 권한 |
| 403 | CSRF_TOKEN_INVALID | 재취득; 변경 요청 자동 재전송 없음 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |
| 404 | CHILD_NOT_FOUND | 없거나 소유권 밖/이미 삭제한 아동 |

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

공통 [api-client.ts](../examples/api-client.ts), [types.ts](../examples/types.ts), [endpoints.ts](../examples/endpoints.ts)를 같은 폴더 구조로 복사합니다. 실서버의 origin·쿠키·권한 설정은 별도로 확인합니다.

### 요청·응답 타입

~~~ts
// 본문 없는 요청/응답입니다.
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface DeleteChildArgs {
  childId: T.UUID;
}

export function deleteChild(
  api: ApiClient,
  args: DeleteChildArgs,
  options: CallOptions = {}
): Promise<void> {
  return api.requestEmpty("/api/v1/children/" + encodeURIComponent(args.childId), {
    method: "DELETE",
    auth: "instructor",
    expectedStatuses: [204],
    signal: options.signal,
  });
}
~~~

### 사용 예시

~~~ts
import { deleteChild } from "./endpoints.ts";

await deleteChild(api, {
  "childId": "10000000-0000-4000-8000-000000000001"
});
// 개인정보·원본·토큰·응답 전문을 로그에 출력하지 않습니다.
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다. 실제 동작/삭제가 발생하는 요청이므로 테스트 데이터로 확인합니다.

~~~bash
curl -i -X DELETE "$NEURINGO_API_ORIGIN/api/v1/children/${childId}" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN" \
  -H "$NEURINGO_CSRF_HEADER: $NEURINGO_CSRF_TOKEN"
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S4-BAE-01` · `S4-JEONG-01`
- 코드 근거: [현재 Controller/DTO](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/blob/63e43144c92449ec5670390a6b99d20d2b2580a5/backend/src/main/java/com/neuringo/neuringobe/deletion/controller/DeletionController.java)
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
