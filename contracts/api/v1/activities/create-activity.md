# API-ACT-01 · 활동·목표 원자 생성 및 문항 배정

> 구현 — 2026-10-22 / develop `63e4314`. 현재 코드의 HTTP 계약. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | POST |
| Path | /api/v1/activities |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 담당 강사 Bearer + CSRF + Idempotency-Key |
| 성공 | 201 / 200 |
| 반환 | ApiResponse<ActivityData> |
| FE 함수 | createActivity |
| 구현·협업 | 배재일·진미나 |

## 2. 용도·중요 규칙

- childId와 activityType, 필요한 경우 goal 내용을 함께 전달합니다. 기존 goalId 필드는 현재 계약이 아닙니다. 목표 저장·활동 생성·문항 배정은 한 트랜잭션이며 실패 시 함께 롤백됩니다.
- Idempotency-Key(UUID)는 필수입니다. 같은 키·같은 아동/유형/목표 내용이면 기존 활동을 200, 새 배정은 201로 반환합니다. 동일 키의 다른 요청은 409입니다.
- activityType 생략/null은 FIRST_COMBINED입니다. FIRST_COMBINED는 아동당 한 번이고 goal 필수입니다. 독립 SELF_EMOTION_QUIZ/OTHER_EMOTION_QUIZ는 최초 결합 COMPLETED 이후만 배정하며 goal을 넣지 않습니다.
- ROLEPLAY는 enum에 있지만 현재 422 ACTIVITY_TYPE_NOT_AVAILABLE로 거부합니다. 독립 퀴즈는 같은 아동·같은 유형의 NOT_STARTED 활동이 있으면 409입니다.
- 최초 결합은 세 유형별1개 총3문항, 내 감정 독립 퀴즈는10문항, 상대 감정 독립 퀴즈는 상황7+이미지3문항입니다. 한 문항이라도 부족하면 422이고 부분 활동을 남기지 않습니다.
- 시나리오 승인·동의·역할극 시작 전체 구현 완료를 의미하지 않습니다. 현재 scenarioId는 null입니다.

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
| Authorization | Bearer <accessToken> | 예 | 아동/public에 강사 토큰을 붙이지 않음 |
| CSRF Header | GET /api/v1/csrf의 headerName:token | 예 | 쿠키와 함께 전송 |
| Idempotency-Key | UUID string | 예 | 동일 행동 재전송은 같은 키/같은 입력 |
| Content-Type | application/json; charset=utf-8 | 예 | JSON body |

### Request Body

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| childId | UUID | 예 | 아니오 | 담당 아동 UUID |
| activityType | ActivityType \| null | 아니오 | 예 | 생략/null은 FIRST_COMBINED. ROLEPLAY는 현재 422로 거부 |
| goal | GoalBody \| null | 아니오 | 예 | FIRST_COMBINED/ROLEPLAY 필수, 독립 퀴즈 금지. goalId 대신 목표 내용 전달 |

~~~json
{
  "childId": "10000000-0000-4000-8000-000000000001",
  "activityType": "FIRST_COMBINED",
  "goal": {
    "title": "감정을 이해하고 공감하기",
    "situationType": "PLAYGROUND",
    "category": "EMOTION"
  }
}
~~~

## 4. 성공 응답

HTTP 201 / 200.

~~~json
{
  "data": {
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
  },
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| data.activityId | UUID | 예 | 아니오 | 아동별 독립 활동 |
| data.childId | UUID | 예 | 아니오 | 아동 |
| data.goalId | UUID \| null | 예 | 예 | 목표. 독립 퀴즈는 null |
| data.activityType | ActivityType | 예 | 아니오 | 활동 유형 |
| data.scenarioId | UUID \| null | 예 | 예 | 현재 생성 시 null |
| data.scenarioSource | string \| null | 예 | 예 | 현재 null; 연결 방식 보완 |
| data.initialSupportLevel | SupportLevel \| null | 예 | 예 | 초기 지원; 아동 평가 projection 합의 필요 |
| data.difficultyFallbackApplied | boolean | 예 | 아니오 | 기본값 적용 |
| data.status | ActivityStatus | 예 | 아니오 | 수행 상태; 준비상태와 구분 |
| data.assignedAt | ISODateTime | 예 | 아니오 | 배정/생성 시각 |
| data.startedAt | ISODateTime \| null | 예 | 예 | 시작 시각 |
| data.completedAt | ISODateTime \| null | 예 | 예 | 완료 시각 |
| data.rewardIssuedAt | ISODateTime \| null | 예 | 예 | 지급 시각; 필드 존재가 구현 완료 의미 아님 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

현재 업무/공통 오류입니다. message 대신 HTTP status와 code로 분기합니다.

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 401 | AUTHENTICATION_REQUIRED | 인증/입장 필요 |
| 401 | INVALID_TOKEN | 강사 토큰 만료/위조; 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 역할 권한 |
| 403 | CSRF_TOKEN_INVALID | 재취득; 변경 요청 자동 재전송 없음 |
| 404 | CHILD_NOT_FOUND | 대상 권한 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |
| 409 | IDEMPOTENCY_KEY_REUSED | 같은 키의 다른 아동/유형/목표 |
| 409 | DUPLICATE_ASSIGNMENT | 동일 독립 유형의 시작 전 활동 존재 |
| 409 | CHILD_NOT_ACTIVE | 참여 중 아동이 아님 |
| 409 | FIRST_ACTIVITY_EXISTS | 최초 결합 활동이 이미 배정됨 |
| 409 | FIRST_ACTIVITY_NOT_COMPLETED | 최초 결합 미완료 |
| 422 | GOAL_REQUIRED | 최초 결합/역할극 목표 누락 |
| 422 | GOAL_NOT_ALLOWED | 독립 퀴즈에 목표 입력 |
| 422 | ACTIVITY_TYPE_NOT_AVAILABLE | 현재 ROLEPLAY 배정 불가 |
| 422 | QUIZ_ITEMS_UNAVAILABLE | 구성 문항 부족 |
| 422 | INVALID_GOAL_CONDITIONS | 목표 조건 배열의 null/공백 원소 |
| 422 | VALIDATION_FAILED | 필수 필드/중첩 DTO 검증 |
| 404 | GOAL_NOT_FOUND | 부모 목표 없음/다른 아동 |
| 400 | INVALID_REQUEST | UUID/필수 헤더·파트/Query 형식 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 401,
    "code": "AUTHENTICATION_REQUIRED",
    "message": "요청을 확인해 주세요.",
    "path": "/api/v1/activities",
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

export interface GoalBody {
  /** 최대200자 */
  title: string;
  /** 최대30자, 선택 */
  situationType?: string | null;
  /** 목표 분류, 최대30자. 별도 enum 아님 */
  category?: string | null;
  /** 추가 등장인물; 누락/null은 현재 [] 정리 */
  characters?: Record<string, unknown>[] | null;
  /** 빈/공백/null 원소는 조건 오류 */
  requiredElements?: string[] | null;
  /** 빈/공백/null 원소는 조건 오류 */
  forbiddenExpressions?: string[] | null;
  /** 접근 가능한 동일 아동의 이전 목표 */
  parentGoalId?: UUID | null;
}

export interface ActivityBody {
  /** 담당 아동 UUID */
  childId: UUID;
  /** 생략/null은 FIRST_COMBINED. ROLEPLAY는 현재 422로 거부 */
  activityType?: ActivityType | null;
  /** FIRST_COMBINED/ROLEPLAY 필수, 독립 퀴즈 금지. goalId 대신 목표 내용 전달 */
  goal?: GoalBody | null;
}

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
  /** 완료 시각 */
  completedAt: ISODateTime | null;
  /** 지급 시각; 필드 존재가 구현 완료 의미 아님 */
  rewardIssuedAt: ISODateTime | null;
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface CreateActivityArgs {
  body: T.ActivityBody;
  idempotencyKey: T.UUID;
}

export function createActivity(
  api: ApiClient,
  args: CreateActivityArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ActivityData>> {
  return api.request<T.ActivityData>("/api/v1/activities", {
    method: "POST",
    auth: "instructor",
    expectedStatuses: [201, 200],
    signal: options.signal,
    idempotencyKey: args.idempotencyKey,
    body: args.body
  });
}
~~~

### 사용 예시

~~~ts
import { createActivity } from "./endpoints.ts";

const response = await createActivity(api, {
  "body": {
    "childId": "10000000-0000-4000-8000-000000000001",
    "activityType": "FIRST_COMBINED",
    "goal": {
      "title": "감정을 이해하고 공감하기",
      "situationType": "PLAYGROUND",
      "category": "EMOTION"
    }
  },
  "idempotencyKey": "70000000-0000-4000-8000-000000000001"
});
const data = response.data;
// 개인정보·원본·토큰·응답 전문을 로그에 출력하지 않습니다.
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다. 실제 동작/삭제가 발생하는 요청이므로 테스트 데이터로 확인합니다.

~~~bash
curl -i -X POST "$NEURINGO_API_ORIGIN/api/v1/activities" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN" \
  -H "$NEURINGO_CSRF_HEADER: $NEURINGO_CSRF_TOKEN" \
  -H "Idempotency-Key: $NEURINGO_REQUEST_KEY" \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data '{"childId":"10000000-0000-4000-8000-000000000001","activityType":"FIRST_COMBINED","goal":{"title":"감정을 이해하고 공감하기","situationType":"PLAYGROUND","category":"EMOTION"}}'
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S4-BAE-02` · `S4-JIN-02`
- 코드 근거: [현재 Controller/DTO](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/blob/63e43144c92449ec5670390a6b99d20d2b2580a5/backend/src/main/java/com/neuringo/neuringobe/assignment/service/ActivityAssignmentService.java)
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
