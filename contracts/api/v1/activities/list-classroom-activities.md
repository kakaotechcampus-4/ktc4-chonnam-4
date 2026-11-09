# API-ACT-05 · 학급 활동 목록 조회

> 구현 — 작업 브랜치 `feature/instructor-overview-screens`. 현재 코드의 HTTP 계약. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | GET |
| Path | /api/v1/classrooms/{classId}/activities |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 담당 강사 Bearer |
| 성공 | 200 |
| 반환 | ApiResponse<ClassroomActivityData[]> |
| FE 함수 | listClassroomActivities |
| 구현 | 배재일 |

## 2. 용도·중요 규칙

학급 아동 전체의 활동을 한 목록으로 돌려줍니다. 학급 상세의 활동 현황·배정 활동 탭과 강사 대시보드가 아동마다 [API-ACT-02](./list-child-activities.md)를 따로 부르지 않게 합니다.

- 최근 배정 순입니다. 같은 배정 시각이면 activityId 순으로 페이지 경계를 고정합니다.
- page는 0 이상, size는 1~100(기본 20)입니다. 범위를 벗어나면 400 `INVALID_REQUEST`입니다. 응답은 배열이고 전체 개수는 주지 않으므로, 받은 개수가 size보다 적을 때까지 다음 page를 이어 받습니다.
- 각 줄은 ActivityData와 같은 칸에 `childName`(아동 표시 이름)과 `goalTitle`(학습 목표 제목)을 더합니다. 독립 퀴즈는 목표가 없어 `goalTitle`이 null입니다.
- 준비 상태(`nextAction`·`blockedReason`)는 넣지 않습니다. 필요하면 아동별 목록이나 활동 상세를 부릅니다.
- 아동이 없는 학급은 빈 배열입니다. 남의 학급과 없는 학급은 같은 404 `CLASSROOM_NOT_FOUND`입니다.
- 강사만 부릅니다. 아동 세션은 403 `ACCESS_DENIED`입니다.

## 3. 요청

### Path parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| classId | UUID string | 예 | 담당 학급 |

### Query parameters

| 이름 | 타입 | 필수 | 기본값 | 조건 |
|---|---|---|---|---|
| page | number | 아니오 | 0 | 0 이상 정수 |
| size | number | 아니오 | 20 | 1~100 정수 |

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
      "childName": "민준",
      "goalId": "30000000-0000-4000-8000-000000000001",
      "goalTitle": "친구가 속상할 때 위로하는 말을 한다",
      "activityType": "FIRST_COMBINED",
      "scenarioId": null,
      "scenarioSource": null,
      "initialSupportLevel": null,
      "difficultyFallbackApplied": false,
      "status": "NOT_STARTED",
      "assignedAt": "2026-10-06T12:00:00+09:00",
      "startedAt": null,
      "completedAt": null,
      "rewardIssuedAt": null
    }
  ],
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

`data`는 `ClassroomActivityData` 배열입니다. [API-ACT-02](./list-child-activities.md)의 강사용 ActivityData 칸에 아래 두 칸을 더합니다.

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| data[].childName | string | 예 | 아니오 | 아동 표시 이름 |
| data[].goalTitle | string | 예 | 예 | 학습 목표 제목. 독립 퀴즈는 null |

## 5. 오류·FE 처리

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID·쿼리 형식, page·size 범위 |
| 401 | AUTHENTICATION_REQUIRED | 토큰 없음 |
| 401 | INVALID_TOKEN | 강사 토큰 만료·위조. 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 강사가 아닌 역할 |
| 404 | CLASSROOM_NOT_FOUND | 없는 학급·남의 학급 |
| 500 | INTERNAL_ERROR | 내부 오류. 원문·예외 전문 비노출 |

## 6. 복사 가능한 TypeScript

공통 [api-client.ts](../examples/api-client.ts), [types.ts](../examples/types.ts), [endpoints.ts](../examples/endpoints.ts)를 같은 폴더 구조로 복사합니다.

### 호출 함수

~~~ts
export interface ListClassroomActivitiesArgs {
  classId: T.UUID;
  query?: {
    page?: number;
    size?: number;
  };
}

export function listClassroomActivities(
  api: ApiClient,
  args: ListClassroomActivitiesArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ClassroomActivityData[]>> {
  return api.request<T.ClassroomActivityData[]>("/api/v1/classrooms/" + encodeURIComponent(args.classId) + "/activities" + queryString(args.query), {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}
~~~

## 7. cURL 계약 확인

~~~bash
curl -sS "$API_ORIGIN/api/v1/classrooms/$CLASS_ID/activities?page=0&size=20" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
~~~
