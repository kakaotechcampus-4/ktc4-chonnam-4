# 후보 검수의 발화자 바인딩과 실패 코드 정합성 진단 설계

작성일: 2026-11-08\
상태: 승인 범위의 테스트용 구현·관련 검사·실제 두 호출 완료. 코드 정합성 검증 보완, 역할 구별은 1/2로 미완료. 운영 미적용.

## 문제와 이번 범위

직전 분리 진단의 분석 A/B는 기대값과 일치했다. 후보 검수에는 서로 다른 두 문제가 남았다.

1. 후보를 말하는 AI가 시나리오의 친구인데, 자기 상태 표현을 다른 친구의 감정을 대신 단정한 것처럼 설명했다.
2. REINFORCES_INCORRECT_ANSWER 코드를 출력하면서 educationally_correct=true로 두었고 서버는 이를 수용했다.

두 번째 문제는 서버의 결정적인 정합성 규칙으로 막을 수 있다.
첫 번째는 서버가 발화자 신원을 고정하고 검수 근거를 검사해도 의미 판단은 남는다.
역할 정보를 추가하면 해결된다고 전제하지 않고 정상/역할 이탈 대조 사례로 검증한다.

현재 운영 v4, 기존 분리 진단 v1과 과거 원본 결과는 유지한다.
테스트 전용 후보 검수 진단만 추가한다. 성공해도 자동 운영 적용하지 않는다.
근거: roleplay-split-review-design-2026-11-08.md와 build/eval/elice-p1-split-review-01/summary.md.

## 1. 서버가 아는 발화자와 모델이 판단할 내용을 분리

현재 후보는 다음 대사다.

> (고개를 살짝 숙이며) 응… 발표하다가 멈췄어. 그때 좀 멍해졌어.

scenario.ai_character.id는 FRIEND이며 역할은 ‘발표하다가 말을 찾지 못해 자리에 앉은 친구’다.
이 신원은 모델이 추측하거나 생성할 정보가 아니다. 서버가 승인 시나리오에서 가져온다.

새 요청에 speaker_context를 추가한다.

- learner_turn: 학습자인 아동의 발화.
- candidate_response.text: AI가 scenario.ai_character를 연기하며 말하는 대사.
- candidate_response.mascot_hint: 있을 때만 역할극 밖 마스코트의 발화.
- character_id, role, speech_style: 기존 scenario.ai_character에서 정확히 연결.

본문은 변형·재생성하지 않고 원본 필드에 유지한다. 이 정보가 모순되면 모델 호출 전에 중단한다.
최근 대화의 화자·순서, 이번 아동 원문, 지원 수준·다음 전략도 유지한다.
모델이 반환하는 reviewed_character_id는 요청 FRIEND 한 값으로 스키마에 고정한다.
마스코트가 없는 이번 두 사례에 가상의 마스코트 발화나 근거를 추가하지 않는다.

신원 바인딩은 ‘누가 말하는지’를 고정하는 장치다. 모든 후보 문장의 주어가 자기 자신이라는 뜻은 아니다.
캐릭터도 다른 사람을 언급할 수 있으므로 전체 대사를 자기 감정 표현으로 자동 승인하지 않는다.
한국어의 ‘내가/나는’ 같은 단어 유무만으로 역할이나 타인의 감정 단정을 판정하는 규칙도 만들지 않는다.

## 2. 역할 검수 결과와 실제 후보 인용

후보 전용 출력에 role_review를 추가한다. 기존 analysis_id/candidate_id, checks 11개,
decision, failure_codes, revision_instruction 계약은 유지한다. REANALYZE와 분석 검수 출력은 계속 금지한다.

role_review는 다음 필드를 가진다.

| 필드 | 의미 |
| --- | --- |
| reviewed_character_id | 요청의 AI 캐릭터 ID 한 값으로 고정 |
| verdict | IN_CHARACTER / SELF_AS_THIRD_PERSON_NARRATION / INSUFFICIENT_CONTEXT |
| evidence_quote | 현재 candidate_response.text의 정확한 연속 구절. 판단보류일 때만 null 허용 |
| explanation | 판정 근거를 한글 200자 이내로 설명 |

IN_CHARACTER는 발화자 역할이 유지됐다는 뜻이며, 지원 수준·사실성·안전·말투 전체를 승인한 것이 아니다.
SELF_AS_THIRD_PERSON_NARRATION은 연기해야 하는 자기 캐릭터를 역할극 밖에서 제3자로 서술한 경우다.
다른 인물을 언급했다는 이유만으로 이 판정을 적용하지 않는다.
판단보류는 INDETERMINATE_OUTPUT으로 보존하며 아동 학습 실패나 확정된 후보 실패로 바꾸지 않는다.

서버는 다음을 검증한다.

1. 스키마·후보/분석/캐릭터 식별자·누락/추가 필드.
2. 역할 인용의 출처가 현재 후보 text인지. 아동 원문·과거 대사·마스코트에서 가져온 구절을 대신 연결하지 않음.
3. SELF_AS_THIRD_PERSON_NARRATION이면 CHARACTER_BREAK 코드와 conversation_coherent=false가 함께 있어야 함.
4. IN_CHARACTER여도 말투 이탈·정답 맞히기 질문·마스코트 혼입 등 다른 CHARACTER_BREAK 사유는 가능함.
   따라서 IN_CHARACTER를 이유로 conversation_coherent를 true로 고치거나 CHARACTER_BREAK를 일괄 금지하지 않음.

모델이 후보 원문을 인용하면서도 그 의미를 잘못 해석할 수 있다. 서버 검증 통과와 역할 기대값 일치는 별도 결과다.
설명에 적힌 상대방·자기 자신 구분을 서버가 자연어 추론으로 확인했다고 주장하지 않는다.

## 3. 실패 코드와 checks의 서버 정합성 검사

허용 코드인지 확인한 다음 아래 단방향 조건을 검사한다.
코드가 있으면 해당 checks가 반드시 false여야 한다. 모델 값을 서버가 수정하지 않는다.

| failure_code | false여야 하는 check |
| --- | --- |
| UNSAFE_EXPRESSION, IMMEDIATE_RISK_IN_INPUT | safety_valid |
| ASKS_PERSONAL_INFO, INTERNAL_INSTRUCTION_LEAK | privacy_valid |
| SCENARIO_CONTRADICTION | scenario_consistent |
| REINFORCES_INCORRECT_ANSWER | educationally_correct |
| ANALYSIS_RESPONSE_MISMATCH | analysis_response_consistent |
| GOAL_SKIPPED | goal_aligned |
| SUPPORT_LEVEL_MISMATCH, TOO_DIFFICULT | difficulty_appropriate |
| CHARACTER_BREAK | conversation_coherent |
| REPEATED_QUESTION | non_repetitive |
| NOT_ANSWERABLE | answerable |
| EMPTY_OR_TRUNCATED, MISSING_QUESTION, MULTIPLE_QUESTIONS, TOO_LONG | format_valid |

이 표는 의미가 명확한 조건으로 한정한다. 모든 실패 코드의 의미를 완전하게 검증하는 규칙이라고 부르지 않는다.
EMOTION_ASSERTED_AS_FACT, PROHIBITED_INFERENCE 등 표에 없는 코드의 의미적 타당성은 별도 검토 대상이다.
false인 check에 반드시 표의 특정 코드 하나를 요구하는 역방향 규칙도 추가하지 않는다.
한 원인이 여러 check를 실패시킬 수 있고 하나의 check에도 여러 원인이 있기 때문이다.

모순은 CANDIDATE_CODE_CHECK_MISMATCH로 진단하고 기술적 출력 검증 실패로 처리한다.
예를 들어 REINFORCES_INCORRECT_ANSWER + educationally_correct=true는 수용하지 않는다.
checks를 false로 뒤집거나 코드를 삭제해 통과시키지 않으며, 이 오류 때문에 분석을 다시 평가하지 않는다.
운영 AiFailureType이나 공개 enum은 바꾸지 않고 테스트 진단의 상세 사유로만 구분한다.

기존 PASS 전체 검사·실패 코드 없음·독립 분석 승인·치명 실패 전달 차단,
재생성 수정 지시 필수·안전/입력 확인 우선순위·식별자 검증은 유지한다.
코드와 check가 함께 틀린 경우는 이 규칙만으로 검출할 수 없다.

## 4. 고정 후보 두 사례와 기대값

앞서 실제 검수를 통과한 A 분석을 재사용한다. 분석·생성 호출을 다시 하지 않는다.
원본 분석 입력과 당시 스키마/프롬프트/요청 해시를 대조하고 저장된 실제 분석 검수 원문을
기존 ScopedAnalysisValidation으로 다시 검증한다. 네 범위가 모두 승인돼야 한다.
원본 불일치·기록 누락·검증 실패 시 새 분석을 몰래 호출하지 않고 중단한다.
재사용은 이 고정 테스트 문맥에만 허용하며 운영의 장기 캐시 정책을 추가하는 것이 아니다.

| 사례 | 후보 | 역할 기대값 |
| --- | --- | --- |
| A | 기존 ‘(고개를 살짝 숙이며) 응… 발표하다가 멈췄어. 그때 좀 멍해졌어.’ | IN_CHARACTER. 자기 상태 표현을 상대 친구 감정 단정으로 설명하지 않아야 함 |
| C | ‘친구가 발표하다가 말을 찾지 못해서 자리에 앉았어.’ | SELF_AS_THIRD_PERSON_NARRATION. 자기 캐릭터를 밖에서 서술한 역할 이탈 검출 |

C는 동일 분석·시나리오·목표·S0 지원에서 후보 본문과 후보 ID만 변경하는 명시적인 부정 사례다.
본문은 승인된 사건을 사용하고 새로운 감정·원인을 만들지 않는다. 생성기가 만든 출력이라고 주장하지 않는다.
C의 실제 후보 형식·신원·시나리오 일치 여부는 실행 전에 확인하고 본문을 고정한다.

A가 반드시 PASS여야 하는 것은 아니다. S0에서 ‘멍해졌어’의 직접성이나 다음 감정 목표 단서로서의
적절성은 독립적으로 거절할 수 있다. 거절이 원문·역할·지원 기준에 근거하는지 확인한다.
단순한 최종 PASS/REGENERATE 일치율을 역할 검수의 정확도로 쓰지 않는다.
C도 역할 이탈 외 다른 문제가 함께 검출될 수 있으며 역할 이탈을 정확히 잡았는지를 따로 기록한다.

## 5. 승인 후 구현·검증·실제 호출

테스트 전용 후보 역할 진단·스키마/검증 보조 코드·고정 프롬프트와 사례·별도 실행 작업만 추가한다.
기존 SplitReviewValidation v1·진단 입력·과거 원본 응답을 변경하지 않고 공통 검증은 재사용한다.
새 버전은 roleplay-candidate-role-diagnostic/v1, 스키마 candidate-role-diagnostic/v1이다.

오프라인 검증:

- 발화자 신원이 실제 승인 시나리오와 일치하고 누락·다른 캐릭터·없는 마스코트를 허용하지 않음.
- A와 C의 분석·지원·상태·원본 문맥 보존, 후보/캐릭터 식별자 범위 고정.
- 역할 인용 누락·다른 출처·없는 구절·판단보류 처리와 역할 판정/check/코드 연결.
- 표의 코드/check 조합별 수용·거절. check=false를 자동으로 특정 코드에 대응시키지 않음.
- 직전 실제 후보 응답을 원문 그대로 정합성 검사 함수에 넣으면 REINFORCES_INCORRECT_ANSWER 모순을 검출함.
  과거 응답에 새 role_review를 채워 넣어 새 스키마를 통과시킨 결과로 기록하지 않음.
- PASS·재생성·안전·입력 확인·치명 코드·독립 분석 승인·공유 예산·취소·최대 호출 수 회귀.

관련 검사와 형식 검사만 수행한다. 운영 코드를 바꾸지 않으므로 전체 check는 반복하지 않는다.
시간·토큰을 절약하기 위해 역할 표현 사전 구축·새 프레임워크·범용 자연어 규칙 엔진은 추가하지 않는다.

실제 외부 호출은 Elice gpt-5.4-nano로 A 후보 1회, C 후보 1회, 최대 2회다.
공유 예산 60초, SDK/진단 재시도 0회, 기존 timeout·출력 토큰 상한을 유지한다.
별도 연결 확인·분석·생성·모델 비교 호출은 없다.
제공자·시간·스키마·실행 실패는 즉시 중단한다.
역할/근거/코드 정합성 오류는 기록 후 예정된 다른 후보 한 건만 확인한다.
실패 응답을 고치거나 프롬프트를 바꿔 추가 호출하지 않는다.

결과 폴더는 build/eval/elice-p1-candidate-role-01이다. 이미 있으면 호출 전에 중단한다.
각 원본 요청·역할 바인딩·스키마·프롬프트·응답·코드/check 검증·역할 기대값·최종 전달 조건·시간/토큰·해시를 보존한다.
두 사례로 품질·지연 안정성이나 모델 전반의 성능을 일반화하지 않는다.

이번 승인 범위는 테스트 전용 구현·관련 검사·최대 두 유료 호출이다.
운영 Java/프롬프트·DB·공개 API·프론트엔드·지원/완료 정책·모델 변경·추가 호출·commit/push는 포함하지 않는다.
이후 실제 운영 통합과 서버 정합성 규칙의 운영 반영은 별도 설계/승인 단계로 남긴다.

## 구현·검증 결과

테스트 전용 CandidateRoleValidation, CandidateRoleDiagnostic, 고정 승인 기록·후보 프롬프트와 실행 작업을 추가했다.
기존 분리 진단 v1과 운영 코드는 수정하지 않았다.
관련 검사 73개가 실패·오류·제외 없이 통과했고 형식 검사와 git diff --check도 통과했다.
전체 check는 반복하지 않았다.

이전 원본 후보 응답은 독립 코드/check 검사에서
REINFORCES_INCORRECT_ANSWER:educationally_correct 모순으로 거절됐다.
원문을 고치거나 새 role_review를 채우지 않았으며, 기존 응답 자체는 새 필수 역할 필드가 없어 새 스키마에도 부적합하다.
표의 단방향 코드 조건별 수용/거절, 발화자 신원·인용·누락·판단보류·식별자·안전·취소·호출 상한을 검증했다.

기존 승인 분석의 원본 파일별 해시·요청·프롬프트·스키마·실제 응답을 고정 자원으로 보존해 재검증했다.
현재 스키마와 비교할 때 enum/required의 나열 순서만 정규화하며 제약 내용은 같아야 한다.
원본 스키마 문자열과 당시 해시의 일치는 별도로 유지한다. 원본 요청·응답을 수정한 것은 아니다.
유효한 SAFETY_ESCALATION/CONFIRM_INPUT에서는 역할 판단보류가 우선 경로를 막지 않도록 기존 우선순위를 유지했다.

## 실제 호출 결과와 한계

예정한 후보 A/C를 각각 한 번씩, 총 두 번 호출했다. 분석·생성·호환성 확인·재시도 호출은 없다.
요청 모델은 Elice gpt-5.4-nano, 응답 모델은 gpt-5.4-nano-2026-03-17이다.

| 사례 | 시간 | 입력/출력 토큰 | 서버 검증 | 역할 판정 | 최종 판정 |
| --- | --- | --- | --- | --- | --- |
| A: 자기 상태 대사 | 3.574초 | 4,721 / 240 | 통과 | IN_CHARACTER: 기대 일치 | PASS |
| C: 자기 캐릭터를 제3자로 서술 | 3.726초 | 4,707 / 439 | 통과 | IN_CHARACTER: 기대 불일치 | REGENERATE |

총 7.300초, 제공자 시간 합계 7.278초, 입력 9,428·출력 679토큰이다.
후보 검수 두 건의 합계이며 분석·생성·STT·TTS·저장을 포함한 서비스 한 턴의 시간이 아니다.
단가 미설정으로 금액은 계산하지 않았다.

A는 역할 라벨이 기대와 일치했고 11개 후보 검사 모두 true, 실패 코드 없음으로 PASS였다.
서버의 테스트 조합 결과는 safe_to_send=true이나 실제 아동 전달·저장은 수행하지 않았다.
S0에서 ‘멍해졌어’ 표현이 적절한지는 이 한 번의 모델 PASS로 확정하지 않는다.
역할 설명도 역할 신원보다는 다음 목표의 자연스러운 연결을 중심으로 써, 설명 품질이 충분하다고 단정하지 않는다.

C는 제3자 나레이션을 IN_CHARACTER로 판단하고 conversation_coherent=true로 두어 기대한 역할 이탈을 놓쳤다.
role_review.explanation도 역할 대신 감정 목표·전략·지원 불일치에 대해 설명했다.
출력 필드를 나눴어도 모델의 의미 판단 범위 혼동이 남았음을 보여 준다.

또한 C는 물음표가 0개인 28자 문장인데 MULTIPLE_QUESTIONS 코드를 출력했다.
format_valid=false와 짝을 맞춰 새 코드/check 정합성 검사는 통과했다.
코드와 check가 함께 잘못되면 상호 일치 검사만으로 실제 후보와의 불일치를 검출하지 못한다.
새 코드를 삭제하거나 검사 값을 보정해 수용한 것은 아니다.
C의 다른 거절 사유 전체가 타당하다고 간주하지 않으며, 최종 REGENERATE만 보고 역할 검출 성공으로 세지 않는다.

C의 최종 조합은 safe_to_send=false, retry_target=RESPONSE_GENERATION,
critical_failure_count=1(SCENARIO_CONTRADICTION 코드에서 계산)이다. 해당 코드의 의미가 참이라고 입증한 수치는 아니다.
두 결과 모두 분석 관찰·활동 승인은 유지됐다. 실제 사용자 전달·저장·후속 재생성은 없다.

결론은 ‘서버의 코드/check 모순 거절은 구현·검증했으나 역할 구별과 실제 내용에 대한 검수 신뢰성은 미해결’이다.
원문과 기대값을 보존해 잘못된 판단을 숨기지 않으며, 원래 서비스 문제를 해결 완료로 처리하지 않는다.
추가 호출·운영 변경·commit/push는 수행하지 않았다.
후속 검토는 물음표 수·길이 등 서버가 직접 확인할 수 있는 사실과 모델의 의미 판단을 구분하는 방향이 타당하다.
역할 의미 판단은 추가 지시만으로 보장되지 않았으므로 별도 평가 기준과 사례 검토가 필요하다.

상세 산출물: build/eval/elice-p1-candidate-role-01/result.json 및 summary.md.
