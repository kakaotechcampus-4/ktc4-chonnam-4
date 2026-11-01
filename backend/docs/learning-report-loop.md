# 학습 리포트·추천·후속 난이도

이 문서는 역할극이 끝난 뒤의 세 기능을 설명한다. 강사가 보는 학습 리포트(03 VS-014), 다음 목표 추천(VS-015), 다음 역할극 난이도(VS-016)다. 시나리오는 ML API(엘리스 Serverless, OpenAI 호환)로 이미 생성·승인되었고, 역할극의 목표별 결과가 남아 있다고 가정한다. 이번 변경은 도메인 규칙과 AI 호출 루프까지만 만든다. 저장·API·화면 연결은 학습 기록(`learningrecord`)과 시나리오(`scenario`) 브랜치가 develop에 들어온 뒤에 붙인다.

## 패키지와 의존 방향

S0~S3 지원 단계와 L1~L3 시나리오 난이도는 01 도메인 책임 맵에서 SCN이 정한다. 그래서 `scenario/domain`에 `SupportLevel`, `ScenarioLevel`, 목표 한 개의 결과인 `GoalOutcome`, 점수 정규화인 `GoalScoreRate`, 후속 난이도 정책인 `FollowUpDifficultyPolicy`를 두었다. 리포트 지표와 추천은 REC 몫이라 `report` 패키지에 두었다. LLM을 부르는 프롬프트·출력 검사·재시도 루프는 기존 역할극과 같은 자리인 `ai/application/prompt`와 `ai/application/report`에 두었다.

의존은 `report`에서 `scenario`와 `ai`로만 흐른다. `scenario`와 `ai`는 `report`를 모른다. 이렇게 한 이유는 후속 난이도가 리포트 화면에 보여야 하고(VS-014 "난이도 결정 근거"), 동시에 리포트가 난이도 정책을 쓰기 때문이다. 정책을 `scenario`에 두고 `report`가 가져다 쓰면 순환이 생기지 않는다. `ArchitectureTest.domainsAreFreeOfCycles`가 이 방향을 매번 검사한다.

클래스는 한 가지 일만 맡게 나눴다. 지표 계산은 `LearningMetrics.from`, 근거를 프롬프트 입력으로 바꾸는 일은 `LearningReportInputAssembler`, 생성·평가 루프는 `RetryingLearningReportGenerator`가 맡는다. `LearningReportComposer`는 이 셋을 순서대로 부르기만 한다. 바뀔 가능성이 큰 부분은 인터페이스 뒤로 뺐다. LLM 호출은 `LearningReportSteps`, 유사 목표 검색은 `GoalSimilaritySearch`, 리포트 규칙은 `ReportQualityRule`이다. 그래서 테스트는 가짜 구현으로 루프를 확인하고, 운영에서는 pgvector 같은 구현을 루프 코드 수정 없이 바꿔 끼울 수 있다.

## 점수 정규화와 후속 난이도

VS-016은 S0 시작 목표를 S0=3, S1=2, S2=1, S3=0점으로 매기고, S1·S2 시작 목표는 "추가 지원 단계 수로 정규화"하라고 정했다. `GoalScoreRate`는 이를 시작 단계에서 S3까지 남은 단계 수로 나누는 방식으로 풀었다. S0 시작이면 분모가 3이고, S1 시작이면 2, S2 시작이면 1이다. 예를 들어 S1에서 시작해 S2에서 도달하면 1/2점이다. S3 도달(정답 제공)과 미도달은 0점이고, 기술 오류·중단·응답 거부로 제외된 목표는 아예 세지 않는다. 목표마다 같은 무게로 평균한다. 이 해석(목표별 평균)은 문서에 명시된 값이 아니라서 팀 확인이 필요하다.

50%와 80% 경계는 소수 비교로 하면 0.79999… 같은 오차로 결과가 바뀔 수 있다. 분모가 1·2·3 중 하나라서, 점수에 최소공배수 6을 곱한 정수로 들고 정수끼리 비교한다. `FollowUpDifficultyPolicyTest`의 정확히 50%(유지)와 정확히 80%(상향 후보) 케이스가 이 경계를 확인한다.

`FollowUpDifficultyPolicy`는 06 확정 정책표 순서대로 판단한다. 유효 목표가 2개 미만이면 유지하고, 점수율이 50% 미만이거나 S3·미도달 목표가 유효 목표의 절반 이상이면 한 단계 내린다. 80% 이상이면서 모든 유효 목표가 S0~S2에서 도달하면 상향 후보가 되고, 후보가 2회 연속이면 올린다. L1에서 내리거나 L3에서 올리라는 판정이 나오면 단계는 그대로 두고 이유(`ALREADY_LOWEST`, `ALREADY_HIGHEST`)를 남긴다. 입력이 비었거나 같은 목표가 두 번 들어오는 등 계산할 수 없으면 `CALCULATION_FAILED`로 유지한다. 상향 후보가 아닌 결정은 연속 후보 수를 0으로 되돌리는데, 근거 부족과 계산 실패도 연속을 끊는 쪽으로 보았다. 근거 없이 난이도가 오르지 않게 하려는 선택이고, 역시 팀 확인이 필요하다. 90% 빠른 상향(DEC-014)은 `FollowUpDifficultyRules.fastUpgradeAtLeastPercent`로 켤 수 있게 했지만 V1 기본값은 꺼져 있다. 결정마다 정책 버전(`FOLLOW_UP_ROLEPLAY_V1`)을 남겨서, 같은 입력과 같은 버전이면 같은 결과가 나온다.

## 리포트 지표

AI 요약은 실패할 수 있지만 숫자는 기록에서 바로 나와야 한다. 그래서 정량 지표는 AI와 따로 `LearningMetrics`가 계산한다. 모든 지표는 0~1로 정규화하고, 분자와 분모를 함께 들고 있어서 화면에 "2/3"처럼 근거를 같이 보여 줄 수 있다. 분모가 0이면 잴 수 없는 지표라 비워 둔다.

| 지표 | 분자 / 분모 |
|---|---|
| `quizAccuracy` | 첫 응답이 정답인 문항 / 유효 문항 |
| `quizHintRecovery` | 힌트 뒤에 맞힌 문항 / 첫 응답이 틀린 유효 문항 |
| `roleplayScore` | 후속 난이도와 같은 `GoalScoreRate` |
| `goalAchievement` | S0~S2에서 도달한 목표 / 유효 목표 |
| `independentAchievement` | 시작 단계보다 지원을 더 받지 않고 도달한 목표 / 유효 목표 |

`roleplayScore`를 후속 난이도와 같은 값으로 둔 것은, 강사가 리포트에서 본 점수와 다음 난이도가 결정된 근거가 다르면 설명할 수 없기 때문이다.

## 생성과 평가를 되풀이하는 루프

리포트 문장은 LLM이 쓰지만, 그대로 강사에게 보여 주지 않는다. `RetryingLearningReportGenerator`는 초안을 쓰고, 코드 규칙으로 검사하고, 평가 모델에 점수를 받고, 통과하지 못하면 고칠 점을 붙여 다시 쓰게 한다. 이 과정을 최대 3바퀴(`MAX_ROUNDS`) 되풀이한다. 3바퀴 안에 통과하지 못한 리포트는 `Failed`가 되고, 03 VS-014의 "생성에 실패한 리포트는 강사 화면에 표시하지 않는다"에 따라 보이지 않는다. 지표는 이때도 남는다.

루프가 근거를 검사할 수 있도록 `LearningReportInputAssembler`는 근거마다 `E1`, `E2`… ID를 붙인다. 퀴즈 결과, 목표별 결과, 관찰 문장, 효과적이었던 지원, 기술 오류가 각각 근거 하나다. 기술 오류로 제외된 목표와 기술 오류 코드는 `TECHNICAL` 근거가 된다. 목표에 속한 근거에는 그 목표의 결과(`goal_status`)도 붙인다. 열린 질문만으로 도달했는지(`NO_SUPPORT_NEEDED`), 처음 준 지원으로 바로 도달했는지(`REACHED_AT_START`), 지원을 더 받았는지(`EXTRA_SUPPORT`), 답을 듣고 따라 했는지(`ANSWER_GIVEN`), 도달하지 못했는지(`NOT_REACHED`)다. 초안의 모든 항목은 근거 ID를 하나 이상 달아야 한다. 아동 이름, 표준 발화 원문, 요청·아동 ID는 프롬프트에 넣지 않는다.

코드 규칙은 평가 호출보다 먼저 돈다. 규칙에 걸리면 평가 모델을 부르지 않고 바로 다음 바퀴로 가기 때문에 비용이 줄어든다. 기본 규칙은 다섯 개다. `EvidenceReferenceRule`은 근거 ID가 비었거나 없는 ID를 쓴 항목을 막는다. `TechnicalSeparationRule`은 어려운 지점이 `TECHNICAL` 근거를 달거나 "음성 인식", "네트워크" 같은 낱말을 쓰면 막는다. `SectionPlacementRule`은 근거를 맞는 칸에 쓰게 한다. 어려움 없이 도달한 목표의 근거만 단 어려운 지점과, 지원 없이 도달한 목표의 근거만 단 효과적인 지원을 막는다. 이 규칙은 2026-10-29 첫 실측에서 혼자 도달한 목표가 어려운 지점에 들어간 초안이 평가를 통과한 뒤 더했다. `DiagnosticLanguageRule`은 "진단", "장애", "산만", "성향" 같은 단정 낱말을 막고, `LengthRule`은 요약 120자, 항목 80자, 목록당 3개 제한을 지키게 한다. 낱말 목록은 확실한 것만 담았고, 문맥이 필요한 단정은 평가 모델의 `non_diagnostic` 점수가 본다. 새 규칙은 `ReportQualityRule`을 구현해 목록에 더하면 되고 루프는 고치지 않는다.

평가 모델(`prompts/report/v3/report-review.md`)은 초안을 고쳐 쓰지 않는다. 먼저 항목마다 단 근거가 그 문장을 뒷받침하는지(`grounded`)와 맞는 칸인지(`right_section`)를 `item_checks`에 적고, 그다음 다섯 기준에 0~4점을 매긴다. 항목 확인을 먼저 시킨 것도 첫 실측 때문이다. GPT-4.1 mini가 자기 초안을 평가했을 때 14번 모두 만점을 줬는데, 그중에는 칸을 잘못 쓴 초안이 섞여 있었다. 근거에 기대는지(`grounded`), 진단·성향 단정이 없는지(`non_diagnostic`), 기술 오류를 아동의 어려움으로 쓰지 않았는지(`technical_separation`), 강사가 다음 활동에 쓸 수 있는지(`actionable`), 짧고 쉬운지(`readable`)다. 루프는 판정이 PASS이고, 항목 확인이 모두 참이고, 모든 점수가 3점 이상일 때만 통과시킨다. PASS인데 항목 확인이 하나라도 거짓이거나 점수가 3점 미만이면, 그 내용을 실패 코드(`UNGROUNDED_CLAIM`, `MISPLACED_ITEM` 등)로 바꿔 돌려보낸다. 평가 모델의 판정을 한 번 더 확인하는 이유는, 모델이 PASS를 주면서 점수를 낮게 매기는 모순된 출력을 낼 수 있기 때문이다. PASS인데 실패 코드가 있거나 REVISE인데 실패 코드가 없는 출력은 출력 검사(`LearningReportOutputParsers`)가 형식 실패로 막는다.

다시 쓸 때는 이전 초안, 실패 코드, 고칠 점(`keep`, `change`, `avoid`, `required`)을 함께 넘긴다. 규칙에서 걸렸으면 코드마다 정해 둔 문장(`ReportCodes.guidance`)을 `change`에, 규칙이 찾은 구체적인 문제(예: "없는 근거 ID: E9")를 `avoid`에 넣는다. 평가 모델이 돌려보냈으면 모델이 쓴 지시를 그대로 넘긴다.

평가 모델이 고칠 점을 객체가 아닌 문장 하나로 보내거나 `keep`·`change`를 문자열로 보내는 일이 모든 모델에서 있었다(2026-10-29 v2 실측). 그래서 출력 검사가 이 칸의 모양만 목록으로 맞추고, 판정·점수·실패 코드는 그대로 엄격하게 본다. 평가 프롬프트 v3에는 REVISE 출력 예시를 넣었다.

LLM 호출에는 출력 상한(`neuringo.ai.max-output-tokens`)과 추론 강도(`neuringo.ai.reasoning-effort`)를 설정할 수 있고, 값이 없으면 보내지 않는다. 엘리스 게이트웨이는 스트리밍이 아닌 응답의 출력을 2,000토큰까지만 허용하는데, 추론 모델은 추론 토큰도 이 상한에 들어간다. 그래서 상한을 넘기면 JSON이 중간에 잘리고, 2,000보다 큰 값을 보내면 400으로 거부된다. 실측에서는 초안 모델의 추론을 끄고 평가 모델은 `low`로 두었다.

호출 하나가 시간 초과처럼 재시도할 수 있는 이유로 실패하면 같은 입력으로 한 번 더 부른다(`MAX_CALL_ATTEMPTS` 2). 인증 오류처럼 다시 불러도 소용없는 실패는 바로 멈춘다. 그래서 최악의 경우 호출 수는 3바퀴 × 2번 × 2단계로 12번이다. 엘리스 ML API의 GPT-4.1 mini 단가(입력 1M 토큰 ₩567, 출력 1M 토큰 ₩2,268)로 계산하면, 호출 한 번을 입력 3,000토큰·출력 500토큰으로 잡았을 때 약 ₩2.8이다. 따라서 최악의 경우에도 리포트 하나에 약 ₩34이고, 첫 바퀴에 통과하면 약 ₩5.7이다.

루프는 바퀴마다 결과·실패 코드·점수를 `ReportRound`로 남기고 초안 문장은 남기지 않는다. 이 기록이 프롬프트를 고쳐 가는 근거가 된다. 프롬프트 버전을 v1에서 v2로 바꿨을 때 첫 바퀴 통과율, 평균 바퀴 수, 규칙에 걸린 비율, 평가 점수의 정규화 평균(`ReportReviewScores.normalized`)을 같은 회귀 세트에서 비교하면 된다.

## 추천과 유사도 검색

`RecommendationPolicy`는 유효 목표의 결과로 네 유형의 후보를 유형마다 최대 한 개 고른다(DEC-013 제안값). 보완(`REINFORCE`)은 도달하지 못한 목표를 먼저 고르고, 없으면 S3에서 도달한 목표를 고른다. 반복(`REPEAT`)은 시작 단계보다 지원을 가장 많이 더 받고 도달한 목표다. 적용(`APPLY`)은 지원을 더 받지 않고 도달한 첫 목표이고, 확장(`EXTEND`)은 점수율이 80% 이상이고 S3·미도달 목표가 없을 때 활동 전체를 보고 낸다. 제외된 목표는 보지 않으므로 기술 오류만으로 보완 추천이 생기지 않는다. 유효 목표가 하나도 없으면 근거 부족(`INSUFFICIENT`)으로 반복 하나만 낸다. 근거 충분성은 유효 목표가 2개 이상이고 바탕 목표에 관찰 증거가 있으면 `SUFFICIENT`, 아니면 `LIMITED`다. 표시 순서는 `RecommendationRules.order`로 바꿀 수 있다.

확장 추천에는 다음 목표 후보가 필요하다. `RecommendationComposer`는 지금 목표의 문장으로 같은 학급의 목표를 유사도 검색하고, 이미 마친 목표와 지금 목표는 뺀다. 유사도가 0.5보다 낮으면 관련이 적은 목표이고, 0.95 이상이면 표현만 다른 같은 목표라서 둘 다 후보에서 뺀다. 맞는 목표가 없으면 후보 없이 확장 추천만 보이고 강사가 직접 고른다. 두 값은 임베딩 모델에 따라 달라지는 운영값이다. 임베딩하는 문장은 학습 목표의 제목·설명뿐이고, 아동 발화는 벡터로 만들지 않는다.

지금 구현은 메모리에 벡터를 두는 `InMemoryGoalSimilaritySearch` 하나다. 학급의 목표 수가 적은 MVP라면 이 구현으로도 충분하다. 운영용 구현은 같은 `GoalSimilaritySearch` 계약으로 임베딩 모델(text-embedding-3-small, 1,536차원, 입력 1M 토큰 ₩28)과 pgvector를 붙이면 된다.

## 확인한 것

`./gradlew test`로 새 테스트 57개를 돌려 모두 통과했다. 정책 단위로는 `FollowUpDifficultyPolicyTest` 17개(04 "통합: 경계값·L1/L3·유효 목표 부족 테스트"에 해당), `LearningMetricsTest` 5개, `RecommendationPolicyTest` 9개가 있다. 루프는 `RetryingLearningReportGeneratorTest` 8개가 통과·규칙 반려·평가 반려·점수 미달·바퀴 한도·재시도·재시도 불가 실패를 확인하고, `ReportQualityRulesTest` 5개가 규칙 네 개를 확인한다. `LearningReportPromptFactoryTest` 6개는 프롬프트에 추적 ID가 없는지와 출력 검사를 본다. `LearningReportComposerTest` 2개는 실제 프롬프트와 파서를 거쳐 가짜 LLM까지 한 번에 지나가고, `RecommendationComposerTest` 5개는 유사도 경계·학급 분리·제외 목록을 본다. 함께 돌린 `ArchitectureTest` 11개와 기존 AI 테스트도 통과했다. 실제 ML API 호출은 아직 하지 않았다.

## 아직 남은 것

저장, API, 화면은 2026-10-31~11-01에 팀 앱(develop `ccfdff0`) 위로 붙였다. 리포트·추천·결정은 V25, 다음 난이도와 역할극 단독 활동의 고정 난이도는 V26이다. 역할극 완료 이벤트에서 두 단계로 만드는 흐름, 계약 API-REC-01~06, 강사 화면, 팀 코드를 고친 곳은 [roleplay-report-integration.md](roleplay-report-integration.md)에 있다. 호출 목적별 모델도 `neuringo.ai.routes`로 나눴다. 초안은 Haiku 5.5, 평가는 Gemini 4 Argon이다. 실제 모델로 끝까지 돌린 기록은 `docs/roleplay-report-e2e-2026-11-01.md`에 있다.

최초 난이도(VS-007)는 팀의 `quiz/domain/InitialDifficultyPolicy`를 그대로 쓴다. 그 정책은 난이도를 `"L1"`, `"S2"` 같은 문자열로 돌려준다. 다음 난이도 쪽 enum(`ScenarioLevel`·`SupportLevel`)과 하나로 맞출지는 아직 정하지 않았다.

임베딩 유사도 검색은 운영에 붙이지 않았다. pgvector를 쓰려면 지금 쓰는 공식 `postgres:18.6` 이미지에 확장이 없어서 이미지를 바꿔야 한다. 그래서 확장 추천은 넓힐 목표를 강사가 직접 쓴다.

정할 것은 네 가지다. 점수율을 목표별 평균으로 낼지 합계 비율로 낼지, 근거 부족·계산 실패 때 연속 상향 후보 수를 끊을지 유지할지, 추천 개수·정렬(DEC-013), 그리고 빠른 상향을 켤지(DEC-014)다.
