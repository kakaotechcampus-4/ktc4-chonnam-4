# API-SCN-03 · 시나리오 검토 상세 조회

> 구현 — 2026-11-01 / 브랜치 `feature/scenario-reuse-approval`. 시나리오 재사용 승인과 [수정하기] 흐름의 실제 구현 기준입니다. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | GET |
| Path | /api/v1/scenarios/{scenarioId} |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 담당 강사 Bearer |
| 성공 | 200 |
| 반환 | ApiResponse<ScenarioData> |
| FE 함수 | getScenario |
| 구현·협업 | 배재일 |

## 2. 용도·중요 규칙

- 초안·판정 기록(`DRAFT`·`REVISION_REQUIRED`·`REJECTED`)은 그 학습 목표의 담당 강사만 본다. 남의 것과 없는 ID는 모두 404 `SCENARIO_NOT_FOUND`로 같게 숨긴다.
- 공용 승인본(`APPROVED`)은 어느 강사나 본다. 활동과 상관없이 조회하므로 `goalId`·`scenarioSource`는 null이다.
- 학습 목표에 매이지 않은 옛 초안은 볼 사람이 없어 404다.
- 다른 아동이 이 시나리오를 언제·몇 번 썼는지는 응답에 담지 않는다.

## 3. 요청

### Path parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| scenarioId | UUID string | 예 | 시나리오 판 ID |

### Query parameters

없음. 지원되지 않는 page/size/sort를 보내도 적용된다고 가정하지 않습니다.

### Headers·쿠키

| 이름 | 값/타입 | 필수 | 주의 |
|---|---|---|---|
| Accept | application/json | 예 | 공통 envelope |
| Cookie | credentials: include | 예 | 브라우저 관리; HttpOnly 쿠키 직접 읽기 금지 |
| Authorization | Bearer <accessToken> | 예 | 담당 강사 |

### Request Body

없음. 빈 JSON 객체를 추가하지 않습니다.

## 4. 성공 응답

HTTP 200. 초안 예시입니다. 공용 승인본은 `goalId`·`scenarioSource`가 null입니다.

~~~json
{
  "data": {
    "scenarioId": "80000000-0000-4000-8000-000000000002",
    "version": 2,
    "editRevision": 0,
    "goalId": "30000000-0000-4000-8000-000000000001",
    "scenarioSource": "NEWLY_GENERATED",
    "status": "DRAFT",
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
| data.editRevision | number | 예 | 아니오 | 수정 번호. 같은 초안을 저장할 때마다 오르고 판 번호는 그대로다. 초안을 고치거나 판정할 때 그대로 보낸다 |
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
| 404 | SCENARIO_NOT_FOUND | 없는 ID, 남의 초안, 주인 없는 옛 초안 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 404,
    "code": "SCENARIO_NOT_FOUND",
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
  /** 수정 번호. 초안을 저장할 때마다 오른다. 고치거나 판정할 때 그대로 보낸다 */
  editRevision: number;
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
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import type * as T from "./types.ts";

export interface GetScenarioArgs {
  scenarioId: T.UUID;
}

export function getScenario(
  api: ApiClient,
  args: GetScenarioArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ScenarioData>> {
  return api.request<T.ScenarioData>("/api/v1/scenarios/" + encodeURIComponent(args.scenarioId), {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다.

~~~bash
curl -i -X GET "$NEURINGO_API_ORIGIN/api/v1/scenarios/${scenarioId}" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN"
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S4-BAE-02`
- 코드 근거: `backend/src/main/java/com/neuringo/neuringobe/scenario/controller/ScenarioController.java` · `scenario/service/ScenarioDraftService.java`

- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제를 통합 테스트로 확인합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
