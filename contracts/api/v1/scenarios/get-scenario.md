# API-SCN-03 · 시나리오 검토 상세 조회

> 설계 — 2026-10-22 / develop `63e4314`. 설계·미구현. 경로/필드/상태는 제안이며 allowDrafts 기본 차단입니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | GET |
| Path | /api/v1/scenarios/{scenarioId} |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 담당 강사 Bearer |
| 성공 | 200 — 설계 예시 |
| 반환 | ApiResponse<DraftScenarioData> |
| FE 함수 | getScenario |
| 구현·협업 | 배재일 |

## 2. 용도·중요 규칙

- 강사검토용콘텐츠. 승인버전·사실·금지추론·목표증거포함. 버전선택방식미정.

## 3. 요청

### Path parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| scenarioId | UUID string | 예 | 경로 식별자 |

### Query parameters

없음. 지원되지 않는 page/size/sort를 보내도 적용된다고 가정하지 않습니다.

### Headers·쿠키

| 이름 | 값/타입 | 필수 | 주의 |
|---|---|---|---|
| Accept | application/json | 예 | 공통 envelope |
| Cookie | credentials: include | 예 | 브라우저 관리; HttpOnly 쿠키 직접 읽기 금지 |
| Authorization | Bearer <accessToken> | 예 | 아동/public에 강사 토큰을 붙이지 않음 |

### Request Body

없음. 빈 JSON 객체를 추가하지 않습니다.

## 4. 성공 응답

HTTP 200 — 설계 예시.

~~~json
{
  "data": {
    "scenarioId": "80000000-0000-4000-8000-000000000001",
    "version": 1,
    "goalId": "30000000-0000-4000-8000-000000000001",
    "scenarioSource": "NEWLY_GENERATED",
    "status": "DRAFT",
    "scenarioLevel": "L1",
    "title": "넘어져서 우는 친구",
    "summary": "친구가 넘어져서 울고 있다.",
    "scenarioFacts": [
      "친구가 넘어졌다.",
      "친구가 울고 있다."
    ],
    "prohibitedInferences": [
      "일부러 넘어졌다고 단정하지 않는다."
    ],
    "characters": [
      {
        "id": "FRIEND",
        "role": "친구"
      }
    ],
    "microGoals": [
      {
        "microGoalId": "81000000-0000-4000-8000-000000000001",
        "description": "친구의 몸과 마음을 표현한다.",
        "requiredEvidence": [
          "상황에 맞는 감정을 표현함"
        ]
      }
    ],
    "openingPrompts": {
      "S0": "친구는 어떤 기분일까?",
      "S1": "친구가 넘어져서 울고 있어. 어떤 기분일까?"
    }
  },
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| data.scenarioId | UUID | 예 | 아니오 | 시나리오 |
| data.version | number | 예 | 아니오 | 승인 버전 |
| data.goalId | UUID | 예 | 아니오 | 관련 목표 |
| data.scenarioSource | string | 예 | 아니오 | NEWLY_GENERATED/REUSED 제안 |
| data.status | string | 예 | 아니오 | DRAFT/APPROVED/REVISION_REQUIRED/REJECTED 제안 |
| data.scenarioLevel | ScenarioLevel | 예 | 아니오 | 난이도 |
| data.title | string | 예 | 아니오 | 제목 |
| data.summary | string | 예 | 아니오 | 요약 |
| data.scenarioFacts | string[] | 예 | 아니오 | 승인 사실 |
| data.prohibitedInferences | string[] | 예 | 아니오 | 금지 추론 |
| data.characters | Record<string, unknown>[] | 예 | 아니오 | 실명 프로필 포함 금지 |
| data.microGoals | DraftMicroGoal[] | 예 | 아니오 | 순서 없는 목표 |
| data.openingPrompts | Record<string, string> | 예 | 아니오 | 지원별 시작 질문; 구체 S운용 미정 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

아래 업무 오류도 설계 예시이며 구현 전 합의가 필요합니다.

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID/Query 형식 |
| 401 | AUTHENTICATION_REQUIRED | 인증/입장 필요 |
| 401 | INVALID_TOKEN | 강사 토큰 만료/위조; 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 역할 권한 |
| 404 | RESOURCE_NOT_FOUND | 리소스/소유권 코드 제안 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 400,
    "code": "INVALID_REQUEST",
    "message": "요청을 확인해 주세요.",
    "path": "/api/v1/scenarios/{scenarioId}",
    "traceId": "90000000-0000-4000-8000-000000000001",
    "fieldErrors": []
  }
}
~~~

네트워크·Abort·성공 응답 파싱 오류는 서버 미실행을 뜻하지 않습니다. 변경 요청을 새 키로 자동 재전송하지 않습니다. CSRF 오류는 토큰을 다시 받고 원래 행동/키로 복구를 판단합니다. 강사 보호 요청401은 토큰·입력·캐시 정리, 아동401은 재입장 안내입니다. refresh 로그인 API는 없습니다. 세부 동작은 [공통 연동 규칙](../integration-guide.md)을 따릅니다.

## 6. 복사 가능한 TypeScript

공통 [api-client.ts](../examples/api-client.ts), [types.ts](../examples/types.ts), [draft-endpoints.ts](../examples/draft-endpoints.ts)를 같은 폴더 구조로 복사합니다. allowDrafts는 Mock에서만 켭니다.

### 요청·응답 타입

~~~ts
export type UUID = string;

export type ScenarioLevel = 'L1' | 'L2' | 'L3';

export interface DraftMicroGoal {
  /** 목표 ID, 순서/번호 선행조건 아님 */
  microGoalId: UUID;
  /** 관찰 목표 */
  description: string;
  /** 관찰 가능한 학습 근거 */
  requiredEvidence: string[];
}

export interface DraftScenarioData {
  /** 시나리오 */
  scenarioId: UUID;
  /** 승인 버전 */
  version: number;
  /** 관련 목표 */
  goalId: UUID;
  /** NEWLY_GENERATED/REUSED 제안 */
  scenarioSource: string;
  /** DRAFT/APPROVED/REVISION_REQUIRED/REJECTED 제안 */
  status: string;
  /** 난이도 */
  scenarioLevel: ScenarioLevel;
  /** 제목 */
  title: string;
  /** 요약 */
  summary: string;
  /** 승인 사실 */
  scenarioFacts: string[];
  /** 금지 추론 */
  prohibitedInferences: string[];
  /** 실명 프로필 포함 금지 */
  characters: Record<string, unknown>[];
  /** 순서 없는 목표 */
  microGoals: DraftMicroGoal[];
  /** 지원별 시작 질문; 구체 S운용 미정 */
  openingPrompts: Record<string, string>;
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface GetScenarioArgs {
  scenarioId: T.UUID;
}

export function getScenario(
  api: ApiClient,
  args: GetScenarioArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftScenarioData>> {
  return api.request<T.DraftScenarioData>("/api/v1/scenarios/" + encodeURIComponent(args.scenarioId), {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true
  });
}
~~~

### 사용 예시

~~~ts
import { getScenario } from "./draft-endpoints.ts";

const response = await getScenario(api, {
  "scenarioId": "80000000-0000-4000-8000-000000000001"
});
const data = response.data;
// 개인정보·원본·토큰·응답 전문을 로그에 출력하지 않습니다.
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다. 설계 경로는 실제 서버로 호출하지 않고 Mock에서 확인합니다.

~~~bash
curl -i -X GET "$NEURINGO_API_ORIGIN/api/v1/scenarios/${scenarioId}" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN"
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S4-BAE-02`
- 현재 서버 Controller가 없는 설계 계약입니다. 구현 PR에서 경로·권한·DTO를 확정한 뒤 연결합니다.
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
