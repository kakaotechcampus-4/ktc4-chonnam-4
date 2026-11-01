# API-SCN-04 · 시나리오 초안 수정·승인 검토

> 구현 — 2026-11-01 / 브랜치 `feature/scenario-reuse-approval`. 시나리오 재사용 승인과 [수정하기] 흐름의 실제 구현 기준입니다. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | PATCH |
| Path | /api/v1/scenarios/{scenarioId} |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 담당 강사 Bearer + CSRF |
| 성공 | 200 |
| 반환 | ApiResponse<ScenarioData> |
| FE 함수 | reviewScenario |
| 구현·협업 | 배재일 |

## 2. 용도·중요 규칙

- 그 학습 목표의 담당 강사만 초안(`DRAFT`)을 고치고 판정한다. 판정이 끝난 판은 바꿀 수 없다(409 `SCENARIO_NOT_EDITABLE`, DB도 막음).
- `version`은 초안의 판 번호와 같아야 한다.
- 칸 수정: `title`·`summary`·`microGoals`(있는 작은 목표의 설명·필요 증거만). 고정 사실·금지 추론·시작 질문은 직접 고치지 않는다. 강사가 쓴 문장은 생성 결과 검사를 거치지 않으므로 빈 값·보이지 않는 문자·목표의 피할 표현을 서버가 거절한다.
- 판정 `status`: 칸을 먼저 고친 뒤 판정한다. `APPROVED`면 작은 목표·필요 증거가 있어야 하고, 그 목표로 만든 최초 결합 활동에 붙으며 공용 재사용 후보가 된다(원본은 그대로). 승인본은 목표에서 떨어지지만 응답의 `goalId`는 붙은 활동의 목표다.
- `REJECTED`는 기록으로 남고 활동은 계속 시나리오를 기다린다. 자동으로 다시 만들지 않는다.
- `REVISION_REQUIRED`(다시 만들기)는 시나리오 생성이 들어오기 전까지 409 `SCENARIO_REGENERATION_UNAVAILABLE`. 칸을 직접 고쳐 저장한다.
- 판정 이유나 수정 지시 문장은 받지 않는다.

## 3. 요청

### Path parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| scenarioId | UUID string | 예 | 초안 ID |

### Query parameters

없음. 지원되지 않는 page/size/sort를 보내도 적용된다고 가정하지 않습니다.

### Headers·쿠키

| 이름 | 값/타입 | 필수 | 주의 |
|---|---|---|---|
| Accept | application/json | 예 | 공통 envelope |
| Cookie | credentials: include | 예 | 브라우저 관리; HttpOnly 쿠키 직접 읽기 금지 |
| Authorization | Bearer <accessToken> | 예 | 담당 강사 |
| CSRF Header | GET /api/v1/csrf의 headerName:token | 예 | 쿠키와 함께 전송 |
| Content-Type | application/json; charset=utf-8 | 예 | JSON body |

### Request Body

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| version | number | 예 | 아니오 | 초안의 판 번호 |
| status | string | 아니오 | 아니오 | `APPROVED` · `REJECTED` · `DRAFT`(그대로). `REVISION_REQUIRED`는 아직 409 |
| title | string | 아니오 | 아니오 | 1~200자 |
| summary | string | 아니오 | 아니오 | 1~1000자 |
| microGoals | DraftMicroGoalPatch[] | 아니오 | 아니오 | 있는 작은 목표의 설명·필요 증거 |

~~~json
{
  "version": 2,
  "status": "APPROVED"
}
~~~

## 4. 성공 응답

HTTP 200. 승인한 예시입니다.

~~~json
{
  "data": {
    "scenarioId": "80000000-0000-4000-8000-000000000002",
    "version": 2,
    "goalId": "30000000-0000-4000-8000-000000000001",
    "scenarioSource": "NEWLY_GENERATED",
    "status": "APPROVED",
    "scenarioLevel": "L2",
    "title": "그림을 망친 친구 위로하기",
    "summary": "쉬는 시간에 친구의 그림에 물이 쏟아져 친구가 속상해한다.",
    "scenarioFacts": [
      "친구의 그림에 물이 쏟아졌다.",
      "친구가 울먹인다."
    ],
    "prohibitedInferences": [
      "친구가 일부러 망쳤다고 단정하지 않는다."
    ],
    "characters": [
      {
        "id": "LEARNER",
        "role": "학습자"
      },
      {
        "id": "FRIEND",
        "role": "같은 반 친구"
      }
    ],
    "microGoals": [
      {
        "microGoalId": "81000000-0000-4000-8000-000000000002",
        "description": "친구의 감정을 말한다.",
        "requiredEvidence": [
          "속상하다는 감정을 말함"
        ]
      }
    ],
    "openingPrompts": {
      "S0": "친구는 지금 어떤 기분일까?",
      "S1": "친구가 울먹이고 있어. 어떤 기분일까?",
      "S2": "친구가 속상할까, 신날까?"
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
| data.scenarioId | UUID | 예 | 아니오 | 시나리오 판 ID. 고친 판은 새 ID |
| data.version | number | 예 | 아니오 | 판 번호. 고친 판은 원본 +1 |
| data.goalId | UUID | 예 | 예 | 재사용 후보면 그 활동의 목표, 초안·판정 기록이면 초안의 목표. 활동과 상관없이 공용 승인본만 조회하면 null |
| data.scenarioSource | string | 예 | 예 | `REUSED`(재사용 후보) · `NEWLY_GENERATED`(이 목표를 위해 만든 판). 공용 승인본만 조회하면 null |
| data.status | ScenarioStatus | 예 | 아니오 | `DRAFT` · `REVISION_REQUIRED` · `REJECTED` · `APPROVED` |
| data.scenarioLevel | ScenarioLevel | 예 | 아니오 | 난이도 |
| data.title | string | 예 | 아니오 | 제목 |
| data.summary | string | 예 | 예 | 상황 요약 |
| data.scenarioFacts | string[] | 예 | 아니오 | 고정 사실 |
| data.prohibitedInferences | string[] | 예 | 아니오 | 금지 추론 |
| data.characters | Record<string, unknown>[] | 예 | 아니오 | 등장인물 `{id, role}` |
| data.microGoals | ScenarioMicroGoal[] | 예 | 아니오 | 작은 목표. 순서는 보여 주는 순서 |
| data.openingPrompts | Record<string, unknown> | 예 | 아니오 | 지원 단계별 시작 질문 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다.

## 5. 오류·FE 처리

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID·JSON 형식 |
| 401 | AUTHENTICATION_REQUIRED | 인증 필요 |
| 401 | INVALID_TOKEN | 강사 토큰 만료/위조; 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 강사가 아닌 역할(아동 세션 등) |
| 403 | CSRF_TOKEN_INVALID | 재취득; 변경 요청 자동 재전송 없음 |
| 409 | SCENARIO_NOT_EDITABLE | 판정이 끝난 판 |
| 404 | SCENARIO_NOT_FOUND | 없는 ID, 남의 초안 |
| 409 | SCENARIO_VERSION_MISMATCH | 판 번호가 다름. 새로 불러오기 |
| 409 | SCENARIO_REGENERATION_UNAVAILABLE | 다시 만들기는 아직 없음 |
| 409 | SCENARIO_NO_WAITING_ACTIVITY | 승인해 붙일 활동이 없음 |
| 409 | SCENARIO_ALREADY_ATTACHED | 활동에 이미 다른 시나리오가 붙음 |
| 422 | VALIDATION_FAILED | 빈 값·보이지 않는 문자, 목표의 피할 표현 포함, 없는 작은 목표, 작은 목표 없이 승인, 알 수 없는 status |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 409,
    "code": "SCENARIO_NOT_EDITABLE",
    "message": "요청을 확인해 주세요.",
    "path": "/api/v1/scenarios/{scenarioId}",
    "traceId": "90000000-0000-4000-8000-000000000001",
    "fieldErrors": []
  }
}
~~~

네트워크·Abort·성공 응답 파싱 오류는 서버 미실행을 뜻하지 않습니다. 변경 요청을 새 키로 자동 재전송하지 않습니다. CSRF 오류는 토큰을 다시 받고 원래 행동/키로 복구를 판단합니다. 강사 보호 요청401은 토큰·입력·캐시 정리, 아동401은 재입장 안내입니다. refresh 로그인 API는 없습니다. 세부 동작은 [공통 연동 규칙](../integration-guide.md)을 따릅니다.

## 6. 복사 가능한 TypeScript

공통 [api-client.ts](../examples/api-client.ts), [types.ts](../examples/types.ts)를 같은 폴더 구조로 복사합니다. 공유 예제 파일(`examples/endpoints.ts`·`types.ts`·`fixtures.json`)은 이 구현에 맞춰 이어서 동기화합니다. 그 전에는 아래 정의를 사용합니다.

### 요청·응답 타입

~~~ts
export type UUID = string;

export type ScenarioStatus = 'DRAFT' | 'REVISION_REQUIRED' | 'REJECTED' | 'APPROVED';

export type ScenarioLevel = 'L1' | 'L2' | 'L3';

export interface ScenarioMicroGoal {
  microGoalId: UUID;
  description: string;
  requiredEvidence: string[];
}

export interface ScenarioData {
  scenarioId: UUID;
  /** 고친 판은 원본 +1 */
  version: number;
  /** 공용 승인본만 조회하면 null */
  goalId: UUID | null;
  /** REUSED · NEWLY_GENERATED. 공용 승인본만 조회하면 null */
  scenarioSource: 'REUSED' | 'NEWLY_GENERATED' | null;
  status: ScenarioStatus;
  scenarioLevel: ScenarioLevel;
  title: string;
  summary: string | null;
  scenarioFacts: string[];
  prohibitedInferences: string[];
  characters: Record<string, unknown>[];
  microGoals: ScenarioMicroGoal[];
  openingPrompts: Record<string, unknown>;
}

export interface DraftMicroGoalPatch {
  /** 이 시나리오의 작은 목표만. 더하거나 빼지 않는다 */
  microGoalId: UUID;
  /** 1~500자 */
  description: string;
  /** 1~5개, 각 1~200자 */
  requiredEvidence: string[];
}

export interface ReviewScenarioRequest {
  version: number;
  status?: 'DRAFT' | 'APPROVED' | 'REJECTED';
  title?: string;
  summary?: string;
  microGoals?: DraftMicroGoalPatch[];
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import type * as T from "./types.ts";

export interface ReviewScenarioArgs {
  scenarioId: T.UUID;
  body: T.ReviewScenarioRequest;
}

export function reviewScenario(
  api: ApiClient,
  args: ReviewScenarioArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ScenarioData>> {
  return api.request<T.ScenarioData>("/api/v1/scenarios/" + encodeURIComponent(args.scenarioId), {
    method: "PATCH",
    auth: "instructor",
    body: args.body,
    expectedStatuses: [200],
    signal: options.signal
  });
}
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다.

~~~bash
curl -i -X PATCH "$NEURINGO_API_ORIGIN/api/v1/scenarios/${scenarioId}" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN" \
  -H "$NEURINGO_CSRF_HEADER: $NEURINGO_CSRF_TOKEN" \
  -H 'Content-Type: application/json' \
  --data '{"version":2,"status":"APPROVED"}'
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S4-BAE-02`
- 코드 근거: `backend/src/main/java/com/neuringo/neuringobe/scenario/controller/ScenarioController.java` · `scenario/service/ScenarioDraftService.java`

- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제를 통합 테스트로 확인합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
