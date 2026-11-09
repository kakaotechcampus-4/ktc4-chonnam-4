# 역할극 음성 미제공 전달

## 범위와 근거

Issue #107의 3번에서 허용한 텍스트 우선 연결을 구현한다. 기준 develop은 f017637이다. 음성을 기본으로 하는 VS-010의 제품 정책을 변경하거나 실제 음성 재생을 완료한 것으로 취급하지 않는다.

| 근거 | 이번 적용 |
|---|---|
| #107: 글자 대화는 음성 없음 반환으로 연결 가능 | 환경에서 `none`을 명시적으로 선택한 경우만 주소 미제공 구현 등록 |
| VS-011: 검수 통과 응답만 전달, 전송 유실 시 같은 승인 후보 재사용 | 기존 검수·체크포인트·응답 매퍼를 유지. 재전송으로 새 AI 호출·턴 확정을 만들지 않음 |
| DEC-010: STT~TTS 전체 최대 60초 | 파이프라인·기한·재시도를 변경하지 않음 |
| DEC-027/055: 검사 장애 전달 금지, 기술 장애는 학습 실패에서 제외 | 음성 미제공을 입력 실패로 집계하지 않음. 기존 검사/복구 경로 유지 |

## 등록과 응답

`ROLEPLAY_HTTP_ENABLED=true` 및 `ROLEPLAY_SPEECH_DELIVERY=none`일 때 `NoAudioRoleplaySpeechDelivery`가 등록된다. Spring 속성명은 `roleplay.speech.delivery`다. HTTP 비활성화 시에는 등록하지 않는다. 별도의 `RoleplaySpeechDelivery` 빈이 있으면 교체하거나 중복 등록하지 않는다.

기본 선택값은 빈 값이다. 미설정/빈 값/지원하지 않는 값에는 대체 빈을 만들지 않는다. 다른 전달 빈도 없이 HTTP를 켜면 기존 응답 매퍼의 필수 의존성이 충족되지 않아 서버 시작이 실패한다. `none` 선택은 자동 음성 기능 감지가 아니다.

`publish(approvedCandidateId, speech)`는 `Optional.empty()`를 반환한다. 음성을 저장·업로드·로그 출력하지 않으며 네트워크·DB 호출도 없다. 기존 `RoleplayTurnResponseMapper`가 대사와 함께 `speech.available=false`, `speech.url=null`을 반환한다. URL·data URL·공개 경로를 임의로 만들지 않는다.

정상 턴은 기존 `DELIVERED`/`NONE`과 확정된 체크포인트 버전을 반환한다. 음성 미제공을 아동 재입력이나 음성 실패로 변환하지 않는다. 재전송은 기존 승인 대사를 돌려주며 표준 발화·임시 음성을 새로 노출하지 않는다. 힌트·안전 안내는 기존 표시 정책을 유지한다.

## 배포 설정과 의존성

`deploy/host/params.txt`의 선택 항목 `ROLEPLAY_SPEECH_DELIVERY?=roleplay/speech/delivery`를 통해 Parameter Store `/neuringo/<환경>/roleplay/speech/delivery` → backend 환경변수 → `application.yml`로 전달한다. 이 변경 자체는 실제 Parameter Store 값을 등록하거나 서버를 배포하지 않는다.

현재 `RoleplayHttpConfiguration`은 STT·TTS 제공자 빈을 필수로 받는다. 텍스트 입력도 승인 응답을 만든 뒤 TTS를 호출한다. `none`은 전달 주소를 제공하지 않는 선택이며 TTS 합성 호출을 생략하지 않는다. 실제 제공자 설정/자격 증명·LLM·입력 검사기·승인 안내·기타 HTTP 필수 정책은 별도로 준비해야 한다. TTS 호출 생략이나 제공자 없는 텍스트 전용 파이프라인은 이번 변경에 포함하지 않는다.

인증된 수신자에게 임시 음성 URL을 전달하는 기능은 후속 구현이다. 접근 권한·만료·폐기 및 브라우저 재생 연동을 갖추기 전에는 음성 재생 완료로 보고하지 않는다.

## 검증과 프론트엔드 전달

설정 테스트는 HTTP 비활성화, 명시적인 `none`, 누락/빈 값/지원하지 않는 값, 기존 빈 유지 및 실제 환경변수 매핑을 확인한다. HTTP/DB 통합 테스트는 운영 전달 빈과 인증된 아동 세션을 사용해 텍스트·음성 입력의 대사 전달, 음성 없음, 동일 키 재전송, 턴·버전 1회 확정 및 음성 실패 미증가를 확인한다. 입력 검사·LLM·STT·TTS는 테스트 대역이므로 실제 모델 품질·음성 제공자·브라우저·배포 검증과 구분한다.

API 경로/필드/인증 계약은 변경하지 않는다. 프론트엔드는 `messages[].speech.available=false`, `url=null`이어도 대사·힌트를 표시하고 정상 진행을 유지해야 한다. 음성 재생 실패나 아동 입력 실패로 표시하거나 null 주소 재생을 시도하지 않는다. 프론트엔드는 직접 수정하지 않았으며 실제 화면 연동은 별도 확인한다.
