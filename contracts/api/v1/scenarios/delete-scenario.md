# API-SCN-06 · 시나리오 검토 취소

> 2026-11-05 공통 풀 정책 반영: 최초 승인 강사와 관계없이 확인된 공통 승인 콘텐츠를 조건에 맞춰 제공한다. 제시 이력·검토·이번 활동의 확정은 강사별이다. NEXT 거절은 이번 검토에만 적용한다. 최초 승인자 탈퇴 후에도 콘텐츠·조건은 보존하고 계정/삭제 학급 연결은 해제한다. 아래는 V39 작업본 기준이며 백엔드 회귀 검증은 완료했으며 운영 배포·브라우저 연동·부하 측정은 아직 완료하지 않았다.

> 2026-11-04 · S4-JIN-01 작업본, 통합 기준 develop `bab136d`. Controller·DTO와 PostgreSQL/HTTP 통합 검사로 확인한 계약이다. 작업 브랜치 구현이며 develop 병합·운영 배포·프론트엔드 연동은 완료하지 않았다.

## 1. Endpoint

`DELETE /api/v1/activities/{activityId}/scenario-review`

기존 DELETE /scenarios/{scenarioId} 초안 폐기 제안을 이번 검토 취소 기능으로 대체한다. 응답은 204가 아닌 **200 JSON**이며 DELETE에도 아래 본문을 보낸다.

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
{"reviewId":"70000000-0000-4000-8000-000000000001","revision":1}
```

reviewId는 필수 UUID, revision은 필수 양수 정수다. scenarioId는 보내지 않는다. 알 수 없는 필드는 400이다.

## 3. 처리·보존

현재 강사 권한·검토 ID·revision을 확인한다. 같은 트랜잭션에서 scenario_review 및 scenario_review_candidate를 DELETE하고 미완료 요청의 결과 스냅샷을 정리한다. 참조 없는 미승인 생성 초안·목표는 정리한다. 승인된 재사용 원본과 제시·재사용 통계, 과거 활동 배정은 DELETE하지 않는다. 버전 계보에서 참조하는 생성 초안도 보존한다.

배정하지 않고 활동의 scenario_review_epoch를 증가시켜 진행 중이던 이전 생성/조회 결과가 뒤늦게 저장되지 못하게 한다. 자동 만료는 없으며, 명시적 취소 또는 확정/활동 삭제로 임시 검토가 정리된다. 강사 계정/아동 삭제 정책은 해당 삭제 API의 종속 데이터 정리 규칙을 따른다.

## 4. 성공 응답

```json
{
  "data":{
    "reviewId":"70000000-0000-4000-8000-000000000001",
    "activityId":"40000000-0000-4000-8000-000000000001",
    "outcome":"CANCELLED",
    "closedAt":"2026-11-04T09:00:00Z"
  },
  "meta":{"traceId":"90000000-0000-4000-8000-000000000001"}
}
```

UUID 두 개, CANCELLED 문자열, ISO-8601 종료 시각을 반환한다. 같은 키·본문은 같은 종료 결과를 반환한다. 취소 이후 이전 START/NEXT 키는 검토를 되살리지 않는다. 새 검토를 의도하면 새 키로 START_OR_RESUME를 보낸다. 취소 응답을 놓쳤다면 먼저 취소를 같은 키로 재전송한다.

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

## 5. 구현·연동

ScenarioReviewController.cancel → ScenarioReviewTransactions.cancel. 취소 성공 시 화면의 미저장 편집을 폐기한다. 기존 draft-endpoints.ts의 deleteScenario는 경로·본문·204 처리가 모두 다르므로 이 계약에 사용하지 않는다. 프론트엔드는 직접 수정하지 않았다.


공통 후보 제외 기능은 제공하지 않는다. 검토 취소·NEXT가 다른 강사의 원본 승인 상태나 후보 풀을 바꾸지 않는다. 이번 검토에서 거절한 콘텐츠는 이후 새 검토에서 조건에 맞으면 다시 제공할 수 있으나 동일 학생의 과거 배정 ID는 계속 제외한다.
