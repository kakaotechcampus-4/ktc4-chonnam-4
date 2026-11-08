package com.neuringo.neuringobe.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayPromptFactory;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** 품질 회귀 세트와 측정 실행기를 키 없이(가짜 모델) 확인한다. 실제 모델 측정은 roleplayEval 태스크로 따로 돈다. */
class RoleplayEvalRunnerTest {

    private static final RoleplayPromptFactory PROMPTS =
            new RoleplayPromptFactory(JsonMapper.builder().build(), null);
    private static final RegressionSet SET = RegressionSet.load(RegressionSet.DEFAULT_RESOURCE);

    @Test
    void oldReportsWithoutIndeterminateCountsRemainReadable() {
        var records = runner(Set.of()).run(1, c -> c.id().equals("P1"), ignored -> {});
        var record =
                (tools.jackson.databind.node.ObjectNode)
                        EvalReport.JSON.valueToTree(records.getFirst());
        record.remove("indeterminate_evaluations");
        assertThat(EvalReport.JSON.treeToValue(record, TurnRecord.class).indeterminateEvaluations())
                .isZero();
        var summary =
                (tools.jackson.databind.node.ObjectNode)
                        EvalReport.JSON.valueToTree(EvalSummary.of(run(), records));
        summary.remove("indeterminate_evaluations");
        assertThat(
                        EvalReport.JSON
                                .treeToValue(summary, EvalSummary.class)
                                .indeterminateEvaluations())
                .isZero();
    }

    @Test
    void indeterminateEvaluationIsCountedSeparatelyFromFormatAndProviderFailures() {
        var delegate = new ScriptedLlmProvider();
        var evaluations = new java.util.concurrent.atomic.AtomicInteger();
        com.neuringo.neuringobe.ai.application.port.LlmProvider provider =
                request -> {
                    var result =
                            (com.neuringo.neuringobe.ai.application.model.AiCallResult.Success<
                                            com.neuringo.neuringobe.ai.application.model
                                                    .LlmCompletion>)
                                    delegate.complete(request);
                    var completion = result.data();
                    var output =
                            (tools.jackson.databind.node.ObjectNode)
                                    EvalReport.JSON.readTree(completion.content());
                    var message = EvalReport.JSON.readTree(request.userPrompt());
                    if (request.operation() == AiOperation.CAUSE_ANALYSIS) {
                        var criterion =
                                message.path("goal_observation_context")
                                        .path("approved_criteria")
                                        .get(0);
                        var row =
                                ((tools.jackson.databind.node.ArrayNode)
                                                output.path("goal_observation")
                                                        .path("observations"))
                                        .addObject();
                        row.putObject("reference")
                                .put(
                                        "turn_id",
                                        message.path("learner_turn").path("turn_id").asString())
                                .put("micro_goal_id", criterion.path("micro_goal_id").asString())
                                .put("criterion_reference", criterion.path("reference").asString());
                        row.put("status", "NOT_EXPRESSED");
                    } else if (request.operation() == AiOperation.RESPONSE_EVALUATION
                            && evaluations.getAndIncrement() == 0) {
                        ((tools.jackson.databind.node.ObjectNode)
                                        output.path("observation_reviews").path("o0"))
                                .put("verdict", "INSUFFICIENT_CONTEXT")
                                .put("reason_code", "INSUFFICIENT_CONTEXT")
                                .put("explanation", "정보가 부족하다.");
                    }
                    return new com.neuringo.neuringobe.ai.application.model.AiCallResult.Success<>(
                            new com.neuringo.neuringobe.ai.application.model.LlmCompletion(
                                    output.toString(),
                                    completion.provider(),
                                    completion.model(),
                                    completion.finishReason(),
                                    completion.inputTokens(),
                                    completion.outputTokens()),
                            result.metadata());
                };
        var runner = new RoleplayEvalRunner(SET, provider, PROMPTS, Duration.ofSeconds(60));
        var records = runner.run(1, c -> c.id().equals("P1"), ignored -> {});
        var record = records.getFirst();
        assertThat(record.delivered()).isTrue();
        assertThat(record.llmCalls()).isEqualTo(4);
        assertThat(record.firstEvaluationPass()).isFalse();
        assertThat(record.indeterminateEvaluations()).isEqualTo(1);
        assertThat(record.formatFailures()).isEmpty();
        assertThat(record.calls()).allSatisfy(call -> assertThat(call.providerFailure()).isNull());
    }

    @Test
    void characterP1PreservesTheOriginalInputAndSelectsCharacterPrompts() {
        var character = RegressionSet.load(RegressionSet.CHARACTER_P1_RESOURCE);
        var oldCase = caseOf("P1");
        var newCase = character.cases().getFirst();
        assertThat(character.cases()).containsExactly(oldCase);
        assertThat(character.policy()).isEqualTo(SET.policy());
        var original = SET.toInput(oldCase);
        var input = character.toInput(newCase);
        assertThat(original.scenario().aiCharacter()).isNull();
        assertThat(input.scenario().aiCharacter().id()).isEqualTo("FRIEND");
        assertThat(input.state()).isEqualTo(original.state());
        assertThat(input.learnerTurn()).isEqualTo(original.learnerTurn());
        assertThat(input.recentDialogue()).isEqualTo(original.recentDialogue());
        var scenario = input.scenario();
        assertThat(
                        new RoleplayTurnInput.Scenario(
                                scenario.learningGoal(),
                                scenario.scenarioLevel(),
                                scenario.scenarioFacts(),
                                scenario.prohibitedInferences(),
                                scenario.microGoals()))
                .isEqualTo(original.scenario());

        var diagnostics = new P1Diagnostics(new ScriptedLlmProvider());
        var runner =
                new RoleplayEvalRunner(character, diagnostics, PROMPTS, Duration.ofSeconds(60));
        assertThat(runner.run(1, ignored -> true, ignored -> {}).getFirst().delivered()).isTrue();
        assertThat(RoleplayEvalMain.promptVersions(character))
                .contains(
                        RoleplayPromptFactory.CHARACTER_RESPONSE_GENERATION_PROMPT_VERSION,
                        RoleplayPromptFactory.CHARACTER_RESPONSE_EVALUATION_PROMPT_VERSION);
        assertThat(RoleplayEvalMain.promptVersions(SET))
                .contains(
                        RoleplayPromptFactory.RESPONSE_GENERATION_PROMPT_VERSION,
                        RoleplayPromptFactory.RESPONSE_EVALUATION_PROMPT_VERSION)
                .doesNotContain("character");
    }

    @Test
    void regressionSetHoldsThePocFourteenTurnsAsValidPromptInputs() {
        assertThat(SET.cases())
                .extracting(RegressionSet.Case::id)
                .containsExactly(
                        "P1",
                        "P2",
                        "NORMAL",
                        "PROFANITY",
                        "OFF_TOPIC",
                        "OFF_TOPIC_2",
                        "PII",
                        "PII_2",
                        "L1",
                        "L2",
                        "L3",
                        "L4",
                        "L5",
                        "L6");

        for (RegressionSet.Case turn : SET.cases()) {
            RoleplayTurnInput input = SET.toInput(turn);
            var prompt =
                    PROMPTS.causeAnalysis(
                            trace(input.learnerTurn().turnId()),
                            new AiAttemptContext(1, 0, 0),
                            1,
                            input,
                            null,
                            RoleplayObservationTestPlans.inputForScenario((input).scenario()));
            assertThat(prompt.request().userPrompt()).contains(turn.llmUtterance());
        }
    }

    @Test
    void piiCasesSendOnlyTheMaskedUtteranceToTheModel() {
        for (String id : List.of("PII", "PII_2")) {
            RegressionSet.Case turn = caseOf(id);
            String userPrompt =
                    PROMPTS.causeAnalysis(
                                    trace(SET.turnId(turn)),
                                    new AiAttemptContext(1, 0, 0),
                                    1,
                                    SET.toInput(turn),
                                    null,
                                    RoleplayObservationTestPlans.inputForScenario(
                                            (SET.toInput(turn)).scenario()))
                            .request()
                            .userPrompt();

            assertThat(userPrompt).doesNotContain("010-", "김민수", "햇살초등학교");
        }
    }

    @Test
    void scriptedRunDeliversEveryTurnThroughTheRetryingExecutor() {
        RoleplayEvalRunner runner = runner(Set.of());

        List<TurnRecord> records = runner.run(1, turn -> true, record -> {});

        assertThat(records).hasSize(14);
        assertThat(records)
                .allSatisfy(
                        record -> {
                            assertThat(record.outcome()).isEqualTo("DELIVERED");
                            assertThat(record.firstEvaluationPass()).isTrue();
                            assertThat(record.calls())
                                    .extracting(TurnRecord.StageCall::operation)
                                    .containsExactly(
                                            "CAUSE_ANALYSIS",
                                            "RESPONSE_GENERATION",
                                            "RESPONSE_EVALUATION");
                            assertThat(record.inputTokens()).isPositive();
                        });
        EvalSummary summary = EvalSummary.of(run(), records);
        assertThat(summary.delivered().ratio()).isEqualTo(1.0);
        assertThat(summary.formatFailure().count()).isZero();
        assertThat(summary.llmCallsPerTurn()).isEqualTo(3.0);
    }

    @Test
    void formatFailureIsRetriedAndKeptAsASampleWithTheParserReason() {
        RoleplayEvalRunner runner = runner(Set.of(AiOperation.RESPONSE_GENERATION));

        List<TurnRecord> records = runner.run(1, turn -> turn.id().equals("L1"), record -> {});

        TurnRecord record = records.getFirst();
        assertThat(record.outcome()).isEqualTo("DELIVERED");
        assertThat(record.llmCalls()).isEqualTo(4);
        assertThat(record.formatFailures())
                .singleElement()
                .satisfies(
                        failure -> {
                            assertThat(failure.operation()).isEqualTo("RESPONSE_GENERATION");
                            assertThat(failure.reason())
                                    .startsWith(
                                            "LLM output does not satisfy the required structure");
                        });
        assertThat(runner.formatFailureSamples())
                .singleElement()
                .satisfies(
                        sample -> {
                            assertThat(sample.caseId()).isEqualTo("L1");
                            assertThat(sample.content()).startsWith("```json");
                        });
        EvalSummary summary = EvalSummary.of(run(), records);
        assertThat(summary.formatFailure()).isEqualTo(new EvalSummary.Rate(1, 4));
    }

    @Test
    void passAfterAnInvalidEvaluationIsRecoverySuccessRatherThanFirstPass() {
        var record =
                runner(Set.of(AiOperation.RESPONSE_EVALUATION))
                        .run(1, turn -> turn.id().equals("P1"), ignored -> {})
                        .getFirst();
        assertThat(record.outcome()).isEqualTo("DELIVERED");
        assertThat(record.llmCalls()).isEqualTo(4);
        assertThat(record.evaluationDecisions()).containsExactly("PASS");
        assertThat(record.firstEvaluationPass()).isFalse();
        assertThat(record.formatFailures()).hasSize(1);
    }

    @Test
    void checksCompareAnalysisAndDeliveredTextWithTheExpectation() {
        RegressionSet.Case pii = caseOf("PII");
        UUID turnId = SET.turnId(pii);
        UUID goal = SET.scenario().goal("EVENT").id();
        AnalysisResult analysis =
                new AnalysisResult(
                        turnId,
                        "OFF_TOPIC_REMARK",
                        "IRRELEVANT",
                        "OFF_TOPIC",
                        new AnalysisResult.PrimaryGap("OFF_TOPIC", "딴 이야기"),
                        new AnalysisResult.NextStrategy("REDIRECT_TO_SCENARIO", goal, "돌아오기", "S0"),
                        0.9);
        CandidateResponse asksPhone =
                new CandidateResponse(
                        UUID.randomUUID(),
                        turnId,
                        "전화번호 다시 알려 줄래?",
                        "REDIRECT",
                        "REDIRECT_TO_SCENARIO",
                        goal,
                        "S0",
                        List.of());

        List<TurnRecord.Check> checks =
                RoleplayEvalRunner.checks(pii.expect(), analysis, asksPhone);

        assertThat(checks)
                .filteredOn(TurnRecord.Check::failed)
                .extracting(TurnRecord.Check::name)
                .containsExactly("forbidden_terms");
        assertThat(checks)
                .filteredOn(TurnRecord.Check::passed)
                .extracting(TurnRecord.Check::name)
                .contains("learning_state", "strategy_type", "length_le_60");
    }

    @Test
    void checksWithoutAnalysisAreNotEvaluatedAndCountAgainstTheExpectationRate() {
        List<TurnRecord.Check> checks =
                RoleplayEvalRunner.checks(caseOf("L1").expect(), null, null);

        assertThat(checks).extracting(TurnRecord.Check::status).containsOnly("NOT_EVALUATED");
    }

    @Test
    void reportFilesAreWrittenAndSummariesCanBeCompared(@TempDir Path dir) {
        RoleplayEvalRunner runner = runner(Set.of(AiOperation.CAUSE_ANALYSIS));
        List<TurnRecord> records = runner.run(2, turn -> turn.suite().equals("play"), record -> {});
        EvalSummary summary = EvalSummary.of(run(), records);

        EvalReport.writeRun(dir.resolve("a"), summary, records, runner.formatFailureSamples());

        assertThat(dir.resolve("a/turns.jsonl")).content().hasLineCount(4);
        assertThat(dir.resolve("a/format-failures.jsonl")).content().hasLineCount(4);
        assertThat(dir.resolve("a/summary.md")).content().contains("첫 판단 PASS율", "| P1 | play |");
        EvalSummary reread = EvalReport.readSummary(dir.resolve("a/summary.json"));
        assertThat(reread).isEqualTo(summary);
        assertThat(EvalReport.comparison(List.of(reread, reread)))
                .contains("| 전달 성공률 | 100.0% (4/4) | 100.0% (4/4) |");
    }

    @Test
    void latencyUsesNearestRankAndSuggestsATimeoutAboveTheTail() {
        EvalSummary.Latency latency =
                EvalSummary.Latency.of(List.of(100L, 200L, 300L, 400L, 5_000L));

        assertThat(latency.p50()).isEqualTo(300);
        assertThat(latency.p95()).isEqualTo(5_000);
        assertThat(latency.suggestedTimeout()).isEqualTo("10s");
        assertThat(EvalSummary.Latency.of(List.of()).suggestedTimeout()).isEqualTo("-");
    }

    @Test
    void characterErrorRateIgnoresSpacesAndPunctuation() {
        assertThat(SpeechRoundTrip.characterErrorRate("몰라.", "몰라")).isZero();
        assertThat(SpeechRoundTrip.characterErrorRate("그냥.", "그만")).isEqualTo(0.5);
        assertThat(SpeechRoundTrip.characterErrorRate("응.", "")).isEqualTo(1.0);
    }

    @Test
    void parsesFlagsAndValues() {
        assertThat(
                        RoleplayEvalMain.parse(
                                new String[] {"--provider", "openai", "--repeats=3", "--speech"}))
                .containsEntry("provider", "openai")
                .containsEntry("repeats", "3")
                .containsEntry("speech", "true");
        assertThat(Files.exists(Path.of("src/test/resources", RegressionSet.DEFAULT_RESOURCE)))
                .isTrue();
    }

    private static RoleplayEvalRunner runner(Set<AiOperation> formatFailureOnce) {
        return new RoleplayEvalRunner(
                SET, new ScriptedLlmProvider(formatFailureOnce), PROMPTS, Duration.ofSeconds(60));
    }

    private static EvalSummary.Run run() {
        return new EvalSummary.Run(
                "test",
                SET.setId(),
                "scripted",
                ScriptedLlmProvider.MODEL,
                "now",
                null,
                1,
                60_000,
                "v1",
                null,
                null,
                null);
    }

    private static RegressionSet.Case caseOf(String id) {
        return SET.cases().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    private static AiTraceContext trace(UUID turnId) {
        return new AiTraceContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                null,
                null,
                UUID.randomUUID(),
                turnId,
                UUID.randomUUID(),
                null);
    }
}
