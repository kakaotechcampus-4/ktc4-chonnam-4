# API-SCN-04 · 검토 시나리오 수정·확정·활동 배정

> 2026-11-05 공통 풀 정책 반영: 최초 승인 강사와 관계없이 확인된 공통 승인 콘텐츠를 조건에 맞춰 제공한다. 제시 이력·검토·이번 활동의 확정은 강사별이다. NEXT 거절은 이번 검토에만 적용한다. 최초 승인자 탈퇴 후에도 콘텐츠·조건은 보존하고 계정/삭제 학급 연결은 해제한다. 아래는 V39 작업본 기준이며 백엔드 회귀 검증은 완료했으며 운영 배포·브라우저 연동·부하 측정은 아직 완료하지 않았다.

> 2026-11-04 · S4-JIN-01 작업본, 통합 기준 develop `bab136d`. Controller·DTO와 PostgreSQL/HTTP 통합 검사로 확인한 계약이다. 작업 브랜치 구현이며 develop 병합·운영 배포·프론트엔드 연동은 완료하지 않았다.

> 구현 — 2026-11-05 / develop `7376e91` (PR #101 병합). 담당 강사 Bearer + CSRF + Idempotency-Key, 성공은 200입니다. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

`PUT /api/v1/activities/{activityId}/scenario`

기존 PATCH /scenarios/{scenarioId} 및 POST /scenarios/{scenarioId}/versions 제안의 이번 검토 기능을 통합한다. 별도 초안 저장·버전 생성 API는 사용하지 않는다.

## 2. 요청

### 공통 헤더·인증

담당 강사 Bearer 인증, 쿠키 CSRF가 필요하다. `GET /api/v1/csrf`의 쿠키와 반환된 헤더 이름·토큰을 보내고 `credentials: include`를 사용한다.

| 이름 | 필수 | 값 |
| --- | --- | --- |
| Authorization | 예 | `Bearer <accessToken>` |
| Content-Type / Accept | 예 | `application/json` |
| Idempotency-Key | 예 | 논리 동작마다 만든 UUID. 응답 유실 재전송은 같은 키·본문 유지 |
| CSRF 헤더·쿠키 | 예 | CSRF 조회 결과. HttpOnly 입장 쿠키를 직접 읽지 않음 |

Path의 activityId는 UUID이며 Query parameters는 없다. 응답은 기존 `{data, meta:{traceId}}` 형식이다. 아래 모든 성공 응답은 200이다. 예시 UUID·시간·문구는 합성 값이며 실제 호출에서는 응답 값을 사용한다.

```json
{
  "reviewId":"70000000-0000-4000-8000-000000000001",
  "revision":1,
  "scenarioId":"80000000-0000-4000-8000-000000000001",
  "scenarioVersion":1,
  "edits":{
    "title":"친구의 마음을 알아봐요",
    "microGoals":[{
      "microGoalId":"81000000-0000-4000-8000-000000000001",
      "description":"친구의 기분을 표현한다.",
      "requiredEvidence":["친구가 슬프다고 말한다."]
    }]
  }
}
```

| 필드 | 타입·규칙 |
| --- | --- |
| reviewId | 필수 UUID. 현재 검토 |
| revision | 필수 양수 정수. 현재 검토 응답 번호 |
| scenarioId / scenarioVersion | 필수 UUID / 양수 정수. 제시된 원본의 ID·버전 |
| edits | 선택 객체, 생략·null은 수정 없음 |
| edits.title / summary | 선택 string 또는 null. null·누락은 원본 유지. 공백만 있는 문자열 금지 |
| edits.microGoals | 선택 배열. 누락·null·[]는 목표 변경 없음 |
| microGoalId | 수정할 기존 목표의 실제 UUID. 새 목표 생성·중복 ID 금지 |
| description | 선택 string 또는 null |
| requiredEvidence | 선택 string[] 또는 null. 명시한 배열은 1~3개, 빈 값 금지 |

사실·등장인물·시작 질문·질문별 목표 연결·달성 예시·목표 개수/순서는 이 API로 수정하지 않는다. 허용하지 않은 필드는 400이다. 원본에 없는 목표 ID, 빈 문자열·근거, 콘텐츠 길이·안전·금지 표현 위반은 거부한다. 승인 시각·강사 ID·status는 서버가 결정한다.

## 3. 승인·버전·DB 처리

재사용본도 매번 강사가 이 요청으로 확정해야 한다. 현재 권한·입력·revision·원본 버전·본문·학생 배정 이력을 저장 직전에 재확인한다.

- 수정이 없거나 실제 본문이 같으면 제시된 원본 ID를 배정한다. 재사용본이면 실제 재사용 횟수를 1 올린다.
- 실제 수정이 있으면 새 scenario UUID와 새 작은 목표 UUID를 INSERT한다. source_scenario_id는 제시된 원본, 버전은 부모 + 1이다. 원본의 승인 상태·내용·기존 활동 배정은 변경하지 않는다. 원본은 계속 재사용 후보가 될 수 있다.
- 새 버전의 질문별 목표 연결을 새 UUID로 옮긴다. 첫 목표에 임의 연결하지 않는다. 수정본은 별도 승인된 재사용 후보가 된다.
- 하나의 트랜잭션으로 시나리오 승인·조건, activity.scenario_id, activity_scenario_approval의 승인 근거를 저장하고 임시 scenario_review·scenario_review_candidate를 DELETE한다. 실패하면 모두 롤백한다.
- 배정받은 시나리오를 나중에 이 API로 수정하지 않는다. 검토 중인 후보를 수정해 확정하는 기능이다.

## 4. 성공 응답

```json
{
  "data":{
    "reviewId":"70000000-0000-4000-8000-000000000001",
    "activityId":"40000000-0000-4000-8000-000000000001",
    "outcome":"CONFIRMED",
    "closedAt":"2026-11-04T09:00:00Z"
  },
  "meta":{"traceId":"90000000-0000-4000-8000-000000000001"}
}
```

data의 reviewId/activityId는 UUID, outcome은 CONFIRMED, closedAt은 ISO-8601 시각이다. 최종 배정 ID는 기존 강사용 `GET /api/v1/activities/{activityId}`의 **data.activity.scenarioId**에서 확인한다. 수정본일 때 요청한 scenarioId는 원본이므로 최종 ID로 간주하지 않는다.

## 5. 재전송·실패

같은 키·정규화된 같은 수정 내용은 검토 목록 DELETE 이후에도 최초 확정 결과를 복원하며 새 버전·배정·재사용 횟수를 중복 생성하지 않는다. 같은 키에 다른 동작/수정 내용을 보내면 409 IDEMPOTENCY_KEY_REUSED다. 수정 목록 순서만 다른 요청은 같은 정규화 내용으로 취급한다. 최소 종료 요청 기록은 활동 삭제까지 보존한다.

## 오류와 재전송

| HTTP | code | 의미·처리 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | UUID/JSON/필수 헤더 형식 오류, 알 수 없는 필드. 요청 수정 |
| 401 | AUTHENTICATION_REQUIRED / INVALID_TOKEN | 인증 없음·유효하지 않은 토큰. 재로그인 |
| 403 | ACCESS_DENIED | 아동 역할 또는 CSRF 등 접근 차단. CSRF는 새 토큰을 받아 원래 키로 재시도 |
| 404 | ACTIVITY_NOT_FOUND / GOAL_NOT_FOUND | 없는 자원과 권한 밖 자원은 구분하지 않음 |
| 409 | INITIAL_DIFFICULTY_NOT_READY | 저장된 최초 난이도 근거가 없음 |
| 409 | SCENARIO_REVIEW_CHANGED | 검토·현재 후보·수정 번호가 달라짐. 새 키의 START_OR_RESUME로 현재 상태 복원 |
| 409 | SCENARIO_REVIEW_INPUT_CHANGED | 목표·난이도·담당 조건이 달라짐. 이전 입력으로 진행하지 않음 |
| 409 | SCENARIO_REVIEW_CLOSED / SCENARIO_REVIEW_NOT_READY | 종료됐거나 진행 중인 검토 없음 |
| 409 | SCENARIO_ALREADY_ASSIGNED | 이미 배정됨. 활동 상태 조회 |
| 409 | IDEMPOTENCY_KEY_REUSED | 같은 키를 다른 동작·본문에 사용함 |
| 409 | SCENARIO_ALREADY_USED | 최종 배정 ID가 같은 학생에게 이미 배정됨 |
| 409 | SCENARIO_OPENING_FOCUS_NOT_READY | 시작 질문별 목표 연결 미준비 |
| 409 | SCENARIO_CANDIDATE_INVALID | 현재 후보 상태·내용·배정 자격이 유효하지 않음 |
| 422 | VALIDATION_FAILED | 필수값·양수 revision/version 오류 |
| 422 | INVALID_SCENARIO_REVIEW_REQUEST / INVALID_SCENARIO_EDITS / INVALID_GOAL_CONDITIONS | 동작별 필드 조합·편집·목표 조건 오류 |
| 502 | SCENARIO_GENERATION_FAILED / SCENARIO_GENERATION_INVALID_OUTPUT | 생성·출력 검사 실패. 준비 요청에서 발생 가능 |
| 503 | SCENARIO_GENERATOR_UNAVAILABLE / SCENARIO_GENERATION_FAILED | 생성 제공자 미설정·일시 불가 |
| 504 | SCENARIO_GENERATION_FAILED | 생성 시간 초과 |

오류는 `{error:{status,code,message,path,traceId,fieldErrors}}` 형식이며 fieldErrors는 오류 세부 목록이며 일반 오류에서는 []다. 위 코드는 세 API에서 발생할 수 있는 공통 범위이며 생성 오류는 준비 POST에서 발생한다. 전송 실패·Abort는 서버 처리 실패의 증거가 아니다. 동일 동작은 기존 키·본문으로 재전송한다. 서버는 실패 후 자동 생성 루프를 돌리지 않는다.

## 6. 구현·연동

ScenarioReviewController.confirm → ScenarioConfirmationService.confirm. 선택적으로 title/summary/목표 설명·근거를 화면에서 편집해 마지막 확정 요청에 포함한다. 별도 초안 PATCH를 호출하지 않는다. 퀴즈 완료·결과 조회만 기다리면 배정되지 않는다. 기존 draft-endpoints.ts의 reviewScenario/createScenarioVersion은 이번 계약과 다르므로 그대로 사용하지 않는다. 프론트엔드는 직접 수정하지 않았다.


공통 승인과 활동별 승인: A의 원본을 B가 수정 없이 확정하면 원본 최초 승인자·조건을 덮어쓰지 않고 B의 활동별 승인 근거를 INSERT한다. 실제 수정하면 새 ID/버전을 B의 최초 승인으로 등록하며 새 콘텐츠도 공통 후보가 된다. 최초 승인자가 탈퇴해 NULL이 된 승인 기록도 유효한 상태·조건이면 재사용할 수 있다. 콘텐츠 본문·조건의 직접 변경이나 소유 강사 재지정 기능은 추가하지 않았다.

## 복사 가능한 TypeScript

공통 [api-client.ts](../examples/api-client.ts), [types.ts](../examples/types.ts), [endpoints.ts](../examples/endpoints.ts)를 같은 폴더 구조로 복사합니다. 요청·응답 타입은 types.ts의 `ScenarioConfirmBody`·`ScenarioReviewClosure`입니다.

~~~ts
export interface ReviewScenarioArgs {
  activityId: T.UUID;
  body: T.ScenarioConfirmBody;
  idempotencyKey: T.UUID;
}

export function reviewScenario(
  api: ApiClient,
  args: ReviewScenarioArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ScenarioReviewClosure>> {
  return api.request<T.ScenarioReviewClosure>("/api/v1/activities/" + encodeURIComponent(args.activityId) + "/scenario", {
    method: "PUT",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    idempotencyKey: args.idempotencyKey,
    body: args.body
  });
}
~~~
