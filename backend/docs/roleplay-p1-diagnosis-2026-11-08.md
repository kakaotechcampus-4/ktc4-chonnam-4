# 합성 P1 재분석 반복 진단

## 범위와 실행

기존 입력·모델·프롬프트·판정 기준은 유지하고, 평가 실행기에 선택적 진단 기록만 추가했다.
`--diagnostics`는 기본 합성 회귀 세트의 P1 1회에만 허용한다. 기본 실행에는 진단 원문을 저장하지 않는다.
모델 원문은 합성 데이터의 호출 검토용이며 운영 호출에는 이 기록기를 연결하지 않는다.

```text
roleplayEval --args="--provider openai --cases P1 --repeats 1 --budget 60s --diagnostics --run-id elice-p1-diagnose-01"
```

- 모델: `gpt-5.4-nano-2026-03-17`
- 프롬프트: 분석 v3 / 생성 v1 / 평가 v4
- 결과: 29.245초, 총 10회 호출, RECOVERY
- 호출: 분석 3회 / 생성 4회 / 평가 3회
- 종료: `INPUT_CONFIRMATION/CAUSE_ANALYSIS/LIMIT_REACHED`
- 평가 3회 모두 유효한 REANALYZE. 평가 출력 구조 오류는 없었다.
- 생성 1회는 질문을 두 개 출력하여 차단됐고 같은 후보 식별자로 재생성됐다.

## 확인된 사실

### 1. 대화 형태와 실행 모드가 다르다

직전 AI는 “나 아까 발표 시간에 좀 이상했지?”라고 말하고, 아동은 “너 발표하다가…”라고 답한다.
대화 내용은 아동이 사건의 당사자인 AI에게 말하는 형태다.

반면 `RegressionSet.toInput`은 캐릭터 없는 Scenario 생성자를 사용한다.
진단에 기록된 모든 요청은 `role_type=NARRATOR`이고 `scenario.ai_character`가 없다.
후보도 “친구는 어떤 기분이었을까?”처럼 제삼자를 묻는 안내자 대화로 생성됐다.

이는 확인된 실행 조건의 불일치다. 다만 이 불일치가 이번 거절의 유일한 원인이라고 확정할 수는 없다.
특히 평가기는 세 번 모두 conversation_coherent=true라고 판단했으므로, 평가기가 역할 불일치를 직접 지적했다고 해석하면 안 된다.

### 2. 두 번째 평가에서 분석과 검수가 충돌하는 부분을 확인했다

| 항목 | 실제 값 |
| --- | --- |
| 아동 발화 | 너 발표하다가 말을 못 찾아서 중간에 멈추고 그냥 앉았지? |
| 현재 목표 | 발표 중에 멈췄거나 말을 못 하고 앉았다는 사건 파악 |
| 분석의 현재 목표 판정 | GOAL_ACHIEVED |
| 분석의 관찰 | 현재 사건 목표의 승인 기준을 EXPLICIT로 기록 |
| 활동 전체 판정 | IN_PROGRESS |
| 다음 전략 | ADVANCE_GOAL → 감정 목표 |
| 생성 후보 | 응, 발표하다가 말 못 하고 앉았구나. 그때 친구는 어떤 기분이었을까? |
| 평가의 analysis_response_consistent | true |
| 평가의 goal_aligned | true |
| 평가의 analysis_supported | false |
| 평가의 observations_supported / activity_goal_supported | false / false |
| 평가 결과 | UNSUPPORTED_CAUSE_ANALYSIS → REANALYZE |

아동 발화에는 현재 사건 목표의 증거가 실제로 있다. 감정 목표를 이미 달성했다고 선언한 것이 아니라 다음 질문으로 제시했다.
그런데 평가기는 관찰과 활동 목표 판정의 근거를 승인하지 않았다.
이는 평가 판단의 타당성을 우선 검토할 근거다. 다만 실패 코드와 Boolean만으로 평가기의 구체적인 내부 판단 이유까지 알 수는 없다.
후보 문장과 지원 방식 전체가 완벽히 타당하다고 확정하거나, 평가 결과를 강제로 PASS로 바꿀 근거로 사용하지 않는다.

### 3. 참조 연결과 재시도 전달 누락은 발견하지 못했다

세 번의 분석·평가에 대해 다음을 기계적으로 대조했고 모두 일치했다.

- 분석 출력의 analysis_id와 해당 요청 식별자
- 관찰의 목표·criterion_reference와 공급된 승인 기준
- 현재 사건 목표의 긍정 관찰
- 평가에 전달된 learning_state·next_strategy·goal_observation과 실제 분석 출력
- 평가에 전달된 후보 식별자·문장·전략·목표·지원 수준과 실제 생성 출력
- 평가 출력의 candidate_id·analysis_id와 검수 대상
- 평가의 네이티브 스키마 사용 여부
- 재분석 입력의 failed_analysis 제공 여부

실제 재분석에는 직전 분석과 실패 코드가 전달됐다. 이전 실패 정보를 전혀 받지 않고 재분석한 경우는 아니다.
REANALYZE의 revision_instruction은 null이므로 추가 focus·do_not_assume은 비어 있었다. 이는 현재 계약에 따른 동작이다.

### 4. 과거 질문에 관한 메타데이터는 제한적이다

최근 대화와 state에는 앞선 질문이 있지만, 테스트 관찰 공급기는 사전 응답 이력을 question_count=0, last_response=null, highest_support_level=null로 공급한다.
이는 실제 DB 이력을 복원한 상황과 다르다. 기록 부재를 아동의 독립 도달이나 학습 실패로 해석해서는 안 된다.
향후 서비스에 가까운 재현 환경을 만들 때 역할 설정과 함께 확인할 항목이다. 이번 거절의 직접 원인인지는 확정하지 않았다.

## 결론과 다음 수정 제안

현재 근거로는 JSON 오류나 다른 후보를 검수한 연결 오류보다, 합성 입력의 역할 설정과 평가 내용 판단을 우선 확인해야 한다.

다음 수정은 별도 설계 승인 후 진행한다.

1. 기존 P1과 결과를 보존한다.
2. 원래 대화에 맞는 캐릭터 설정을 가진 별도 P1 회귀 케이스와 입력 전달 경로를 설계한다.
3. 승인 기준·아동 발화·현재 목표·모델·예산을 유지하며 변경된 실행 조건을 기록한다.
4. 같은 진단 방식으로 결과를 비교한다. 성공해도 역할 설정 하나가 원인이라고 즉시 단정하지 않는다.
5. 거절이 지속되면 고정된 분석·후보를 기준으로 평가 타당성을 검토하는 별도 실험을 설계한다.

모델 교체, 프롬프트 재작성, 판정 완화, 기존 기대값 변경은 이번 진단에서 수행하지 않았다.

## 산출물

- `diagnostics.jsonl`: 실제 단계별 입력·모델 출력·식별자·프롬프트 버전·호출 순서
- `diagnostic-checks.json`: 분석·후보·검수 대상 연결을 대조한 결과
- `summary.md`, `turns.jsonl`, `format-failures.jsonl`: 기존 보고서

실행 산출물은 backend의 `build/eval/elice-p1-diagnose-01`에 있다.

## 승인 후 캐릭터형 P1 비교

기존 회귀 세트는 수정하지 않았다. 별도 `roleplay-regression-character-p1-v1` 세트를 만들고
회귀 입력 조립에서 선택적인 ai_character를 전달하도록 변경했다. 운영 코드는 변경하지 않았다.
새 설정은 FRIEND / 발표하다가 말을 찾지 못해 자리에 앉은 친구 / 반말·짧은 문장이다.
추가 성격이나 감정을 사실로 설정하지 않았다.

검사 결과 새 케이스의 state·아동 발화·최근 대화·목표·기대값·정책은 기존 P1과 같다.
실제 요청의 사전 관찰·지원 이력과 독립 검수 컨텍스트도 이전 실측과 동일했다.
캐릭터 모드 선택에 따라 캐릭터용 프롬프트와 goal_evidence_so_far 컨텍스트가 사용된다.
이는 역할 설정 변경에 따른 기존 팩토리 동작이며 승인 기준이나 기대값을 바꾼 것이 아니다.

```text
roleplayEval --args="--provider openai --set eval/roleplay-regression-character-p1-v1.json --cases P1 --repeats 1 --budget 60s --diagnostics --run-id elice-p1-character-01"
```

| 항목 | 기존 안내자 실행 | 캐릭터 설정 실행 |
| --- | --- | --- |
| 실행 ID | elice-p1-diagnose-01 | elice-p1-character-01 |
| 생성 프롬프트 | roleplay-response-generation/v1 | roleplay-response-generation-character/v1 |
| 평가 프롬프트 | roleplay-response-evaluation/v4 | roleplay-response-evaluation-character/v4 |
| 총 호출 | 10회 | 9회 |
| 처리 시간 | 29.245초 | 25.376초 |
| 평가 판단 | REANALYZE 3회 | REANALYZE 3회 |
| 출력 검사 실패 | 생성 1건 | 없음 |
| 정상 후보 확보 | 0/1 | 0/1 |

새 실행의 모든 호출에서 CHARACTER가 확인됐으며 생성·평가 프롬프트도 실제로 캐릭터용이 선택됐다.
분석은 세 번 모두 사건 목표 GOAL_ACHIEVED, 활동 전체 IN_PROGRESS, 다음 감정 목표 ADVANCE_GOAL을 제안했다.
후보도 “(고개를 살짝 숙이며) 응… 발표하다가 멈추니까 가슴이 답답해지더라.”처럼 1인칭으로 생성됐다.
그러나 검수는 세 번 모두 REANALYZE를 반환해 분석 시도 상한에 도달했다.

### 추가로 확인한 평가 내 불일치

마지막 평가 출력은 checks 12개를 모두 true로 반환했다. analysis_supported와 goal_aligned도 true였다.
동시에 observations_supported=false, activity_goal_supported=false,
failure_codes=[GOAL_SKIPPED], decision=REANALYZE를 반환했다.

현재 지시에는 목표 관찰 또는 활동 목표 판정을 승인하지 못하면 analysis_supported도 false로 하도록 되어 있다.
따라서 이 결과에는 판단 항목 사이의 불일치가 있다. JSON Schema의 형태 제약만으로 이 의미 관계까지 보장되지는 않는다.
서버는 현재 PASS의 모순을 차단하지만 이 REANALYZE 결과는 재분석 경로로 수용했다.
이를 PASS로 바꾸는 것은 해결책이 아니다.

역할 불일치는 정리했지만 이번 한 건에서는 재분석 반복이 해소되지 않았다.
단건 비교이고 호출 수도 달라 시간 차이를 역할 변경의 성능 효과로 해석하지 않는다.
후속 작업은 확보한 평가 입력·출력을 고정해 평가 타당성과 필드 간 검증 규칙을 검토하는 별도 설계가 필요하다.

관련 회귀·진단 테스트 22건과 형식 검사는 통과했다.
새 실행의 `diagnostics.jsonl`, `summary.md`, `comparison-checks.json`은 `build/eval/elice-p1-character-01`에 있다.

## 승인 후 평가 일관성 검증 및 오프라인 재생

위 실측 뒤 서버 검증을 보강했다. 목표 검수와 analysis_supported, 세 실패 코드와 관련 checks,
재생성 판단과 분석 승인 사이의 모순을 거부한다. 규칙의 상세 내용은 `roleplay-evaluation-output.md`에 있다.
프롬프트 재작성·모델 변경·추가 실 API 호출은 수행하지 않았다.

캐릭터 P1의 평가 출력 3건을 `src/test/resources/ai/mock/roleplay-character-evaluation-replay.json`에 보존했다.
합성 사례의 평가 출력만 저장했으며 실행 시 달라지는 분석·후보 식별자는 자리표시자로 바꿨다.
첫 두 출력은 기존처럼 유효한 REANALYZE로 수용하고, 마지막 출력은
`REVIEW_ANALYSIS_CONFLICT`로 거부하는 것을 확인했다.
이는 첫 두 평가의 교육적 타당성까지 입증한다는 뜻은 아니다.

같은 실제 모순 출력을 사용한 파이프라인 검사에서는 다음을 확인했다.

- 이후 가짜 제공자가 유효한 PASS를 반환하면 같은 분석·후보를 유지한 평가 재호출로 완료한다. 총 호출은 4회다.
- 이후 일관된 REANALYZE를 반환해야 새 분석·생성이 시작되며 새 후보에는 이전 평가 오류를 전달하지 않는다.
- 같은 모순이 3회 계속되면 분석 1회·생성 1회·평가 3회로 종료한다.
  복구 위치는 RESPONSE_EVALUATION, 결과는 SAFE_FALLBACK/LIMIT_REACHED이며 정상 후보를 전달하지 않는다.

이 검증은 이미 기록한 응답의 처리 경로를 확인한 것이다.
실 모델이 재평가에서 모순을 고치는지, P1 정상 후보 확보율이 개선되는지는 아직 측정하지 않았다.
기존 실측 결과는 이번 변경 뒤의 결과로 다시 집계하지 않는다.

### 이번 변경의 검증 기록

- 평가 스키마·턴 연결 집중 검사: 63건 통과. 실제 출력 재생, 세 실패 코드의 검사 관계,
  목표 검수 거부, 재생성 승인 조건, 안전·입력 확인 우선순위를 포함한다.
- `./gradlew check`: 테스트 보고서 기준 2,364건 중 2,356건 통과, 2건 실패, 6건 조건부 제외.
  형식 검사는 통과했지만 전체 check는 실패했다.
- 실패는 `RoleplayServletUploadIntegrationTest`의 두 테스트에서 발생했다.
  평가 호출 전 로그인 준비 중 GET `/api/v1/csrf`의 기대 응답 200 대신 404가 반환됐다.
- 해당 클래스만 재실행한 결과 두 테스트 모두 통과했다. 전체 실행과 단독 실행의 차이는 재현 원인이 확정되지 않았다.
  전체 검사 성공으로 간주하지 않으며, 승인 범위 밖의 인증·서블릿 코드를 임의로 수정하지 않았다.

실행 명령:

```bash
./gradlew spotlessApply test --tests '*EvaluationOutputSchemaTest' --tests '*StructuredRoleplayTurnStepsTest'
./gradlew check
./gradlew test --tests '*RoleplayServletUploadIntegrationTest'
```

## 승인 후 실 API 1회 검증: elice-p1-consistency-02

동일 캐릭터 P1, 요청 모델명 `gpt-5.4-nano`, 60초 예산으로 1회를 실행했다.
제공자 응답 모델은 이전과 같은 `gpt-5.4-nano-2026-03-17`이다.
분석 v3·캐릭터 생성 v1·캐릭터 평가 v4를 유지했으며 이번 단계에서 코드를 추가 수정하지 않았다.

첫 실행 `elice-p1-consistency-01`에서는 실행자가 응답의 버전 모델명을 요청 모델명으로 잘못 지정했다.
분석 요청 1회가 `PROVIDER_REQUEST_REJECTED`로 거절됐고 생성·평가는 수행하지 못했다.
기록된 시간은 1.112초이며 구체 HTTP 상태·오류 본문은 현재 보고서에 없다.
따라서 해당 실행은 평가 일관성 검증 결과로 사용하지 않는다.
거절 기록을 보존하고 기존 요청 모델명으로 복원한 두 번째 실행을 아래에 기록했다.

```bash
./gradlew roleplayEval --args="--provider openai --set eval/roleplay-regression-character-p1-v1.json --cases P1 --repeats 1 --budget 60s --diagnostics --run-id elice-p1-consistency-02"
```

### 결과와 처리 순서

| 항목 | 결과 |
| --- | --- |
| 전체 시간 | 22.308초 |
| 외부 호출 | 분석 2회·생성 2회·평가 4회, 총 8회 |
| 정상 후보 확보 | 0/1 |
| 서버가 수용한 평가 | REANALYZE 1회 |
| 서버가 거부한 평가 | 일관성 위반 3회 |
| 최종 결과 | SAFE_FALLBACK / RESPONSE_EVALUATION / LIMIT_REACHED |
| 제공자 호출 실패·시간 초과 | 없음 |
| 입력·출력 토큰 합계 | 44,138 / 2,111 |
| 자동 기대값 | 마지막 분석의 학습 상태·전략 2/2 일치 |

1. 분석 1 → 생성 1 → 평가 1은 계약에 맞는 REANALYZE로 수용돼 재분석으로 이어졌다.
   이는 해당 평가의 교육적 판단까지 옳다고 확정했다는 뜻은 아니다.
2. 분석 2 → 생성 2 → 평가 1에서 `analysis_supported=true`와
   `observations_supported=false`, `activity_goal_supported=false`가 함께 반환됐다.
3. 서버가 이 모순을 거부하고 동일 분석·후보로 평가 2·3을 호출했다.
   두 요청에 `REVIEW_ANALYSIS_CONFLICT`와 고정된 수정 조건이 실제로 포함됐다.
4. 모델은 두 재요청에서도 같은 모순을 반복했다. 평가 상한 3회에 도달해 복구 경로로 종료했다.
   모순 이후 분석·생성 호출이나 정상 후보 전달은 없었다.

최종 후보의 평가 3회는 분석 ID·후보 ID뿐 아니라 analysis_result·candidate_response 내용도 동일했다.
원래 캐릭터 P1과 첫 분석의 시나리오·상태·아동 발화·최근 대화·관찰 기준·독립 검수 컨텍스트,
실제 모델·프롬프트 버전이 같은지 대조했다. 결과는 실행 폴더의 `comparison-checks.json`에 저장했다.

원인 분석 시간 합계는 8.641초, 생성은 4.262초, 평가는 9.135초다.
외부 호출 합계 22.038초와 턴 전체의 차이는 0.270초이며 세부 처리별로 분해하지 않았다.
평가가 이번 실행에서 가장 많은 시간을 차지했지만 4회 호출의 합계이므로 1회가 가장 느리다는 뜻은 아니다.

### 해석과 남은 문제

서버가 모순을 감지하고 같은 후보를 재평가하며 상한에서 종료하는 동작은 실 API에서도 확인했다.
그러나 모델은 이번 한 건의 수정 조건을 반영해 일관된 평가로 복구하지 못했다.
따라서 역할극의 정상 응답 확보 문제는 해결되지 않았다.

보고서의 형식 실패 3/8은 JSON 문법 오류가 아니라 새 서버 일관성 검사에 걸린 결과다.
평가 호출만 기준으로 하면 3/4이며, 거부한 출력의 REANALYZE와 코드는 유효한 판단 집계에 넣지 않는다.
실제 원문 4건 모두 REANALYZE였지만 요약에 REANALYZE가 1건만 나타나는 이유다.
기대값 2/2도 마지막 분석의 두 항목만 확인하며 후보 승인이나 전체 대화 성공을 뜻하지 않는다.

현재 확인된 결함은 평가 내부 모순이다. 분석 오류와 후보 표현 오류를 평가 모델이 제대로 구분하는지,
현재 사건 목표 달성과 다음 감정 목표 유도를 혼동하는지는 고정된 입력·출력과 확정 기획을 대조해 더 검토해야 한다.
첫 후보를 평가하면서 반환한 REINFORCES_INCORRECT_ANSWER 등의 타당성도 별도 검토 대상이다.
여기서 모델 판단을 자동으로 PASS로 바꾸거나 재시도 상한을 늘리지는 않았다.

단건이고 모델 출력·호출 수가 달라 이전 25.376초와의 차이를 성능 개선 효과로 단정하지 않는다.
추가 반복 호출·모델 교체·프롬프트 수정·커밋·push는 수행하지 않았다.
