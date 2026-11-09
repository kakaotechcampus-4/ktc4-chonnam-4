# S0 거절 근거와 서버 필수 조건 구현 결과

기준: 56bc6b1, 브랜치 refactor/roleplay-latency. 사용자가 S0 기준 설명과 속도 비용 검토 후 승인한 경량 설계를 구현했다. 추가 실제 AI 호출·push·배포는 하지 않았다.

## 변경된 처리

- S0_OBSERVABLE_CUE_V1 정책을 생성과 검수의 같은 계약으로 전달한다. 맥락과 다음 목표에 연결되는 소리·행동 하나로 S0 단서를 충족할 수 있으며 감정 이름·이유·질문을 추가로 요구하지 않는다. 단순 맞장구·무관한 행동은 부족할 수 있다. 특정 대사의 자동 PASS 목록은 없다.
- 캐릭터 일반 학습 전략의 ANALYSIS_RESPONSE_MISMATCH 거절에서만 target_cue_violation={reason, quote}를 받는다. 정상 출력은 null이다. 사유는 NO_TARGET_CUE, WRONG_TARGET_CUE, NO_REACTION_TO_INPUT이며 목표 ID는 기존 서버 적용 전략을 재사용한다.
- 실제 후보 인용, 전체 단서 부재 주장 시 전체 대사 인용, 실패 코드·검사 false·근거의 일치를 검사한다. 없는 인용·추가 ID·모순은 같은 후보 재검수로 보낸다. 안전/입력 의미 확인 우선 및 비학습 단서 전략은 null이다.
- 캐릭터 모델용 revision_instruction은 keep/change/avoid만 받는다. required를 출력하면 무시하거나 지우지 않고 잘못된 모델 출력으로 거절한다. 수신 스키마 검증 후 서버가 역할·적용 목표/지원·승인 사실/교사식 문답 금지의 세 필수 조건을 복원한다.
- 내부 RevisionInstruction·EvaluationResult의 JSON 형태와 GenerationRetry 소비 방식은 유지한다. 안내자 모델의 네 배열 수정 조건도 유지한다. 원문 진단에는 모델이 작성한 세 배열만 보이고, 내부 결과에는 서버의 required가 추가된다.

기존 60초 공유 예산·단계별 시도 상한·SDK 재시도 0·독립 검수·안전 우선 경로는 유지한다. 새 모델 호출 단계·DB 변경·프론트엔드 수정은 없다.

## 버전

| 항목 | 현재 값 |
|---|---|
| 기본 캐릭터 분석 | roleplay-cause-analysis-character/v1 |
| 기본 캐릭터 생성 | roleplay-response-generation-character/v7 |
| 기본 캐릭터 검수 | roleplay-response-evaluation-character/v14 |
| 기존 형태 캐릭터 생성·검수 | generation-character/v6, evaluation-character/v13 |
| 제공자 검수 스키마 | evaluation-output/v9 |
| 분석·후보·내부 검수 계약 | character-analysis-output/v1, character-candidate-output/v1, evaluation-result/v2 유지 |

## 속도 비용의 로컬 비교

실제 제공자를 호출하지 않는 준비 모드로 같은 P1 A의 검수 요청을 구성했다. 이전 실측에 저장된 시스템 지침·사용자 JSON·native 스키마의 문자 수와 비교했다. 문자 수는 토큰·시간·네트워크 바이트가 아니다.

| 입력 구성 | 이전 v12/v8 | 현재 v14/v9 | 차이 |
|---|---:|---:|---:|
| 시스템 지침 | 5,474 | 6,179 | +705 |
| 사용자 JSON | 5,175 | 5,211 | +36 |
| API 스키마 | 8,375 | 8,591 | +216 |
| 합계 | 19,024 | 19,981 | +957, 약 5.0% |

정상 응답의 동일 JSON에 새 nullable 필드만 적용한 비교는 1,674→1,702자로 +28자였다. 모델이 실제로 새 형식으로 출력한 결과는 아니다. 과거 B 출력의 모델 required를 제거하고 새 null 필드를 적용한 경우에는 58자 줄었다. 검수의 다른 설명·추론 출력량은 실모델에서 달라질 수 있다.

따라서 이번 변경을 입력량 감소나 속도 개선으로 보고하지 않는다. 불필요한 재생성이 줄어 입력·출력 비용을 상쇄하는지, 새 근거 검사 때문에 동일 후보 재검수가 늘어나는지는 후속 실측 대상이다. 근거 정합성 검사가 단서의 의미 분류 정확도를 보장하지 않는다.

## 검증

검수의 정상 nullable 근거, 유효한 거절 유지, 사유·코드·검사 모순, 없는/부분/아동 발화 인용, 추가 목표 ID, 모델 required 주입 거절, 서버 required 복원과 공개 JSON 유지, 기존 안전·안내자·재시도 경로를 로컬 검사로 확인했다. 새 의미 기준에 맞는 fake 판정은 서버 계약 검사이며 실제 모델의 의미 정확도 근거로 사용하지 않는다.

최종 `./gradlew --no-daemon build`가 3분 31초에 통과했다. JUnit XML 합계 2,921개 중 2,915개 통과, 실패·오류 0, 실행 조건이 필요한 6개 제외다. 이 안에 P1 계약 회귀 13개와 고정 후보 진단 7개가 포함된다. 제품 코드 변경 후 전체 검사는 한 번 실행했다. 공개 금지 파일·diff 검사와 마이그레이션 가드(기존 최대 43)도 통과했다. DB 변경은 없다.

최종 빌드 로그는 로컬 build/reports/s0-cue-build.log, 집계는 build/reports/s0-cue-validation/summary.json에 보존했다. 실제 모델 호출 없이 준비된 네 후보 요청의 생성도 확인했다.

## 다음 실제 검증 준비

characterInteractionDiagnostic은 A 행동 단서, B 교사식 문답, C 금지 추론에 D 단순 맞장구 `응.`을 추가했다. 별도 실제 호출 승인 후에만 최대 네 번의 검수·공유 60초·자동 재시도 없이 실행한다. 정상 승인과 정확한 이유의 거절을 함께 읽는다. 과거 세 후보 보고서는 당시 결과 그대로 보존한다.

현재 실행한 것은 아래 준비 모드뿐이다. 제공자 컨텍스트를 열기 전에 종료하며 모델을 호출하지 않는다.

```text
characterInteractionDiagnostic --args="--prepare-only"
```

준비 결과는 로컬 build/eval/character-interaction-prepared-gpt-6-luna-13494267618431995317/에 있다. source.json의 prepare_only=true와 네 요청을 확인할 수 있으며 실측 result.json은 없다. build/reports/s0-cue-validation/size-comparison.json에 문자 수 비교를 보존했다. 이 경로들은 Git에 포함하지 않는다.

실제 실행 명령은 인자 없는 characterInteractionDiagnostic이다. --prepare-only를 빼면 실제 API를 호출하므로 이번 작업에서는 실행하지 않았다. 실제 Dev의 도와줄까 실패 맥락과 전체 HTTP 지연 검증도 아직 남아 있다.

설계: [승인한 경량 설계](roleplay-s0-cue-review-design-2026-11-09.md). 이전 실제 결과: [고정 후보 검수](roleplay-character-negative-check-2026-11-09.md).
