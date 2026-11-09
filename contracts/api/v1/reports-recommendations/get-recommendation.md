# API-REC-04 · 추천 상세 조회

> 구현 — 2026-11-06 / 브랜치 `feature/roleplay-report-integration`(develop `f017637` 위, PR #101 시나리오 검토 흐름 통합). 현재 코드의 HTTP 계약. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | GET |
| Path | `/api/v1/recommendations/{recommendationId}` |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 담당 강사 Bearer |
| 성공 | 200 |
| FE 함수 | getRecommendation |
| 구현 | 민서(작업 브랜치), 계약 초안 배재일 |

## 2. 용도·중요 규칙

추천 하나의 유형·이유·근거·충분성과 강사의 마지막 결정을 돌려준다.

- 아동의 특성을 진단하거나 낙인찍는 표현은 쓰지 않는다. 이유 문장은 목표 이름과 받은 지원만 말한다.
- 다음 활동 목표 제안은 지금 학습 목표를 이어받는다(`suggestedGoal.parentGoalId`). 확장은 `title`이 null이라 강사가 넓힐 목표를 쓴다.
- `createdGoalId`는 늘 null이다. 목표와 활동은 강사가 활동 만들기에서 만든다.

## 3. 요청

### Path parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| recommendationId | UUID string | 예 | 추천 |

### Request Body

없음. 빈 JSON 객체를 추가하지 않습니다.

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
    "decision": null,
    "decidedGoal": null,
    "rejectionReason": null,
    "createdGoalId": null,
    "policyVersion": "RECOMMENDATION_V1",
    "createdAt": "2026-10-31T14:20:01+09:00",
    "decidedAt": null
  },
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| data.recommendationId | UUID | 예 | 아니오 | 추천 |
| data.recordId | UUID | 예 | 아니오 | 근거 학습 기록 |
| data.activityId | UUID | 예 | 아니오 | 근거 활동 |
| data.childId | UUID | 예 | 아니오 | 담당 아동 |
| data.type | RecommendationType | 예 | 아니오 | REINFORCE·REPEAT·APPLY·EXTEND |
| data.displayOrder | number | 예 | 아니오 | 1~3 |
| data.suggestedGoal | SuggestedGoal | 예 | 아니오 | 다음 활동 목표 제안 |
| data.reason | string | 예 | 아니오 | 강사 화면 문구(해요체) |
| data.reasonCode | string | 예 | 아니오 | 규칙 이유 코드 |
| data.basisGoalTitle | string | 예 | 예 | 근거 작은 목표 |
| data.evidence | string[] | 예 | 아니오 | 관찰 문장 |
| data.evidenceSufficiency | string | 예 | 아니오 | SUFFICIENT·LIMITED·INSUFFICIENT |
| data.decision | RecommendationDecision | 예 | 예 | 마지막 결정 |
| data.decidedGoal | SuggestedGoal | 예 | 예 | 고쳐서 채택한 목표 |
| data.rejectionReason | string | 예 | 예 | 거절 이유 코드 |
| data.createdGoalId | UUID | 예 | 예 | 늘 null |
| data.policyVersion | string | 예 | 아니오 | 추천 정책 버전 |
| data.createdAt | ISODateTime | 예 | 아니오 | 만든 시각 |
| data.decidedAt | ISODateTime | 예 | 예 | 결정 시각 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID·쿼리·헤더 형식 |
| 401 | AUTHENTICATION_REQUIRED | 토큰 없음 |
| 401 | INVALID_TOKEN | 강사 토큰 만료·위조. 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 강사가 아닌 역할 |
| 404 | RECOMMENDATION_NOT_FOUND | 없는 추천·남의 추천 |
| 500 | INTERNAL_ERROR | 내부 오류. 원문·예외 전문 비노출 |

남의 아동·기록·추천은 없는 자원과 같은 404로 숨깁니다. 공통 오류 envelope은 [공통 연동 규칙](../integration-guide.md)을 따릅니다.

## 6. 복사 가능한 TypeScript

공통 [api-client.ts](../examples/api-client.ts), [types.ts](../examples/types.ts), [endpoints.ts](../examples/endpoints.ts)를 같은 폴더 구조로 복사합니다. 응답 타입은 types.ts의 리포트·추천 절에 있습니다.

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface GetRecommendationArgs {
  recommendationId: T.UUID;
}

export function getRecommendation(
  api: ApiClient,
  args: GetRecommendationArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.RecommendationData>> {
  return api.request<T.RecommendationData>("/api/v1/recommendations/" + encodeURIComponent(args.recommendationId), {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}
~~~

## 7. cURL 계약 확인

~~~bash
curl -i -X GET "$NEURINGO_API_ORIGIN/api/v1/recommendations/{recommendationId}" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN"
~~~

## 8. 연결 Task·검증

- 관련 Task ID: `S7-BAE-01`
- 서버: `backend/src/main/java/com/neuringo/neuringobe/report/controller/LearningReportController.java`. 설계 글은 `backend/docs/roleplay-report-integration.md`입니다.
- 검증: `LearningReportApiIntegrationTest`(완료 기록·소유권·결정·삭제), `OwnershipPatrolIntegrationTest`, `contracts/api-v1.json` 시나리오(백엔드 ApiContractTest·프론트 MSW 계약 테스트).
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
