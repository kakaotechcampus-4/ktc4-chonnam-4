# S5·S3 통합 프론트엔드 후속 작업

기준은 develop `9f6ffa5`와 `codex/s5-s3-backend-integration`의 미커밋 백엔드 변경이다. 아래 프론트엔드 파일은 읽기만 했고 수정하지 않았다. 실제 아동 입장·사용 종료는 이미 연결돼 있으며 활동·퀴즈·역할극·완료 호출은 아직 모의 응답이다.

## 연결 순서와 실제 호출부

| 현재 파일·호출 | 필요한 변경 | 완료 확인 |
|---|---|---|
| `frontend/src/features/child/apiClient.ts` | 기존 아동 쿠키·CSRF 처리를 재사용하면서 GET·PUT·요청 헤더·FormData 전송을 지원한다. 현재 변경 함수는 POST/DELETE JSON만 받는다. 아동 요청에는 강사 Bearer 토큰을 싣지 않는다. multipart의 Content-Type/boundary는 브라우저에 맡긴다. | 세션 쿠키·서버가 알려준 CSRF 헤더·필요한 UUID 키가 실제 요청에 포함된다. 실패 후 무조건 자동 재전송하지 않는다. |
| `frontend/src/features/child/api.ts`의 `getMyActivities`와 `pages/ChildActivitiesPage.tsx` | `GET /api/v1/children/{childId}/activities`를 사용한다. 실제 UUID·activityType과 NOT_STARTED/IN_PROGRESS/PAUSED/RECOVERY_NEEDED/COMPLETED 상태를 연결한다. | mock-activity ID로 실제 API를 호출하지 않는다. 다른 아동 세션으로 캐시를 재사용하지 않는다. |
| 같은 API 파일의 `getQuizQuestions`, `submitQuizAnswer` | `GET /activities/{activityId}/quiz-items`, `PUT /activity-quiz-items/{activityQuizId}/attempt`로 교체한다. 질문 ID와 배정 ID를 구분하고 유형별 firstResponse/finalResponse·카메라/기술 제외 계약을 따른다. 힌트는 별도 POST `.../hints`와 UUID 키를 사용한다. | 첫 응답이 불변이고 재전송으로 힌트를 추가 소모하지 않는다. 카메라·기술 실패를 학습 오답으로 바꾸지 않는다. |
| `pages/ChildQuizPage.tsx`의 마지막 응답·완료 화면·계속 버튼 | 마지막 응답 저장 후 `GET /activities/{activityId}/quiz-result`로 서버 확정을 확인한다. 현재처럼 모든 완료에서 역할극으로 이동하지 않고 FIRST_COMBINED는 시작 준비 확인, 독립 퀴즈는 완료 status 조회로 분기한다. | `QUIZ_NOT_COMPLETED` 409 또는 계산 실패 500에서 완료로 이동하지 않는다. 독립 퀴즈는 역할극의 최초 S를 변경하지 않는다. |
| `api.ts`의 `getRoleplayScenario`, `pages/ChildRoleplayPage.tsx`의 시작 query | 모의 대본 대신 `POST /activities/{activityId}/roleplay-sessions`를 호출한다. Body를 생략하고 쿠키·CSRF·UUID Idempotency-Key를 보낸다. 최초 201과 복원 200을 모두 처리한다. | 최초 응답 유실은 같은 키로 복구하고 새 키 재입장도 같은 세션의 현재 상태를 받는다. |
| 같은 역할극 화면의 로컬 메시지·turnIndex·장소 표시 | sessionId·checkpointVersion·lastDeliveredTurnId를 보존하고 시작 메시지의 messageId로 중복을 막는다. status와 activityStatus를 함께 읽고 빈 메시지 배열에서 질문을 만들지 않는다. `place`는 시작 응답에 없으므로 승인된 장소 메타데이터 계약이 필요하다. | 재접속·뒤로 가기·응답 유실 후 첫 질문이 반복되거나 현재 진행이 초기화되지 않는다. |
| `api.ts`의 `sendRoleplayTurn`과 역할극 입력 UI | 현재 text/turnIndex/requestId 입력은 모의 계약이다. 실제 제공 경로는 `POST /roleplay-sessions/{sessionId}/voice-inputs`의 audio multipart·UUID 요청 키다. 텍스트 입력 API는 아직 설계 경로이므로 실제 제공으로 가정하지 않는다. | 서버에 설정된 형식·업로드 크기에 맞춰 녹음·전송하고 같은 오디오의 재전송에는 같은 키를 유지한다. |
| `api.ts`의 `completeActivity`, 역할극 화면의 completeMutation | 별도 완료 요청 대신 서버가 마지막 턴과 함께 확정한 결과를 `GET /activities/{activityId}/status`로 읽는다. 로컬 isFinished나 CLOSING 문구만으로 완료·스탬프를 확정하지 않는다. | 재전송·응답 유실 후에도 실제 COMPLETED만 완료 화면으로 연결된다. |
| `pages/ChildDonePage.tsx` | location.state의 mock completion/stampGranted 의존성을 서버 status 확인과 맞춘다. 새로고침·직접 접근 시에도 본인 활동의 실제 완료를 확인한다. COMPLETED 조회는 새 보상 지급 요청이 아니다. | 같은 완료를 다시 보여줘도 서버 기록·스탬프가 추가 생성되지 않는다. |

경로 표의 `/api/v1`은 첫 두 행 이후 생략했다. 인증은 현재 아동 요청 함수를 재사용하고 서버 오류의 status/code를 기준으로 화면을 분기한다. 오류 message·표준 발화·응답 전문·토큰을 일반 로그에 남기지 않는다.

## 결과 확정·준비 확인

- 퀴즈 결과 GET의 200은 퀴즈 결과가 저장됐다는 의미다. 승인본 부재·연결 실패여도 200일 수 있다.
- 미연결 복구 때는 결과 GET을 실제로 재요청한다. 본인 활동 목록을 갱신해 해당 활동의 scenarioId를 확인하고 시작 API의 준비 미완료 409도 처리한다. 활동 상세 단건 GET은 강사 전용이다.
- overallAccuracy는 0~1이며 유효 문항 0개는 null이다. null을 0%로 바꾸거나 상세 L/S를 아동 평가 화면에 노출하지 않는다. L/S를 클라이언트에서 재계산해 보내지 않는다.
- `QUIZ_RESULT_CALCULATION_FAILED`는 기술적 확정 실패다. 근거 확인·동일 요청 복구 대상으로 처리하고 학습 오답으로 세지 않는다.

## 시작 응답과 음성 응답의 차이

| 항목 | 시작·복원 | 음성 턴 |
|---|---|---|
| 성공 코드 | 신규 201 / 복원 200 | 결과 200 / 이미 처리 중 202 |
| 메시지 위치 | `data.delivery.messages[]` | `data.messages[]` |
| 식별·중복 | 고정 messageId, lastDeliveredTurnId | 요청 키·checkpointVersion·replayed. 응답에 turnId/messageId가 있다고 가정하지 않는다. |
| 캐릭터·용도 | DIALOGUE, QUESTION 또는 RESUME | 현재 DTO에 speakerRole/purpose가 없다. 모의 rabbit/turtle 역할을 임의 추측해 나누지 않는다. 힌트 캐릭터 분리는 별도 계약 의존성이다. |
| 음성 | 텍스트 시작이며 speech.available=false | speech.available/url을 읽는다. 음성 미제공일 때 텍스트 표시를 막지 않는다. |
| 입력 표시 | 표준 발화를 반환하지 않음 | 신규 성공 acceptedInput은 표준 발화이며 재전송 때는 null일 수 있다. |

음성의 HTTP 200만으로 학습 턴 성공을 판정하지 않는다. DELIVERED/REINPUT_REQUIRED/RETRY_REQUIRED/STOPPED/NOTICE_UNAVAILABLE과 action을 구분한다. 202 PROCESSING은 WAIT이며 미정인 폴링 주기를 임의로 넣거나 새 질문을 만들지 않는다. STOPPED도 활동 COMPLETED와 같은 뜻이 아니다. 완료 여부는 status API로 확인한다.

상태·오류 확인 후 재시도할 때 원래 입력과 요청 키를 보존한다. 새 사용자 입력과 같은 요청의 재전송을 구분하고, 네트워크 오류만으로 서버 저장 성공·실패를 단정하지 않는다. 재입장·사용 종료 때 기존 아동의 캐시·녹음·늦은 응답 처리를 함께 확인한다.

## 운영 의존성과 아직 없는 계약

- 진행 한도 환경변수 `ROLEPLAY_MAXIMUM_TURN_COUNT`, `ROLEPLAY_MAXIMUM_SUPPORT_TURNS_PER_MICRO_GOAL`을 양수로 함께 설정해야 한다. 운영 값은 아직 미설정이며 코드에 숫자 기본값이 없다.
- 새 대화에는 학급·목표 조건·버전이 맞는 승인 시나리오, 작은 목표·증거, S별 첫 질문과 `scenario_opening_focus` 연결이 필요하다. 기존 공용 자료를 임의 귀속하거나 과거 질문 목표를 추측해 채우지 않는다.
- 조건부 음성 API 활성화에는 roleplay.http.enabled와 제공자·입력 안전 처리·업로드/음성 전달·고정 안내 설정이 필요하다. 플래그만 켜는 작업은 운영 인수가 아니다.
- 텍스트 입력, 전체 대화 이력/턴 조회, 힌트 표시 주체, 장소 정보 등 설계 계약을 실제 구현처럼 사용하지 않는다. 공유 examples의 draft 함수·타입·fixture 동기화도 프론트엔드 후속이다.
- 통합본의 V13~V24 번호는 실험 이력이다. 운영 flyway_schema_history와 다른 V13 이력이 있는 경우를 대조하기 전에는 배포 준비 완료로 간주하지 않는다.

## 연동 완료 확인 사례

1. 실제 아동 입장 → 실제 UUID 활동 조회 → 퀴즈 답안·힌트 확정 → 결과 확인 → 승인 첫 질문 표시.
2. 결과 GET 200이어도 승인본이 없으면 준비 대기. 조건을 갖춘 승인 자료 공급 후 결과 재조회·시작 재시도로 복구.
3. 시작 응답 유실·같은 키 재시도·새 키 재입장에서 세션·메시지 중복이 없고 현재 S/진행을 초기화하지 않음.
4. PAUSED/COMPLETED/RECOVERY_NEEDED 상태와 빈 시작 메시지에서 임의 질문·자동 재시작을 만들지 않음.
5. 확정 턴 재전송, 기술 오류, 완료 응답 유실 후 서버 status 확인에서 추가 턴·보상·기록이 생기지 않음.
6. 아동 전환·사용 종료 후 이전 아동의 응답·캐시·녹음이 다음 아동 화면에 반영되지 않음.

실제 FE·브라우저·외부 AI/음성으로 위 흐름을 실행한 결과는 아직 없다. 이번 백엔드 최종 빌드의 합성 자료 검증과 구분한다.

## 계약 문서

- [퀴즈 결과](../../contracts/api/v1/quizzes/get-quiz-result.md)
- [역할극 시작](../../contracts/api/v1/roleplay/start-roleplay.md)
- [음성 턴](../../contracts/api/v1/roleplay/submit-voice-input.md)
- [활동 완료 상태](../../contracts/api/v1/activities/get-activity-status.md)
- [통합 구현·검증 기록](./s5-s3-backend-integration.md)
