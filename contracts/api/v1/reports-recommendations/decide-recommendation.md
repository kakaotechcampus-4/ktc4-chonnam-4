# API-REC-05 · 추천 채택·수정 채택·보류·거절

> 설계 — 2026-10-22 / develop `63e4314`. 설계·미구현. 경로/필드/상태는 제안이며 allowDrafts 기본 차단입니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | PATCH |
| Path | /api/v1/recommendations/{recommendationId} |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 담당 강사 Bearer + CSRF + Idempotency-Key |
| 성공 | 200 — 설계 예시 |
| 반환 | ApiResponse<DraftRecommendationData> |
| FE 함수 | decideRecommendation |
| 구현·협업 | 배재일·진미나 |

## 2. 용도·중요 규칙

- 명시적강사결정.거절이유선택.채택새목표생성/계보/결정원자처리여부미정.
- 같은키재전송시중복목표생성금지.다음활동배정은별도강사행동/새키,자동배정없음.

## 3. 요청

### Path parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| recommendationId | UUID string | 예 | 경로 식별자 |

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
| decision | 'ACCEPT' \| 'MODIFIED_ACCEPT' \| 'DEFER' \| 'REJECT' | 예 | 아니오 | 제안값; 확정 전 실서버 연결 금지 |
| goal | Partial<GoalBody> | 아니오 | 아니오 | 수정 채택 목표/새 목표 생성 방식 미정 |
| rejectionReason | string \| null | 아니오 | 예 | 거절 이유 선택 |

~~~json
{
  "decision": "MODIFIED_ACCEPT",
  "goal": {
    "title": "친구의 마음을 설명하고 도움을 제안하기",
    "requiredElements": [
      "상황에 맞는 도움 한 가지"
    ],
    "forbiddenExpressions": []
  },
  "rejectionReason": null
}
~~~

## 4. 성공 응답

HTTP 200 — 설계 예시.

~~~json
{
  "data": {
    "recommendationId": "87000000-0000-4000-8000-000000000001",
    "recordId": "86000000-0000-4000-8000-000000000001",
    "type": "EXAMPLE_EXTENSION",
    "suggestedGoal": {
      "title": "감정과 이유를 설명하기"
    },
    "reason": "상황 단서가 효과적이었어요.",
    "evidence": [
      "감정을 사건에 연결함"
    ],
    "evidenceSufficiency": "EXAMPLE_SUFFICIENT",
    "decision": null,
    "createdGoalId": null
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
| data.recordId | UUID | 예 | 아니오 | 근거 기록 |
| data.type | string | 예 | 아니오 | 보완/반복/확장/적용 코드 미정 |
| data.suggestedGoal | Record<string, unknown> | 예 | 아니오 | 목표 제안 |
| data.reason | string | 예 | 아니오 | 추천 이유 |
| data.evidence | string[] | 예 | 아니오 | 관찰 근거 |
| data.evidenceSufficiency | string | 예 | 아니오 | 충분성 코드 미정 |
| data.decision | string \| null | 예 | 예 | 미결정/강사 결정 |
| data.createdGoalId | UUID \| null | 예 | 예 | 채택 목표 연결; 생성 방식 합의 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

아래 업무 오류도 설계 예시이며 구현 전 합의가 필요합니다.

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID/Query 형식 |
| 401 | AUTHENTICATION_REQUIRED | 인증/입장 필요 |
| 401 | INVALID_TOKEN | 강사 토큰 만료/위조; 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 역할 권한 |
| 403 | CSRF_TOKEN_INVALID | 재취득; 변경 요청 자동 재전송 없음 |
| 404 | RESOURCE_NOT_FOUND | 리소스/소유권 코드 제안 |
| 422 | VALIDATION_FAILED | 검증 코드 제안 |
| 409 | IDEMPOTENCY_KEY_REUSED | 범용 키 충돌 제안 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 400,
    "code": "INVALID_REQUEST",
    "message": "요청을 확인해 주세요.",
    "path": "/api/v1/recommendations/{recommendationId}",
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

export interface GoalBody {
  /** 최대200자 */
  title: string;
  /** 최대30자, 선택 */
  situationType?: string | null;
  /** 목표 분류, 최대30자. 별도 enum 아님 */
  category?: string | null;
  /** 추가 등장인물; 누락/null은 현재 [] 정리 */
  characters?: Record<string, unknown>[] | null;
  /** 빈/공백/null 원소는 조건 오류 */
  requiredElements?: string[] | null;
  /** 빈/공백/null 원소는 조건 오류 */
  forbiddenExpressions?: string[] | null;
  /** 접근 가능한 동일 아동의 이전 목표 */
  parentGoalId?: UUID | null;
}

export interface DraftRecommendationDecision {
  /** 제안값; 확정 전 실서버 연결 금지 */
  decision: 'ACCEPT' | 'MODIFIED_ACCEPT' | 'DEFER' | 'REJECT';
  /** 수정 채택 목표/새 목표 생성 방식 미정 */
  goal?: Partial<GoalBody>;
  /** 거절 이유 선택 */
  rejectionReason?: string | null;
}

export interface DraftRecommendationData {
  /** 추천 */
  recommendationId: UUID;
  /** 근거 기록 */
  recordId: UUID;
  /** 보완/반복/확장/적용 코드 미정 */
  type: string;
  /** 목표 제안 */
  suggestedGoal: Record<string, unknown>;
  /** 추천 이유 */
  reason: string;
  /** 관찰 근거 */
  evidence: string[];
  /** 충분성 코드 미정 */
  evidenceSufficiency: string;
  /** 미결정/강사 결정 */
  decision: string | null;
  /** 채택 목표 연결; 생성 방식 합의 */
  createdGoalId: UUID | null;
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface DecideRecommendationArgs {
  recommendationId: T.UUID;
  body: T.DraftRecommendationDecision;
  idempotencyKey: T.UUID;
}

export function decideRecommendation(
  api: ApiClient,
  args: DecideRecommendationArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftRecommendationData>> {
  return api.request<T.DraftRecommendationData>("/api/v1/recommendations/" + encodeURIComponent(args.recommendationId), {
    method: "PATCH",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true,
    idempotencyKey: args.idempotencyKey,
    body: args.body
  });
}
~~~

### 사용 예시

~~~ts
import { decideRecommendation } from "./draft-endpoints.ts";

const response = await decideRecommendation(api, {
  "recommendationId": "87000000-0000-4000-8000-000000000001",
  "body": {
    "decision": "MODIFIED_ACCEPT",
    "goal": {
      "title": "친구의 마음을 설명하고 도움을 제안하기",
      "requiredElements": [
        "상황에 맞는 도움 한 가지"
      ],
      "forbiddenExpressions": []
    },
    "rejectionReason": null
  },
  "idempotencyKey": "89000000-0000-4000-8000-000000000001"
});
const data = response.data;
// 개인정보·원본·토큰·응답 전문을 로그에 출력하지 않습니다.
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다. 설계 경로는 실제 서버로 호출하지 않고 Mock에서 확인합니다.

~~~bash
curl -i -X PATCH "$NEURINGO_API_ORIGIN/api/v1/recommendations/${recommendationId}" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN" \
  -H "$NEURINGO_CSRF_HEADER: $NEURINGO_CSRF_TOKEN" \
  -H "Idempotency-Key: $NEURINGO_REQUEST_KEY" \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data '{"decision":"MODIFIED_ACCEPT","goal":{"title":"친구의 마음을 설명하고 도움을 제안하기","requiredElements":["상황에 맞는 도움 한 가지"],"forbiddenExpressions":[]},"rejectionReason":null}'
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S7-BAE-02` · `S7-JIN-01`
- 현재 서버 Controller가 없는 설계 계약입니다. 구현 PR에서 경로·권한·DTO를 확정한 뒤 연결합니다.
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
