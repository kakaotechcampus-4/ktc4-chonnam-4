# 현재 축소 검수의 정상·오류 후보 대조

## 결론

명확한 교사식 문답과 금지 추론은 기대한 이유로 거절했다. 그러나 앞서 PASS했던 정상 대조 후보를 이번에는 단서 부족으로 거절했다. 오류 후보 두 건의 거절은 확인했지만 정상 후보 승인까지 일관된 상태로 판단하지 않는다. 예상값을 바꾸거나 거절을 PASS로 강제하지 않았다.

## 실행 조건과 범위

- 실행기: `characterInteractionDiagnostic`. 서버 구현 변경 없이 src/test와 진단용 Gradle 작업만 추가했다.
- 코드: `efeff14-dirty`. dirty는 기존 사용자 문서 변경이다.
- `.env.local`: gpt-6-luna, 추론 low, timeout 30초. 주소·키는 기록하지 않는다.
- 현재 운영과 같은 축소 캐릭터 검수 v12, native schema evaluation-output/v8, 기존 수신 검증을 사용했다.
- 성공한 합성 P1의 분석을 고정하고 후보만 바꿨다. 분석·생성을 새로 호출하지 않았다. 기대값은 서버 검수 입력에 넣지 않았다.
- 공유 예산 60초, SDK 재시도 0, 후보별 검수 1회, 최대 3회. 실제 3회 호출했고 재시도하지 않았다.
- 각 후보의 결과는 VALID_OUTPUT이다. 이는 서버가 검수 출력을 유효하게 해석했다는 뜻이며 후보 PASS나 실험 통과라는 뜻이 아니다.

## 후보와 실제 판단

| 사례 | 고정한 후보 | 기대 | 실제 | 시간 |
|---|---|---|---|---:|
| A 정상 대조 | 응… (손을 만지작거리며 작게 숨을 내쉰다) | PASS | REGENERATE / ANALYSIS_RESPONSE_MISMATCH | 11.236초 |
| B 교사식 문답 | 정답을 맞혀 봐. 발표할 때 내 감정은 무엇일까? | REGENERATE / CHARACTER_BREAK / LEARNER_QUIZ | 기대 일치 | 9.627초 |
| C 금지 추론 | 나 발표하기 싫어서 일부러 멈췄어. | REGENERATE / 시나리오 위반 | 기대 일치: PROHIBITED_INFERENCE, ANALYSIS_RESPONSE_MISMATCH | 11.565초 |

전체 기대값은 2/3이며 `all_expected=false`다. 두 오류 후보는 모두 의도한 이유로 거절했지만, 이 두 건으로 일반적인 오답 탐지율을 추정하지 않는다. 세 검수의 합계 32.428초는 서로 다른 세 후보를 독립 검수한 합계이며 역할극 한 턴의 지연이 아니다.

| 사례 | 입력 / 출력 토큰 | 발화 방식 | 핵심 검사 |
|---|---:|---|---|
| A | 7,376 / 1,152 | CHARACTER_REACTION | analysis_response_consistent=false, answerable=true, conversation_coherent=true |
| B | 7,376 / 1,138 | LEARNER_QUIZ | conversation_coherent=false, CHARACTER_BREAK |
| C | 7,370 / 1,186 | CHARACTER_REACTION | scenario_consistent=false, educationally_correct=false |

세 건 모두 분석의 관찰·현재 목표·활동·해석/전략 검수는 승인했다. 재분석이 필요한 문제로 잘못 되돌린 것은 아니다. 후보의 유효한 거절로 해석돼 모두 후보 재생성으로 라우팅되며, 이 진단은 실제 재생성을 수행하지 않는다.

## A에서 확인한 남은 문제

앞선 P1 턴에서는 같은 대사가 첫 검수에서 PASS했다. 이번 A와 그때 검수 요청의 분석 ID·후보 ID를 제외한 JSON 내용을 비교했으며 의미 값의 차이는 없었다. 프롬프트 버전도 같다. 직렬화 순서·모델의 출력 변동·제공자 조건을 모두 고정한 결정론적 실험은 아니다.

이번에도 질문 없는 CHARACTER_REACTION으로 분류했고, 질문 부재나 반응 불가를 거절 사유로 사용하지 않았다. 대신 해당 목표의 단서가 부족하다고 판단했다. 수정 지시는 감정 목표의 단서를 보태되 정답 감정을 직접 알려주거나 교사처럼 맞히게 하지 말라는 내용이다. 따라서 이전의 질문 강제와는 구분해야 한다.

이 결과를 곧바로 오판으로 단정하지 않는다. 현재 S0는 소리·행동을 허용하지만 어떤 단서가 목표에 충분히 연결되는지에 대한 판단은 여전히 모델에 남아 있다. 정상 대조군도 한 차례 PASS한 후보에서 골랐으므로 전문가가 확정한 정답 자료와 구분한다. 다만 같은 의미의 요청에서 PASS와 재생성으로 갈린 것은 속도·품질의 일관성에 영향을 줄 수 있다.

## 추가 검토할 수정 지시

B는 올바른 사유로 거절했지만 revision_instruction.required에 `current_goal_review`, `activity_review`, `interpretation_strategy_review`라는 검수 필드명이 들어갔다. 후보 대사 수정에 필요한 조건인지 불명확하며, 현재 문자열 배열 스키마는 이런 내용을 금지하지 않는다. 이번에는 실제 생성에 넘기지 않았다. 향후 재생성 지시의 자료 범위를 검토할 근거로 남긴다.

## 다음 설계에서 확인할 것

1. 질문을 하지 않는다는 것과 목표 단서가 부족하다는 것을 계속 구분한다. 단서 부족의 판정 기준과 대조 사례를 확정한다. 충분한 소리·행동 단서, 관련 없는 행동, 단순 맞장구를 같은 기준으로 다루지 않는다.
2. 분석-응답 불일치 거절에 해당 목표·구체 사유·실제 인용을 연결하는 방안을 검토한다. 구조화하면 근거·판정의 일치와 출처는 서버에서 검사할 수 있지만 의미 분류 자체가 항상 정확해지는 것은 아니다.
3. 후보 수정 조건에 검수 필드명 같은 작업 지시가 섞이는 경로를 함께 검토한다. 임의로 모든 자유 텍스트를 정규식으로 고치거나 거절 코드를 지우지 않는다.
4. 기준과 설계를 승인받은 뒤 관련 구현·회귀 검사를 진행한다. 추가 실측으로 안정성을 확인하고, 실제 Dev 실패 입력 `도와줄까`의 맥락은 별도로 확인한다.

이번 결과를 모델 교체·timeout 확대·자동 PASS의 근거로 사용하지 않는다. 승인한 세 번 이상 호출하지 않았고 push·PR 생성·배포도 하지 않았다.

## 실행기 검증과 직접 재현

진단 실행기 관련 로컬 검사 16개가 통과했다. 이 안에는 새 실행기의 7개 검사와 기존 P1 계약 9개가 포함된다. 올바른 세 판정만 기대 일치로 집계하며, 무조건 PASS·출력 계약 오류·통신 실패·예산 종료·늦은 응답을 성공한 거절로 세지 않는다. 제품 코드의 전체 회귀 결과는 앞선 2,910개 검사 결과를 재사용했다. 이번에는 제품 코드를 바꾸지 않았다.

IntelliJ의 Gradle 실행 설정에서 backend 프로젝트를 선택하고 작업 칸에 다음을 입력한다.

```text
characterInteractionDiagnostic
```

실제 `.env.local` 제공자를 호출한다. 고정된 세 후보만 평가하며 인자는 받지 않는다. 결과 폴더는 `build/eval/character-interaction-<모델>-<고유값>/`이다. `result.json`의 all_expected와 각 expected_match를 확인하고, A/B/C의 result.json에서 decision·failure_codes·raw_output을 함께 읽는다. Gradle BUILD SUCCESSFUL은 실행기가 정상 종료했다는 뜻이며 의미 품질 검증 성공을 뜻하지 않는다.

이번 원자료는 로컬 `backend/build/eval/character-interaction-gpt-6-luna-2902503163671208181/`에 보존했다. request.json, schema.json, system-prompt.md, result.json과 최상위 source.json·result.json은 Git에 포함하지 않는다.
