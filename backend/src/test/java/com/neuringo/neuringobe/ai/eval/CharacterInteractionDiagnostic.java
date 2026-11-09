package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.*;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.*;
import com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/** 정상 반응·교사식 문답·금지 추론을 현재 운영 검수로 각각 한 번만 비교한다. */
public final class CharacterInteractionDiagnostic {
    private final List<Sample> samples;

    CharacterInteractionDiagnostic() {
        var set = RegressionSet.load(RegressionSet.CHARACTER_P1_RESOURCE);
        var input = set.toInput(set.cases().getFirst());
        var observations = RoleplayObservationTestPlans.inputForScenario(input.scenario());
        var prompts = new RoleplayPromptFactory(EvalReport.JSON, null, true);
        UUID analysisId = UUID.randomUUID();
        JsonNode source;
        try (var stream =
                getClass().getResourceAsStream("/eval/character-interaction-diagnostic-v1.json")) {
            source = EvalReport.JSON.readTree(Objects.requireNonNull(stream).readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var analysis =
                prompts.causeAnalysis(
                                trace(input, analysisId, null),
                                new AiAttemptContext(0, 0, 0),
                                1,
                                input,
                                null,
                                observations)
                        .parser()
                        .parse(source.path("analysis").toString());
        var prepared = new ArrayList<Sample>();
        for (var row : source.path("candidates")) {
            var id = UUID.randomUUID();
            var trace = trace(input, analysisId, id);
            var candidate =
                    prompts.responseGeneration(
                                    trace,
                                    new AiAttemptContext(0, 0, 0),
                                    1,
                                    input,
                                    analysis,
                                    id,
                                    null)
                            .parser()
                            .parse(
                                    EvalReport.JSON
                                            .createObjectNode()
                                            .put("text", row.path("text").asString())
                                            .putNull("mascot_hint")
                                            .toString());
            prepared.add(
                    new Sample(
                            row.path("id").asString(),
                            row.path("expected").asString(),
                            prompts.responseEvaluation(
                                    trace,
                                    new AiAttemptContext(0, 0, 0),
                                    1,
                                    input,
                                    analysis,
                                    candidate,
                                    observations)));
        }
        samples = List.copyOf(prepared);
        if (!samples.stream().map(Sample::id).toList().equals(List.of("A", "B", "C")))
            throw new IllegalStateException("고정된 세 후보만 허용합니다.");
    }

    List<Sample> samples() {
        return samples;
    }

    Report run(LlmProvider provider, RoleplayTurnDeadline deadline, Consumer<Entry> completed) {
        List<Entry> entries = new ArrayList<>();
        var calls = new AtomicInteger();
        String stopped = null;
        for (var sample : samples) {
            long started = System.nanoTime();
            var raw = new AtomicReference<String>();
            var metadata = new AtomicReference<AiCallMetadata>();
            var executor =
                    new StructuredLlmExecutor(
                            request -> {
                                calls.incrementAndGet();
                                var result = provider.complete(request);
                                if (result instanceof AiCallResult.Success<LlmCompletion> success) {
                                    raw.set(success.data().content());
                                    metadata.set(success.metadata());
                                } else if (result
                                        instanceof AiCallResult.Failure<LlmCompletion> failure)
                                    metadata.set(failure.metadata());
                                return result;
                            });
            String outcome;
            EvaluationResult evaluation = null;
            try {
                var result =
                        deadline.withinBudget(
                                () ->
                                        executor.execute(
                                                sample.prepared()
                                                        .request()
                                                        .withCallBudget(deadline.callBudget()),
                                                sample.prepared().parser()));
                if (result instanceof AiCallResult.Success<EvaluationResult> success) {
                    evaluation = success.data();
                    outcome = "VALID_OUTPUT";
                } else
                    outcome =
                            ((AiCallResult.Failure<EvaluationResult>) result)
                                    .failure()
                                    .type()
                                    .name();
            } catch (RoleplayTurnDeadline.Expired e) {
                outcome = "TIMED_OUT";
            } catch (RuntimeException e) {
                outcome = "EXECUTION_FAILURE";
            }
            var entry =
                    new Entry(
                            sample.id(),
                            sample.expected(),
                            outcome,
                            (System.nanoTime() - started) / 1_000_000,
                            metadata.get(),
                            raw.get(),
                            evaluation,
                            matches(sample, evaluation, raw.get()));
            entries.add(entry);
            completed.accept(entry);
            if (!outcome.equals("VALID_OUTPUT")) {
                stopped = outcome;
                break;
            }
        }
        return new Report(
                calls.get(),
                entries.size() == 3 && entries.stream().allMatch(Entry::expectedMatch),
                List.copyOf(entries),
                stopped);
    }

    private static boolean matches(Sample sample, EvaluationResult result, String raw) {
        if (result == null) return false;
        var node = EvalReport.JSON.readTree(raw);
        if (sample.id().equals("A"))
            return result.canDeliver()
                    && node.path("character_interaction_review")
                            .path("mode")
                            .asString()
                            .equals("CHARACTER_REACTION");
        if (result.canDeliver() || result.decision() != EvaluationDecision.REGENERATE) return false;
        if (!result.goalObservationReview().supportsAnalysis()) return false;
        for (String key :
                List.of("current_goal_review", "activity_review", "interpretation_strategy_review"))
            if (!node.path(key).path("verdict").asString().equals("SUPPORTED")) return false;
        return sample.id().equals("B")
                ? result.failureCodes().contains("CHARACTER_BREAK")
                        && node.path("character_interaction_review")
                                .path("mode")
                                .asString()
                                .equals("LEARNER_QUIZ")
                        && !node.path("checks").path("conversation_coherent").asBoolean()
                : result.failureCodes().stream()
                                .anyMatch(
                                        Set.of("SCENARIO_CONTRADICTION", "PROHIBITED_INFERENCE")
                                                ::contains)
                        && !node.path("checks").path("scenario_consistent").asBoolean();
    }

    private static AiTraceContext trace(
            RoleplayTurnInput input, UUID analysisId, UUID candidateId) {
        return new AiTraceContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                input.learnerTurn().turnId(),
                analysisId,
                candidateId);
    }

    record Sample(String id, String expected, PreparedPrompt<EvaluationResult> prepared) {}

    record Entry(
            String id,
            String expected,
            String outcome,
            long wallMs,
            AiCallMetadata metadata,
            String rawOutput,
            EvaluationResult evaluation,
            boolean expectedMatch) {}

    record Report(
            int physicalCalls, boolean allExpected, List<Entry> cases, String stoppedReason) {}

    public static void main(String[] args) throws IOException {
        if (args.length != 0) throw new IllegalArgumentException("고정된 세 후보만 허용합니다.");
        String model = RoleplayEvalMain.requireEnv("AI_MODEL");
        var diagnostic = new CharacterInteractionDiagnostic();
        var output = RoleplayEvalMain.diagnosticOutput("character-interaction", model);
        for (var sample : diagnostic.samples()) {
            var dir = Files.createDirectory(output.resolve(sample.id()));
            Files.writeString(
                    dir.resolve("request.json"), sample.prepared().request().userPrompt());
            Files.writeString(
                    dir.resolve("schema.json"), sample.prepared().request().responseJsonSchema());
            Files.writeString(
                    dir.resolve("system-prompt.md"), sample.prepared().request().systemPrompt());
        }
        Files.writeString(
                output.resolve("source.json"),
                EvalReport.JSON.writeValueAsString(
                        Map.of(
                                "model",
                                model,
                                "reasoning_effort",
                                RoleplayEvalMain.env("AI_REASONING_EFFORT", "unset"),
                                "timeout",
                                RoleplayEvalMain.env("AI_TIMEOUT", "10s"),
                                "maximum_calls",
                                3,
                                "shared_budget_ms",
                                60000,
                                "sdk_max_retries",
                                0,
                                "analysis_calls",
                                0,
                                "generation_calls",
                                0,
                                "git_commit",
                                String.valueOf(RoleplayEvalMain.gitCommit()))));
        try (var context = RoleplayEvalMain.openAiContext(model)) {
            var report =
                    diagnostic.run(
                            context.getBean(LlmProvider.class),
                            RoleplayTurnDeadline.start(),
                            entry -> {
                                try {
                                    Files.writeString(
                                            output.resolve(entry.id()).resolve("result.json"),
                                            EvalReport.JSON.writeValueAsString(entry));
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                                System.out.printf(
                                        "%s %s expected_match=%s %dms%n",
                                        entry.id(),
                                        entry.outcome(),
                                        entry.expectedMatch(),
                                        entry.wallMs());
                            });
            Files.writeString(
                    output.resolve("result.json"), EvalReport.JSON.writeValueAsString(report));
            System.out.printf(
                    "검수 calls=%d all_expected=%s stopped=%s%n결과: %s%n",
                    report.physicalCalls(),
                    report.allExpected(),
                    report.stoppedReason(),
                    output.toAbsolutePath());
        }
    }
}
