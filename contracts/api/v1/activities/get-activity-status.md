# API-ACT-04 · 활동 완료 status 전용 조회

> 구현 — 2026-10-27 / PR #76. 현재 코드의 HTTP 계약. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | GET |
| Path | /api/v1/activities/{activityId}/status |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 호출 인자 auth로 본인 아동/담당 강사를 구분 |
| 성공 | 200 |
| 반환 | ApiResponse<ActivityStatusData> |
| FE 함수 | getActivityStatus |
| 구현·협업 | 배재일·윤상현 |

## 2. 용도·중요 규칙

- 서버가 완료/보상 확정, FE는status를 읽는다. GET으로 완료/지급을 다시 실행하지 않는다.
- 독립 퀴즈(나의 감정·친구 감정)는 마지막 문항이 FINALIZED되는 PUT 요청 안에서 서버가 COMPLETED로 바꾸고 스탬프 지급 시각·학습 기록을 한 번만 남긴다. 따로 보내는 완료 요청은 없다.
- 마지막 문항 확정 응답을 받았으면 완료가 이미 저장된 상태라 이 조회를 한 번만 한다. 응답을 못 받았으면 이 조회부터 한다. COMPLETED면 스탬프 화면, 그 외는 원래 요청을 같은 키로 복구할지 판단한다.
- 최초 결합은 퀴즈만으로 완료되지 않는다(역할극 종료 연결 전).
- 본인 아동 세션 또는 담당 강사만 조회한다. 없는 활동·다른 아동의 활동·담당이 아닌 강사는 모두 404 ACTIVITY_NOT_FOUND로 같게 숨긴다(존재 여부를 드러내지 않음).
- 강사가 조회하면 민감 조회 기록(target=ACTIVITY)을 남긴다.

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
| Authorization | Bearer <accessToken> | 강사 모드 | 아동/public에 강사 토큰을 붙이지 않음 |

### Request Body

없음. 빈 JSON 객체를 추가하지 않습니다.

## 4. 성공 응답

HTTP 200.

~~~json
{
  "data": {
    "status": "COMPLETED"
  },
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| data.status | ActivityStatus | 예 | 아니오 | status만; 읽기로 보상/완료 실행 금지 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID/Query 형식 |
| 401 | AUTHENTICATION_REQUIRED | 인증/입장 필요 |
| 401 | INVALID_TOKEN | 강사 토큰 만료/위조; 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 역할 권한(아동·강사 외). 다른 아동·담당 아닌 강사는 403이 아니라 404 |
| 404 | ACTIVITY_NOT_FOUND | 없거나 소유권 밖 활동 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 400,
    "code": "INVALID_REQUEST",
    "message": "요청을 확인해 주세요.",
    "path": "/api/v1/activities/{activityId}/status",
    "traceId": "90000000-0000-4000-8000-000000000001",
    "fieldErrors": []
  }
}
~~~

네트워크·Abort·성공 응답 파싱 오류는 서버 미실행을 뜻하지 않습니다. 변경 요청을 새 키로 자동 재전송하지 않습니다. CSRF 오류는 토큰을 다시 받고 원래 행동/키로 복구를 판단합니다. 강사 보호 요청401은 토큰·입력·캐시 정리, 아동401은 재입장 안내입니다. refresh 로그인 API는 없습니다. 세부 동작은 [공통 연동 규칙](../integration-guide.md)을 따릅니다.

## 6. 복사 가능한 TypeScript

공통 [api-client.ts](../examples/api-client.ts), [types.ts](../examples/types.ts), [endpoints.ts](../examples/endpoints.ts)를 같은 폴더 구조로 복사합니다.

### 요청·응답 타입

~~~ts
export type ActivityStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'PAUSED' | 'COMPLETED' | 'RECOVERY_NEEDED';

export interface ActivityStatusData {
  /** status만; 읽기로 보상/완료 실행 금지 */
  status: ActivityStatus;
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface GetActivityStatusArgs {
  activityId: T.UUID;
  auth: "instructor" | "child";
}

export function getActivityStatus(
  api: ApiClient,
  args: GetActivityStatusArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ActivityStatusData>> {
  return api.request<T.ActivityStatusData>("/api/v1/activities/" + encodeURIComponent(args.activityId) + "/status", {
    method: "GET",
    auth: args.auth,
    expectedStatuses: [200],
    signal: options.signal
  });
}
~~~

### 사용 예시

~~~ts
import { getActivityStatus } from "./endpoints.ts";

const response = await getActivityStatus(api, {
  "activityId": "40000000-0000-4000-8000-000000000001",
  "auth": "child"
});
const data = response.data;
// 개인정보·원본·토큰·응답 전문을 로그에 출력하지 않습니다.
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다.

~~~bash
curl -i -X GET "$NEURINGO_API_ORIGIN/api/v1/activities/${activityId}/status" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN"
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S6-BAE-01` · `S6-BAE-02` · `S7-YOON-01`
- 코드 근거: [Controller](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/blob/ca04cd2/backend/src/main/java/com/neuringo/neuringobe/activity/controller/ActivityController.java) · [ActivityCompletionService](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/blob/ca04cd2/backend/src/main/java/com/neuringo/neuringobe/activity/service/ActivityCompletionService.java)
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
