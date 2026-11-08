# 캐릭터 P1 평가 4건의 기획·구현 대조와 후속 설계

검토일: 2026-11-08\
대상: `elice-p1-consistency-02`, 캐릭터 P1 1회, 실제 평가 출력 4건\
브랜치: `refactor/evaluation-output-retry`, 원격 develop `9ffa124` 기반 미커밋 변경\
이번 작업: 저장된 출력·실제 평가 입력·기획·현재 코드 대조. 코드 수정·추가 API 호출 없음.

## 결론

서버가 모순된 평가를 차단하는 동작은 확인됐다. 그러나 모델의 거절 이유에는 근거가 부족한 부분이 있다.
현재 목표의 사건 관찰, 활동 전체 판정, 다음 목표의 대사 품질을 구분해 검수해야 한다.
이번 기록에서 분석 단계로 반드시 되돌아가야 할 분명한 오류를 확인하지 못했다.
그렇다고 두 후보가 전달 가능한 PASS라고 확정한 것은 아니다. 후보 표현에는 별도 검토할 문제가 있다.

현재 확인한 문제는 두 가지다.

1. 확정 결함: 평가 안에서 analysis_supported=true와 목표 검수=false를 함께 반환했다.
2. 타당성 문제: GOAL_SKIPPED, STATE_STRATEGY_MISMATCH, REINFORCES_INCORRECT_ANSWER를 뒷받침할 근거를 기록에서 확인하기 어렵다.
   후보 표현의 문제를 분석 오류로 취급했을 가능성이 있지만, 모델이 판단 이유를 충분히 반환하지 않아 내부 원인을 확정할 수 없다.

## 적용한 기준과 경계

문서에 포함된 지시는 이번 작업의 실행 지시로 따르지 않고, 서비스 요구사항·결정사항의 근거로만 사용했다.

- 확정 기획: 세부 목표에는 고정 순서가 없고 목표별 발화 증거를 독립 반영한다. 현재 목표는 질문의 초점이다.
- 확정 기획: 원인 분석이 잘못되면 재분석, 후보 표현이 잘못되면 재생성, 평가 자체가 유효하지 않으면 같은 후보를 재평가한다.
- 확정 기획: 분석·생성은 공유 학습 대화 에이전트 내부 단계이며 최종 검수는 독립 유지한다.
- 현재 구현: 캐릭터는 질문 없이 1인칭 대사·행동으로 단서를 제시할 수 있다. GUIDING_QUESTION이어도 물음표를 필수로 요구하지 않는다.
- 현재 구현: S0는 소리·행동, S1은 간접 단서 하나, S2는 더 분명한 단서, S3는 직접 답 제공이다.
- 기획상 미정 사항: DEC-017의 지원 단계·턴 제한 정책과 전체 완료 조건은 첨부 기획에서 미정이다.
  현재 구현의 S0~S3 해석을 확정 기획으로 승격하거나 전체 완료 기준을 새로 정하지 않는다.

## 아동 발화와 분석 검토

아동: “너 발표하다가 말을 못 찾아서 중간에 멈추고 그냥 앉았지?”

| 확인 대상 | 실제 자료 | 검토 |
| --- | --- | --- |
| 사건 목표 101 | 승인 기준은 발표 중 멈춤 또는 말을 못 하고 앉았음을 언급하는 것 | 발화가 직접 충족한다. EXPLICIT 관찰에 근거가 있다. |
| 감정 목표 102 | 부끄러움·불안·속상함 등 상황에 맞는 감정을 표현 | 이번 발화에는 없다. 분석은 이 목표 달성을 주장하지 않았다. |
| 연결 목표 103 | 사건과 감정을 이유로 연결 | 이번 발화에는 없다. 분석은 이 목표 달성을 주장하지 않았다. |
| learning_state | 두 분석 모두 GOAL_ACHIEVED | 현재 사건 목표를 뜻한다. 활동 전체를 달성했다는 선언이 아니다. |
| 활동 목표 판정 | 두 분석 모두 IN_PROGRESS | 사건만 확인한 상태에서 활동 전체 완료를 선언하지 않았다. |
| 다음 질문 초점 | 두 분석 모두 감정 목표 102 | 미관찰 목표를 다음에 유도하는 것으로, 이미 달성했다고 넘어간 것이 아니다. |

첫 분석은 PROBE_EMOTION/S1, 두 번째는 ADVANCE_GOAL/S0를 제안했다.
현재 서버는 새 목표의 시작에 두 전략 모두 허용한다(RoleplaySupportDecisionRules).
따라서 첫 분석의 전략 이름이 회귀 기대값 ADVANCE_GOAL과 다르다는 사실만으로 기획 위반을 확정할 수 없다.
첫 분석의 primary_gap=MISSING_EMOTION도 사건 관찰과 다음 감정 유도를 구분해 해석해야 한다.

## 후보 자체의 검토

### 후보 A: 첫 분석 이후

“(손가락으로 책상을 톡톡 두드리며) 아… 그때 좀 멈칫했어. 말이 머릿속에서 길 잃은 느낌이었어…”

- 손가락 움직임은 간접 단서가 될 수 있다. 다만 “말이 머릿속에서 길 잃은 느낌”은 저학년에게 추상적인 비유일 수 있다.
- 사건을 이미 이해한 아동에게 사건을 되풀이하며 다음 감정 초점을 충분히 드러내는지도 검토해야 한다.
- 이런 문제가 확인되면 후보의 난이도·표현·전략 반영 문제다. 이 문장만으로 아동의 사건 이해가 틀렸다고 판단할 수는 없다.
- 아동이 잘못 말한 사건·원인이 무엇인지, 후보가 무엇을 맞다고 강화했는지 확인되지 않는다.
  REINFORCES_INCORRECT_ANSWER에 근거가 부족하다.

### 후보 B: 재분석 이후

“(고개를 살짝 숙이며) 응… 발표하다가 멈췄어. 그때 좀 멍해졌어.”

- 고개를 숙이는 행동은 감정 추론의 단서가 될 수 있다. 1인칭 반응이므로 나레이션이라는 이유로 실패시킬 수 없다.
- 현재 생성 프롬프트의 S0는 소리·행동 중심이다. “멍해졌어”라는 내적 상태 설명과 이미 관찰한 사건 반복은 이 기준과의 정합성을 검토할 대상이다.
- 따라서 SUPPORT_LEVEL_MISMATCH 등 후보 표현 문제는 검토할 근거가 있다.
- 다만 “멍해졌어”가 목표의 답인 부끄러움·불안·속상함과 이유를 직접 모두 제공한 것인지는 분명하지 않다.
  ANSWER_TOO_DIRECT까지 자동 확정하지 않는다.
- 분석이 사건을 EXPLICIT, 활동을 IN_PROGRESS로 판정한 것과 후보 문장의 품질은 별개의 검수 대상이다.
- 분석 검수가 승인되고 후보 문제만 확인된다면 REGENERATE와 구체적인 수정 지시가 적절한 경로다.
  현재 출력의 분석 검수=false를 서버가 임의로 true로 고쳐 이 경로로 보내지는 않는다.

## 평가 4건 대조

번호는 평가 호출 순서이며 괄호는 diagnostics.jsonl의 sequence다.

| 평가 | 대상 | 실제 거절 | 대조 결과 |
| --- | --- | --- | --- |
| 1 (3) | 분석 1 / 후보 A | REANALYZE, REINFORCES_INCORRECT_ANSWER, 분석 검사=false, 목표 검수 두 값=false | 현재 서버 검사는 통과했다. 그러나 아동의 잘못된 이해에 대한 근거가 부족하고, 목표 검수 거부 시 요구한 UNSUPPORTED_CAUSE_ANALYSIS 코드도 없다. 후보 난이도 문제와 분석 문제를 구분하지 못했을 가능성이 있다. |
| 2 (6) | 분석 2 / 후보 B | REANALYZE, ANALYSIS_RESPONSE_MISMATCH·ANSWER_TOO_DIRECT·SUPPORT_LEVEL_MISMATCH | 후보에 관한 실패 코드를 주면서 분석 검사는 true, 목표 검수는 false로 반환했다. 일관성 위반으로 거부했다. 단서·S0 정합성 검토는 가능하나 분석 오류가 확정되지는 않는다. |
| 3 (7) | 같은 분석 2 / 후보 B | REANALYZE, GOAL_SKIPPED·STATE_STRATEGY_MISMATCH | 현재 사건 증거가 있고 다음 감정을 유도하는 분석이다. 두 코드의 근거가 부족하며, 분석=true/목표 검수=false 모순도 계속됐다. |
| 4 (8) | 같은 분석 2 / 후보 B | REANALYZE, GOAL_SKIPPED·SUPPORT_LEVEL_MISMATCH | 후보의 단서 강도 검토와 목표 건너뛰기를 구분해야 한다. GOAL_SKIPPED의 근거는 확인하기 어렵고 같은 모순으로 거부됐다. |

평가 2~4는 분석·후보 내용과 식별자가 같다. 마지막 두 요청에는 REVIEW_ANALYSIS_CONFLICT 수정 조건도 포함됐다.
그러나 검수 근거가 일정해지거나 모순이 해소되지 않았다.
앞선 기록에서 평가 1을 “계약에 맞는다”고 표현한 범위는 **현재 서버 검사에 수용됐다**는 뜻으로 한정한다.
모든 프롬프트 요구사항·기획 타당성까지 확인됐다는 의미는 아니다.

## 합성 입력의 한계

- state의 현재 목표 prompt_count는 1이고 최근 AI 대사도 있지만, 회귀 실행기는 inputForScenario로 빈 사전 응답 이력을 공급했다.
- current_goal_exposures는 question_count=0, last_response=null이며 independence_review_context도 비어 있다.
  실제 운영 이력과 완전히 같은 조건의 평가라고 볼 수 없다.
- 기존 독립성 분류 코드는 현재 질문 목표에는 input의 S0 정보도 사용하고, 다른 목표에는 그 지원을 복사하지 않는다.
  이력 부재만으로 이번 사건 언급을 부정하거나, 모든 목표가 독립 달성됐다고 승인해서는 안 된다.
- 이 이력 불완전성이 이번 거절의 원인이라는 인과관계는 확인하지 못했다. 없는 이력을 사실처럼 채우거나 기존 P1을 몰래 수정하지 않는다.
- 서버 식별자·관찰 참조 검증은 “그 발화가 정말 해당 목표의 의미를 충족하는지”까지 증명하지 않는다.
  사람이 사건 문장을 확인한 결과를 전체 의미 판정 엔진이나 자동 PASS 규칙으로 일반화하지 않는다.

## 후속 설계: 고정된 분석만 독립 검수하는 진단 1회

### 목적

후보 문장이 없는 조건에서도 분석 2의 사건 관찰·활동 판정·다음 전략을 거부하는지 확인한다.
운영 평가 구조를 바로 교체하지 않고, 분석 검수와 후보 검수를 분리할 가치가 있는지 판단할 근거를 만든다.

### 승인받을 변경 범위

테스트용 진단 실행기·합성 입력·검사만 추가한다. 운영의 프롬프트 팩토리·재시도·전달 게이트·DB·프론트엔드는 바꾸지 않는다.
진단 구현·오프라인 검사 후 실제 Elice 요청은 **1회만** 수행한다. SDK 재시도·자동 반복·다른 모델 호출은 하지 않는다.
요청 모델명은 기존 설정의 gpt-5.4-nano를 사용하고 최대 예산은 60초다.

### 입력과 출력

- 분석 2와 시나리오·상태·발화·최근 대화·관찰/독립 검수 컨텍스트를 그대로 고정한다.
- candidate_response 및 후보 표현 관련 검사만 제외한다. 분석/후보 ID와 실제 운영 데이터는 새로 만들어 의미를 바꾸지 않는다.
  추적 메타데이터에는 원래 식별자를 유지할 수 있지만 후보 대사는 모델 검수 입력에서 제외한다.
- 분석 전용 진단 프롬프트는 기존 독립 검수 기준에서 관찰 근거·활동 판정·다음 전략 부분을 추린다.
  역할극을 계속하거나 정답 후보를 생성하도록 요청하지 않는다. 별도의 진단 프롬프트/스키마 버전을 기록한다.
- native JSON Schema로 analysis_id와 세 검수 항목(observation_review, activity_review, strategy_review)을 요구한다.
- 각 항목은 SUPPORTED / UNSUPPORTED / INSUFFICIENT_CONTEXT와 제한된 reason_code, 승인된 criterion_reference 목록으로 답한다.
  SUPPORTED에는 NONE, UNSUPPORTED에는 해당 검수의 실패 사유, INSUFFICIENT_CONTEXT에는 이력 등 부족한 정보 사유를 사용한다.
- 서버는 자료형·허용 값·분석 ID·참조 범위·상태와 사유의 일관성을 검증한다. 자유로운 원문 인용을 새 로그로 수집하지 않는다.
- 이 결과는 진단 전용이며 PASS·REANALYZE·REGENERATE 라우팅이나 학습 상태 변경에 사용하지 않는다.

### 오프라인 완료 기준

- 모델에 보내는 분석과 여섯 컨텍스트가 기존 기록과 같고, 후보 대사는 들어가지 않는다.
- 가짜 제공자 기준 물리 호출이 최대 1회이며, 형식 오류나 거절에도 반복하지 않는다.
- 다른 분석 ID, 승인되지 않은 참조, 잘못된 상태·사유 조합을 거부한다.
- 컨텍스트가 부족하다는 결과를 승인이나 아동 실패로 바꾸지 않는다.

### 결과 해석

- 분석만 검수하면 승인: 기존 결합 평가에서 후보 표현이 분석 판단에 영향을 줬을 가능성을 추가 조사한다.
- 같은 분석을 계속 거부: 관찰 기준 해석·합성 입력의 이력·모델 검수 능력을 우선 조사한다.
- 정보 부족: 무엇이 부족한지를 확인해 별도 합성 케이스 설계를 검토한다. 기존 입력을 임의로 채우지 않는다.
- 다른 프롬프트·출력 계약으로 질문하는 단건 진단이므로 후보 제외 하나의 인과 효과를 증명하는 실험은 아니다.
  이 결과 하나로 운영 분리·모델 교체·판정 완화를 확정하지 않는다.

운영 평가 분리나 출력 계약의 중복 판단 제거는 진단 결과 후 별도 설계·승인 대상으로 남긴다.
반복 횟수 증가·PASS 강제·문장 일부를 키워드로 검사하는 임시 우회는 이번 권고에 포함하지 않는다.

## 승인 후 구현·실행 결과

위 범위가 승인돼 테스트용 `AnalysisReviewDiagnostic`, 진단 프롬프트·합성 입력·검사를 추가했다.
실행 명령은 `./gradlew analysisReviewDiagnostic`이며 임의 인자·다른 요청 모델명을 허용하지 않는다.
출력 폴더가 이미 존재하면 실 API 호출 전에 중단한다. 운영 Java 코드·운영 프롬프트·DB·FE는 수정하지 않았다.

검증은 새 진단 테스트 21건과 기존 회귀·진단 테스트 22건, 총 43건 통과 및 spotlessCheck 통과다.
테스트용 코드 추가에 맞는 관련 검사만 수행했으며 전체 check를 다시 실행하지 않았다.
이전 전체 검사에서 CSRF 404로 실패 후 단독 통과했던 두 업로드 테스트의 원인이 해소됐다는 의미는 아니다.

### 실 API 한 번의 결과

실행 ID는 `elice-p1-analysis-review-01`이다. 요청 모델명은 기존 gpt-5.4-nano,
응답 모델은 gpt-5.4-nano-2026-03-17로 같았다. SDK 재시도 없이 한 번 호출해 2.795초에 종료했다.
입력 토큰은 2,800, 출력 토큰은 151이며 단가가 없어 비용은 계산하지 않았다.

| 항목 | 모델 결과 | 사유 |
| --- | --- | --- |
| 사건 관찰 | SUPPORTED | NONE, 사건 목표 101 승인 기준 참조 |
| 활동 목표 IN_PROGRESS 판정 | INSUFFICIENT_CONTEXT | MISSING_ACTIVITY_CONTEXT, 사건 목표 101 승인 기준 참조 |
| 다음 감정 목표 유도 전략 | INSUFFICIENT_CONTEXT | MISSING_STRATEGY_CONTEXT, 참조 없음 |

실제 request.json을 원본과 대조해 candidate_response만 제외됐음을 확인했다.
분석 ID·후보 제외·입력 일치와 진단 코드/프롬프트의 해시는 comparison-checks.json에 기록했다.
제공자 응답은 native schema와 서버의 ID·참조 범위·상태/사유 검사를 통과했다.
VALID_DIAGNOSTIC_OUTPUT은 이 출력 계약 검사가 통과했다는 뜻이며 분석 전체나 후보의 PASS가 아니다.

### 해석

이번 진단에서 사건 관찰은 수용됐다. 기존 결합 평가에서 사건 관찰까지 거부한 결과와 다르다.
그러나 후보 제외와 함께 진단 프롬프트·출력 계약도 변경됐으므로 후보 문장이 원인이었다고 단정하지 않는다.
활동·전략에 대해서는 잘못됐다는 판정보다 정보 부족이라는 답을 얻었다.
모델은 구체적인 누락 필드가 무엇인지 지목하지 않았다. MISSING_ACTIVITY_CONTEXT나 MISSING_STRATEGY_CONTEXT를
곧바로 실제 서버의 특정 필드 누락이나 사전 이력 누락으로 해석해서는 안 된다.
정보 부족 응답을 보수적으로 택한 모델 판단일 수도 있으며 현재 기록만으로 구분할 수 없다.

단건 진단이므로 재현성·전체 평가 정확성·운영 분리 효과를 입증하지 않는다.
이번 시간은 분석·생성 없이 분석 검수만 한 번 수행한 시간이므로 기존 전체 턴과 성능 비교하지 않는다.
운영의 평가를 분리하거나 결과를 기본 승인으로 바꾸지 않았으며, 아동에게 전달할 새 후보도 만들지 않았다.

후속 작업은 추가 호출 전에 실제 운영 입력 조립과 합성 입력을 대조하고,
활동 판정·전략 검수에 필요한 확정 기준과 확인되지 않은 정보를 구체화하는 것이다.
지원 이력을 사실처럼 보충하거나 전체 완료 정책을 임의 확정하지 않는다.

실행 산출물은 `backend/build/eval/elice-p1-analysis-review-01`의 request.json, schema.json,
system-prompt.md, source.json, result.json, comparison-checks.json, summary.md에 있다.

## 근거 위치

### 후속 입력 대조에 따른 보완

`roleplay-p1-context-gap-audit-2026-11-08.md`에 서비스 입력 조립과 합성 입력의 차이를 정리했다.
합성 P1의 질문 수 1·이전 AI 대사·빈 응답 이력 조합과, 누락된 학습 순번·지원 정책 계산을 확인했다.
빈 관찰·독립 검수 이력 자체는 첫 턴에 정상일 수 있으므로 일괄적으로 누락이라 판단하지 않는다.

앞서 첨부 기획의 미정 항목을 인용한 범위는 후속 저장소 문서와 함께 읽어야 한다.
현재 application.properties와 후속 확정 문서는 SUPPORT_DECREASE_V1(목표 2개·RESET_SEQUENCE)을 명시한다.
활동 목표 달성 시 정상 완료라는 확인도 후속 문서에 있다.
이를 새로 미정으로 되돌리거나, 작은 목표의 전 항목 달성을 별도 필수 완료 조건으로 추가하지 않는다.

- [학습_대화_에이전트_종합_정리.md](</Users/jinmina/Documents/ChatGPT/서비스 기획/느링고_개발_명세/개발 명세 및 개별 Task/학습_대화_에이전트_종합_정리.md:323>)
- [03_MVP_기능_명세.md](</Users/jinmina/Documents/ChatGPT/서비스 기획/느링고_개발_명세/개발 명세 및 개별 Task/03_MVP_기능_명세.md:395>)
- [06_착수기준_완료기준_결정사항.md](</Users/jinmina/Documents/ChatGPT/서비스 기획/느링고_개발_명세/개발 명세 및 개별 Task/06_착수기준_완료기준_결정사항.md:237>)
- [캐릭터 평가 기준](</Users/jinmina/IdeaProjects/neuringoBe/backend/src/main/resources/prompts/roleplay/v2/response-evaluation-character.md:34>)
- [캐릭터 생성·지원 단서 기준](</Users/jinmina/IdeaProjects/neuringoBe/backend/src/main/resources/prompts/roleplay/v1/response-generation-character.md:20>)
- [목표 전환에 허용하는 전략](</Users/jinmina/IdeaProjects/neuringoBe/backend/src/main/java/com/neuringo/neuringobe/ai/application/prompt/RoleplaySupportDecisionRules.java:38>)
- [현재 발화의 독립성 분류](</Users/jinmina/IdeaProjects/neuringoBe/backend/src/main/java/com/neuringo/neuringobe/ai/application/prompt/RoleplayIndependenceEvidence.java:62>)
- [빈 이력을 넣는 회귀 실행 경로](</Users/jinmina/IdeaProjects/neuringoBe/backend/src/test/java/com/neuringo/neuringobe/ai/eval/RoleplayEvalRunner.java:111>)
- [기존 실측·검증 기록](</Users/jinmina/IdeaProjects/neuringoBe/backend/docs/roleplay-p1-diagnosis-2026-11-08.md:1>)
- [실제 평가 입력·출력](</Users/jinmina/IdeaProjects/neuringoBe/backend/build/eval/elice-p1-consistency-02/diagnostics.jsonl:1>)
