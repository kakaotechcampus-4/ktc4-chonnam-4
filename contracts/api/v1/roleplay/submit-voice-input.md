# API-RPL-04 · 역할극 음성 입력 제출

> 조건부 구현 — 2026-10-22 / develop `63e4314`. 조건부 구현. 기본 비활성 서버이며 allowConditional 기본 차단입니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | POST |
| Path | /api/v1/roleplay-sessions/{sessionId}/voice-inputs |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 본인 아동 HttpOnly 세션 + CSRF + Idempotency-Key |
| 성공 | 200 / 202 |
| 반환 | ApiResponse<RoleplayVoiceData> |
| FE 함수 | submitVoiceInput |
| 구현·협업 | 진미나·윤상현·이건우 |

## 2. 용도·중요 규칙

- roleplay.http.enabled=true인 서버에서만 등록됩니다. 기본 비활성입니다. 승인 컨텍스트 공급·입력 정제/안전 처리·임시 음성 발행의 운영 구현/설정 확인이 필요합니다. 단순 코드 존재는 운영 준비 완료가 아닙니다.
- audio 파일 파트와 Idempotency-Key(UUID)만 받습니다. 활동/아동은 인증된 서버 세션으로 조회합니다. clientInputId/recordedDurationMs는 현재 계약 필드가 아닙니다.
- 200은 처리/고정 안내 결과입니다. status가 DELIVERED인지 확인해야 하며 HTTP200만으로 학습 턴 저장 성공을 표시하지 않습니다. 202 PROCESSING/action=WAIT는 같은 세션·키 요청이 이미 실행 중인 경우입니다.
- status/action/messages/acceptedInput/checkpointVersion/replayed를 반환합니다. inputId/turnId를 돌려주는 비동기 접수 API가 아니며 RPL-06의 GET /turns/{turnId}는 현재 미구현입니다. 202 조회/재시도 주기는 미정입니다.
- 신규 성공의 acceptedInput은 표준 발화입니다. 재전송은 기존 응답 문구만 복원하며 acceptedInput=null·음성 미제공입니다. 실패 후보/평가/원본/STT는 공개하지 않습니다. 표시 주체/용도 필드는 아직 없습니다.
- MIME/최대 업로드 바이트·multipart 전체 크기는 서버 설정으로 명시합니다. 파일 바이트 상한은2,097,152바이트(2MiB) 이하 처리 용량 안에서 정합니다. 현재 검사는 선언 MIME/바이트 수이며 실제 코덱·녹음30초 길이 검증은 없습니다.
- 단일 최대60초 예산을 controller 진입부터 인증 조회·spool·대기·STT/LLM재시도·TTS·체크포인트에 공유합니다. multipart 파싱/전송·물리 취소·발행 경계는 별도입니다. 초과 시 고정 문구 “다시 한 번만 말해줄래?”를 사용하고 늦은 결과를 차단합니다.
- 신뢰도0.40이하/null·무음은 입력 재시도입니다. 실패 합산3회·초기화·텍스트 권유와 실제 누르고녹음30초 FE 연결은 아직 남아 있습니다. 새 입력은 새 키, 유실 재전송은 같은 키/같은 음성 바이트입니다.
- no-store 응답입니다. 409 ROLEPLAY_CONFLICT·503 ROLEPLAY_BUSY를 새 입력 성공으로 표시하지 않습니다.

## 3. 요청

### Path parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| sessionId | UUID string | 예 | 경로 식별자 |

### Query parameters

없음. 지원되지 않는 page/size/sort를 보내도 적용된다고 가정하지 않습니다.

### Headers·쿠키

| 이름 | 값/타입 | 필수 | 주의 |
|---|---|---|---|
| Accept | application/json | 예 | 공통 envelope |
| Cookie | credentials: include | 예 | 브라우저 관리; HttpOnly 쿠키 직접 읽기 금지 |
| CSRF Header | GET /api/v1/csrf의 headerName:token | 예 | 쿠키와 함께 전송 |
| Idempotency-Key | UUID string | 예 | 동일 행동 재전송은 같은 키/같은 입력 |
| Content-Type | multipart/form-data | 예 | boundary는 브라우저가 설정; 수동 지정 금지 |

### Request Body

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| audio | File | 예 | 아니오 | 실제 음성 File. MIME/크기 제한은 서버 배포 설정 확인; 현재 길이 디코딩 검증 없음 |

현재 파일 파트는 audio뿐입니다. clientInputId/recordedDurationMs는 전송하지 않습니다.

## 4. 성공 응답

HTTP 200 / 202.

~~~json
{
  "data": {
    "status": "DELIVERED",
    "action": "NONE",
    "messages": [
      {
        "text": "친구는 왜 그런 기분이 들었을까?",
        "speech": {
          "available": false,
          "url": null
        }
      }
    ],
    "acceptedInput": "친구가 속상할 것 같아요.",
    "checkpointVersion": 1,
    "replayed": false
  },
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| data.status | RoleplayDeliveryStatus | 예 | 아니오 | 실제 HTTP 200/202 상태. CONFLICT/BUSY는 공통 오류 envelope |
| data.action | RoleplayDeliveryAction | 예 | 아니오 | FE의 다음 행동. PROCESSING은 WAIT |
| data.messages | RoleplayMessageData[] | 예 | 아니오 | 검증 출력/고정 안내. NOTICE_UNAVAILABLE 등에서는 [] |
| data.acceptedInput | string \| null | 예 | 예 | 신규 성공의 표준 발화만; 재전송/실패는 null. 완료 시 클라이언트 사본 삭제 |
| data.checkpointVersion | number \| null | 예 | 예 | 저장 확정 버전. 복구/처리 중에는 null |
| data.replayed | boolean | 예 | 아니오 | 같은 세션/키/입력의 저장 결과 재전송 여부 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

현재 업무/공통 오류입니다. message 대신 HTTP status와 code로 분기합니다.

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID/Query 형식 |
| 401 | AUTHENTICATION_REQUIRED | 인증/입장 필요 |
| 403 | ACCESS_DENIED | 역할 권한 |
| 403 | CSRF_TOKEN_INVALID | 재취득; 변경 요청 자동 재전송 없음 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |
| 400 | VOICE_UPLOAD_EMPTY | 빈 음성 파일 |
| 404 | ROLEPLAY_NOT_FOUND | 본인 세션/활동 없음 또는 비활성 아동 |
| 409 | ROLEPLAY_SCENARIO_CONFLICT | 활동/세션 시나리오 불일치 |
| 409 | ROLEPLAY_CONFLICT | 동일 키의 다른 입력/체크포인트 충돌 |
| 413 | VOICE_UPLOAD_TOO_LARGE | 업로드 바이트 상한 초과 |
| 415 | VOICE_UPLOAD_FORMAT | 허용되지 않은 선언 MIME |
| 503 | ROLEPLAY_CONTEXT_UNAVAILABLE | 승인 컨텍스트 미준비/불일치 |
| 503 | ROLEPLAY_BUSY | 처리 용량 부족 |

공통 오류 envelope 예시(message/traceId는 동적):

~~~json
{
  "error": {
    "status": 400,
    "code": "INVALID_REQUEST",
    "message": "요청을 확인해 주세요.",
    "path": "/api/v1/roleplay-sessions/{sessionId}/voice-inputs",
    "traceId": "90000000-0000-4000-8000-000000000001",
    "fieldErrors": []
  }
}
~~~

네트워크·Abort·성공 응답 파싱 오류는 서버 미실행을 뜻하지 않습니다. 변경 요청을 새 키로 자동 재전송하지 않습니다. CSRF 오류는 토큰을 다시 받고 원래 행동/키로 복구를 판단합니다. 강사 보호 요청401은 토큰·입력·캐시 정리, 아동401은 재입장 안내입니다. refresh 로그인 API는 없습니다. 세부 동작은 [공통 연동 규칙](../integration-guide.md)을 따릅니다.

## 6. 복사 가능한 TypeScript

공통 [api-client.ts](../examples/api-client.ts), [types.ts](../examples/types.ts), [endpoints.ts](../examples/endpoints.ts)를 같은 폴더 구조로 복사합니다. 조건부 서버 활성화를 확인하고 allowConditional을 설정해야 합니다.

### 요청·응답 타입

~~~ts
export interface VoiceInputBody {
  /** 실제 음성 File. MIME/크기 제한은 서버 배포 설정 확인; 현재 길이 디코딩 검증 없음 */
  audio: File;
}

export type RoleplayDeliveryStatus = 'DELIVERED' | 'REINPUT_REQUIRED' | 'RETRY_REQUIRED' | 'STOPPED' | 'NOTICE_UNAVAILABLE' | 'PROCESSING';

export type RoleplayDeliveryAction = 'NONE' | 'REQUEST_NEW_INPUT' | 'RETRY_TURN' | 'STOP_DIALOGUE' | 'WAIT';

export interface RoleplaySpeechData {
  /** TTS 발행 URL이 있을 때만 true */
  available: boolean;
  /** 인증된 임시 음성 주소; 미제공/재전송은 null */
  url: string | null;
}

export interface RoleplayMessageData {
  /** 검증된 응답 또는 승인 고정 안내. 실패 후보 제외 */
  text: string;
  /** 음성 제공 정보 */
  speech: RoleplaySpeechData;
}

export interface RoleplayVoiceData {
  /** 실제 HTTP 200/202 상태. CONFLICT/BUSY는 공통 오류 envelope */
  status: RoleplayDeliveryStatus;
  /** FE의 다음 행동. PROCESSING은 WAIT */
  action: RoleplayDeliveryAction;
  /** 검증 출력/고정 안내. NOTICE_UNAVAILABLE 등에서는 [] */
  messages: RoleplayMessageData[];
  /** 신규 성공의 표준 발화만; 재전송/실패는 null. 완료 시 클라이언트 사본 삭제 */
  acceptedInput: string | null;
  /** 저장 확정 버전. 복구/처리 중에는 null */
  checkpointVersion: number | null;
  /** 같은 세션/키/입력의 저장 결과 재전송 여부 */
  replayed: boolean;
}
~~~

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface SubmitVoiceInputArgs {
  sessionId: T.UUID;
  body: T.VoiceInputBody;
  idempotencyKey: T.UUID;
}

export function submitVoiceInput(
  api: ApiClient,
  args: SubmitVoiceInputArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.RoleplayVoiceData>> {
  const form = new FormData();
  form.set("audio", args.body.audio);
  return api.request<T.RoleplayVoiceData>("/api/v1/roleplay-sessions/" + encodeURIComponent(args.sessionId) + "/voice-inputs", {
    method: "POST",
    auth: "child",
    expectedStatuses: [200, 202],
    signal: options.signal,
    idempotencyKey: args.idempotencyKey,
    conditional: true,
    form
  });
}
~~~

### 사용 예시

~~~ts
import { submitVoiceInput } from "./endpoints.ts";

// audioFile: 실제 녹음 File. 녹음 상한에 걸리면 제출하지 않습니다.
const response = await submitVoiceInput(api, {
  "sessionId": "82000000-0000-4000-8000-000000000001",
  "body": {
    "audio": audioFile
  },
  "idempotencyKey": "89000000-0000-4000-8000-000000000001"
});
const data = response.data;
// data.status/action으로 분기. 202 PROCESSING은 완료가 아니며 현재 턴 조회 경로는 없습니다.
// 개인정보·원본·토큰·응답 전문을 로그에 출력하지 않습니다.
~~~

## 7. cURL 계약 확인

[공통 규칙](../integration-guide.md)에 따라 CSRF·쿠키·origin을 준비합니다. 경로 변수는 해당 UUID로 설정합니다. 조건부 활성화가 확인된 서버에서만 수행합니다.

~~~bash
curl -i -X POST "$NEURINGO_API_ORIGIN/api/v1/roleplay-sessions/${sessionId}/voice-inputs" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "$NEURINGO_CSRF_HEADER: $NEURINGO_CSRF_TOKEN" \
  -H "Idempotency-Key: $NEURINGO_REQUEST_KEY" \
  -F "audio=@$NEURINGO_AUDIO_FILE"
~~~

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S6-JIN-01` · `S6-LEE-01` · `S6-YOON-01`
- 코드 근거: [현재 Controller/DTO](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/blob/63e43144c92449ec5670390a6b99d20d2b2580a5/backend/src/main/java/com/neuringo/neuringobe/roleplay/controller/RoleplayVoiceController.java)
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
