# API-ACT-02 · 아동 배정 활동 목록 조회

> 구현 — 2026-10-22 / develop `63e4314`. 현재 코드의 HTTP 계약. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | GET |
| Path | /api/v1/children/{childId}/activities |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 호출 인자 auth로 본인 아동/담당 강사를 구분 |
| 성공 | 200 |
| 반환 | ApiResponse<ActivityData[]> |
| FE 함수 | listChildActivities |
| 구현·협업 | 윤상현 |

## 2. 용도·중요 규칙

- status/page/size로 조회하되 data는 배열이며 totalPages/totalElements 메타는 없습니다. page는0 이상, size는1~100입니다.
- 각 활동의 activityType을 확인합니다. 독립 퀴즈의 goalId는 null이므로 목표 상세를 무조건 요청하지 않습니다.
- 본인 아동/담당 강사만 조회합니다. 현재 공유 DTO의 평가/지원 필드를 아동 화면에 그대로 표시하지 않습니다.

## 3. 요청

### Path parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| childId | UUID string | 예 | 경로 식별자 |

### Query parameters

| 이름 | 타입 | 필수 | 기본값 | 조건 |
|---|---|---|---|---|
| status | ActivityStatus | 아니오 | 없음 | 상태 필터 |
| page | number | 아니오 | 0 | 0이상 정수 |
| size | number | 아니오 | 20 | 1~100 정수 |

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
  "data": [
    {
      "activityId": "40000000-0000-4000-8000-000000000001",
      "childId": "10000000-0000-4000-8000-000000000001",
      "goalId": "30000000-0000-4000-8000-000000000001",
      "scenarioId": null,
      "scenarioSource": null,
      "initialSupportLevel": null,
      "difficultyFallbackApplied": false,
      "status": "NOT_STARTED",
      "assignedAt": "2026-10-06T12:00:00+09:00",
      "startedAt": null,
      "completedAt": null,
      "rewardIssuedAt": null,
      "activityType": "FIRST_COMBINED"
    }
  ],
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| data[].activityId | UUID | 예 | 아니오 | 아동별 독립 활동 |
| data[].childId | UUID | 예 | 아니오 | 아동 |
| data[].goalId | UUID \| null | 예 | 예 | 목표. 독립 퀴즈는 null |
| data[].activityType | ActivityType | 예 | 아니오 | 활동 유형 |
| data[].scenarioId | UUID \| null | 예 | 예 | 현재 생성 시 null |
| data[].scenarioSource | string \| null | 예 | 예 | 현재 null; 연결 방식 보완 |
| data[].initialSupportLevel | SupportLevel \| null | 예 | 예 | 초기 지원; 아동 평가 projection 합의 필요 |
| data[].difficultyFallbackApplied | boolean | 예 | 아니오 | 기본값 적용 |
| data[].status | ActivityStatus | 예 | 아니오 | 수행 상태; 준비상태와 구분 |
| data[].assignedAt | ISODateTime | 예 | 아니오 | 배정/생성 시각 |
| data[].startedAt | ISODateTime \| null | 예 | 예 | 시작 시각 |
| data[].completedAt | ISODateTime \| null | 예 | 예 | 완료 시각. 독립 퀴즈는 마지막 문항 확정 때 채워짐. 최초 결합은 역할극 종료 연결 전이라 아직 null |
| data[].rewardIssuedAt | ISODateTime \| null | 예 | 예 | 스탬프 지급 시각. completedAt과 함께 한 번만 채워짐 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

현재 업무/공통 오류입니다. message 대신 HTTP status와 code로 분기합니다.

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID/Query 형식 |
| 401 | AUTHENTICATION_REQUIRED | 인증/입장 필요 |
| 401 | INVALID_TOKEN | 강사 토큰 만료/위조; 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 역할 권한 |
| 404 | CHILD_NOT_FOUND | 본인/담당 범위 아님 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 400,
    "code": "INVALID_REQUEST",
    "message": "요청을 확인해 주세요.",
    "path": "/api/v1/children/{childId}/activities",
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
export type UUID = string;

export type ActivityType = 'FIRST_COMBINED' | 'SELF_EMOTION_QUIZ' | 'OTHER_EMOTION_QUIZ' | 'ROLEPLAY';

export type SupportLevel = 'S0' | 'S1' | 'S2' | 'S3';

export type ActivityStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'PAUSED' | 'COMPLETED' | 'RECOVERY_NEEDED';

export type ISODateTime = string;

export interface ActivityData {
  /** 아동별 독립 활동 */
  activityId: UUID;
  /** 아동 */
  childId: UUID;
  /** 목표. 독립 퀴즈는 null */
  goalId: UUID | null;
  /** 활동 유형 */
  activityType: ActivityType;
  /** 현재 생성 시 null */
  scenarioId: UUID | null;
  /** 현재 null; 연결 방식 보완 */
  scenarioSource: string | null;
  /** 초기 지원; 아동 평가 projection 합의 필요 */
  initialSupportLevel: SupportLevel | null;
  /** 기본값 적용 */
  difficultyFallbackApplied: boolean;
  /** 수행 상태; 준비상태와 구분 */
  status: ActivityStatus;
  /** 배정/생성 시각 */
  assignedAt: ISODateTime;
  /** 시작 시각 */
  startedAt: ISODateTime | null;
  /** 완료 시각. 독립 퀴즈는 마지막 문항 확정 때 채워짐. 최초 결합은 역할극 종료 연결 전이라 아직 null */
  completedAt: ISODateTime | null;
  /** 스탬프 지급 시각. completedAt과 함께 한 번만 채워짐 */
  rewardIssuedAt: ISODateTime | null;
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface ListChildActivitiesArgs {
  childId: T.UUID;
  auth: "instructor" | "child";
  query?: {
    status?: T.ActivityStatus;
    page?: number;
    size?: number;
  };
}

export function listChildActivities(
  api: ApiClient,
  args: ListChildActivitiesArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ActivityData[]>> {
  return api.request<T.ActivityData[]>("/api/v1/children/" + encodeURIComponent(args.childId) + "/activities" + queryString(args.query), {
    method: "GET",
    auth: args.auth,
    expectedStatuses: [200],
    signal: options.signal
  });
}
~~~

### 사용 예시

~~~ts
import { listChildActivities } from "./endpoints.ts";

const response = await listChildActivities(api, {
  "childId": "10000000-0000-4000-8000-000000000001",
  "auth": "child",
  "query": {
    "page": 0,
    "size": 20
  }
});
const data = response.data;
// 개인정보·원본·토큰·응답 전문을 로그에 출력하지 않습니다.
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다. 실제 동작/삭제가 발생하는 요청이므로 테스트 데이터로 확인합니다.

~~~bash
curl -i -X GET "$NEURINGO_API_ORIGIN/api/v1/children/${childId}/activities" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN"
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S4-YOON-01`
- 코드 근거: [현재 Controller/DTO](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/blob/63e43144c92449ec5670390a6b99d20d2b2580a5/backend/src/main/java/com/neuringo/neuringobe/activity/dto/ActivityResponse.java)
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
