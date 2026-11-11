# 역할극 운영 설정 전달

`deploy/host/params.txt` → Parameter Store `/neuringo/<환경>/…` → `deploy.sh`의 서버 `.env`(권한 600) → `compose.dev.yml`의 backend `env_file` → `application.yml` 순서로 전달한다. `preview`도 같은 환경의 정책을 사용한다. 이 변경은 실제 Parameter Store 값을 등록하거나 배포하지 않는다.

| 환경변수 | Parameter Store 경로(`/neuringo/<환경>/` 뒤) | 의미 |
|---|---|---|
| `ROLEPLAY_HTTP_ENABLED` | `roleplay/http/enabled` | 기본 `false`, 명시적으로 켤 때만 HTTP 빈 등록 |
| `ROLEPLAY_SPEECH_DELIVERY` | `roleplay/speech/delivery` | `none`을 명시하면 음성 주소 없이 대사 전달. 기본 빈 값이며 미설정 시 대체 구현을 만들지 않음 |
| `ROLEPLAY_SPOOL_DIRECTORY` | `roleplay/http/spool-directory` | 컨테이너 안에서 쓸 수 있는 임시 음성 경로 |
| `ROLEPLAY_UPLOAD_MAX_BYTES` | `roleplay/http/maximum-upload-bytes` | 음성 파일 상한(바이트) |
| `ROLEPLAY_REQUEST_MAX_BYTES` | `roleplay/http/maximum-request-bytes` | multipart 요청 전체 상한(바이트), 파일 상한 이상 |
| `ROLEPLAY_ALLOWED_FORMATS` | `roleplay/http/allowed-formats` | `AudioFormat` 값의 쉼표 구분 목록 |
| `ROLEPLAY_WORKER_COUNT` | `roleplay/http/worker-count` | 동시 작업자 수(양수) |
| `ROLEPLAY_QUEUE_CAPACITY` | `roleplay/http/queue-capacity` | 대기 작업 수(0 이상) |
| `ROLEPLAY_TURN_BUDGET` | `roleplay/http/turn-budget` | 생략하면 기존 60초, 양수이며 최대 60초 |
| `ROLEPLAY_MAXIMUM_TURN_COUNT` | `roleplay/progress/maximum-turn-count` | 전체 턴 제한(양수) |
| `ROLEPLAY_MAXIMUM_SUPPORT_TURNS_PER_MICRO_GOAL` | `roleplay/progress/maximum-support-turns-per-micro-goal` | 목표별 지원 턴 제한(양수) |

모두 배포 목록에서는 선택 항목이다. 미설정 상태에서도 앱은 시작되고 역할극 HTTP API는 꺼져 있다. 전체·목표별 턴 제한은 둘 다 없거나 둘 다 양수여야 한다. HTTP API를 켜면 두 제한과 업로드·작업자 정책이 필수이며, 잘못된 설정은 앱 시작 시 실패한다. 실제 제품 정책값은 이 문서나 코드에서 정하지 않는다. 테스트의 77턴·9회 및 용량·작업자 수는 테스트 전용이다.

## 작업자 수와 동시 사용자 (dev, 2026-11-11 측정)

한 턴은 AI 를 약 5번 불러 혼자 쓸 때도 20초 안팎이 걸린다. 작업자가 모자라면 턴이 큐에서 기다리고, 기다린 시간도 `turn-budget`(60초)에 들어간다. 60초를 넘긴 턴은 `REINPUT_REQUIRED` 로 끝나 아이에게 다시 말해 달라는 안내가 나간다. 작업자는 AI 응답을 기다리는 동안 DB 연결을 잡지 않는다.

측정은 테스트 주소(t3.medium 1대)에 `frontend/e2e/deployed/concurrent-load.spec.ts`(가상 사용자, 역할극 6턴)와 `concurrent-users.spec.ts`(브라우저 4명)를 돌린 결과다.

| `worker-count`·`queue-capacity` | 동시 사용자 | 턴 시간 p50·p95·최대 | 60초 초과 | 503 BUSY |
|---|---|---|---|---|
| 2·4 | 4 | 38.7초·56.2초·66.6초 | 42턴 중 2 | 0 |
| 16·32 | 20 | 18.6초·30.5초·34.5초 | 120턴 중 0 | 0 |
| 16·32 | 30 | 28.0초·42.3초·52.4초 | 180턴 중 0 | 0 |
| 16·32 | 50 | 50.2초·60.0초·60.1초 | 300턴 중 30 | 4 (같은 키로 다시 보내 처리됨) |

dev 의 현재 값은 16·32 다. 30명까지는 60초 안에 모두 처리되고, 50명부터는 턴의 약 10%가 60초를 넘긴다. 사용자가 더 많을 때는 작업자를 늘리기 전에 AI 공급자의 분당 요청 한도부터 확인한다.

## 활성화 전 필요한 구현

- S4의 강사 승인 시나리오 공급과 첫 질문 목표 연결이 준비되어야 한다.
- `RoleplayInputProcessor`와 `RoleplaySpeechDelivery`의 운영 구현 빈이 필요하다. 현재 테스트 구현만으로 운영 API를 활성화하지 않는다.
- 음성 미제공은 `ROLEPLAY_SPEECH_DELIVERY=none`으로 선택한다. [음성 미제공 전달](roleplay-speech-delivery.md)의 활성화 조건·제공자 의존성·프론트엔드 계약을 확인한다. 설정 누락/지원하지 않는 값에 기본 전달 구현을 대신 등록하지 않는다. 별도로 등록된 전달 구현이 있으면 그것을 유지한다.
- AI·STT·TTS 제공자와 자격 증명을 각각 설정한다. 제공자 설정과 HTTP 활성화는 별개다. 역할극 사용 시 기존 AI 재시도 제한도 적용된다.
- TIMEOUT 외 안내 문구는 승인 메타데이터를 포함해 `roleplay.http.notices`로 별도 공급한다. 이 목록의 공급 경로는 이번 환경변수 매핑에 포함하지 않는다.
- 임시 경로의 쓰기 권한과 서버 자원에 맞는 용량·동시 작업 정책을 확인한다.

설정 전달이 준비되었다는 사실만으로 위 구현과 승인 자료가 완성되지는 않는다. S4 완료 후 실제 연동 검증과 활성화를 진행한다.
