# API-RPL-05 · 역할극 텍스트 입력 제출

> 조건부 구현 — 2026-11-04 / develop `bab136d` (S6-JIN-01 입력 흐름 병합). 배포·외부 모델·입력 정제 운영 포트·FE 연동 완료와 구분합니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | POST |
| Path | /api/v1/roleplay-sessions/{sessionId}/text-inputs |
| 인증 | 본인 아동 세션 + CSRF + Idempotency-Key |
| 요청 | application/json |
| 응답 | 음성과 같은 ApiResponse<RoleplayTurnResponse> |
| 등록 조건 | roleplay.http.enabled=true 및 roleplay.text.enabled=true. 꺼져 있으면 경로가 없어 404 RESOURCE_NOT_FOUND |
| 성공 | 200 / 202(같은 키 처리 중) |
| FE 함수 | submitTextInput (endpoints.ts, conditional) |
| 처리 상한 | roleplay.text.maximum-characters 필수, 제품 기본값 없음 |

환경변수 ROLEPLAY_TEXT_ENABLED는 기본 false입니다. 활성화 시 ROLEPLAY_TEXT_MAXIMUM_CHARACTERS를 명시해야 합니다. Unicode 코드 포인트 단위이며 FE의 200자를 서버 정책으로 자동 채택하지 않았습니다.

## 2. 요청

~~~json
{"text": "친구가 아프고 속상할 것 같아요."}
~~~

- 본문은 필수 문자열 text 하나입니다. 아동/활동/시나리오/지원/학습 판정은 서버에서 조회합니다.
- 새 입력은 새 UUID 요청 키, 네트워크 재전송은 같은 Idempotency-Key/텍스트를 사용합니다.
- 빈 문자열·공백·정제 거절·길이 초과는 재입력 안내입니다. 누락/null·잘못된 JSON·필수 헤더는 요청 검증 오류입니다.
- 초안의 clientInputId·inputId·turnId·일괄 202 접수 방식은 채택하지 않았습니다. 기존 음성 계약을 재사용합니다.

## 3. 처리

처음부터 텍스트를 제출할 수 있으며 음성 3회 실패는 사용 권한 조건이 아닙니다. STT·미디어 검증·파일 spool을 건너뛰고 기존 RoleplayInputProcessor의 최소 정제/개인정보 마스킹/의미 보존/안전 검사 뒤 표준 발화 하나를 분석·생성·검수·응답 TTS·목표 평가·확정 저장에 전달합니다. 운영 입력 처리 포트의 실제 공급·품질 검증은 별도입니다.

음성과 같은 작업자·요청 가드·체크포인트·기한을 사용합니다. 생성/검수 중 DB 트랜잭션을 유지하지 않습니다. 입력 종류와 정제 전 문자열의 UTF-16 코드 단위를 포함한 버전 지문으로 요청을 구분합니다. 원문은 저장/응답/toString에 직접 노출하지 않고, 표준 발화는 기존 보관/삭제 정책을 따릅니다.

처리 중 또는 확정된 같은 키의 내용 변경·음성↔텍스트 키 재사용은 409입니다. 확정 재전송은 입력 처리·AI·TTS를 재호출하지 않고 acceptedInput=null·음성 미제공으로 원래 결과를 복원합니다.

잘못된 텍스트는 음성 실패 원장에 기록하지 않습니다. 정상 텍스트도 기존 음성 실패 수를 자동 초기화하지 않습니다. 위험 입력은 기존 안전 중단 경로를 유지합니다.

입력 정제·안전 검사기가 검사 불가를 반환하거나 예외/결과 누락이 발생하면 HTTP 200 / RETRY_REQUIRED / RETRY_TURN으로 안내합니다. 승인된 SAFETY_CHECK_ERROR 문구가 없으면 공통 “다시 한 번만 말해줄래?”를 제공합니다. 분석·TTS·학습 저장·음성 실패 집계를 수행하지 않으며 같은 요청 키/본문으로 다시 시도할 수 있습니다. 전체 기한 초과는 기존 REINPUT_REQUIRED가 우선합니다.

검사기가 정상 결과에 정제 신뢰도·의미 보존·안전·개인정보 마스킹 여부를 제공해야 분석을 시작합니다. 의미 보존이 확인되지 않으면 재입력, 위험 판정은 안전 중단, 필수 판정이 없거나 유효하지 않으면 턴 재시도입니다. 검증된 품질/안전 값은 내부 분석·검수에만 전달합니다.

## 4. 응답

기존 음성 응답의 status/action/messages/acceptedInput/checkpointVersion/replayed와 공통 meta.traceId를 사용합니다. messages[].speakerRole(DIALOGUE 캐릭터 대사·HINT 마스코트 힌트/고정 안내) 규칙도 [API-RPL-04](./submit-voice-input.md)와 같습니다. no-store이며 HTTP 200만으로 학습 진행 성공을 판정하지 않습니다.

| HTTP/status | 의미 |
|---|---|
| 200 / DELIVERED | 검수·DB 확정 완료. acceptedInput은 정제된 표준 발화 |
| 200 / REINPUT_REQUIRED | 현재 입력 방식에서 다시 입력. text 모드를 음성으로 강제 전환하지 않음 |
| 200 / RETRY_REQUIRED | 기존 평가 오류의 턴 재시도 |
| 200 / STOPPED 또는 NOTICE_UNAVAILABLE + STOP_DIALOGUE | 안전 경로 유지 |
| 202 / PROCESSING + WAIT | 같은 키가 처리 중. 신규 비동기 접수 API가 아니며 inputId/turnId 없음 |
| 400 / INVALID_REQUEST | 잘못된 JSON, Idempotency-Key 누락/UUID 아님, sessionId UUID 형식 |
| 415 / UNSUPPORTED_MEDIA_TYPE | application/json이 아닌 요청 |
| 422 / VALIDATION_FAILED | text 누락/null |
| 401/403 | 인증·CSRF·역할 |
| 404 / ROLEPLAY_NOT_FOUND | 본인 세션/활동 없음 또는 비활성 아동 |
| 409 / ROLEPLAY_SCENARIO_CONFLICT | 활동/세션 시나리오 불일치 |
| 409 / ROLEPLAY_CONFLICT | 키/입력 종류/체크포인트 충돌 |
| 503 / ROLEPLAY_BUSY 또는 ROLEPLAY_CONTEXT_UNAVAILABLE | 기존 용량·승인 문맥 오류 |
| 500 | 기존 예상하지 못한 저장/내부 장애 |

컨트롤러 진입 후 동일한 최대60초 기한을 사용합니다. 본문 수신/파싱은 그 이전 경계입니다. 초과 시 “다시 한 번만 말해줄래?”로 안내하며 늦은 결과를 차단합니다.

## 5. 복사 가능한 TypeScript

요청 본문 타입은 `TextInputBody`, 응답은 음성과 같은 `RoleplayVoiceData`입니다([types.ts](../examples/types.ts)). 조건부 서버 활성화를 확인하고 allowConditional을 설정해야 합니다.

~~~ts
export interface TextInputBody {
  /** 아이가 입력한 원문. 누락/null은 422, 빈 값·공백·길이 초과는 200 REINPUT_REQUIRED */
  text: string;
}
~~~

~~~ts
export interface SubmitTextInputArgs {
  sessionId: T.UUID;
  body: T.TextInputBody;
  idempotencyKey: T.UUID;
}

export function submitTextInput(
  api: ApiClient,
  args: SubmitTextInputArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.RoleplayVoiceData>> {
  return api.request<T.RoleplayVoiceData>("/api/v1/roleplay-sessions/" + encodeURIComponent(args.sessionId) + "/text-inputs", {
    method: "POST",
    auth: "child",
    expectedStatuses: [200, 202],
    signal: options.signal,
    idempotencyKey: args.idempotencyKey,
    conditional: true,
    body: args.body
  });
}
~~~

## 6. FE 후속 작업·검증 경계

현재 ChildRoleplayPage → sendRoleplayTurn은 mock입니다. 공유 예제 submitTextInput은 2026-11-04에 위 본문/응답/기능 설정으로 동기화해 `examples/endpoints.ts`의 조건부 함수(allowConditional 기본 차단)로 옮겼습니다. 실제 FE 화면 연동은 별도입니다.

텍스트는 처음부터 선택할 수 있고 3회 권유 후에도 음성을 유지합니다. 성공/재입력마다 입력 모드를 강제 변경하지 않습니다. 새 답은 새 키, 응답 유실 재전송은 기존 키/입력으로 처리합니다.

실제 PostgreSQL·아동 세션/CSRF·MockMvc와 합성 입력/모델/TTS 포트로 정상·재전송·내용/종류 충돌·텍스트/음성 전환·3회 권유 후 텍스트·입력 거절·안전·음성 실패 미집계·동시 요청을 검증합니다. 실제 외부 모델·운영 개인정보 정제·브라우저 연결은 별도입니다.

저장 실패 시 턴·세션·목표 평가·학습 기록이 함께 롤백되며 같은 키로 재시도할 수 있습니다. 활동 완료 뒤 표준 발화가 삭제되어도 확정된 같은 요청은 복원하고 새 입력은 409로 차단합니다.
