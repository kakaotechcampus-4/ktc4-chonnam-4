# API-GOAL-01 · 아동 학습 목표 생성

> 구현 — 2026-11-04 / develop `bab136d` (PR #78 `feature/goal-lineage-edit` 병합). 학급·아동 이름 수정과 목표 조건 정리·모순 거절의 실제 구현 기준입니다. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | POST |
| Path | /api/v1/children/{childId}/learning-goals |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 담당 강사 Bearer + CSRF |
| 성공 | 201 |
| 반환 | ApiResponse<GoalData> |
| FE 함수 | createLearningGoal |
| 구현·협업 | 배재일 |

## 2. 용도·중요 규칙

- 서버가 instructorId/contentHash를 결정합니다. category는 최대30자 선택 문자열이며 enum이 아닙니다. situationType은 상황 설명 문장(최대200자)입니다.
- parentGoalId는 동일 아동의 접근 가능한 이전 목표입니다.
- 저장 전 정리: 모든 문자열의 앞뒤 공백을 지우고 안쪽 연속 공백(전각 공백 포함)을 한 칸으로 줄입니다. situationType·category가 공백뿐이면 null입니다. 목록은 같은 값을 한 번만 남기고 입력 순서를 지킵니다. 응답은 정리한 값입니다.
- 등장인물은 `{id, role}`입니다. role은 인물 설명이고 실제 이름을 넣지 않습니다(서버는 실명 여부를 검사하지 않으므로 FE·강사 안내로 지킵니다). id를 생략·null·빈 문자열로 두면 `OTHER`(공백만 있는 id는 형식 오류 422), 같은 id가 여럿이면 role 글자 순서로 첫 인물만 그대로 두고 나머지는 `FRIEND_2`처럼 번호를 붙입니다. `LEARNER`(역할극의 아이 자리)는 거부합니다.
- 조건 오류는 422 `INVALID_GOAL_CONDITIONS`이며 `fieldErrors[].field`가 문제 칸을 요청 기준 위치로 가리킵니다(예: `forbiddenExpressions[1]`, `characters[0].id`). 목록의 null/공백 원소, 그리고 피할 표현이 필수 요소나 등장인물 role 안에 들어 있는 경우(띄어쓰기·대소문자 무시)입니다. 이런 조건은 시나리오 생성 결과 검사를 늘 실패시키기 때문입니다.
- contentHash는 정리한 값으로 계산하되 목록을 정렬해 넣습니다. 칩 순서만 다른 목표는 같은 해시입니다. 이미 저장된 목표의 해시는 다시 계산하지 않습니다.
- 활동 배정에서는 이 API를 먼저 호출하고 goalId를 넘기는 방식 대신 ACT-01에 goal 내용을 넣어 원자 저장합니다. 이 단독 생성 API는 별도로 유지됩니다.

## 3. 요청

### Path parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| childId | UUID string | 예 | 경로 식별자 |

### Query parameters

없음. 지원되지 않는 page/size/sort를 보내도 적용된다고 가정하지 않습니다.

### Headers·쿠키

| 이름 | 값/타입 | 필수 | 주의 |
|---|---|---|---|
| Accept | application/json | 예 | 공통 envelope |
| Cookie | credentials: include | 예 | 브라우저 관리; HttpOnly 쿠키 직접 읽기 금지 |
| Authorization | Bearer <accessToken> | 예 | 아동/public에 강사 토큰을 붙이지 않음 |
| CSRF Header | GET /api/v1/csrf의 headerName:token | 예 | 쿠키와 함께 전송 |
| Content-Type | application/json; charset=utf-8 | 예 | JSON body |

### Request Body

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| title | string | 예 | 아니오 | 최대200자 |
| situationType | string \| null | 아니오 | 예 | 상황 설명 문장, 최대200자, 선택. 공백만이면 null |
| category | string \| null | 아니오 | 예 | 목표 분류, 최대30자. 별도 enum 아님. 공백만이면 null |
| characters | GoalCharacter[] \| null | 아니오 | 예 | 추가 등장인물 `{id, role}`; 누락/null은 [] |
| characters[].id | string \| null | 아니오 | 예 | 관계 종류(예: FRIEND). 영문 대문자로 시작, 대문자·숫자·밑줄, 최대30자. 생략/null/""은 OTHER, 공백만은 422, LEARNER 불가 |
| characters[].role | string | 예 | 아니오 | 인물 설명, 최대50자, 보이는 글자 1자 이상·제어 문자 불가. 실제 이름 금지(서버 미검사) |
| requiredElements | string[] \| null | 아니오 | 예 | 아이가 대화에서 해 볼 행동. 빈/공백/null 원소는 조건 오류 |
| forbiddenExpressions | string[] \| null | 아니오 | 예 | 역할극에 나오면 안 되는 말. 필수 요소·등장인물 role 안에 들어 있으면 조건 오류 |
| parentGoalId | UUID \| null | 아니오 | 예 | 접근 가능한 동일 아동의 이전 목표 |

~~~json
{
  "title": "감정을 이해하고 공감하기",
  "situationType": "PLAYGROUND",
  "characters": [
    {
      "id": "FRIEND",
      "role": "친구"
    }
  ],
  "requiredElements": [
    "넘어진 친구의 마음 살피기"
  ],
  "forbiddenExpressions": [
    "친구를 비난하는 표현"
  ],
  "parentGoalId": null
}
~~~

## 4. 성공 응답

HTTP 201.

~~~json
{
  "data": {
    "goalId": "30000000-0000-4000-8000-000000000001",
    "childId": "10000000-0000-4000-8000-000000000001",
    "instructorId": "20000000-0000-4000-8000-000000000001",
    "parentGoalId": null,
    "title": "감정을 이해하고 공감하기",
    "situationType": "PLAYGROUND",
    "characters": [
      {
        "id": "FRIEND",
        "role": "친구"
      }
    ],
    "requiredElements": [
      "넘어진 친구의 마음 살피기"
    ],
    "forbiddenExpressions": [
      "친구를 비난하는 표현"
    ],
    "contentHash": "example-content-hash",
    "createdAt": "2026-10-06T03:00:00Z",
    "category": null
  },
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| data.goalId | UUID | 예 | 아니오 | 목표 ID |
| data.childId | UUID | 예 | 아니오 | 아동 ID |
| data.instructorId | UUID | 예 | 아니오 | 담당 강사 |
| data.parentGoalId | UUID \| null | 예 | 예 | 이전 목표, 없으면 null |
| data.title | string | 예 | 아니오 | 목표명 |
| data.situationType | string \| null | 예 | 예 | 상황 설명 문장 |
| data.category | string \| null | 예 | 예 | 목표 분류, 최대30자. 별도 enum 아님 |
| data.characters | GoalCharacterData[] | 예 | 아니오 | 등장인물 `{id, role}`; 정리한 값, id는 늘 있음 |
| data.requiredElements | string[] | 예 | 아니오 | 필수 요소; 정리한 값 |
| data.forbiddenExpressions | string[] | 예 | 아니오 | 피할 표현; 정리한 값 |
| data.contentHash | string | 예 | 아니오 | 서버 계산; 목록 순서와 무관. FE 수정 금지 |
| data.createdAt | ISODateTime | 예 | 아니오 | 생성 시각 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

현재 업무/공통 오류입니다. message 대신 HTTP status와 code로 분기합니다.

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID/Query 형식 |
| 401 | AUTHENTICATION_REQUIRED | 인증/입장 필요 |
| 401 | INVALID_TOKEN | 강사 토큰 만료/위조; 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 역할 권한 |
| 403 | CSRF_TOKEN_INVALID | 재취득; 변경 요청 자동 재전송 없음 |
| 404 | CHILD_NOT_FOUND | 아동 권한 |
| 404 | GOAL_NOT_FOUND | 부모 목표 없음/다른 아동 |
| 422 | INVALID_GOAL_CONDITIONS | 빈 원소, LEARNER id, 피할 표현이 필수 요소·등장인물 role과 겹침. `fieldErrors`로 칸 표시 |
| 422 | VALIDATION_FAILED | DTO 길이/필수 검증, 등장인물 id 형식·role 빈 값 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 422,
    "code": "INVALID_GOAL_CONDITIONS",
    "message": "목표 조건이 올바르지 않습니다.",
    "path": "/api/v1/children/{childId}/learning-goals",
    "traceId": "90000000-0000-4000-8000-000000000001",
    "fieldErrors": [
      {
        "field": "forbiddenExpressions[0]",
        "message": "필수 요소나 등장인물에 들어 있는 표현은 피할 표현으로 둘 수 없습니다."
      }
    ]
  }
}
~~~

네트워크·Abort·성공 응답 파싱 오류는 서버 미실행을 뜻하지 않습니다. 변경 요청을 새 키로 자동 재전송하지 않습니다. CSRF 오류는 토큰을 다시 받고 원래 행동/키로 복구를 판단합니다. 강사 보호 요청401은 토큰·입력·캐시 정리, 아동401은 재입장 안내입니다. refresh 로그인 API는 없습니다. 세부 동작은 [공통 연동 규칙](../integration-guide.md)을 따릅니다.

## 6. 복사 가능한 TypeScript

공통 [api-client.ts](../examples/api-client.ts), [types.ts](../examples/types.ts), [endpoints.ts](../examples/endpoints.ts)를 같은 폴더 구조로 복사합니다. 실서버의 origin·쿠키·권한 설정은 별도로 확인합니다.

### 요청·응답 타입

~~~ts
export type UUID = string;

export interface GoalCharacter {
  /** 아이와의 관계 종류(예: FRIEND). 영문 대문자로 시작, 대문자·숫자·밑줄, 최대30자. 비우면 OTHER, LEARNER 불가 */
  id?: string | null;
  /** 인물 설명(예: 넘어진 친구), 최대50자. 실제 이름 금지 */
  role: string;
}

export interface GoalBody {
  /** 최대200자 */
  title: string;
  /** 상황 설명 문장, 최대200자, 선택. 공백만이면 null */
  situationType?: string | null;
  /** 목표 분류, 최대30자. 별도 enum 아님. 공백만이면 null */
  category?: string | null;
  /** 추가 등장인물; 누락/null은 [] */
  characters?: GoalCharacter[] | null;
  /** 아이가 대화에서 해 볼 행동. 빈/공백/null 원소는 조건 오류 */
  requiredElements?: string[] | null;
  /** 역할극에 나오면 안 되는 말. 필수 요소·등장인물 role 안에 들어 있으면 조건 오류 */
  forbiddenExpressions?: string[] | null;
  /** 접근 가능한 동일 아동의 이전 목표 */
  parentGoalId?: UUID | null;
}

export type ISODateTime = string;

export interface GoalCharacterData {
  /** 저장된 관계 id. 비었으면 OTHER, 같은 id가 겹치면 FRIEND_2처럼 번호가 붙음 */
  id: string;
  /** 인물 설명 */
  role: string;
}

export interface GoalData {
  /** 목표 ID */
  goalId: UUID;
  /** 아동 ID */
  childId: UUID;
  /** 담당 강사 */
  instructorId: UUID;
  /** 이전 목표, 없으면 null */
  parentGoalId: UUID | null;
  /** 목표명 */
  title: string;
  /** 상황 설명 문장 */
  situationType: string | null;
  /** 목표 분류, 최대30자. 별도 enum 아님 */
  category: string | null;
  /** 등장인물; 정리한 값 */
  characters: GoalCharacterData[];
  /** 필수 요소; 정리한 값 */
  requiredElements: string[];
  /** 피할 표현; 정리한 값 */
  forbiddenExpressions: string[];
  /** 서버 계산; 목록 순서와 무관. FE 수정 금지 */
  contentHash: string;
  /** 생성 시각 */
  createdAt: ISODateTime;
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface CreateLearningGoalArgs {
  childId: T.UUID;
  body: T.GoalBody;
}

export function createLearningGoal(
  api: ApiClient,
  args: CreateLearningGoalArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.GoalData>> {
  return api.request<T.GoalData>("/api/v1/children/" + encodeURIComponent(args.childId) + "/learning-goals", {
    method: "POST",
    auth: "instructor",
    expectedStatuses: [201],
    signal: options.signal,
    body: args.body
  });
}
~~~

### 사용 예시

~~~ts
import { createLearningGoal } from "./endpoints.ts";

const response = await createLearningGoal(api, {
  "childId": "10000000-0000-4000-8000-000000000001",
  "body": {
    "title": "감정을 이해하고 공감하기",
    "situationType": "PLAYGROUND",
    "characters": [
      {
        "id": "FRIEND",
        "role": "친구"
      }
    ],
    "requiredElements": [
      "넘어진 친구의 마음 살피기"
    ],
    "forbiddenExpressions": [
      "친구를 비난하는 표현"
    ],
    "parentGoalId": null
  }
});
const data = response.data;
// 개인정보·원본·토큰·응답 전문을 로그에 출력하지 않습니다.
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다. 실제 동작/삭제가 발생하는 요청이므로 테스트 데이터로 확인합니다.

~~~bash
curl -i -X POST "$NEURINGO_API_ORIGIN/api/v1/children/${childId}/learning-goals" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN" \
  -H "$NEURINGO_CSRF_HEADER: $NEURINGO_CSRF_TOKEN" \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data '{"title":"감정을 이해하고 공감하기","situationType":"PLAYGROUND","characters":[{"id":"FRIEND","role":"친구"}],"requiredElements":["넘어진 친구의 마음 살피기"],"forbiddenExpressions":["친구를 비난하는 표현"],"parentGoalId":null}'
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S4-BAE-01`
- 코드 근거: [현재 Controller/DTO](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/blob/develop/backend/src/main/java/com/neuringo/neuringobe/goal/dto/CreateLearningGoalRequest.java) · [조건 정리·검사](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/blob/develop/backend/src/main/java/com/neuringo/neuringobe/goal/service/GoalConditions.java)
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
