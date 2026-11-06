# API-SCN-08 · 시나리오 승인 대기 활동 목록 조회

> 구현 — 2026-11-06 / 브랜치 `feature/scenario-review-screens`(develop `d1a7b0e` 위). 콘텐츠·승인 → 승인 대기함의 실제 구현 기준입니다. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | GET |
| Path | /api/v1/scenario-approvals |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 담당 강사 Bearer |
| 성공 | 200 |
| 반환 | ApiResponse<ScenarioApprovalItem[]> |
| FE 함수 | listScenarioApprovals |
| 구현·협업 | 배재일 |

## 2. 용도·중요 규칙

- 로그인한 강사의 학급에서 퀴즈로 난이도가 정해졌고 시나리오가 아직 없는 최초 결합 활동을 기다린 순서(퀴즈 결과 시각)대로 모은다. 빈 목록도 정상이다.
- 재사용 후보는 이미 있는 공용 승인본이라 그 아동을 위한 시나리오 행이 없다. 그래서 시나리오가 아니라 활동을 한 줄로 보여 준다. 시나리오 목록(API-SCN-02)과 목적이 다르다.
- `state`: 검토를 시작해 서버에 남은 검토가 있으면 `IN_REVIEW`, 아직 시작하지 않았으면 `WAITING`.
- FE는 두 상태 모두 같은 검토 화면으로 들어가 [준비·복원·다음 후보](./prepare-scenario.md)의 `START_OR_RESUME`를 보낸다. `IN_REVIEW`면 보던 후보에서 이어 본다.
- 후보가 있는지는 줄마다 조회하지 않는다. 후보 목록은 검토를 시작할 때 정해지고, 없으면 그 요청 안에서 새로 만든다.
- 후보 개수, 다른 아동이 언제 썼는지는 담지 않는다. 내 학급 활동만 모으므로 경로·쿼리에 남의 ID가 없다.
- 조회하면 민감 조회 기록(target=SCENARIO_APPROVALS, 대상 ID는 강사 자신)을 남긴다.

## 3. 요청

### Path parameters

없음.

### Query parameters

없음. page/size/sort는 아직 없습니다.

### Headers·쿠키

| 이름 | 값/타입 | 필수 | 주의 |
|---|---|---|---|
| Accept | application/json | 예 | 공통 envelope |
| Cookie | credentials: include | 예 | 브라우저 관리; HttpOnly 쿠키 직접 읽기 금지 |
| Authorization | Bearer <accessToken> | 예 | 담당 강사 |

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
      "childName": "가명아동",
      "classId": "20000000-0000-4000-8000-000000000001",
      "className": "햇살반",
      "goalId": "30000000-0000-4000-8000-000000000001",
      "goalTitle": "친구가 속상할 때 위로하는 말을 한다",
      "scenarioLevel": "L2",
      "waitingSince": "2026-11-01T10:00:00Z",
      "state": "IN_REVIEW"
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
| data[].activityId | UUID | 예 | 아니오 | 시나리오를 기다리는 최초 결합 활동 |
| data[].childId | UUID | 예 | 아니오 | 담당 아동 |
| data[].childName | string | 예 | 아니오 | 아동 표시 이름 |
| data[].classId | UUID | 예 | 아니오 | 내 학급 |
| data[].className | string | 예 | 아니오 | 학급 이름 |
| data[].goalId | UUID | 예 | 아니오 | 학습 목표 |
| data[].goalTitle | string | 예 | 아니오 | 목표 문장 |
| data[].scenarioLevel | ScenarioLevel | 예 | 아니오 | 퀴즈로 정해진 난이도 |
| data[].waitingSince | ISODateTime | 예 | 아니오 | 기다리기 시작한 때(퀴즈 결과 시각) |
| data[].state | string | 예 | 아니오 | `WAITING` · `IN_REVIEW` |

meta.traceId는 필수 문자열입니다. 빈 배열도 정상입니다.

## 5. 오류·FE 처리

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 401 | AUTHENTICATION_REQUIRED | 인증 필요 |
| 401 | INVALID_TOKEN | 강사 토큰 만료/위조; 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 강사가 아닌 역할 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |

세부 동작은 [공통 연동 규칙](../integration-guide.md)을 따릅니다.

## 6. 복사 가능한 TypeScript

### 요청·응답 타입

~~~ts
export type UUID = string;
export type ISODateTime = string;
export type ScenarioLevel = 'L1' | 'L2' | 'L3';

export interface ScenarioApprovalItem {
  activityId: UUID;
  childId: UUID;
  childName: string;
  classId: UUID;
  className: string;
  goalId: UUID;
  goalTitle: string;
  scenarioLevel: ScenarioLevel;
  waitingSince: ISODateTime;
  state: 'WAITING' | 'IN_REVIEW';
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import type * as T from "./types.ts";

export type ListScenarioApprovalsArgs = Record<string, never>;

export function listScenarioApprovals(
  api: ApiClient,
  _args: ListScenarioApprovalsArgs = {},
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ScenarioApprovalItem[]>> {
  return api.request<T.ScenarioApprovalItem[]>("/api/v1/scenario-approvals", {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}
~~~

## 7. cURL 계약 확인

~~~bash
curl -i -X GET "$NEURINGO_API_ORIGIN/api/v1/scenario-approvals" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN"
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S4-BAE-02`
- 코드 근거: `backend/src/main/java/com/neuringo/neuringobe/scenario/controller/ScenarioApprovalController.java` · `scenario/service/ScenarioApprovalService.java`
- 이번 구현은 최초 결합 활동만 대상입니다. 페이지 나누기·필터는 아직 없습니다.
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
