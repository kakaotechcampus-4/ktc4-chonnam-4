# API-SCN-08 · 역할극 단독 배정 시나리오 후보 조회

> 구현 — 2026-11-02 / `feature/roleplay-report-integration` 작업 브랜치. develop 미반영이며 운영 배포를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | GET |
| Path | `/api/v1/children/{childId}/roleplay-scenarios` |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 담당 강사 Bearer |
| 성공 | 200 |
| FE 함수 | listRoleplayScenarios |
| 구현 | 민서(작업 브랜치). 강사용 목록 API-SCN-02가 구현되면 그쪽으로 옮길 수 있음 |

## 2. 용도·중요 규칙

역할극 단독 활동(ROLEPLAY)을 배정할 때 강사가 고를 승인 시나리오를 돌려준다. 강사는 상황(summary)과 작은 목표(microGoals)를 보고 하나를 골라 API-ACT-01의 `scenarioId`로 보낸다. 서버는 아동이 시작할 때 시나리오를 대신 고르지 않는다.

- 후보는 `status = APPROVED`이고, 배정할 목표와 내용(content_hash)이 같고, 이 아동의 다음 역할극 난이도(API-REC-06) 레벨과 같은 승인본이다.
- 학급은 보지 않는다(팀 결정). 다른 학급에서 만든 승인본도 목표 내용·레벨이 같으면 후보다.
- 이 아동이 이미 쓴 승인본은 빠진다. 다른 아동이 쓴 승인본은 `reuseApprovalRequired: true`로 오고, 배정할 때 `reuseApproved: true`(강사의 재사용 승인)가 있어야 한다.
- 순서는 마지막으로 쓰인 지 가장 오래된 것부터다. 한 번도 쓰이지 않은 것이 맨 앞이고, 같으면 최근 승인본이 앞이다. "쓰였다"는 그 시나리오가 붙은 활동이 배정된 시각이다. 최대 50개.
- 목표는 저장하지 않는다. 쿼리의 제목·분류로 배정 때와 같은 방식으로 해시만 계산한다.

## 3. 요청

### Path parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| childId | UUID string | 예 | 담당 아동 |

### Query parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| goalTitle | string | 예 | 배정할 목표 제목. API-ACT-01 `goal.title`과 같은 값 |
| goalCategory | string | 아니오 | 배정할 목표 분류. API-ACT-01 `goal.category`와 같은 값 |

### Request Body

없음. 빈 JSON 객체를 추가하지 않습니다.

## 4. 성공 응답

HTTP 200.

~~~json
{
  "data": {
    "childId": "10000000-0000-4000-8000-000000000001",
    "scenarioLevel": "L2",
    "initialSupportLevel": "S1",
    "items": [
      {
        "scenarioId": "80000000-0000-4000-8000-000000000002",
        "scenarioVersion": 1,
        "scenarioLevel": "L2",
        "title": "놀이터에서 넘어진 친구",
        "summary": "쉬는 시간에 친구가 놀이터에서 넘어졌다.",
        "microGoals": ["친구의 속상한 마음 알아보기", "위로하는 말 하기"],
        "approvedAt": "2026-10-28T10:00:00+09:00",
        "lastUsedAt": null,
        "usedByOtherChildren": 0,
        "reuseApprovalRequired": false
      },
      {
        "scenarioId": "80000000-0000-4000-8000-000000000001",
        "scenarioVersion": 1,
        "scenarioLevel": "L2",
        "title": "교실에서 넘어진 친구",
        "summary": "교실에서 친구가 의자에 걸려 넘어졌다.",
        "microGoals": ["친구의 속상한 마음 알아보기", "넘어진 이유 말하기"],
        "approvedAt": "2026-10-20T10:00:00+09:00",
        "lastUsedAt": "2026-10-30T09:10:00+09:00",
        "usedByOtherChildren": 1,
        "reuseApprovalRequired": true
      }
    ]
  },
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| data.scenarioLevel | ScenarioLevel | 예 | 아니오 | 지금 배정하면 활동에 고정될 다음 역할극 레벨 |
| data.initialSupportLevel | SupportLevel | 예 | 아니오 | 그 시작 지원 |
| data.items[].scenarioId | UUID | 예 | 아니오 | API-ACT-01 `scenarioId`로 보낼 값 |
| data.items[].scenarioVersion | number | 예 | 아니오 | 승인 버전 |
| data.items[].title / summary | string | 예 | summary만 예 | 상황 |
| data.items[].microGoals | string[] | 예 | 아니오 | 작은 목표 설명. 보여 주는 순서일 뿐 통과 순서가 아님 |
| data.items[].approvedAt | ISODateTime | 예 | 예 | 승인 시각. 2026-11-02(V27) 이전 승인본은 만든 시각 |
| data.items[].lastUsedAt | ISODateTime | 예 | 예 | 마지막으로 쓰인 시각. 쓰인 적 없으면 null |
| data.items[].usedByOtherChildren | number | 예 | 아니오 | 이 시나리오를 쓴 다른 아동 수 |
| data.items[].reuseApprovalRequired | boolean | 예 | 아니오 | true면 배정할 때 `reuseApproved: true` 필요 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 맞는 승인본이 없으면 `items`가 빈 배열입니다.

## 5. 오류·FE 처리

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID·쿼리 형식, goalTitle 누락 |
| 401 | AUTHENTICATION_REQUIRED | 토큰 없음 |
| 401 | INVALID_TOKEN | 강사 토큰 만료·위조 |
| 403 | ACCESS_DENIED | 강사가 아닌 역할 |
| 404 | CHILD_NOT_FOUND | 없는 아동·남의 아동 |
| 404 | ROLEPLAY_DIFFICULTY_NOT_FOUND | 아직 마친 역할극이 없어 다음 난이도가 없음. 역할극 단독 배정 불가 |
| 422 | GOAL_REQUIRED | goalTitle이 공백 |
| 500 | INTERNAL_ERROR | 내부 오류. 원문·예외 전문 비노출 |

남의 아동은 없는 자원과 같은 404로 숨깁니다. 공통 오류 envelope은 [공통 연동 규칙](../integration-guide.md)을 따릅니다.

## 6. 복사 가능한 TypeScript

공통 [api-client.ts](../examples/api-client.ts), [types.ts](../examples/types.ts), [endpoints.ts](../examples/endpoints.ts)를 같은 폴더 구조로 복사합니다. 응답 타입은 types.ts의 리포트·추천 절 끝에 있습니다.

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface ListRoleplayScenariosArgs {
  childId: T.UUID;
  query: {
    goalTitle: string;
    goalCategory?: string;
  };
}

export function listRoleplayScenarios(
  api: ApiClient,
  args: ListRoleplayScenariosArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.RoleplayScenarioCandidatesData>> {
  return api.request<T.RoleplayScenarioCandidatesData>("/api/v1/children/" + encodeURIComponent(args.childId) + "/roleplay-scenarios" + queryString(args.query), {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}
~~~

## 7. cURL 계약 확인

~~~bash
curl -i -G "$NEURINGO_API_ORIGIN/api/v1/children/{childId}/roleplay-scenarios" \
  --data-urlencode 'goalTitle=친구 마음 공감하기' \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN"
~~~

## 8. 연결 Task·검증

- 관련 Task ID: `S7-JEONG`. PR #90 리뷰(시나리오는 강사가 배정 때 고른다, 재사용 규칙, 학급 미고려)를 반영했습니다.
- 서버: `backend/src/main/java/com/neuringo/neuringobe/scenario/controller/RoleplayScenarioController.java`, 고르는 규칙은 `scenario/service/RoleplayScenarioSelectionService.java`, 조회는 `scenario/repository/RoleplayScenarioCandidateStore.java`, 승인 시각은 `V27__scenario_approved_at.sql`입니다.
- 검증: `StandaloneRoleplayIntegrationTest`(후보 순서·학급 무관·이미 쓴 것 제외·배정 검사·시작), `contracts/api-v1.json` 시나리오(백엔드 ApiContractTest·프론트 MSW 계약 테스트).
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
