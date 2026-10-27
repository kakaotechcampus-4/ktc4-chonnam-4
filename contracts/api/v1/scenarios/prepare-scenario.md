# API-SCN-01 · 시나리오 재사용 우선 준비·생성

> 설계 — 2026-10-22 / develop `63e4314`. 설계·미구현. 경로/필드/상태는 제안이며 allowDrafts 기본 차단입니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | POST |
| Path | /api/v1/scenarios |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 담당 강사 Bearer + CSRF + Idempotency-Key |
| 성공 | 201 / 202 — 설계 예시 |
| 반환 | ApiResponse<DraftScenarioPrepareData> |
| FE 함수 | prepareScenario |
| 구현·협업 | 진미나·이건우 |

## 2. 용도·중요 규칙

- 서버가학급·목표조건해시·난이도·과거제시를읽는다. FE가임의승인/제외목록으로우회하지않는다.
- 미사용승인본우선재사용, 없을때생성·검수·강사승인. async202본문미정이므로unknown형태를확정파싱하지않는다.

## 3. 요청

### Path parameters

없음.

### Query parameters

없음. 지원되지 않는 page/size/sort를 보내도 적용된다고 가정하지 않습니다.

### Headers·쿠키

| 이름 | 값/타입 | 필수 | 주의 |
|---|---|---|---|
| Accept | application/json | 예 | 공통 envelope |
| Cookie | credentials: include | 예 | 브라우저 관리; HttpOnly 쿠키 직접 읽기 금지 |
| Authorization | Bearer <accessToken> | 예 | 아동/public에 강사 토큰을 붙이지 않음 |
| CSRF Header | GET /api/v1/csrf의 headerName:token | 예 | 쿠키와 함께 전송 |
| Idempotency-Key | UUID string | 예 | 동일 행동 재전송은 같은 키/같은 입력 |
| Content-Type | application/json; charset=utf-8 | 예 | JSON body |

### Request Body

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| childId | UUID | 예 | 아니오 | 대상 아동 |
| goalId | UUID | 예 | 아니오 | 확정 목표 |

~~~json
{
  "childId": "10000000-0000-4000-8000-000000000001",
  "goalId": "30000000-0000-4000-8000-000000000001"
}
~~~

## 4. 성공 응답

HTTP 201 / 202 — 설계 예시.

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

구체 필드는 미합의입니다. 아래 TypeScript의 unknown/Record는 확정된 DTO를 뜻하지 않습니다.

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

아래 업무 오류도 설계 예시이며 구현 전 합의가 필요합니다.

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 401 | AUTHENTICATION_REQUIRED | 인증/입장 필요 |
| 401 | INVALID_TOKEN | 강사 토큰 만료/위조; 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 역할 권한 |
| 403 | CSRF_TOKEN_INVALID | 재취득; 변경 요청 자동 재전송 없음 |
| 422 | VALIDATION_FAILED | 검증 코드 제안 |
| 409 | IDEMPOTENCY_KEY_REUSED | 범용 키 충돌 제안 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |
| 400 | INVALID_REQUEST | UUID/필수 헤더·파트/Query 형식 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 401,
    "code": "AUTHENTICATION_REQUIRED",
    "message": "요청을 확인해 주세요.",
    "path": "/api/v1/scenarios",
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

export interface DraftScenarioBody {
  /** 대상 아동 */
  childId: UUID;
  /** 확정 목표 */
  goalId: UUID;
}

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

export type DraftScenarioPrepareData = DraftScenarioData | Record<string, unknown>;
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface PrepareScenarioArgs {
  body: T.DraftScenarioBody;
  idempotencyKey: T.UUID;
}

export function prepareScenario(
  api: ApiClient,
  args: PrepareScenarioArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftScenarioPrepareData>> {
  return api.request<T.DraftScenarioPrepareData>("/api/v1/scenarios", {
    method: "POST",
    auth: "instructor",
    expectedStatuses: [201, 202],
    signal: options.signal,
    draft: true,
    idempotencyKey: args.idempotencyKey,
    body: args.body
  });
}
~~~

### 사용 예시

~~~ts
import { prepareScenario } from "./draft-endpoints.ts";

const response = await prepareScenario(api, {
  "body": {
    "childId": "10000000-0000-4000-8000-000000000001",
    "goalId": "30000000-0000-4000-8000-000000000001"
  },
  "idempotencyKey": "89000000-0000-4000-8000-000000000001"
});
const data = response.data;
// 개인정보·원본·토큰·응답 전문을 로그에 출력하지 않습니다.
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다. 설계 경로는 실제 서버로 호출하지 않고 Mock에서 확인합니다.

~~~bash
curl -i -X POST "$NEURINGO_API_ORIGIN/api/v1/scenarios" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN" \
  -H "$NEURINGO_CSRF_HEADER: $NEURINGO_CSRF_TOKEN" \
  -H "Idempotency-Key: $NEURINGO_REQUEST_KEY" \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data '{"childId":"10000000-0000-4000-8000-000000000001","goalId":"30000000-0000-4000-8000-000000000001"}'
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S4-JIN-01` · `S4-JIN-02` · `S4-LEE-01`
- 현재 서버 Controller가 없는 설계 계약입니다. 구현 PR에서 경로·권한·DTO를 확정한 뒤 연결합니다.
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
