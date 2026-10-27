# API v1 공통 연동 규칙

> 2026-10-22 / develop `63e4314`. 상세 필드와 상태는 각 API MD를 따릅니다.

## 1. 현재 계약·조건부·설계·미제공

[목차](./README.md)의 구현28개는 현재 Controller 형식입니다. 조건부 음성1개는 서버 활성화/운영 연결 확인이 필요합니다. 설계26개는 미구현 제안이고 미제공2개(문항 풀 조회)는 호출 함수가 없습니다.

- endpoints.ts: 구현28개+조건부1개. `allowConditional` 기본 false.
- draft-endpoints.ts: 설계26개. `allowDrafts` 기본 false.
- 두 설정은 독립입니다. `allowDrafts:true`로 현재 음성 API를 열 수 없습니다. 코드 존재가 배포/정책/제품 Done을 뜻하지 않습니다.

## 2. 파일·초기화

[api-client.ts](./examples/api-client.ts) · [types.ts](./examples/types.ts) · [endpoints.ts](./examples/endpoints.ts) · [draft-endpoints.ts](./examples/draft-endpoints.ts)를 같은 폴더에 둡니다. fetch/브라우저 API만 필요합니다. import의 .ts 확장자는 noEmit/allowImportingTsExtensions 설정 또는 기존 빌드 규칙에 맞춥니다.

~~~ts
import { createApiClient, createSessionTokenStore } from "./api-client.ts";
const api = createApiClient({
  origin: "https://api.example.invalid", // 실제 환경 origin으로 교체. /api/v1 제외
  tokenStore: createSessionTokenStore(window.sessionStorage, "neuringo.instructor.accessToken"),
  onInstructorUnauthorized: () => {
    // 현재 화면의 입력·보호 Query/사용자 상태를 비우고 로그인으로 이동
  }
});
~~~

createSessionTokenStore의 기본 키는 예제입니다. 기존 FE 저장소와 키/콜백을 맞추세요. 쿠키·원본·비밀번호·토큰을 localStorage/로그/URL에 복사하지 않습니다. 제네릭 T는 전체 DTO의 런타임 스키마 검증을 대신하지 않습니다.

## 3. 인증·가입·탈퇴

- 강사: Bearer 불투명 accessToken, 로그인12시간 고정·연장/refresh 없음. sessionStorage는 탭 종료 시 사라지고 만료401에서는 입력/캐시도 폐기합니다.
- 강사 Bearer 요청에는 서버 세션(JSESSIONID)을 만들지 않습니다. 세션 쿠키로 인증되는 것은 아동뿐이고, 쿠키만 보낸 강사 API 요청은 401 AUTHENTICATION_REQUIRED입니다. 로그아웃 뒤 남은 쿠키로 강사 API가 열리지 않습니다(PR #75).
- 공개 가입/로그인/아동 입장: auth=public, 남아 있는 강사 Bearer를 보내지 않습니다.
- 아동: auth=child, HttpOnly JSESSIONID. credentials:include로 보내며 쿠키를 직접 읽지 않습니다. 4자리 접근 코드24시간과 아동 인증 세션 자동 만료 없음은 별개입니다.
- 겸용 GET은 auth를 명시합니다. 역할·대상 변경 시 캐시를 정리하고 서버 소유권 검사를 우회하지 않습니다.
- 가입은 termsAgreed=true와 privacyAgreed=true 필수입니다. 약관/방침 버전·동의 시각은 서버가 저장합니다. 현재 UserRole=INSTRUCTOR, AccountStatus=ACTIVE입니다.
- 탈퇴는 POST /users/me/withdrawal + {password}, 성공204입니다. 비밀번호 불일치는403 PASSWORD_MISMATCH이며 CSRF 오류와 구분합니다. 과거 DELETE /users/me는 제공하지 않습니다.
- 아동 사용 종료는 서버 DELETE 성공 후 현재 브라우저의 아동 상태/캐시를 지웁니다. 코드 자체는 유지됩니다. 네트워크 오류만으로 서버 성공/실패를 단정하지 않습니다.

## 4. CSRF·쿠키·본문

모든 요청은 credentials:include입니다. 첫 변경 요청 전에 GET /api/v1/csrf를 호출하고 응답의 headerName/token을 헤더에 넣습니다. 가입·로그인·코드 입장도 포함합니다. 403 CSRF_TOKEN_INVALID이면 client는 CSRF 캐시만 버리고 원래 변경 요청을 자동 재전송하지 않습니다.

JSON은 Content-Type application/json입니다. FormData는 boundary를 브라우저가 지정하므로 Content-Type을 수동 지정하지 않습니다. 쿠키 Secure/SameSite·CORS·HTTPS는 배포 브라우저/서버에서 따로 검증합니다. Mock 통과가 이를 보장하지 않습니다.

## 5. 활동 생성·목록·문항

현재 POST /activities는 {childId, activityType, goal}입니다. goalId를 보내는 방식이 아닙니다. 목표·활동·문항을 원자 저장하고 Idempotency-Key 필수, 신규201/재전송200입니다.

| 유형 | 목표 | 문항/선행 |
|---|---|---|
| FIRST_COMBINED (생략/null 기본) | goal 필수 | 아동당1회·유형별1개 총3문항 |
| SELF_EMOTION_QUIZ | goal 금지 | 최초 결합 COMPLETED 후10문항 |
| OTHER_EMOTION_QUIZ | goal 금지 | 최초 결합 COMPLETED 후 상황7+이미지3문항 |
| ROLEPLAY | goal 필수 | 현재422 ACTIVITY_TYPE_NOT_AVAILABLE |

독립 퀴즈는 같은 아동·유형의 NOT_STARTED 중복을 차단합니다. 필요한 문항이 모자라면 배정을 거부하고 일부만 만들지 않습니다. 배정 문항은 재조회 시 유지합니다. GET /quiz-items와 GET /quiz-items/{itemId}는 현재 미제공입니다.

목록은 내부 paging이 있어도 배열 응답일 수 있습니다. ACT-02는 status/page/size를 받지만 totalPages/totalElements가 없습니다. 상세는 data.activity/quizItems/sessionSummary/learningRecord이며 뒤 두 값은 현재 null입니다. ACT-03 상세는 서비스 내부 검사와 달리 현재 SecurityConfig에서 강사만 허용하므로 아동 요청으로 연결하지 않습니다. 독립 퀴즈 goalId=null을 처리합니다. 목표 category는 선택 문자열이며 enum으로 고정하지 않습니다.

## 6. 조건부 음성 입력

서버 roleplay.http.enabled와 필수 운영 포트를 확인한 경우에만 `allowConditional:true`를 설정합니다. 개발 Mock에서는 명시적 fetchImpl 대체 구현을 주입합니다.

~~~ts
const mockApi = createApiClient({
  origin: "https://mock.invalid",
  tokenStore: createSessionTokenStore(window.sessionStorage),
  allowConditional: true,
  fetchImpl: yourMockFetch // 네트워크 없는 Mock 함수를 직접 공급
});
~~~

POST /roleplay-sessions/{sessionId}/voice-inputs는 audio 파일과 Idempotency-Key만 사용합니다. clientInputId/recordedDurationMs는 현재 필드가 아닙니다. 200 결과에서 status/action을 확인하고 DELIVERED만 저장 확정 응답으로 취급합니다. 202 PROCESSING/action=WAIT는 동일 요청이 실행 중인 경우입니다. 별도 GET /turns/{turnId}는 현재 미구현이고 자동 조회 주기/Retry-After는 미정입니다.

실제 응답은 messages(text/speech), acceptedInput, checkpointVersion, replayed를 포함합니다. 재전송은 기존 문구만 복원하고 음성을 새로 생성/발행하거나 표준 발화를 복사하지 않습니다. messages에 speaker/용도는 아직 없습니다. acceptedInput의 화면/캐시 사본도 활동 완료/만료 정책에 따라 삭제해야 합니다.

최대60초 처리·고정 timeout 안내와 선언 MIME/바이트 검사는 존재합니다. 실제 녹음30초 길이/코덱 검증·실패합산3회 텍스트 권유·원본 보관 수명주기·세션 시작/완료·보상·강사 안전 안내는 남은 연결과 구분합니다. 대기/확인/안전 안내의 HTTP200을 정상 학습 완료로 표시하지 않습니다.

## 7. 오류·204·재전송

오류는 error.status/code/message/path/traceId/fieldErrors입니다. HTTP status/code로 분기합니다. 204는 본문 없음이며 JSON 파싱하지 않습니다. 가입/로그인/학급 생성/아동 생성은 현재200이며 생성이라고 일괄201을 가정하지 않습니다.

~~~ts
import { ApiHttpError, ApiNetworkError, ApiContractError,
  DraftContractError, ConditionalContractError } from "./api-client.ts";
try {
  // 선택한 API를 호출
} catch (error) {
  if (error instanceof ConditionalContractError || error instanceof DraftContractError) {
    // 서버 활성화/계약 확인 또는 Mock 개발
  } else if (error instanceof ApiHttpError) {
    // error.detail.status/code와 fieldErrors로 처리. 전문 로그 금지
  } else if (error instanceof ApiNetworkError || error instanceof ApiContractError) {
    // 서버 실행 여부 불명. 원래 행동·키/입력을 보존하고 기존 결과 확인
  } else if (error instanceof Error && error.name === "AbortError") {
    // fetch 취소는 서버 AI/DB 작업 미실행이나 물리 취소를 뜻하지 않음
  }
}
~~~

client는 GET/변경 요청 모두 자동 재시도하지 않습니다. 실제 앱의 mutation retry도 별도로 확인합니다. 같은 행동의 키를 재렌더/재시도 때 다시 만들지 않습니다. 새로운 활동/음성/힌트 행동만 새 키입니다. 서버 내부 기술 재시도와 아동 입력 실패 횟수는 별개입니다. 배정·턴·코드·힌트는 각 업무의 현재 보장 범위를 따르고 범용 TTL/lease/완료 멱등성까지 확정된 것으로 확대하지 않습니다.

완료 응답 유실→status 조회는 제품 목표 흐름이나 ACT-04 status 조회와 서버 완료/보상 업무는 아직 미구현입니다. 현재 호출 가능한 복구 API처럼 사용하지 않습니다. 음성202에 없는 turnId로 결과 조회를 만들지 않습니다.

## 8. cURL 환경·검증

~~~bash
export NEURINGO_API_ORIGIN='http://localhost:8080' # 예시 origin
export NEURINGO_ACCESS_TOKEN='<로그인 응답의 토큰>'
export NEURINGO_CSRF_HEADER='<GET /api/v1/csrf의 headerName>'
export NEURINGO_CSRF_TOKEN='<GET /api/v1/csrf의 token>'
export NEURINGO_REQUEST_KEY='<한 새 행동에서 생성해 재전송 시 유지하는 UUID>'
~~~

각 MD의 경로 변수도 해당 UUID로 설정합니다. 음성은 NEURINGO_AUDIO_FILE에 테스트용 파일 경로를 지정합니다. cookie jar/비밀값/실제 원본을 Git이나 공유 문서에 넣지 않습니다. [모의 검사](./examples/verify-examples.mjs)는 실제 네트워크를 호출하지 않으며 Node24에서 실행합니다.
