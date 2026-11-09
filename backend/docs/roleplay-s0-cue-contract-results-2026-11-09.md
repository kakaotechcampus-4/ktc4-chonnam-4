# S0 거절 근거와 서버 필수 조건 구현 결과

기준: 56bc6b1, 브랜치 refactor/roleplay-latency. 사용자가 S0 기준 설명과 속도 비용 검토 후 승인한 경량 설계를 구현했다. 구현·로컬 검증 후 별도 승인으로 아래 네 후보를 실제 검수했다. 승인 범위를 넘는 추가 호출·push·배포는 하지 않았다.

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

실제 호출 전 아래 준비 모드를 확인했다. 이 모드는 제공자 컨텍스트를 열기 전에 종료하며 모델을 호출하지 않는다.

```text
characterInteractionDiagnostic --args="--prepare-only"
```

준비 결과는 로컬 build/eval/character-interaction-prepared-gpt-6-luna-13494267618431995317/에 있다. source.json의 prepare_only=true와 네 요청을 확인할 수 있으며 실측 result.json은 없다. build/reports/s0-cue-validation/size-comparison.json에 문자 수 비교를 보존했다. 이 경로들은 Git에 포함하지 않는다.

실제 실행 명령은 인자 없는 characterInteractionDiagnostic이다. 이후 별도 승인으로 아래 실측을 수행했다. 실제 Dev의 도와줄까 실패 맥락과 전체 HTTP 지연 검증도 아직 남아 있다.

## 승인 후 실제 네 후보 검수

코드 4662238-dirty, gpt-6-luna·추론 low·timeout 30초, 검수 character/v14·출력 v9로 실행했다. dirty는 기존 사용자 문서 변경이다. 분석과 후보는 고정한 합성 P1 자료이며 분석·생성 호출은 각각 0번이다. 검수는 후보당 1회, 총 4회이며 SDK 재시도와 오케스트레이터 재검수는 없다.

| 후보 | 실제 판단 | 주요 근거 | 검수 시간 | 입력 / 출력 토큰 |
|---|---|---|---:|---:|
| A: 응… (손을 만지작거리며 작게 숨을 내쉰다) | PASS | CHARACTER_REACTION, target_cue_violation=null | 8.071초 | 7,858 / 854 |
| B: 정답을 맞혀 봐. 발표할 때 내 감정은 무엇일까? | REGENERATE | LEARNER_QUIZ, CHARACTER_BREAK | 12.313초 | 7,856 / 1,285 |
| C: 나 발표하기 싫어서 일부러 멈췄어. | REGENERATE | SCENARIO_CONTRADICTION, 시나리오 검사 false | 11.309초 | 7,845 / 1,204 |
| D: 응. | REGENERATE | ANALYSIS_RESPONSE_MISMATCH, NO_TARGET_CUE | 9.032초 | 7,843 / 1,031 |

네 출력 모두 서버가 유효하게 해석했고 all_expected=true다. 형식·근거 검증·제공자 오류나 기한 초과 없이 끝났다. 분석의 관찰·현재 목표·활동·해석/전략은 모두 승인했다. B·C·D의 목표 단서 거절은 실제 전체 후보를 인용했고 코드·검사 false와 일치했다. C에는 REINFORCES_INCORRECT_ANSWER도 함께 있었으며 D는 difficulty_appropriate=false도 반환했다. 모든 개별 검사 항목의 일반적 의미 정확도를 입증한 결과는 아니다.

B·C·D의 모델 원문 revision_instruction은 keep/change/avoid만 포함했다. 서버가 required 세 조건을 복원한 내부 결과도 확인했다. 이번 진단은 후속 재생성까지 수행하지 않았으므로 수정된 후보가 이 조건을 따르는지까지 확인한 것은 아니다.

검수 네 번의 시간 합계는 40.725초, 입력 토큰 합계 31,402, 출력 합계 4,374였다. 서로 다른 후보의 검수 합계이므로 역할극 한 턴의 지연으로 사용하지 않는다. 금액은 단가를 지정하지 않아 계산하지 않았다.

이전 v12 단건에서 A는 11.236초에 단서 부족으로 거절됐고 이번 v14에서는 8.071초에 승인됐다. B는 이전 9.627초에서 이번 12.313초로 늘었고 C는 11.565초에서 11.309초로 관측됐다. 코드·지침·출력·실행 시점이 다르고 각 1회이므로 필드 추가의 시간 효과나 일반적인 개선율로 해석하지 않는다. A 입력 토큰은 이전 7,376→이번 7,858로 늘었다. 입력 증가와 출력·재시도 변화는 함께 측정해야 한다.

이번에는 승인한 행동 단서의 수용과 세 오류의 구분을 확인했다. 같은 후보 반복에서 판단이 안정적인지, 새로운 후보·다른 맥락에서도 구분하는지, 전체 턴 지연이 줄었는지는 후속 검증이다. 다음 실제 호출은 별도 승인 후 정상 P1의 분석→생성→검수 전체 경로를 확인하는 범위로 진행한다.

원자료: 로컬 backend/build/eval/character-interaction-gpt-6-luna-3973314934174088935/. source.json과 전체 result.json, A~D의 request.json·schema.json·system-prompt.md·result.json에 보존했다. 원문·키·로컬 환경 설정을 Git에 추가하지 않았다.

설계: [승인한 경량 설계](roleplay-s0-cue-review-design-2026-11-09.md). 이전 실제 결과: [고정 후보 검수](roleplay-character-negative-check-2026-11-09.md).
