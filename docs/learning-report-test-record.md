# 학습 리포트·추천·후속 난이도 — 검사 기록

이 문서는 브랜치 `feature/report-recommendation-difficulty`(아직 커밋 전)에서 무엇을 어떤 테스트로 확인했는지 적은 기록이다. 기준은 팀 노션 `03_MVP_기능_명세`의 VS-014(리포트), VS-015(추천), VS-016(후속 난이도) 인수 조건이다. 설계는 `backend/docs/learning-report-loop.md`, 실제 모델 실측 결과는 `docs/ai-quality-results-report-loop-2026-10-29.md`에 있다.

표시는 세 가지다. ✅는 테스트로 확인했다는 뜻이다. ⏸는 아직 구현하지 않아 확인할 수 없다는 뜻인데, 대부분 저장(DB)·강사 API·화면이 학습 기록·시나리오 브랜치 머지 뒤로 미뤄져 있어서다. 🐞는 실측에서 결함을 찾았다는 뜻이고, 고친 내용을 함께 적었다.

## 층별 테스트

검사는 세 층으로 나눴다. 도메인 단위 테스트는 외부 의존 없이 정책·지표·규칙의 계산을 본다. 애플리케이션 테스트는 프롬프트·출력 검사·생성 평가 루프를 실제 코드 그대로 지나가되 LLM만 가짜로 둔다. 실측은 엘리스 ML API의 실제 모델로 회귀 사례를 돌려 품질·비용·지연을 잰다. 저장과 HTTP가 없어서 통합(E2E) 테스트는 아직 없다.

| 층 | 테스트 클래스 | 개수 | 보는 것 |
|---|---|---|---|
| 도메인 | `FollowUpDifficultyPolicyTest` | 17 | VS-016 점수 정규화, 50·80% 경계, L1·L3 끝, 제외 목표, 계산 실패 |
| 도메인 | `LearningMetricsTest` | 5 | 리포트 정량 지표 0~1 정규화, 잴 수 없는 지표는 비움 |
| 도메인 | `RecommendationPolicyTest` | 9 | 추천 4유형 선택, 유형별 최대 1개, 근거 충분성, 기술 오류 제외 |
| 애플리케이션 | `RecommendationComposerTest` | 5 | 확장 추천의 다음 목표를 유사도로 고르기, 학급 분리, 차원 검사 |
| 애플리케이션 | `ReportQualityRulesTest` | 6 | 규칙 5개(근거 ID, 기술 오류 분리, 칸 배치, 진단 낱말, 길이) |
| 애플리케이션 | `RetryingLearningReportGeneratorTest` | 9 | 생성→규칙→평가→고쳐 쓰기 루프의 통과·반려·한도·재시도 |
| 애플리케이션 | `LearningReportPromptFactoryTest` | 8 | 프롬프트 입력 모양(추적 ID 없음), 출력 형식 검사, 고칠 점 모양 맞추기(v2.1) |
| 인프라 | `SpringAiLlmProviderTest`(1개 추가) | 1 | 출력 상한·추론 강도를 설정했을 때만 요청에 싣는지(v2.1) |
| 애플리케이션 | `LearningReportComposerTest` | 2 | 근거→지표→입력 조립→루프를 한 번에, 실패해도 지표가 남는지 |
| 실측 도구 | `ReportEvalRunnerTest` | 1 | 키 없이 실측 실행기·회귀 세트·보고서가 깨지지 않는지 |
| 실측 | `./gradlew reportEval` | 사례 7개 | 실제 모델의 통과율·평가 점수·형식 실패·비용·지연 |

새 테스트는 63개다. 2026-10-29 v2 때 위 테스트와 `ArchitectureTest`(도메인 순환 없음), 기존 AI 단위 테스트를 함께 돌려 192개가 모두 통과했고, v2.1 뒤에는 관련 단위 테스트 151개를 다시 돌려 모두 통과했다. 같은 실행에서 Spring 통합 테스트 43개(`RoleplayHttpIntegrationTest` 등)는 Docker가 꺼져 있어 테스트용 Postgres 컨테이너를 띄우지 못했다. 이번 변경과 관계없는 환경 문제이고 CI에서 돈다.

## VS-016 후속 역할극 난이도

| 인수 조건·예외 | 확인한 테스트 | 상태 |
|---|---|---|
| S0 시작은 S0=3·S1=2·S2=1·S3=0점 | `scoresSupportLevelsFromS0StartAsThreeTwoOneZero` | ✅ |
| S1·S2 시작은 추가 지원 단계 수로 정규화 | `normalizesS1AndS2StartsByRemainingSupportSteps`, `doesNotExceedFullScoreWhenSupportWasLoweredBelowStart` | ✅ 해석(시작 단계에서 S3까지 남은 단계 수로 나눔)은 팀 확인 필요 |
| 50% 미만이면 한 단계 하향 | `lowersBelowFiftyPercent` | ✅ |
| S3·미도달이 유효 목표 절반 이상이면 하향 | `lowersWhenHalfOfValidGoalsNeededAnswerOrWereMissedEvenAtFiftyPercent` | ✅ |
| 50% 이상 80% 미만은 유지 | `keepsAtExactlyFiftyPercent`, `keepsJustBelowEightyPercent` | ✅ |
| 80% 이상·모든 목표 S0~S2 도달이면 상향 후보, 2회 연속 상향 | `becomesUpgradeCandidateAtExactlyEightyPercentAndWaitsForSecond`, `highScoreWithAnAnsweredGoalIsNotAnUpgradeCandidate` | ✅ |
| 결과·점수율·연속 후보 수·정책 버전 기록 | `recordsPolicyVersionAndIsDeterministic` | ✅ 결정 객체에 담김 · ⏸ DB 저장 |
| 유효 목표 2개 미만이면 유지 | `keepsWhenFewerThanTwoValidGoalsAfterExclusions` | ✅ |
| 기술 오류·중단·응답 거부는 계산에서 제외 | `technicalErrorsDoNotLowerDifficulty`, `keepsWhenFewerThanTwoValidGoalsAfterExclusions` | ✅ |
| 계산 실패 시 현재 난이도 유지와 이유 표시 | `keepsCurrentLevelWhenInputCannotBeCalculated` | ✅ |
| 한 활동으로 두 단계 이상 바꾸지 않음 | `decisionRejectsMovingMoreThanOneLevel`, `staysAtL1WhenLoweringFromLowest`, `staysAtL3WhenRaisingFromHighest` | ✅ |
| 90% 빠른 상향은 비활성(DEC-014) | `fastUpgradeIsOffByDefaultAndRaisesOnFirstCandidateWhenEnabled` | ✅ 켜는 스위치도 확인 |
| 새 역할극 활동 생성·배정(ACT) | 없음 | ⏸ |

## VS-014 기본 학습 리포트

| 인수 조건·예외 | 확인한 테스트 | 상태 |
|---|---|---|
| 퀴즈 결과와 역할극 목표별 증거가 구분되어 보임 | `LearningMetricsTest` 5개, `LearningReportComposerTest.composesMetricsAndAReviewedReport`(근거 종류·목표 상태) | ✅ 데이터 수준 · ⏸ 화면 |
| 효과적 지원과 어려운 지점이 관찰 근거와 함께 표시 | `everyItemNeedsKnownEvidence`, `difficultiesAndSupportsMustComeFromGoalsThatNeededThem`, 실측 R1·R4 | ✅ · 🐞 v1 실측에서 칸을 잘못 쓴 리포트가 통과 → v2에서 `SectionPlacementRule`·`item_checks` 추가 |
| 난이도 결정 근거와 정책 버전 확인 | `generationSendsEvidenceMetricsAndLimitsWithoutTraceIds`(difficulty 절) | ✅ 입력에 실림 · ⏸ 화면 |
| 완료된 리포트만 조회, 강사 수정 불가 | `failsAfterMaxRoundsWithoutShowingTheReport`, `keepsMetricsEvenWhenTheReportCannotBeShown`(`displayable`) | ✅ 표시 여부 · ⏸ 조회 API |
| 표준 발화 원문을 보관·전송하지 않음 | `generationSendsEvidenceMetricsAndLimitsWithoutTraceIds`, `composesMetricsAndAReviewedReport`(아동 ID 없음) | ✅ 입력 모델에 원문 칸 없음 |
| AI 요약에 진단·성향 단정 금지 | `diagnosticWordsAreBlockedInSummaryAndItems`, 실측 R3·R7 | ✅ |
| STT·네트워크 오류를 아동의 어려움으로 표시하지 않음 | `technicalEvidenceOrWordsAreNotDifficulties`, 실측 R2 | ✅ |
| 생성 중·실패 리포트는 표시하지 않음 | `failsAfterMaxRoundsWithoutShowingTheReport`, `stopsAtNonRetryableFailure`, `failsWhenRetryableReviewFailuresReachTheCallLimit` | ✅ |
| 최근 활동·완료 여부·소요 시간·중단 정보 | 없음 | ⏸ |

## VS-015 추천

| 인수 조건·예외 | 확인한 테스트 | 상태 |
|---|---|---|
| 추천 유형·이유·관찰 근거·근거 충분성 표시 | `RecommendationPolicyTest` 9개 | ✅ |
| 기술 오류만으로 하향·보완 추천을 만들지 않음 | `technicalErrorsAloneDoNotProduceReinforcement` | ✅ |
| 진단·능력 단정 표현 금지 | 이유를 고정 코드(`Reason`)로만 냄 | ✅ 설계로 막음 |
| 강사 확정 전 다음 활동을 만들지 않음, 자동 배정 없음 | 추천 코드에 배정 호출 없음 | ✅ 설계로 막음 |
| 추천 개수·정렬은 운영값 | `picksAtMostOneCandidatePerTypeInConfiguredOrder`, `followsCustomOrder`, `rulesMustListEveryTypeOnce` | ✅ |
| 확장 추천의 다음 목표(유사도 검색, 팀 결정 밖 제안) | `RecommendationComposerTest` 5개, 실측 `--embeddings`(v2) | ✅ 메모리 구현·임베딩 실측 · 🐞 같은 목표가 0.949로 기준 0.95를 못 넘음 → 0.90으로 낮춤, 유사도만으로는 쉬움·어려움 방향을 못 고름(설계 남음) · ⏸ pgvector |
| 강사 채택·수정 채택·보류·거절·직접 입력, 목표 계보 연결 | 없음 | ⏸ GOAL 도메인 |

## 리포트 루프 자체

루프는 인수 조건에 직접 나오지 않지만, VS-014의 "진단 단정 금지"와 "실패 리포트 비표시"를 지키는 장치라서 따로 확인했다.

| 동작 | 확인한 테스트 | 상태 |
|---|---|---|
| 첫 초안이 규칙·평가를 통과하면 끝남 | `completesWhenFirstDraftPassesRulesAndReview` | ✅ |
| 규칙에 걸리면 평가를 부르지 않고 문제를 다음 초안에 넘김 | `ruleViolationSkipsReviewAndFeedsTheIssueIntoTheNextDraft` | ✅ |
| 평가가 돌려보낸 지시를 다음 초안에 그대로 넘김 | `reviewRevisionInstructionIsPassedToTheNextDraft` | ✅ |
| PASS인데 점수 3점 미만이면 다시 씀 | `passBelowMinimumScoreIsTreatedAsRevision` | ✅ |
| PASS인데 항목 확인이 하나라도 실패하면 다시 씀(v2) | `passWithAFailedItemCheckIsTreatedAsRevision` | ✅ |
| 3바퀴 안에 통과 못 하면 실패 | `failsAfterMaxRoundsWithoutShowingTheReport` | ✅ |
| 재시도할 수 있는 호출 실패는 한 번 더, 아니면 바로 멈춤 | `retriesARetryableCallFailureOnceWithTheSameRevision`, `stopsAtNonRetryableFailure`, `failsWhenRetryableReviewFailuresReachTheCallLimit` | ✅ |
| 판정과 실패 코드가 어긋난 평가 출력은 형식 실패 | `reviewParserRejectsInconsistentDecisionsAndCoercedScores`, `reviewParserReadsItemChecksAndRejectsUnknownSections` | ✅ |
| 문자열 "4" 같은 형 변환을 받아 주지 않음 | `reviewParserRejectsInconsistentDecisionsAndCoercedScores` | ✅ |
| 고칠 점이 문장 하나나 문자열 칸으로 와도 목록으로 맞춤, 판정·점수는 그대로 엄격(v2.1) | `reviewParserReshapesRevisionInstructionSentAsText` | ✅ · 🐞 v2 실측에서 모든 평가 모델이 이 모양으로 보내 돌려보낸 평가가 형식 실패로 집계됨 → v2.1 실측 세 조합 형식 실패 0 |
| 출력 상한·추론 강도는 설정했을 때만 요청에 실림(v2.1) | `SpringAiLlmProviderTest.sendsOutputLimitAndReasoningEffortOnlyWhenConfigured` | ✅ · 🐞 v2 실측에서 추론 토큰이 엘리스 게이트웨이의 2,000토큰 상한에 닿아 JSON 이 잘림 → v2.1 실측 호출 142번 모두 `STOP` |
| 약한 초안도 평가·고쳐 쓰기로 통과 수준까지 올라감 | 실측 v2.1 mini41 / sonnet55 | ✅ 첫 바퀴 통과 2/14 → 최종 13/14(평가 반려 17번) |

## 역할극 기록에서 목표별 결과 만들기 (2026-10-30)

팀 develop 이 역할극 답마다 남기는 값(작은 목표, 지원 단계, 학습 상태)과 같은 모양의 기록을 받아 리포트·다음 난이도·추천의 입력으로 바꾸는 변환기를 더했다. 새 테스트는 23개다(변환기 12, 시뮬레이션 엔진 3, 무작위 불변식 8). 다회차 흐름은 `./gradlew roleplaySim` 으로 쟀고, 결과는 `docs/roleplay-simulation-2026-10-30.md` 에 있다.

| 동작 | 확인한 테스트 | 상태 |
|---|---|---|
| 처음 달성한 답의 지원 단계가 최종 지원, 더 받은 지원이 효과적인 지원 | `reachedGoalEndsAtTheSupportWhereItWasFirstAchieved` | ✅ |
| S3 에서 못 해내면 미도달·S3, 따라 말하면 도달·S3(점수 0) | `answerRevealedWithoutAchievementIsNotReachedAtS3`, `repeatingTheRevealedAnswerIsReachedAtS3AndCountsAsAnswerSupport` | ✅ |
| 묻지 못한 목표는 중단, 판단 불가만 있으면 기술 오류, 거부만 있으면 거부로 제외 | `goalNeverAskedIsExcludedAsInterrupted`, `onlyUnassessableAnswersAreATechnicalExclusionNotADifficulty`, `onlyRefusalsAreExcludedAsRefused` | ✅ |
| 판단 불가가 섞이면 그 답만 무시하고 `UNCLEAR_INPUT` 을 표시 | `unassessableAnswersMixedWithRealOnesAreIgnoredForTheResult` | ✅ |
| 관찰 문장은 원문 없이 만들고 처음·마지막 반응만 남김 | `unreachedGoalEndsAtTheLastAssessableSupportAndKeepsFirstAndLastObservations` | ✅ |
| 기술 오류를 난이도 근거에서 뺌(VS-016) | 시뮬레이션 C4·C4N | ✅ 결과 단계 · 🐞 역할극 진행 단계에서 판단 불가 답이 "물은 횟수"를 써서 S3 로 밀어 하향이 늘어남(팀 진행 규칙 몫, 제안으로 남김) |
| 한 번에 한 단계만, 상향은 두 번 연속 후보 뒤(VS-016) | 시뮬레이션 C6 | ✅ 3회차에 L3, 이후 하향 없음 |
| 실제 모델 리포트가 역할극 근거만으로 통과 | 시뮬레이션 `--report` C2·C3·C4 4회차 | ✅ 12/12 통과, ₩170.3 · 문장이 지원 경로만 다시 말함(근거에 관찰 기준이 없어서) |
| 관찰 문장을 줄여도 같은 문장이 연달아 남지 않음 | `shortenedObservationsNeverRepeatTheFirstSentence`, 불변식 `summarizerMatchesItsRulesForArbitraryTurnSequences` | ✅ · 🐞 무작위 테스트 case 44 에서 A, B, A, C → A, A, C 를 찾아 고침 |
| 기준별 관찰(develop roleplay_goal_observation 과 같은 값)을 원문 없이 관찰 문장으로 씀 | `criterionObservationsSayWhatWasExpressedWithoutQuotingTheChild`, `objectParticleFollowsTheFinalConsonant` | ✅ |
| 제외 목표를 더해도 난이도·추천이 같고, 결과가 좋아지면 결정이 나빠지지 않음(VS-015·016) | 불변식 `excludedGoalsNeverChangeTheDifficultyDecisionOrRecommendations`, `aBetterResultOnOneGoalNeverMakesTheDifficultyDecisionWorse` | ✅ 각 3,000건 |
| 기준을 붙인 리포트가 내용을 담음 | 시뮬레이션 `--criteria --report` 12건 | ✅ 빈 관찰 19 → 0, 기준 내용 항목 0 → 29, 11/12 통과 · 목표 이름이 빠진 항목 몇 개 남음 |
