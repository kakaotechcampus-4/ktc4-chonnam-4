# API-SCN-01 · 시나리오 준비·복원·다음 후보

> 2026-11-05 공통 풀 정책 반영: 최초 승인 강사와 관계없이 확인된 공통 승인 콘텐츠를 조건에 맞춰 제공한다. 제시 이력·검토·이번 활동의 확정은 강사별이다. NEXT 거절은 이번 검토에만 적용한다. 최초 승인자 탈퇴 후에도 콘텐츠·조건은 보존하고 계정/삭제 학급 연결은 해제한다. 아래는 V39 작업본 기준이며 백엔드 회귀 검증은 완료했으며 운영 배포·브라우저 연동·부하 측정은 아직 완료하지 않았다.

> 2026-11-04 · S4-JIN-01 작업본, 통합 기준 develop `bab136d`. Controller·DTO와 PostgreSQL/HTTP 통합 검사로 확인한 계약이다. 작업 브랜치 구현이며 develop 병합·운영 배포·프론트엔드 연동은 완료하지 않았다.

> 구현 — 2026-11-05 / develop `7376e91` (PR #101 병합). 담당 강사 Bearer + CSRF + Idempotency-Key, 성공은 200입니다. 운영 배포·제품 인수 완료를 의미하지 않습니다.

## 1. Endpoint

`POST /api/v1/activities/{activityId}/scenario-review`

기존 제안 `POST /api/v1/scenarios`의 201/202 계약을 대체한다. 별도 목록·복원 GET·NEXT API를 추가하지 않는다.

## 2. 용도·처리

- 저장된 학습 목표와 이 활동의 난이도로 전체 강사의 확인된 승인 시나리오를 공통 풀에서 조회한다. 같은 학생의 과거 배정 ID는 모든 활동 상태에서 제외한다.
- 난이도는 최초 결합이면 최초 퀴즈 결과의 레벨, 역할극 단독(ROLEPLAY)이면 배정 때 활동에 고정한 다음 역할극 난이도(roleplay_activity_difficulty)다(2026-11-06, PR #90 통합). 역할극 단독의 입력 지문에는 고정값을 정한 결정 ID·시작 지원이 더 들어가, 그 활동의 고정값이 바뀌면 이전 검토 확정이 409 `SCENARIO_REVIEW_INPUT_CHANGED`이고 다른 역할극이 끝나 아동의 최신 결정만 바뀐 경우는 그대로 이어진다.
- 목표 문구·레벨, 지정한 범주·상황·등장인물 구성·필수 행동·금지 표현을 비교한다. 전체 목표 해시 일치만으로 후보를 고르지 않는다. 요구한 조건의 확인 정보가 없으면 제외한다.
- 강사별 미제시 → 오래전에 제시 → UUID 순으로 첫 재사용 목록을 고정한다. 검토 중 새 승인본을 추가하지 않는다. 화면에는 한 개만 반환한다.
- NEXT는 현재 후보를 이번 검토에서 거절하고 다음 유효 후보를 제시한다. 원본 승인본을 전역 거절·삭제하지 않는다.
- 후보가 없거나 소진되면 동일 요청에서 동기 LLM 생성·검사를 수행하고 한 개를 제공한다. 202·폴링·후보 없음만 반환하는 정상 응답은 없다. 전역 생성 큐·워커·병렬 제한은 후속 논의다.
- 재사용·신규 모두 이 응답만으로 활동에 배정되지 않는다. 강사가 확정 PUT을 보내야 한다.

## 3. 요청

### 공통 헤더·인증

담당 강사 Bearer 인증, 쿠키 CSRF가 필요하다. `GET /api/v1/csrf`의 쿠키와 반환된 헤더 이름·토큰을 보내고 `credentials: include`를 사용한다.

| 이름 | 필수 | 값 |
| --- | --- | --- |
| Authorization | 예 | `Bearer <accessToken>` |
| Content-Type / Accept | 예 | `application/json` |
| Idempotency-Key | 예 | 논리 동작마다 만든 UUID. 응답 유실 재전송은 같은 키·본문 유지 |
| CSRF 헤더·쿠키 | 예 | CSRF 조회 결과. HttpOnly 입장 쿠키를 직접 읽지 않음 |

Path의 activityId는 UUID이며 Query parameters는 없다. 응답은 기존 `{data, meta:{traceId}}` 형식이다. 아래 모든 성공 응답은 200이다. 예시 UUID·시간·문구는 합성 값이며 실제 호출에서는 응답 값을 사용한다.

시작·복원:

```json
{"action":"START_OR_RESUME"}
```

다른 후보 요청:

```json
{"action":"NEXT","reviewId":"70000000-0000-4000-8000-000000000001","revision":1}
```

| 필드 | 타입·규칙 |
| --- | --- |
| action | 필수. START_OR_RESUME 또는 NEXT |
| reviewId | NEXT 필수 UUID. START_OR_RESUME에는 보내지 않음 |
| revision | NEXT 필수 양수 정수. 직전 응답 revision. START_OR_RESUME에는 보내지 않음 |

강사 ID·학급·난이도·후보 ID 배열·승인 여부는 요청하지 않는다. 서버 저장 근거로 결정한다. 알 수 없는 JSON 필드는 400으로 거부한다.

## 4. 성공 응답

```json
{
  "data": {
    "reviewId": "70000000-0000-4000-8000-000000000001",
    "activityId": "40000000-0000-4000-8000-000000000001",
    "revision": 1,
    "conditions": {
      "title": "차례를 기다려요",
      "category": null,
      "situationType": null,
      "characters": [],
      "requiredElements": [],
      "forbiddenExpressions": [],
      "scenarioLevel": "L2"
    },
    "scenario": {
      "scenarioId": "80000000-0000-4000-8000-000000000001",
      "scenarioVersion": 1,
      "scenarioLevel": "L2",
      "title": "넘어져서 우는 친구",
      "summary": "놀이터에서 친구가 미끄럼틀 앞에서 넘어져 울었다.",
      "characters": [
        {
          "id": "LEARNER",
          "role": "학습자"
        },
        {
          "id": "FRIEND",
          "role": "넘어진 친구"
        }
      ],
      "scenarioFacts": [
        "친구가 미끄럼틀로 뛰어갔다.",
        "친구가 넘어졌다.",
        "친구가 울었다.",
        "친구가 무릎을 보았다."
      ],
      "microGoals": [
        {
          "microGoalId": "81000000-0000-4000-8000-000000000001",
          "description": "친구가 넘어졌다는 사실을 말하기",
          "requiredEvidence": [
            "관련된 내용을 말한다."
          ],
          "achievementExample": "친구가 넘어졌어."
        },
        {
          "microGoalId": "81000000-0000-4000-8000-000000000002",
          "description": "친구의 표정을 알아보기",
          "requiredEvidence": [
            "관련된 내용을 말한다."
          ],
          "achievementExample": "친구가 넘어졌어."
        },
        {
          "microGoalId": "81000000-0000-4000-8000-000000000003",
          "description": "친구의 마음을 생각하기",
          "requiredEvidence": [
            "관련된 내용을 말한다."
          ],
          "achievementExample": "친구가 넘어졌어."
        }
      ],
      "startingQuestions": {
        "S0": "친구에게 무슨 일이 있었어?",
        "S1": "친구가 미끄럼틀 앞에서 울고 있어. 무슨 일이 있었을까?",
        "S2": "친구가 넘어졌을까, 아니면 장난감을 잃어버렸을까?"
      },
      "startingQuestionGoalIds": {
        "S0": "81000000-0000-4000-8000-000000000001",
        "S1": "81000000-0000-4000-8000-000000000001",
        "S2": "81000000-0000-4000-8000-000000000001"
      }
    }
  },
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
```

| 필드 | 타입·의미 |
| --- | --- |
| data.reviewId / activityId | UUID |
| data.revision | 양수 정수. 늦은 응답 판별·다음 명령에 사용 |
| data.conditions | 현재 목표와 최초 난이도. title/scenarioLevel 문자열, category/situationType은 string 또는 null, characters는 역할 객체 배열, requiredElements/forbiddenExpressions는 문자열 배열 |
| data.scenario | 현재 콘텐츠 한 개. 전체 후보 목록·개수·내부 생성 설정은 미노출 |
| scenarioId / scenarioVersion / scenarioLevel | UUID / 양수 정수 / L1·L2·L3 |
| title / summary | 문자열 |
| characters / scenarioFacts | {id,role} 배열 / 문자열 배열 |
| microGoals | {microGoalId:UUID,description,requiredEvidence:string[],achievementExample} 배열 |
| startingQuestions | S0·S1·S2별 질문 문자열 |
| startingQuestionGoalIds | S0·S1·S2별 같은 시나리오의 실제 microGoalId UUID |

microGoalId는 DB UUID이며 MG-01 같은 모델 키가 아니다. 응답 배열 순서를 학습 목표 통과 순서로 해석하지 않는다. facts/질문/목표·요청 조건을 함께 보여 강사가 교육적 적합성을 확인한다.

## 5. 복원·동시 요청

새로고침·로그아웃 후 재로그인·서버 재시작 뒤 START_OR_RESUME를 보내면 PostgreSQL의 같은 검토와 현재 위치를 복원한다. 서버에 저장하지 않은 화면 편집은 복원하지 않는다. 검토는 자동 만료되지 않는다.

동일 NEXT 키·본문은 최초 결과와 revision을 반환하며 제시 횟수를 더 올리지 않는다. 나중 후보까지 이동한 뒤 과거 NEXT를 재전송하면 과거 결과가 반환될 수 있으므로 화면은 더 낮은 revision을 반영하지 않는다. 서로 다른 키로 같은 revision을 변경하면 하나만 성공한다. 취소·확정된 과거 준비 키로 새 검토를 만들 수 없다.

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

## 6. 호출 예시

```js
const response = await fetch(`${origin}/api/v1/activities/${activityId}/scenario-review`, {
  method: 'POST', credentials: 'include',
  headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json',
    'Idempotency-Key': requestKey, [csrf.headerName]: csrf.token },
  body: JSON.stringify({ action: 'START_OR_RESUME' })
});
```

공유 예제 prepareScenario는 2026-11-05에 이 계약으로 바꿔 `examples/endpoints.ts`로 옮겼다(아래 TypeScript). 예제 코드 파일과 실제 프론트엔드 연동은 이번 변경에서 수정하지 않았다.

## 7. 구현·검증

ScenarioReviewController.prepare → ScenarioPreparationService → ScenarioReviewViewService. 실제 PostgreSQL·인증·CSRF HTTP 검사로 확인했으며 LlmProvider는 모의 응답이다. 실제 제공자·브라우저·배포·부하 측정 완료를 뜻하지 않는다. 관련 Task: S4-JIN-01. 최종 확정·취소 계약도 같은 검토 ID/revision을 사용한다.


공통 풀 경계: 다른 강사의 미승인 초안·학생·학습 목표·검토 기록은 공유하지 않는다. 응답 conditions는 요청한 학생의 목표이며 최초 승인자의 개인 데이터를 반환하지 않는다. APPROVED 상태만 있고 확인된 승인 조건 기록이 없는 과거 자료는 조건을 추정하지 않고 제외한다. 공통 후보가 있어도 현재 담당 강사의 확정 PUT 전에는 배정하지 않는다.

## 복사 가능한 TypeScript

공통 [api-client.ts](../examples/api-client.ts), [types.ts](../examples/types.ts), [endpoints.ts](../examples/endpoints.ts)를 같은 폴더 구조로 복사합니다. 요청·응답 타입은 types.ts의 `ScenarioReviewBody`·`ScenarioReviewData`입니다.

~~~ts
export interface PrepareScenarioArgs {
  activityId: T.UUID;
  body: T.ScenarioReviewBody;
  idempotencyKey: T.UUID;
}

export function prepareScenario(
  api: ApiClient,
  args: PrepareScenarioArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ScenarioReviewData>> {
  return api.request<T.ScenarioReviewData>("/api/v1/activities/" + encodeURIComponent(args.activityId) + "/scenario-review", {
    method: "POST",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    idempotencyKey: args.idempotencyKey,
    body: args.body
  });
}
~~~
