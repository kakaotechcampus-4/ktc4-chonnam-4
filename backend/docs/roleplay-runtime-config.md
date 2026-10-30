# 역할극 운영 설정 전달

`deploy/host/params.txt` → Parameter Store `/neuringo/<환경>/…` → `deploy.sh`의 서버 `.env`(권한 600) → `compose.dev.yml`의 backend `env_file` → `application.yml` 순서로 전달한다. `preview`도 같은 환경의 정책을 사용한다. 이 변경은 실제 Parameter Store 값을 등록하거나 배포하지 않는다.

| 환경변수 | Parameter Store 경로(`/neuringo/<환경>/` 뒤) | 의미 |
|---|---|---|
| `ROLEPLAY_HTTP_ENABLED` | `roleplay/http/enabled` | 기본 `false`, 명시적으로 켤 때만 HTTP 빈 등록 |
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

## 활성화 전 필요한 구현

- S4의 강사 승인 시나리오 공급과 첫 질문 목표 연결이 준비되어야 한다.
- `RoleplayInputProcessor`와 `RoleplaySpeechDelivery`의 운영 구현 빈이 필요하다. 현재 테스트 구현만으로 운영 API를 활성화하지 않는다.
- AI·STT·TTS 제공자와 자격 증명을 각각 설정한다. 제공자 설정과 HTTP 활성화는 별개다. 역할극 사용 시 기존 AI 재시도 제한도 적용된다.
- TIMEOUT 외 안내 문구는 승인 메타데이터를 포함해 `roleplay.http.notices`로 별도 공급한다. 이 목록의 공급 경로는 이번 환경변수 매핑에 포함하지 않는다.
- 임시 경로의 쓰기 권한과 서버 자원에 맞는 용량·동시 작업 정책을 확인한다.

설정 전달이 준비되었다는 사실만으로 위 구현과 승인 자료가 완성되지는 않는다. S4 완료 후 실제 연동 검증과 활성화를 진행한다.
