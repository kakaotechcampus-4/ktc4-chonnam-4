# API-REC-06 · 다음 역할극 난이도 조회

> 구현 — 2026-11-06 / 브랜치 `feature/roleplay-report-integration`(develop `f017637` 위, PR #101 시나리오 검토 흐름 통합). 현재 코드의 HTTP 계약. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | GET |
| Path | `/api/v1/children/{childId}/roleplay-difficulty` |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 담당 강사 Bearer |
| 성공 | 200 |
| FE 함수 | getRoleplayDifficulty |
| 구현 | 민서(작업 브랜치), 계약 초안 배재일 |

## 2. 용도·중요 규칙

아동이 마지막으로 마친 역할극 뒤에 서버가 정한 다음 역할극 난이도(03 VS-016)를 돌려준다. 활동 만들기의 역할극 확인 단계와 추천 화면이 "다음 역할극은 L2 · 시작 지원 S1"처럼 보여 줄 때 쓴다.

- 결정은 06 확정 정책표(`FOLLOW_UP_ROLEPLAY_V1`)를 따른다. 점수율 50% 미만이거나 S3·미도달 목표가 유효 목표의 절반 이상이면 한 단계 내리고, 80% 이상이고 모든 유효 목표를 S0~S2에서 도달한 것이 2번 이어지면 한 단계 올린다. 그 밖에는 유지한다.
- 시작 지원은 레벨에 따라 L1·S2, L2·S1, L3·S0이다.
- 역할극 단독 활동(ROLEPLAY)을 배정하면 그 시점의 이 값이 활동에 고정된다. 아직 마친 역할극이 없으면 404 `ROLEPLAY_DIFFICULTY_NOT_FOUND`이고, 역할극 단독 배정도 409 `ROLEPLAY_DIFFICULTY_NOT_READY`로 막힌다.

## 3. 요청

### Path parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| childId | UUID string | 예 | 담당 아동 |

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
    "previousLevel": "L2",
    "direction": "KEEP",
    "reason": "SCORE_IN_KEEP_RANGE",
    "scoreNumerator": 9,
    "scoreDenominator": 12,
    "consecutiveUpgradeCandidates": 0,
    "policyVersion": "FOLLOW_UP_ROLEPLAY_V1",
    "sourceActivityId": "40000000-0000-4000-8000-000000000001",
    "sourceRecordId": "86000000-0000-4000-8000-000000000001",
    "decidedAt": "2026-10-31T14:20:01+09:00"
  },
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| data.scenarioLevel | ScenarioLevel | 예 | 아니오 | 다음 역할극 레벨 |
| data.initialSupportLevel | SupportLevel | 예 | 아니오 | 다음 역할극 시작 지원 |
| data.previousLevel | ScenarioLevel | 예 | 아니오 | 마친 역할극 레벨 |
| data.direction | string | 예 | 아니오 | LOWER·KEEP·RAISE |
| data.reason | string | 예 | 아니오 | 결정 이유 코드 |
| data.scoreNumerator / scoreDenominator | number | 예 | 아니오 | 점수율. 분모는 유효 목표 수의 6배 |
| data.consecutiveUpgradeCandidates | number | 예 | 아니오 | 이어진 상향 후보 수 |
| data.policyVersion | string | 예 | 아니오 | 정책 버전. 설정을 바꾸면 뒤에 값이 붙음 |
| data.sourceActivityId | UUID | 예 | 아니오 | 결정을 낸 활동 |
| data.sourceRecordId | UUID | 예 | 예 | 그 활동의 학습 기록. AI 요약이 실패해 리포트가 404여도 이 ID로 추천(API-REC-03)을 읽는다 |
| data.decidedAt | ISODateTime | 예 | 아니오 | 결정 시각 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID·쿼리·헤더 형식 |
| 401 | AUTHENTICATION_REQUIRED | 토큰 없음 |
| 401 | INVALID_TOKEN | 강사 토큰 만료·위조. 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 강사가 아닌 역할 |
| 404 | CHILD_NOT_FOUND | 없는 아동·남의 아동 |
| 404 | ROLEPLAY_DIFFICULTY_NOT_FOUND | 아직 마친 역할극이 없음 |
| 500 | INTERNAL_ERROR | 내부 오류. 원문·예외 전문 비노출 |

남의 아동·기록·추천은 없는 자원과 같은 404로 숨깁니다. 공통 오류 envelope은 [공통 연동 규칙](../integration-guide.md)을 따릅니다.

## 6. 복사 가능한 TypeScript

공통 [api-client.ts](../examples/api-client.ts), [types.ts](../examples/types.ts), [endpoints.ts](../examples/endpoints.ts)를 같은 폴더 구조로 복사합니다. 응답 타입은 types.ts의 리포트·추천 절에 있습니다.

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface GetRoleplayDifficultyArgs {
  childId: T.UUID;
}

export function getRoleplayDifficulty(
  api: ApiClient,
  args: GetRoleplayDifficultyArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.RoleplayDifficultyData>> {
  return api.request<T.RoleplayDifficultyData>("/api/v1/children/" + encodeURIComponent(args.childId) + "/roleplay-difficulty", {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}
~~~

## 7. cURL 계약 확인

~~~bash
curl -i -X GET "$NEURINGO_API_ORIGIN/api/v1/children/{childId}/roleplay-difficulty" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN"
~~~

## 8. 연결 Task·검증

- 관련 Task ID: `S7-JEONG`
- 서버: `backend/src/main/java/com/neuringo/neuringobe/report/controller/LearningReportController.java`. 설계 글은 `backend/docs/roleplay-report-integration.md`입니다.
- 검증: `LearningReportApiIntegrationTest`(완료 기록·소유권·결정·삭제), `OwnershipPatrolIntegrationTest`, `contracts/api-v1.json` 시나리오(백엔드 ApiContractTest·프론트 MSW 계약 테스트).
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
