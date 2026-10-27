# API-QUIZ-07 · 퀴즈 결과·최초 난이도 조회

> 구현 — 2026-10-22 / develop `63e4314`. 현재 코드의 HTTP 계약. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | GET |
| Path | /api/v1/activities/{activityId}/quiz-result |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 호출 인자 auth로 본인 아동/담당 강사를 구분 |
| 성공 | 200 |
| 반환 | ApiResponse<QuizResultData> |
| FE 함수 | getQuizResult |
| 구현·협업 | 진미나·윤상현 |

## 2. 용도·중요 규칙

- overallAccuracy는0~1이며 기술 제외 후 힌트 전 첫 응답 정답만 집계합니다. 유효0개는 null입니다. 힌트 후 해결은 별도 지표입니다.
- initialDifficultyUsed=true는 FIRST_COMBINED 결과 중 최초 난이도로 실제 사용한 경우입니다. 독립 퀴즈 결과에도 계산 값은 있을 수 있지만 initialDifficultyUsed=false이며 활동 난이도에 적용하지 않습니다.
- 최초 매핑은50%미만 L1/S2, 50~80%미만 L2/S1, 80%이상 L3/S0, 계산2회실패 L1/S1입니다. 후속 역할극 난이도는 이 독립 퀴즈로 바꾸지 않습니다.
- 아동에게 상세 평가/난이도 결과를 그대로 표시하지 않습니다.

## 3. 요청

### Path parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| activityId | UUID string | 예 | 경로 식별자 |

### Query parameters

없음. 지원되지 않는 page/size/sort를 보내도 적용된다고 가정하지 않습니다.

### Headers·쿠키

| 이름 | 값/타입 | 필수 | 주의 |
|---|---|---|---|
| Accept | application/json | 예 | 공통 envelope |
| Cookie | credentials: include | 예 | 브라우저 관리; HttpOnly 쿠키 직접 읽기 금지 |
| Authorization | Bearer <accessToken> | 강사 모드 | 아동/public에 강사 토큰을 붙이지 않음 |

### Request Body

없음. 빈 JSON 객체를 추가하지 않습니다.

## 4. 성공 응답

HTTP 200.

~~~json
{
  "data": {
    "activityId": "40000000-0000-4000-8000-000000000001",
    "validQuestionCount": 3,
    "correctQuestionCount": 2,
    "overallAccuracy": 0.6666666666666666,
    "totalHintCount": 1,
    "resolvedAfterHintCount": 1,
    "scenarioLevel": "L2",
    "initialSupportLevel": "S1",
    "difficultyFallbackApplied": false,
    "initialDifficultyUsed": true,
    "policyVersion": "example-policy-v1"
  },
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| data.activityId | UUID | 예 | 아니오 | 활동 |
| data.validQuestionCount | number | 예 | 아니오 | 기술 제외 후 분모 |
| data.correctQuestionCount | number | 예 | 아니오 | 힌트 전 최초 정답 수 |
| data.overallAccuracy | number \| null | 예 | 예 | 0~1 비율, 유효0개면null |
| data.totalHintCount | number | 예 | 아니오 | 힌트 총수 |
| data.resolvedAfterHintCount | number | 예 | 아니오 | 힌트 후 해결 별도 집계 |
| data.scenarioLevel | ScenarioLevel \| null | 예 | 예 | 최초 난이도 |
| data.initialSupportLevel | SupportLevel \| null | 예 | 예 | 초기 지원 |
| data.difficultyFallbackApplied | boolean | 예 | 아니오 | 기본값 사용 |
| data.initialDifficultyUsed | boolean | 예 | 아니오 | FIRST_COMBINED 결과만 최초 난이도에 적용; 독립 퀴즈는 false |
| data.policyVersion | string | 예 | 아니오 | 정책 버전 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

현재 업무/공통 오류입니다. message 대신 HTTP status와 code로 분기합니다.

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID/Query 형식 |
| 401 | AUTHENTICATION_REQUIRED | 인증/입장 필요 |
| 401 | INVALID_TOKEN | 강사 토큰 만료/위조; 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 역할 권한 |
| 409 | QUIZ_NOT_COMPLETED | 모든답안미확정 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |
| 404 | ACTIVITY_NOT_FOUND | 없거나 소유권 밖 리소스 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 400,
    "code": "INVALID_REQUEST",
    "message": "요청을 확인해 주세요.",
    "path": "/api/v1/activities/{activityId}/quiz-result",
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

export type ScenarioLevel = 'L1' | 'L2' | 'L3';

export type SupportLevel = 'S0' | 'S1' | 'S2' | 'S3';

export interface QuizResultData {
  /** 활동 */
  activityId: UUID;
  /** 기술 제외 후 분모 */
  validQuestionCount: number;
  /** 힌트 전 최초 정답 수 */
  correctQuestionCount: number;
  /** 0~1 비율, 유효0개면null */
  overallAccuracy: number | null;
  /** 힌트 총수 */
  totalHintCount: number;
  /** 힌트 후 해결 별도 집계 */
  resolvedAfterHintCount: number;
  /** 최초 난이도 */
  scenarioLevel: ScenarioLevel | null;
  /** 초기 지원 */
  initialSupportLevel: SupportLevel | null;
  /** 기본값 사용 */
  difficultyFallbackApplied: boolean;
  /** FIRST_COMBINED 결과만 최초 난이도에 적용; 독립 퀴즈는 false */
  initialDifficultyUsed: boolean;
  /** 정책 버전 */
  policyVersion: string;
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface GetQuizResultArgs {
  activityId: T.UUID;
  auth: "instructor" | "child";
}

export function getQuizResult(
  api: ApiClient,
  args: GetQuizResultArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.QuizResultData>> {
  return api.request<T.QuizResultData>("/api/v1/activities/" + encodeURIComponent(args.activityId) + "/quiz-result", {
    method: "GET",
    auth: args.auth,
    expectedStatuses: [200],
    signal: options.signal
  });
}
~~~

### 사용 예시

~~~ts
import { getQuizResult } from "./endpoints.ts";

const response = await getQuizResult(api, {
  "activityId": "40000000-0000-4000-8000-000000000001",
  "auth": "child"
});
const data = response.data;
// 개인정보·원본·토큰·응답 전문을 로그에 출력하지 않습니다.
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다. 실제 동작/삭제가 발생하는 요청이므로 테스트 데이터로 확인합니다.

~~~bash
curl -i -X GET "$NEURINGO_API_ORIGIN/api/v1/activities/${activityId}/quiz-result" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN"
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S5-JIN-01` · `S5-JIN-02` · `S5-YOON-01`
- 코드 근거: [현재 Controller/DTO](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/blob/63e43144c92449ec5670390a6b99d20d2b2580a5/backend/src/main/java/com/neuringo/neuringobe/quiz/controller/QuizController.java)
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
