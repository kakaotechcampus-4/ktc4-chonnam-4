# API-REC-05 · 추천 채택·수정 채택·보류·거절

> 구현 — 2026-11-06 / 브랜치 `feature/roleplay-report-integration`(develop `f017637` 위, PR #101 시나리오 검토 흐름 통합). 현재 코드의 HTTP 계약. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | PATCH |
| Path | `/api/v1/recommendations/{recommendationId}` |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 담당 강사 Bearer + CSRF + Idempotency-Key |
| 성공 | 200 |
| FE 함수 | decideRecommendation |
| 구현 | 민서(작업 브랜치), 계약 초안 배재일 |

## 2. 용도·중요 규칙

강사가 추천을 채택(ACCEPT)·고쳐서 채택(MODIFIED_ACCEPT)·보류(DEFER)·거절(REJECT)한다. 결정만 남기고 목표나 활동은 만들지 않는다. 화면은 채택 뒤 활동 만들기로 이동해 강사가 따로 배정한다(자동 배정 금지).

- 채택·고쳐서 채택·거절은 확정이다. 다시 바꾸면 409 `RECOMMENDATION_ALREADY_DECIDED`다. 보류는 나중에 다른 결정으로 바꿀 수 있다.
- 같은 `Idempotency-Key`에 같은 입력이면 앞 결과를 200으로 돌려준다. 같은 키에 다른 입력이면 409 `IDEMPOTENCY_KEY_REUSED`다.
- MODIFIED_ACCEPT는 `goal.title`이 있어야 한다. 없으면 422 `GOAL_REQUIRED`다.
- REJECT의 `rejectionReason`은 NOT_NOW·NOT_SUITABLE·ALREADY_PLANNED·OTHER 중 하나를 고른다. 다른 결정에서는 무시한다.

## 3. 요청

### Headers

| 이름 | 값/타입 | 필수 | 주의 |
|---|---|---|---|
| X-XSRF-TOKEN | GET /api/v1/csrf 의 token | 예 | 쿠키와 함께 |
| Idempotency-Key | UUID | 예 | 같은 행동 재전송은 같은 키·같은 입력 |

### Request Body

~~~json
{
  "decision": "MODIFIED_ACCEPT",
  "goal": {
    "title": "놀이터 장면에서 위로하는 말 하기"
  }
}
~~~

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| decision | RecommendationDecision | 예 | ACCEPT·MODIFIED_ACCEPT·DEFER·REJECT. 다른 값은 400 |
| goal.title | string ≤200 | MODIFIED_ACCEPT 때 | 고친 목표 제목 |
| goal.category | string ≤30 | 아니오 | 목표 분류 |
| goal.parentGoalId | UUID | 아니오 | 이어받을 목표 |
| rejectionReason | string | 아니오 | REJECT 때 이유 코드 |

## 4. 성공 응답

HTTP 200.

~~~json
{
  "data": {
    "recommendationId": "88000000-0000-4000-8000-000000000001",
    "recordId": "86000000-0000-4000-8000-000000000001",
    "activityId": "40000000-0000-4000-8000-000000000001",
    "childId": "10000000-0000-4000-8000-000000000001",
    "type": "REPEAT",
    "displayOrder": 1,
    "suggestedGoal": {
      "title": "넘어진 친구의 속상한 마음 알아보기",
      "category": "EMPATHY",
      "parentGoalId": "50000000-0000-4000-8000-000000000001"
    },
    "reason": "'감정의 이유 말하기' 목표는 두 선택지를 받고 도달했어요. 다른 장면으로 한 번 더 해 봐요.",
    "reasonCode": "REACHED_WITH_EXTRA_SUPPORT",
    "basisGoalTitle": "감정의 이유 말하기",
    "evidence": [
      "두 선택지를 들은 뒤 넘어져서 아픈 이유를 분명히 말함"
    ],
    "evidenceSufficiency": "SUFFICIENT",
    "decision": "ACCEPT",
    "decidedGoal": null,
    "rejectionReason": null,
    "createdGoalId": null,
    "policyVersion": "RECOMMENDATION_V1",
    "createdAt": "2026-10-31T14:20:01+09:00",
    "decidedAt": "2026-10-31T15:02:00+09:00"
  },
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

`data`는 결정이 반영된 `RecommendationData`다.

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID·쿼리·헤더 형식 |
| 401 | AUTHENTICATION_REQUIRED | 토큰 없음 |
| 401 | INVALID_TOKEN | 강사 토큰 만료·위조. 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 강사가 아닌 역할 |
| 403 | CSRF_TOKEN_INVALID | CSRF 토큰을 다시 받고 같은 키로 판단 |
| 404 | RECOMMENDATION_NOT_FOUND | 없는 추천·남의 추천 |
| 409 | IDEMPOTENCY_KEY_REUSED | 같은 키에 다른 입력 |
| 409 | RECOMMENDATION_ALREADY_DECIDED | 확정된 추천을 다시 결정 |
| 422 | GOAL_REQUIRED | MODIFIED_ACCEPT 인데 목표 제목 없음 |
| 422 | VALIDATION_FAILED | 제목 200자·분류 30자 초과, 거절 이유 코드 오류 |
| 500 | INTERNAL_ERROR | 내부 오류. 원문·예외 전문 비노출 |

남의 아동·기록·추천은 없는 자원과 같은 404로 숨깁니다. 공통 오류 envelope은 [공통 연동 규칙](../integration-guide.md)을 따릅니다.

## 6. 복사 가능한 TypeScript

공통 [api-client.ts](../examples/api-client.ts), [types.ts](../examples/types.ts), [endpoints.ts](../examples/endpoints.ts)를 같은 폴더 구조로 복사합니다. 응답 타입은 types.ts의 리포트·추천 절에 있습니다.

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface DecideRecommendationArgs {
  recommendationId: T.UUID;
  body: T.RecommendationDecisionBody;
  idempotencyKey: T.UUID;
}

export function decideRecommendation(
  api: ApiClient,
  args: DecideRecommendationArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.RecommendationData>> {
  return api.request<T.RecommendationData>("/api/v1/recommendations/" + encodeURIComponent(args.recommendationId), {
    method: "PATCH",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    idempotencyKey: args.idempotencyKey,
    body: args.body
  });
}
~~~

## 7. cURL 계약 확인

~~~bash
curl -i -X PATCH "$NEURINGO_API_ORIGIN/api/v1/recommendations/{recommendationId}" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN" \
  -H "X-XSRF-TOKEN: $NEURINGO_CSRF_TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' -d '{"decision":"DEFER"}'
~~~

## 8. 연결 Task·검증

- 관련 Task ID: `S7-BAE-01`
- 서버: `backend/src/main/java/com/neuringo/neuringobe/report/controller/LearningReportController.java`. 설계 글은 `backend/docs/roleplay-report-integration.md`입니다.
- 검증: `LearningReportApiIntegrationTest`(완료 기록·소유권·결정·삭제), `OwnershipPatrolIntegrationTest`, `contracts/api-v1.json` 시나리오(백엔드 ApiContractTest·프론트 MSW 계약 테스트).
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
