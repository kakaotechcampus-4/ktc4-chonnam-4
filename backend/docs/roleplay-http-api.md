# S1-JIN-02 BE 음성 입력 API

기준: develop c644dc2, feat/roleplay-response-pipeline. FE 변경·배포 없음.
이 문서는 이번 BE 구현의 실제 계약을 기록한다. 외부 API 명세 초안이나 FE 계약 합의를 대신하지 않는다.

## 구현 흐름

기존 아동 입장 API → 서버 세션 + CSRF → 음성 입력 컨트롤러 → 인증된 아동의 세션/활동 조회
→ 업로드 검증/임시 파일 → 서버 SHA-256 지문 → 처리권/기존 결과 확인 → 승인 입력 조립
→ STT/표준화·안전 처리/분석·생성·평가/TTS → 짧은 체크포인트 트랜잭션 → 공개 응답.
외부 호출 동안 DB 트랜잭션을 유지하지 않는다. 확정 이전 후보는 성공 응답으로 내보내지 않는다.
새 턴 저장 직전에는 활동→아동→세션 순서로 행을 잠그고 참여 상태·활동 진행 상태·시나리오를 재검사한다.
삭제·퀴즈와 함께 사용하는 [DB 잠금 순서 규칙](database-locking.md)을 따른다.
다른 종료/상태 변경 작업이 같은 부모/세션을 잠그면 이 잠금 순서를 맞춰야 한다. 기존 결과 재전송은 새 턴을 만들지 않는다.

## 요청

`POST /api/v1/roleplay-sessions/{sessionId}/voice-inputs`

- 아동 서버 세션 쿠키와 CSRF 헤더가 필요하다. 강사 Bearer 인증으로는 입력을 제출할 수 없다.
- `GET /api/v1/csrf` 응답의 headerName/token을 사용한다. 기존 아동 입장 API도 CSRF가 필요하다.
- `Idempotency-Key`: UUID, 필수. 네트워크 재전송은 원래 키와 원래 음성 바이트를 유지한다.
- `Content-Type`: multipart/form-data. 브라우저 FormData의 boundary를 수동 설정하지 않는다.
- `audio`: 필수 파일 파트. filename은 저장 경로에 쓰지 않는다.
- 아동 ID, 활동 ID, 후보 ID, 지문을 요청에서 받지 않는다. 활동은 본인 세션에서 조회한다.
- clientInputId/recordedDurationMs는 이번 API 계약에 추가하지 않았다. 실제 길이/디코딩 검증은 미구현이다.
- 선언 MIME은 AudioFormat으로 정규화한 뒤 주입된 허용 목록과 대조한다. 실제 컨테이너/코덱 판별을 의미하지 않는다.

## 응답

기존 `ApiResponse`/`ApiErrorResponse` 및 `X-Trace-Id`를 재사용한다.
완료/복구 응답은 Cache-Control: no-store. 정상 처리 결과는 200이며 비동기 작업 접수 API가 아니다.

| HTTP | 응답 | 의미 |
|---|---|---|
| 200 | data.status=DELIVERED | DB에 확정된 응답 또는 동일 입력 재전송 |
| 200 | REINPUT_REQUIRED / RETRY_REQUIRED / STOPPED / NOTICE_UNAVAILABLE | 승인 안내와 행동 또는 미확정 안내 부재. 학습 턴 저장 성공과 구분 |
| 202 | PROCESSING, action=WAIT | 같은 세션·키의 요청이 이미 실행 중. 완료 판정 아님 |
| 400 | 공통 요청 오류 / VOICE_UPLOAD_EMPTY | UUID·필수 헤더/파트 형식 오류 또는 빈 파일 |
| 401 | AUTHENTICATION_REQUIRED | 아동 입장 필요 |
| 403 | ACCESS_DENIED / CSRF_TOKEN_INVALID | 아동 권한 또는 CSRF 실패 |
| 404 | ROLEPLAY_NOT_FOUND | 본인 세션/활동이 없거나 아동이 비활성 |
| 409 | ROLEPLAY_CONFLICT | 같은 키의 다른 입력 또는 체크포인트 충돌. 원인을 단정하지 않음 |
| 413 | VOICE_UPLOAD_TOO_LARGE 또는 서블릿 공통 오류 | 실측 업로드/전체 multipart 크기 초과 |
| 415 | VOICE_UPLOAD_FORMAT 또는 공통 Content-Type 오류 | 허용되지 않은 선언 MIME/요청 형식 |
| 503 | ROLEPLAY_CONTEXT_UNAVAILABLE | 승인 콘텐츠/진행 상태가 없거나 일치하지 않음 |
| 503 | ROLEPLAY_BUSY | 처리 용량 부족. 재시도는 같은 요청 키·입력으로 판단 |
| 500 | INTERNAL_ERROR | 저장/IO/예상하지 못한 제공자·발행 장애. 내부 원문 비노출 |

200/202 data 필드는 status, action, messages(text, speech.available/url), acceptedInput,
checkpointVersion, replayed이다. 신규 정상 응답에는 검증된 표준 발화 acceptedInput이 있으며,
재전송/실패에서는 null이다. 재전송은 저장된 문구를 반환하고 음성을 다시 생성/발행하지 않는다.
평가·실패 후보·원본/STT·제공자 정보·강사용 위험 안내·승인 메타데이터는 공개 응답에 넣지 않는다.
202 결과 조회 경로/자동 재시도 주기/Retry-After는 구현하거나 임의 확정하지 않았다.

## 활성화와 필수 연결

`roleplay.http.enabled=true`일 때만 컨트롤러와 실행 설정을 등록한다. 기본은 비활성이다.
활성화한 상태에서 필수 정책이나 구현이 없으면 Spring 시작 단계에서 실패한다.
테스트용 구현을 운영 기본값으로 등록하지 않는다.

필수 설정:

- spool-directory: 서버가 관리하는 기존 임시 디렉터리. 클라이언트 경로를 쓰지 않는다.
- maximum-upload-bytes: 음성 파일 크기. 기존 SpeechTranscriptionRequest의 2MB 처리 용량 이하로 명시해야 한다.
- maximum-request-bytes: multipart 전체 크기. 파트 헤더/boundary 등의 추가 크기까지 포함해 설정해야 한다.
- allowed-formats: AudioFormat 허용 목록. WEBM/OGG/WAV/MP3/MP4 중 확정 정책에 맞춰 명시한다.
- worker-count/queue-capacity: 운영 처리 자원 수와 대기 용량. 대기 용량 0도 지원한다.
- turn-budget: 선택. 기본 최대 60초, 양수이며 60초 이하만 허용한다. 짧은 예산은 장애 재현 테스트에도 사용한다.
- notices: 승인된 kind/text/version/approval-reference 목록. 없으면 TIMEOUT 확정 문구만 제공하고 그 외는 NOTICE_UNAVAILABLE로 처리한다. TIMEOUT 재정의는 허용하지 않는다.

필수 제공자 중 이 브랜치에서 실제 운영 구현이 없는 경계:

1. RoleplayContextSource: 승인 시나리오 고정 버전, 목표 진행/카운터, 확정 정책과 정제된 최근 대화.
2. RoleplayInputProcessor: 실제 의미 보존 표준화, PII 제거 및 위험 입력 판정.
3. SpeechToTextProvider/TextToSpeechProvider: 담당 PR의 실제 어댑터/설정과 연결. 포트만 테스트용 구현으로 검증했다.
4. RoleplaySpeechDelivery: 인증된 수신자가 접근할 수 있는 임시 음성 주소. 보관 기간/전달 정책과 실제 구현은 미정이다.

LlmProvider는 기존 BE 제공자 빈을 주입받는다. 실제 제공자 호출/보관 계약·키 설정을 이번 검증에서 사용하지 않았다.
연결된 제공자는 호출당 1회 시도를 수행해야 전체 재시도 예산을 지킬 수 있다.
역할극 HTTP 활성화 시 LLM의 SDK 내부 재시도가 0회인지 설정과 실제 ChatModel 옵션을 검사한다.
`callBudget`이 전달된 호출에서도 같은 조건을 검사하며, 재시도 설정이 0회가 아니면 호출하지 않는다.
STT/TTS 어댑터에는 내부 재시도가 없다. 다른 제공자를 연결할 때도 같은 계약을 지켜야 한다.
서버 세션 생성/승인 시나리오 준비 API는 기존 담당 영역의 선행 작업이며 이 음성 입력 API가 만들지 않는다.

## 시간 제한과 삭제 범위

컨트롤러 진입 후 하나의 예산을 인증 조회·spool·대기·STT·LLM 재시도·TTS·체크포인트에 공유한다.
서블릿 multipart 파싱은 컨트롤러 진입 전이며 별도의 서버 크기 제한을 적용한다. 차단된 DB/입력 IO의 물리적
취소, 네트워크 업로드 시간, COMMIT/음성 발행 경계에 대한 제품 계약은 최종 확정이 필요하다.
실행 중 늦은 제공자 응답은 다음 단계나 DB 저장으로 진행할 수 없다. 물리적으로 멈추지 않는 작업은
작업이 종료될 때까지 처리권을 보유한다. 큐 포화는 503이며 무제한 대기를 만들지 않는다.
STT·LLM·TTS는 [호출별 예산](roleplay-call-budget.md)을 전달받아 설정 상한과 남은 턴 시간 중 작은 값을
실제 요청 제한에 적용한다. 이 변경은 60초 턴 기본값이나 CloudFront 설정을 변경하지 않는다.
임시 업로드는 실패/충돌/재전송/시간 초과/정상 반환에서 삭제한다. 서블릿 multipart 임시 데이터는 서블릿이
요청 종료 때 정리한다. DEC-008 별도 보관본 및 세션 종료 삭제 트리거를 대신 구현한 것은 아니다.

## 검증 범위와 남은 계약

실제 PostgreSQL, 실제 임시 파일, 실제 아동 입장/세션/CSRF, MockMvc HTTP 계약 및 로컬 Tomcat
multipart 요청으로 검증했다. 승인 콘텐츠/표준화·안전/AI/음성 주소는 테스트 코드의 대체 구현이다.
로컬 HTTP 서버 테스트만 Secure 세션 쿠키를 비활성으로 설정했다. 운영 쿠키 설정은 변경하지 않았다.

미정/후속 연동: 위 운영 포트, 공개 MIME/크기·디코딩/녹음 길이 정책, 확정 안내 문구,
음성 실패 누적 범위/초기화, 원본/STT/실패 후보의 DEC-008 보관 수명주기,
완료·보상·기록 연결, 턴/목표별 상한,
멱등 결과 보관 기간 및 제공자 저장/학습 이용 계약. 이번 작업으로 S1-JIN-02 전체의 운영 완료를 선언하지 않는다.
FE 수정·외부 배포·커밋/푸시는 수행하지 않았다.

최종 검증: `./gradlew --offline test spotlessCheck` — 전체 754개, 실패 0, 오류 0, 건너뜀 0.
공통 계약 파일은 원본 기준 커밋의 내용을 검증 폴더에 그대로 복사했으며 변경하지 않았다.
