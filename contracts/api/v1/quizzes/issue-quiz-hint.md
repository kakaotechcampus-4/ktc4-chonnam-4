# API-QUIZ-06 · 퀴즈 힌트 요청

> 2026-11-05 S4-JIN-02 작업본: 새 답변·새 힌트를 저장할 때 중단된 활동을 검사 후 재개합니다. S4-JIN-01 `f8e3dfa` 기반 `feat/activity-readiness`이며 병합·운영 배포 완료를 뜻하지 않습니다.

> 구현 — 2026-11-04 / develop `bab136d`. 현재 코드의 HTTP 계약. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | POST |
| Path | /api/v1/activity-quiz-items/{activityQuizId}/hints |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 본인 아동 HttpOnly 세션 + CSRF + Idempotency-Key |
| 성공 | 201 |
| 반환 | ApiResponse<QuizHintData> |
| FE 함수 | issueQuizHint |
| 구현·협업 | 배재일·윤상현 |

## 2. 용도·중요 규칙

- 최초답안 제출후 최대2회. 같은키는같은힌트·횟수증가없음, 새키는다음힌트.
- 힌트내용/정답을 목록으로미리받지않는다.

### S4-JIN-02 · 중단된 퀴즈 재개

- 별도 재개 Endpoint와 준비 상태 요청 필드를 추가하지 않습니다. PAUSED·RECOVERY_NEEDED 활동은 새 쓰기 요청에서 현재 페이지 안내와 같은 준비 근거를 다시 검사합니다. 현재 단계가 QUIZ일 때만 IN_PROGRESS로 전환합니다.
- 재개 검사에는 활동 유형별 문항 구성·배정 버전·문항 승인/본문/선택지·아직 확정하지 않은 문항 존재 여부를 사용합니다. 최초 난이도나 시나리오 확정은 퀴즈 풀이의 선행 조건이 아닙니다. 퀴즈 결과가 이미 저장돼 다음 단계로 넘어간 중단 활동은 이 API로 재개하지 않습니다.
- 활동 → 아동 순서로 행 잠금을 획득하고 소유권·참여 상태를 검사합니다. 비활성 아동은 404 ACTIVITY_QUIZ_NOT_FOUND이며 참여 상태를 해제하지 않습니다. 재개와 응답/힌트 저장, 마지막 문항의 결과·완료 처리는 같은 트랜잭션입니다. 검증·저장 실패 시 재개도 롤백합니다.
- 최초 startedAt과 기존 답변·힌트 기록은 보존합니다. 완료 활동에 새 데이터를 추가하는 요청과 재개 근거가 불충분한 새 요청은 409 QUIZ_ACTIVITY_NOT_READY입니다. 배정 문항 자체의 버전 충돌 등 기존 검증 오류는 유지합니다.
- 활동 목록·배정 문항·답변 GET은 재개하거나 답변을 확정하지 않습니다. 준비 상태는 응답에서 계산하며 body/query로 전달하지 않습니다. 현재 작업은 중단 상태 재개와 완료 활동의 새 쓰기 차단을 추가하며, 진행 중 활동의 기존 문항별 제출 검증은 유지합니다.

- 같은 Idempotency-Key 재전송은 기존 hintId·내용·순서를 그대로 201로 반환하며 재개하거나 발급 횟수를 늘리지 않습니다. 새 키로 실제 다음 힌트를 발급할 때만 재개합니다. 힌트가 없거나 확정된 답변이면 기존 NO_MORE_HINTS/ATTEMPT_FINALIZED 오류를 유지합니다.

## 3. 요청

### Path parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| activityQuizId | UUID string | 예 | 경로 식별자 |

### Query parameters

없음. 지원되지 않는 page/size/sort를 보내도 적용된다고 가정하지 않습니다.

### Headers·쿠키

| 이름 | 값/타입 | 필수 | 주의 |
|---|---|---|---|
| Accept | application/json | 예 | 공통 envelope |
| Cookie | credentials: include | 예 | 브라우저 관리; HttpOnly 쿠키 직접 읽기 금지 |
| CSRF Header | GET /api/v1/csrf의 headerName:token | 예 | 쿠키와 함께 전송 |
| Idempotency-Key | UUID string | 예 | 동일 행동 재전송은 같은 키/같은 입력 |

### Request Body

없음. 빈 JSON 객체를 추가하지 않습니다.

## 4. 성공 응답

HTTP 201.

~~~json
{
  "data": {
    "hintId": "70000000-0000-4000-8000-000000000001",
    "hintOrder": 1,
    "type": "OBSERVABLE_FACE_CUE",
    "text": "친구가 넘어져서 아플 수 있어요."
  },
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| data.hintId | UUID | 예 | 아니오 | 힌트 ID |
| data.hintOrder | number | 예 | 아니오 | 1~2; 같은키 동일순서 |
| data.type | string | 예 | 아니오 | 문항 등록 힌트 종류 |
| data.text | string | 예 | 아니오 | 아동 표시 문구 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

현재 업무/공통 오류입니다. message 대신 HTTP status와 code로 분기합니다.

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID/Query 형식 |
| 401 | AUTHENTICATION_REQUIRED | 인증/입장 필요 |
| 403 | ACCESS_DENIED | 역할 권한 |
| 403 | CSRF_TOKEN_INVALID | 재취득; 변경 요청 자동 재전송 없음 |
| 404 | ATTEMPT_NOT_FOUND | 첫응답미제출 |
| 404 | QUIZ_ITEM_NOT_FOUND | 배정 당시 문항이 문항 풀에서 사라짐 |
| 409 | ITEM_VERSION_CONFLICT | 배정 당시 문항 버전이 바뀜 |
| 409 | IDEMPOTENCY_KEY_REUSED | 다른힌트요청키 |
| 409 | ATTEMPT_FINALIZED | 확정답안 |
| 409 | NO_MORE_HINTS | 힌트소진 |
| 409 | QUIZ_ACTIVITY_NOT_READY | 완료 활동에 새 쓰기를 시도했거나 중단 활동의 퀴즈 재개 근거가 불충분함. 현재 목록/상태를 다시 확인 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |
| 404 | ACTIVITY_QUIZ_NOT_FOUND | 없거나 소유권 밖 리소스 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 400,
    "code": "INVALID_REQUEST",
    "message": "요청을 확인해 주세요.",
    "path": "/api/v1/activity-quiz-items/{activityQuizId}/hints",
    "traceId": "90000000-0000-4000-8000-000000000001",
    "fieldErrors": []
  }
}
~~~

네트워크·Abort·성공 응답 파싱 오류는 서버 미실행을 뜻하지 않습니다. 변경 요청을 새 키로 자동 재전송하지 않습니다. CSRF 오류는 토큰을 다시 받고 원래 행동/키로 복구를 판단합니다. 강사 보호 요청401은 토큰·입력·캐시 정리, 아동401은 재입장 안내입니다. refresh 로그인 API는 없습니다. 세부 동작은 [공통 연동 규칙](../integration-guide.md)을 따릅니다.

## 6. 복사 가능한 TypeScript

공통 [api-client.ts](../examples/api-client.ts), [types.ts](../examples/types.ts), [endpoints.ts](../examples/endpoints.ts)를 같은 폴더 구조로 복사합니다. 실서버의 origin·쿠키·권한 설정은 별도로 확인합니다.

### 요청·응답 타입

~~~ts
export type UUID = string;

export interface QuizHintData {
  /** 힌트 ID */
  hintId: UUID;
  /** 1~2; 같은키 동일순서 */
  hintOrder: number;
  /** 문항 등록 힌트 종류 */
  type: string;
  /** 아동 표시 문구 */
  text: string;
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface IssueQuizHintArgs {
  activityQuizId: T.UUID;
  idempotencyKey: T.UUID;
}

export function issueQuizHint(
  api: ApiClient,
  args: IssueQuizHintArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.QuizHintData>> {
  return api.request<T.QuizHintData>("/api/v1/activity-quiz-items/" + encodeURIComponent(args.activityQuizId) + "/hints", {
    method: "POST",
    auth: "child",
    expectedStatuses: [201],
    signal: options.signal,
    idempotencyKey: args.idempotencyKey
  });
}
~~~

### 사용 예시

~~~ts
import { issueQuizHint } from "./endpoints.ts";

const response = await issueQuizHint(api, {
  "activityQuizId": "50000000-0000-4000-8000-000000000001",
  "idempotencyKey": "89000000-0000-4000-8000-000000000001"
});
const data = response.data;
// 개인정보·원본·토큰·응답 전문을 로그에 출력하지 않습니다.
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다. 실제 동작/삭제가 발생하는 요청이므로 테스트 데이터로 확인합니다.

~~~bash
curl -i -X POST "$NEURINGO_API_ORIGIN/api/v1/activity-quiz-items/${activityQuizId}/hints" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "$NEURINGO_CSRF_HEADER: $NEURINGO_CSRF_TOKEN" \
  -H "Idempotency-Key: $NEURINGO_REQUEST_KEY"
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S5-BAE-01` · `S5-YOON-01`
- 코드 근거: [현재 Controller/DTO](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/blob/bab136d86b0642fd8446b3299fe09de3e9eb4a4e/backend/src/main/java/com/neuringo/neuringobe/quiz/controller/QuizController.java)
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
