# API-ACT-05 · 최초 결합 활동의 재사용 후보 하나 조회

> 구현 — 2026-11-01 / 브랜치 `feature/scenario-reuse-approval`. 시나리오 재사용 승인과 [수정하기] 흐름의 실제 구현 기준입니다. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | GET |
| Path | /api/v1/activities/{activityId}/scenario-candidate |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 담당 강사 Bearer |
| 성공 | 200 |
| 반환 | ApiResponse<ScenarioCandidateData> |
| FE 함수 | getScenarioCandidate |
| 구현·협업 | 배재일 |

## 2. 용도·중요 규칙

- 퀴즈로 난이도가 정해진 최초 결합 활동에 쓸 재사용 후보를 하나씩 준다. 시나리오는 학급에 묶지 않는 공용 자료다.
- 후보 조건: 승인본, 같은 목표 내용(조건 포함)·같은 난이도, 이 아동이 그 시나리오로 배정받은 적 없음(완료·진행 중 모두 제외).
- 순서: 한 번도 활동에 붙지 않은 것 → 마지막으로 붙은 활동의 배정 시각이 오래된 것 → 최근 승인본 → ID. 사용 시각·횟수는 응답에 담지 않는다.
- `after`가 없으면 첫 후보, 있으면 그 다음 후보다. `after`가 그사이 후보에서 빠졌으면 첫 후보부터 준다. 순서는 요청마다 다시 계산하므로, 넘기는 사이 다른 아동이 쓴 후보는 드물게 건너뛰거나 다시 보일 수 있다.
- 후보가 없으면 `scenario`가 null이다. 넘긴 후보는 서버에 남지 않는다. 이 조회는 시나리오를 붙이지 않는다.
- 담당 강사만 부른다. 없는 활동과 남의 활동은 같은 404. 조회하면 민감 조회 기록(target=ACTIVITY)을 남긴다.

## 3. 요청

### Path parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| activityId | UUID string | 예 | 최초 결합 활동 |

### Query parameters

| 이름 | 타입 | 필수 | 기본값 | 조건 |
|---|---|---|---|---|
| after | UUID | 아니오 | 없음 | 지금 보고 있는 후보. 그 다음 후보를 받음 |

### Headers·쿠키

| 이름 | 값/타입 | 필수 | 주의 |
|---|---|---|---|
| Accept | application/json | 예 | 공통 envelope |
| Cookie | credentials: include | 예 | 브라우저 관리; HttpOnly 쿠키 직접 읽기 금지 |
| Authorization | Bearer <accessToken> | 예 | 담당 강사 |

### Request Body

없음. 빈 JSON 객체를 추가하지 않습니다.

## 4. 성공 응답

HTTP 200. 다음 후보가 더 있는 예시입니다. 후보가 없으면 `{"scenario": null, "hasNext": false}`입니다.

~~~json
{
  "data": {
    "scenario": {
      "scenarioId": "80000000-0000-4000-8000-000000000001",
      "version": 1,
      "editRevision": 0,
      "goalId": "30000000-0000-4000-8000-000000000001",
      "scenarioSource": "REUSED",
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
    "hasNext": true
  },
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| data.scenario | ScenarioData | 예 | 예 | 후보 하나. 없으면 null |
| data.hasNext | boolean | 예 | 아니오 | 이 다음 후보가 있는지 |

`ScenarioData`:

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| scenario.scenarioId | UUID | 예 | 아니오 | 시나리오 판 ID. 고친 판은 새 ID |
| scenario.version | number | 예 | 아니오 | 판 번호. 고친 판은 원본 +1 |
| scenario.editRevision | number | 예 | 아니오 | 수정 번호. 같은 초안을 저장할 때마다 오르고 판 번호는 그대로다. 초안을 고치거나 판정할 때 그대로 보낸다 |
| scenario.goalId | UUID | 예 | 예 | 재사용 후보면 그 활동의 목표, 초안·판정 기록이면 초안의 목표. 활동과 상관없이 공용 승인본만 조회하면 null |
| scenario.scenarioSource | string | 예 | 예 | `REUSED`(재사용 후보) · `NEWLY_GENERATED`(이 목표를 위해 만든 판). 공용 승인본만 조회하면 null |
| scenario.status | ScenarioStatus | 예 | 아니오 | `DRAFT` · `REVISION_REQUIRED` · `REJECTED` · `APPROVED` |
| scenario.scenarioLevel | ScenarioLevel | 예 | 아니오 | 난이도 |
| scenario.title | string | 예 | 아니오 | 제목 |
| scenario.summary | string | 예 | 예 | 상황 요약 |
| scenario.scenarioFacts | string[] | 예 | 아니오 | 고정 사실 |
| scenario.prohibitedInferences | string[] | 예 | 아니오 | 금지 추론 |
| scenario.characters | Record<string, unknown>[] | 예 | 아니오 | 등장인물 `{id, role}` |
| scenario.microGoals | ScenarioMicroGoal[] | 예 | 아니오 | 작은 목표. 순서는 보여 주는 순서 |
| scenario.openingPrompts | Record<string, unknown> | 예 | 아니오 | 지원 단계별 시작 질문 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다.

## 5. 오류·FE 처리

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID·JSON 형식 |
| 401 | AUTHENTICATION_REQUIRED | 인증 필요 |
| 401 | INVALID_TOKEN | 강사 토큰 만료/위조; 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 강사가 아닌 역할(아동 세션 등) |
| 409 | SCENARIO_ALREADY_ATTACHED | 이미 시나리오가 정해진 활동. 후보 화면을 닫음 |
| 404 | ACTIVITY_NOT_FOUND | 없거나 남의 활동 |
| 409 | INITIAL_DIFFICULTY_NOT_READY | 퀴즈로 난이도가 아직 정해지지 않음 |
| 409 | INITIAL_SCENARIO_INPUT_NOT_READY | 목표 내용을 확인할 수 없음 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 409,
    "code": "SCENARIO_ALREADY_ATTACHED",
    "message": "요청을 확인해 주세요.",
    "path": "/api/v1/activities/{activityId}/scenario-candidate",
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

export interface ScenarioCandidateData {
  /** 후보가 없으면 null */
  scenario: ScenarioData | null;
  hasNext: boolean;
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import type * as T from "./types.ts";

export interface GetScenarioCandidateArgs {
  activityId: T.UUID;
  /** 지금 보고 있는 후보 */
  after?: T.UUID;
}

export function getScenarioCandidate(
  api: ApiClient,
  args: GetScenarioCandidateArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ScenarioCandidateData>> {
  const query = args.after ? "?after=" + encodeURIComponent(args.after) : "";
  return api.request<T.ScenarioCandidateData>("/api/v1/activities/" + encodeURIComponent(args.activityId) + "/scenario-candidate" + query, {
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
curl -i -X GET "$NEURINGO_API_ORIGIN/api/v1/activities/${activityId}/scenario-candidate?after=${scenarioId}" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN"
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S4-BAE-02`
- 코드 근거: `backend/src/main/java/com/neuringo/neuringobe/scenario/controller/ActivityScenarioController.java` · `scenario/service/ScenarioReuseService.java`
- 이번 구현은 최초 결합 활동만 대상입니다. 역할극 단독 활동은 그 배정 기능이 들어온 뒤 같은 흐름으로 맞춥니다.
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제를 통합 테스트로 확인합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
