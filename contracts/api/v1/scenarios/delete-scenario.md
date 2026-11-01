# API-SCN-06 · 미사용 시나리오 초안 폐기

> 구현 — 2026-11-01 / 브랜치 `feature/scenario-reuse-approval`. 시나리오 재사용 승인과 [수정하기] 흐름의 실제 구현 기준입니다. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | DELETE |
| Path | /api/v1/scenarios/{scenarioId} |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 담당 강사 Bearer + CSRF |
| 성공 | 204 |
| 반환 | 없음 |
| FE 함수 | deleteScenario |
| 구현·협업 | 배재일 |

## 2. 용도·중요 규칙

- 초안(`DRAFT`)만 지운다([폐기]). 작은 목표·첫 질문 목표 연결도 함께 지워진다.
- 판정이 끝난 판(승인·고쳐 와·반려)은 기록이라 지우지 않는다(409 `SCENARIO_IN_USE`). 기존 활동 참조를 끊지 않는다.
- 그 학습 목표의 담당 강사만 지운다. 남의 초안과 없는 ID는 같은 404.
- 아동을 지우면 그 아동의 학습 목표에 매인 초안·판정 기록은 DB가 함께 지운다. 공용 승인본은 남는다.

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

없음. 빈 JSON 객체를 추가하지 않습니다.

## 4. 성공 응답

HTTP 204. 본문이 없습니다.


### response schema

본문 없음.

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다.

## 5. 오류·FE 처리

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID·JSON 형식 |
| 401 | AUTHENTICATION_REQUIRED | 인증 필요 |
| 401 | INVALID_TOKEN | 강사 토큰 만료/위조; 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 강사가 아닌 역할(아동 세션 등) |
| 403 | CSRF_TOKEN_INVALID | 재취득; 변경 요청 자동 재전송 없음 |
| 409 | SCENARIO_IN_USE | 판정이 끝난 판 |
| 404 | SCENARIO_NOT_FOUND | 없는 ID, 남의 초안 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 409,
    "code": "SCENARIO_IN_USE",
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
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import type * as T from "./types.ts";

export interface DeleteScenarioArgs {
  scenarioId: T.UUID;
}

export function deleteScenario(
  api: ApiClient,
  args: DeleteScenarioArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<null>> {
  return api.request<null>("/api/v1/scenarios/" + encodeURIComponent(args.scenarioId), {
    method: "DELETE",
    auth: "instructor",
    expectedStatuses: [204],
    signal: options.signal
  });
}
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다.

~~~bash
curl -i -X DELETE "$NEURINGO_API_ORIGIN/api/v1/scenarios/${scenarioId}" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN" \
  -H "$NEURINGO_CSRF_HEADER: $NEURINGO_CSRF_TOKEN"
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S4-BAE-02`
- 코드 근거: `backend/src/main/java/com/neuringo/neuringobe/scenario/controller/ScenarioController.java` · `scenario/service/ScenarioDraftService.java`

- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제를 통합 테스트로 확인합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
