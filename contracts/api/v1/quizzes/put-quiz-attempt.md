# API-QUIZ-04 · 퀴즈 최초·최종 답안 제출

> 2026-11-05 S4-JIN-02 작업본: 새 답변·새 힌트를 저장할 때 중단된 활동을 검사 후 재개합니다. S4-JIN-01 `f8e3dfa` 기반 `feat/activity-readiness`이며 병합·운영 배포 완료를 뜻하지 않습니다.

> 구현 — 2026-10-22 / develop `63e4314`. 현재 코드의 HTTP 계약. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | PUT |
| Path | /api/v1/activity-quiz-items/{activityQuizId}/attempt |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 본인 아동 HttpOnly 세션 + CSRF |
| 성공 | 201 / 200 |
| 반환 | ApiResponse<QuizAttemptData> |
| FE 함수 | putQuizAttempt |
| 구현·협업 | 배재일·윤상현 |

## 2. 용도·중요 규칙

- 최초 firstResponse/카메라값은 유지. 힌트후 finalResponse만추가. 동일확정응답은기존결과.
- SELF_EMOTION_SITUATION만 카메라판정/모델전송. 첫NOT_ANALYZABLE을 즉시확정하지말고 최대2회재촬영후기술제외.
- Body재전송으로동일응답보장; 현재Header멱등키없음. 원본영상전송없음.
- 독립 퀴즈(나의 감정·친구 감정)는 마지막 문항이 FINALIZED되는 요청 안에서 활동이 COMPLETED가 되고 스탬프 지급 시각과 학습 기록이 한 번만 남는다. 응답 모양은 그대로이고, 완료 여부는 [API-ACT-04](../activities/get-activity-status.md)로 확인한다. 최초 결합은 퀴즈만으로 완료되지 않는다.

### S4-JIN-02 · 중단된 퀴즈 재개

- 별도 재개 Endpoint와 준비 상태 요청 필드를 추가하지 않습니다. PAUSED·RECOVERY_NEEDED 활동은 새 쓰기 요청에서 현재 페이지 안내와 같은 준비 근거를 다시 검사합니다. 현재 단계가 QUIZ일 때만 IN_PROGRESS로 전환합니다.
- 재개 검사에는 활동 유형별 문항 구성·배정 버전·문항 승인/본문/선택지·아직 확정하지 않은 문항 존재 여부를 사용합니다. 최초 난이도나 시나리오 확정은 퀴즈 풀이의 선행 조건이 아닙니다. 퀴즈 결과가 이미 저장돼 다음 단계로 넘어간 중단 활동은 이 API로 재개하지 않습니다.
- 활동 → 아동 순서로 행 잠금을 획득하고 소유권·참여 상태를 검사합니다. 비활성 아동은 404 ACTIVITY_QUIZ_NOT_FOUND이며 참여 상태를 해제하지 않습니다. 재개와 응답/힌트 저장, 마지막 문항의 결과·완료 처리는 같은 트랜잭션입니다. 검증·저장 실패 시 재개도 롤백합니다.
- 최초 startedAt과 기존 답변·힌트 기록은 보존합니다. 완료 활동에 새 데이터를 추가하는 요청과 재개 근거가 불충분한 새 요청은 409 QUIZ_ACTIVITY_NOT_READY입니다. 배정 문항 자체의 버전 충돌 등 기존 검증 오류는 유지합니다.
- 활동 목록·배정 문항·답변 GET은 재개하거나 답변을 확정하지 않습니다. 준비 상태는 응답에서 계산하며 body/query로 전달하지 않습니다. 현재 작업은 중단 상태 재개와 완료 활동의 새 쓰기 차단을 추가하며, 진행 중 활동의 기존 문항별 제출 검증은 유지합니다.

- 새 첫 답변 또는 DRAFT의 새 finalResponse를 저장할 때만 재개합니다. 같은 첫 답변만 재전송한 DRAFT와 동일 확정 답변 재전송은 기존 결과를 반환하며 상태를 변경하지 않습니다. 내부 PATCH 확정은 이번 아동 PUT 재개 동작과 구분합니다.

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
| Content-Type | application/json; charset=utf-8 | 예 | JSON body |

### Request Body

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| firstResponse | string \| null | 아니오 | 예 | 최대200자; 재PUT에서도 최초 값을 유지 |
| expressionMatchResult | ExpressionMatchResult \| null | 아니오 | 예 | SELF 유형만, 서버 원본 업로드 없음 |
| cameraModelVersion | string \| null | 아니오 | 예 | 판정 있으면 필요; 최대100자 |
| finalResponse | string \| null | 아니오 | 예 | 최대200자; 최초는null, 힌트후 최종 추가 |
| technicalFailureReason | TechnicalFailureReason \| null | 아니오 | 예 | 현재 두 enum; 다른 문자열 금지 |

~~~json
{
  "firstResponse": "속상함",
  "expressionMatchResult": "MATCH",
  "cameraModelVersion": "browser-model-v1",
  "finalResponse": null,
  "technicalFailureReason": null
}
~~~

## 4. 성공 응답

HTTP 201 / 200.

~~~json
{
  "data": {
    "attemptId": "88000000-0000-4000-8000-000000000001",
    "activityQuizId": "50000000-0000-4000-8000-000000000001",
    "firstResponse": "속상함",
    "firstResponseCorrect": true,
    "expressionMatchResult": "MATCH",
    "cameraModelVersion": "browser-model-v1",
    "usedHintTypes": [],
    "finalResponse": "속상함",
    "resolvedAfterHint": false,
    "itemCorrect": true,
    "excludedFromScoring": false,
    "status": "FINALIZED",
    "respondedAt": "2026-10-06T12:05:00+09:00"
  },
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| data.attemptId | UUID | 예 | 아니오 | 응답 ID |
| data.activityQuizId | UUID | 예 | 아니오 | 배정 문항 |
| data.firstResponse | string \| null | 예 | 예 | 최초 답안 |
| data.firstResponseCorrect | boolean | 예 | 아니오 | 서버 최초 판정 |
| data.expressionMatchResult | ExpressionMatchResult \| null | 예 | 예 | 표정 판정 |
| data.cameraModelVersion | string \| null | 예 | 예 | 모델 버전 |
| data.usedHintTypes | string[] | 예 | 아니오 | 실제 발급된 힌트 종류 |
| data.finalResponse | string \| null | 예 | 예 | 최종 답안 |
| data.resolvedAfterHint | boolean | 예 | 아니오 | 힌트 후 해결 |
| data.itemCorrect | boolean | 예 | 아니오 | 최종 문항 판정 |
| data.excludedFromScoring | boolean | 예 | 아니오 | 기술 제외 |
| data.status | 'DRAFT' \| 'FINALIZED' | 예 | 아니오 | 현재 응답 상태 |
| data.respondedAt | ISODateTime | 예 | 아니오 | 응답 시각 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

현재 업무/공통 오류입니다. message 대신 HTTP status와 code로 분기합니다.

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID/Query 형식 |
| 401 | AUTHENTICATION_REQUIRED | 인증/입장 필요 |
| 403 | ACCESS_DENIED | 역할 권한 |
| 403 | CSRF_TOKEN_INVALID | 재취득; 변경 요청 자동 재전송 없음 |
| 409 | FIRST_RESPONSE_IMMUTABLE | 최초 값 변경 |
| 409 | ATTEMPT_FINALIZED | 확정 응답 변경 |
| 422 | INVALID_QUIZ_RESULT | 판정/힌트/응답 모순 |
| 422 | VALIDATION_FAILED | 길이 검증 |
| 404 | ACTIVITY_QUIZ_NOT_FOUND | 배정 문항 없음 |
| 500 | QUIZ_RESULT_CALCULATION_FAILED | 정상 집계를 만들지 못함. 마지막 답안·결과·활동 변경이 롤백됨. 기존 firstResponse와 최종 답안 등을 그대로 유지해 수동 재전송 |
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
    "path": "/api/v1/activity-quiz-items/{activityQuizId}/attempt",
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
export type ExpressionMatchResult = 'MATCH' | 'MISMATCH' | 'NOT_ANALYZABLE';

export type TechnicalFailureReason = 'CAMERA_PERMISSION_DENIED' | 'IMAGE_LOAD_FAILED';

export interface QuizAttemptBody {
  /** 최대200자; 재PUT에서도 최초 값을 유지 */
  firstResponse?: string | null;
  /** SELF 유형만, 서버 원본 업로드 없음 */
  expressionMatchResult?: ExpressionMatchResult | null;
  /** 판정 있으면 필요; 최대100자 */
  cameraModelVersion?: string | null;
  /** 최대200자; 최초는null, 힌트후 최종 추가 */
  finalResponse?: string | null;
  /** 현재 두 enum; 다른 문자열 금지 */
  technicalFailureReason?: TechnicalFailureReason | null;
}

export type UUID = string;

export type ISODateTime = string;

export interface QuizAttemptData {
  /** 응답 ID */
  attemptId: UUID;
  /** 배정 문항 */
  activityQuizId: UUID;
  /** 최초 답안 */
  firstResponse: string | null;
  /** 서버 최초 판정 */
  firstResponseCorrect: boolean;
  /** 표정 판정 */
  expressionMatchResult: ExpressionMatchResult | null;
  /** 모델 버전 */
  cameraModelVersion: string | null;
  /** 실제 발급된 힌트 종류 */
  usedHintTypes: string[];
  /** 최종 답안 */
  finalResponse: string | null;
  /** 힌트 후 해결 */
  resolvedAfterHint: boolean;
  /** 최종 문항 판정 */
  itemCorrect: boolean;
  /** 기술 제외 */
  excludedFromScoring: boolean;
  /** 현재 응답 상태 */
  status: 'DRAFT' | 'FINALIZED';
  /** 응답 시각 */
  respondedAt: ISODateTime;
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface PutQuizAttemptArgs {
  activityQuizId: T.UUID;
  body: T.QuizAttemptBody;
}

export function putQuizAttempt(
  api: ApiClient,
  args: PutQuizAttemptArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.QuizAttemptData>> {
  return api.request<T.QuizAttemptData>("/api/v1/activity-quiz-items/" + encodeURIComponent(args.activityQuizId) + "/attempt", {
    method: "PUT",
    auth: "child",
    expectedStatuses: [201, 200],
    signal: options.signal,
    body: args.body
  });
}
~~~

### 사용 예시

~~~ts
import { putQuizAttempt } from "./endpoints.ts";

const response = await putQuizAttempt(api, {
  "activityQuizId": "50000000-0000-4000-8000-000000000001",
  "body": {
    "firstResponse": "속상함",
    "expressionMatchResult": "MATCH",
    "cameraModelVersion": "browser-model-v1",
    "finalResponse": null,
    "technicalFailureReason": null
  }
});
const data = response.data;
// 개인정보·원본·토큰·응답 전문을 로그에 출력하지 않습니다.
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다. 실제 동작/삭제가 발생하는 요청이므로 테스트 데이터로 확인합니다.

~~~bash
curl -i -X PUT "$NEURINGO_API_ORIGIN/api/v1/activity-quiz-items/${activityQuizId}/attempt" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "$NEURINGO_CSRF_HEADER: $NEURINGO_CSRF_TOKEN" \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data '{"firstResponse":"속상함","expressionMatchResult":"MATCH","cameraModelVersion":"browser-model-v1","finalResponse":null,"technicalFailureReason":null}'
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S5-BAE-01` · `S5-YOON-01`
- 코드 근거: [현재 Controller/DTO](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/blob/63e43144c92449ec5670390a6b99d20d2b2580a5/backend/src/main/java/com/neuringo/neuringobe/quiz/controller/QuizController.java)
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.

S5-JIN-01(2026-10-28): 계산 장애는 기본값으로 저장하지 않습니다. 실패한 마지막 PUT은 롤백되며 GET attempt는 기존 답안 상태를 반환하거나, 최초 제출이었다면404 ATTEMPT_NOT_FOUND입니다. 정상 집계의 유효 문항0개는 성공 처리하며 L1/S1·NO_VALID_QUESTIONS를 저장합니다. 정책은 INITIAL_QUIZ_V2입니다.
