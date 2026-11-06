# API-RPL-04 · 역할극 음성 입력 제출

> 조건부 구현 — 2026-11-04 / develop `bab136d` (S6-JIN-01 입력 흐름 병합). 기본 비활성 서버이며 allowConditional 기본 차단입니다.

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

- roleplay.http.enabled=true인 서버에서만 등록됩니다. 기본 비활성입니다. 켜면 roleplay.progress.maximum-turn-count·maximum-support-turns-per-micro-goal과 roleplay.http의 spool-directory·maximum-upload-bytes·maximum-request-bytes·allowed-formats·worker-count·queue-capacity가 모두 있어야 서버가 시작됩니다(backend/docs/roleplay-runtime-config.md). 승인 컨텍스트 공급·입력 정제/안전 처리·임시 음성 발행의 운영 구현/설정 확인이 필요합니다. 단순 코드 존재는 운영 준비 완료가 아닙니다.
- audio 파일 파트와 Idempotency-Key(UUID)만 받습니다. 활동/아동은 인증된 서버 세션으로 조회합니다. clientInputId/recordedDurationMs는 현재 계약 필드가 아닙니다.
- 200은 처리/고정 안내 결과입니다. status가 DELIVERED인지 확인해야 하며 HTTP200만으로 학습 턴 저장 성공을 표시하지 않습니다. 202 PROCESSING/action=WAIT는 같은 세션·키 요청이 이미 실행 중인 경우입니다.
- status/action/messages/acceptedInput/checkpointVersion/replayed를 반환합니다. inputId/turnId를 돌려주는 비동기 접수 API가 아니며 RPL-06의 GET /turns/{turnId}는 현재 미구현입니다. 202 조회/재시도 주기는 미정입니다.
- 신규 성공의 acceptedInput은 표준 발화입니다. 재전송은 기존 응답 문구만 복원하며 acceptedInput=null·음성 미제공입니다. 실패 후보/평가/원본/STT는 공개하지 않습니다.
- messages[].speakerRole로 표시 자리를 나눕니다. DIALOGUE는 캐릭터 대사, HINT는 마스코트 힌트·고정 안내입니다. DELIVERED는 보통 DIALOGUE 1개이고, 아이가 막혀 마스코트 힌트가 붙으면 `[HINT(음성 없음), DIALOGUE]` 2개입니다. 같은 키 재전송도 힌트를 함께 복원합니다. REINPUT_REQUIRED·RETRY_REQUIRED·STOPPED의 고정 안내는 HINT 1개입니다.
- MIME/최대 업로드 바이트·multipart 전체 크기는 서버 설정으로 명시합니다. 파일 상한은 기존 2MiB 이하 용량을 따릅니다. WAV 비압축 프레임과 WebM/OGG(Opus·Vorbis), MP3, MP4/M4A(AAC-LC)의 실제 형식·단일 음성 트랙·디코딩 프레임으로 30초 상한을 검사합니다. AAC 마지막 패딩은 패킷 재생 구간과 대조합니다. 검증 실패·미지원 인코딩은 아래의 고정 재입력 응답으로 처리합니다.
- 최대 60초 예산은 백엔드의 음성 요청 수신부터 multipart 파싱·인증 조회·spool·대기·STT/LLM 재시도·TTS·체크포인트에 공유합니다. 브라우저에서 전송을 시작한 시각부터 적용하려면 프록시와 클라이언트 연동이 추가로 필요합니다. 차단된 수신의 물리 취소 및 정확히 60초 내 응답은 보장하지 않습니다. 초과가 확인되면 고정 문구 “다시 한 번만 말해줄래?”를 사용하고 늦은 결과를 차단합니다.
- 설정된 신뢰도 기준 이하·무음·STT 최종 실패·신뢰도 미제공·의미 불확실/정제 거절·빈 파일·손상·미지원 형식·검사 도구 장애·제출된 30초 초과·크기 상한 초과를 합산해 3회부터 action=SUGGEST_TEXT_KEEP_VOICE로 텍스트를 권유합니다. 전체 입력 지문이 있는 요청은 같은 키/지문으로 재전송을 구별하며, 전체 요청 상한 초과는 지문 없이 한 번 기록하고 같은 키 재사용을 409로 처리합니다. 집계는 공유 요청 기한 내에 확정합니다. 신뢰도 기준은 roleplay.input.stt-confidence-threshold, 기본 0.40이며 ROLEPLAY_STT_CONFIDENCE_THRESHOLD로 변경 후 서버 재시작 시 적용합니다. 0~1 사이의 유한한 값만 허용하며 기준과 같은 값도 재입력입니다. 음성 입력은 계속 가능합니다. 정상 입력 성공만으로 실패 횟수를 초기화하지 않으며 완료/24시간 정리 정책에 따라 원장을 삭제합니다.
- 새 저신뢰 입력의 신뢰도와 당시 적용 기준을 실패 사유·누적과 함께 서버에 기록합니다. 같은 키를 재전송해도 기존 기록을 바꾸지 않으며 API 응답에 신뢰도나 기준을 추가하지 않습니다. 과거 기록에서 알 수 없는 값은 비워 둡니다.
- 저장된 실패의 같은 키/바이트 재전송은 replayed=true이며 STT·LLM을 재호출하거나 횟수를 늘리지 않습니다. 원래 실패 분류를 유지하되 권유 여부는 현재 누적 횟수를 사용합니다. 정상 음성 입력으로 횟수를 초기화하지 않습니다. 새 입력은 새 키, 유실 재전송은 같은 키/같은 음성 바이트입니다.
- 승인된 개별 입력 안내를 우선 사용하고, 미등록이면 공통 재입력 고정 문구를 제공합니다. 재입력 응답은 REINPUT_REQUIRED이며, 3회 이상에서는 SUGGEST_TEXT_KEEP_VOICE 행동을 함께 반환합니다. 실제 녹음·화면 연동은 별도 잔여 작업입니다. 텍스트 제출 API는 백엔드에 구현됐지만 기본 비활성입니다.
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
| audio | File | 예 | 아니오 | 실제 음성 File. MIME/크기 제한은 서버 배포 설정 확인; WAV 비압축 구조/30초 검증, 압축 형식도 지원 코덱 범위 내 실제 프레임/30초 검증 |

현재 파일 파트는 audio뿐입니다. clientInputId/recordedDurationMs는 전송하지 않습니다.

음성 파일 검증 거절은 STT·분석·학습 턴 확정 전에 발생하고, 사용자 확정 범위의 오류는 별도 음성 실패 원장에 기록합니다. 거절된 임시 파일은 제거합니다. 30초 이하 파일도 FE 녹음 타이머 준수를 증명하지는 않으므로, FE는 제한 도달 시 녹음을 중단·폐기하고 자동 제출하지 않아야 합니다.

음성 검증 실패(빈 파일·크기/시간 초과·형식 불일치·손상·검증기 도구/용량/시간 문제)는 HTTP 200, status=REINPUT_REQUIRED, action=REQUEST_NEW_INPUT과 고정 안내 “다시 한 번만 말해줄래?”로 응답합니다. 안내 설정이 없어도 문구가 제공되며 acceptedInput/checkpointVersion은 null입니다. 기존 파일 오류 코드를 직접 반환하지 않습니다.

새 녹음은 새 Idempotency-Key를 사용합니다. 동일 키와 동일한 전체 입력 지문의 실패 재전송은 새 실패로 세지 않고 원래 결과를 복원합니다. 동일 키에 다른 입력을 쓰면 409입니다. STT·학습 저장은 실행하지 않으며 임시 파일은 제거합니다. 인증·필수 필드/전체 요청 형식·서블릿 multipart 상한·상태 충돌·문맥/저장 오류는 별도 계약을 유지합니다.

STT 실패·무음·저신뢰·신뢰도 확인 불가·입력 정제 거절은 개별 승인 안내가 미등록이어도 공통 고정 문구 “다시 한 번만 말해줄래?”를 제공합니다. HTTP 200 / REINPUT_REQUIRED / REQUEST_NEW_INPUT이며, 집계된 실패가 3회 이상이면 action=SUGGEST_TEXT_KEEP_VOICE입니다.

입력 정제·안전 검사 자체의 검사 불가·예외·결과 누락은 HTTP 200 / RETRY_REQUIRED / RETRY_TURN으로 안내합니다. SAFETY_CHECK_ERROR 승인 문구를 우선 사용하고 없으면 공통 문구를 사용합니다. 분석·TTS·학습 저장·음성 실패 집계를 수행하지 않으며 같은 키/음성으로 재시도할 수 있습니다. 전체 기한 초과는 기존 재입력 안내가 우선합니다. 실제 운영 검사기 구현은 별도 의존성입니다.

정상 음성도 검사기가 정제 신뢰도·의미 보존·안전·개인정보 마스킹 여부를 제공해야 분석을 시작합니다. 의미 보존 미확인은 재입력, 위험 판정은 안전 중단, 필수 판정 누락·유효하지 않은 신뢰도는 턴 재시도입니다. 확인된 값과 실제 STT 신뢰도/기준은 내부 분석·검수에 전달하며 공개 응답에는 넣지 않습니다.

파일 오류·검사 도구 장애·30초 초과 및 파일 크기 상한 초과는 활성 공유 기한 내에 음성 실패로 확정합니다. 전체 지문이 있는 실패는 같은 키와 입력의 재전송을 복원합니다. 전체 multipart 요청 상한을 넘으면 지문 없이 요청 키를 실패로 확정하고 재입력 안내를 반환합니다. 이 키는 이후 같은 입력을 포함해 다시 사용하면 409이며, 새 입력에는 새 키를 사용해야 합니다. 인증·세션 소유권·활동 상태 확인 후에만 기록합니다. 전체 60초 기한이 먼저 끝나면 시간 초과 재입력 안내를 사용하며 늦은 실패 기록을 확정하지 않습니다.

음성 실패 원장과 요청 키·지문은 활동 완료 즉시 삭제하고, 미완료 세션은 마지막 확정 활동 24시간 이후 정리 작업에서 삭제합니다. 삭제 후 과거 실패 결과를 복원할 수 없고, 미완료 세션에서 과거 실패 키를 다시 쓰면 새 실패로 집계될 수 있으므로 화면은 새 입력에 새 키를 사용해야 합니다.

STT 신뢰도 미제공(`UNKNOWN_CONFIDENCE`)과 의미 불확실·정제 거절(`INVALID_INPUT`)도 음성 실패 횟수에 포함합니다. 신뢰도 수치는 임의로 채우지 않으며 기존 실패와 합산해 세 번째 실패부터 텍스트 입력을 권유합니다. 같은 키와 음성 입력의 재전송은 원장 보관 중에 한 번만 집계합니다. 텍스트 입력 거절은 음성 실패 횟수를 바꾸지 않습니다.

## 4. 성공 응답

HTTP 200 / 202.

~~~json
{
  "data": {
    "status": "DELIVERED",
    "action": "NONE",
    "messages": [
      {
        "speakerRole": "DIALOGUE",
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
| data.action | RoleplayDeliveryAction | 예 | 아니오 | FE의 다음 행동. PROCESSING은 WAIT, SUGGEST_TEXT_KEEP_VOICE는 음성을 유지하며 텍스트 권유 |
| data.messages | RoleplayMessageData[] | 예 | 아니오 | 검증 출력/고정 안내. 마스코트 힌트가 있으면 HINT·DIALOGUE 2개. NOTICE_UNAVAILABLE 등에서는 [] |
| data.messages[].speakerRole | 'DIALOGUE' \| 'HINT' | 예 | 아니오 | DIALOGUE 캐릭터 대사, HINT 마스코트 힌트·고정 안내(음성 없음) |
| data.messages[].text | string | 예 | 아니오 | 검증된 응답 또는 승인 고정 안내 |
| data.messages[].speech | RoleplaySpeechData | 예 | 아니오 | 음성 제공 정보. HINT는 available=false |
| data.acceptedInput | string \| null | 예 | 예 | 신규 성공의 표준 발화만; 재전송/실패는 null. 완료 시 클라이언트 사본 삭제 |
| data.checkpointVersion | number \| null | 예 | 예 | 저장 확정 버전. 복구/처리 중에는 null |
| data.replayed | boolean | 예 | 아니오 | 같은 세션/키/입력의 저장 결과 재전송 여부 |

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

현재 업무/공통 오류입니다. message 대신 HTTP status와 code로 분기합니다.

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID 형식, audio 파트 누락, Idempotency-Key 누락/UUID 아님 |
| 404 | RESOURCE_NOT_FOUND | 서버에서 기능이 꺼져 경로가 등록되지 않음 |
| 415 | UNSUPPORTED_MEDIA_TYPE | multipart/form-data가 아닌 요청 |
| 401 | AUTHENTICATION_REQUIRED | 인증/입장 필요 |
| 403 | ACCESS_DENIED | 역할 권한 |
| 403 | CSRF_TOKEN_INVALID | 재취득; 변경 요청 자동 재전송 없음 |
| 500 | INTERNAL_ERROR | 내부 오류; 원문/예외 전문 비노출 |
| 404 | ROLEPLAY_NOT_FOUND | 본인 세션/활동 없음 또는 비활성 아동 |
| 409 | ROLEPLAY_SCENARIO_CONFLICT | 활동/세션 시나리오 불일치 |
| 409 | ROLEPLAY_CONFLICT | 동일 키의 다른 입력/체크포인트 충돌 |
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

아래 타입은 공유 `examples/types.ts`와 같습니다. 실제 FE 화면의 행동 값·speakerRole 대응은 후속 연동 항목입니다.

공통 [api-client.ts](../examples/api-client.ts), [types.ts](../examples/types.ts), [endpoints.ts](../examples/endpoints.ts)를 같은 폴더 구조로 복사합니다. 조건부 서버 활성화를 확인하고 allowConditional을 설정해야 합니다.

### 요청·응답 타입

~~~ts
export interface VoiceInputBody {
  /** 실제 음성 File. MIME/크기 제한은 서버 배포 설정 확인; WAV 비압축 구조/30초 검증, 압축 형식도 지원 코덱 범위 내 실제 프레임/30초 검증 */
  audio: File;
}

export type RoleplayDeliveryStatus = 'DELIVERED' | 'REINPUT_REQUIRED' | 'RETRY_REQUIRED' | 'STOPPED' | 'NOTICE_UNAVAILABLE' | 'PROCESSING';

/** RESUBMIT_SAME_REQUEST는 503 ROLEPLAY_BUSY 오류로만 나가 성공 본문에는 오지 않습니다. */
export type RoleplayDeliveryAction = 'NONE' | 'REQUEST_NEW_INPUT' | 'SUGGEST_TEXT_KEEP_VOICE' | 'RETRY_TURN' | 'STOP_DIALOGUE' | 'WAIT';

export interface RoleplaySpeechData {
  /** TTS 발행 URL이 있을 때만 true */
  available: boolean;
  /** 인증된 임시 음성 주소; 미제공/재전송은 null */
  url: string | null;
}

export type RoleplaySpeakerRole = 'DIALOGUE' | 'HINT';

export interface RoleplayMessageData {
  /** DIALOGUE 캐릭터 대사, HINT 마스코트 힌트·고정 안내(음성 없음) */
  speakerRole: RoleplaySpeakerRole;
  /** 검증된 응답 또는 승인 고정 안내. 실패 후보 제외 */
  text: string;
  /** 음성 제공 정보 */
  speech: RoleplaySpeechData;
}

export interface RoleplayVoiceData {
  /** 실제 HTTP 200/202 상태. CONFLICT/BUSY는 공통 오류 envelope */
  status: RoleplayDeliveryStatus;
  /** FE의 다음 행동. PROCESSING은 WAIT, SUGGEST_TEXT_KEEP_VOICE는 음성을 유지하며 텍스트 권유 */
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
- 코드 근거: [음성 입력 Controller](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/blob/bab136d86b0642fd8446b3299fe09de3e9eb4a4e/backend/src/main/java/com/neuringo/neuringobe/roleplay/controller/RoleplayVoiceController.java)
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
- 정상·빈/null·인증/역할·소유권·중복 요청·삭제/보관을 검증합니다. 동봉 검사는 문서·전송 모의 검사이며 서버·DB·AI·배포 성공을 대신하지 않습니다.
