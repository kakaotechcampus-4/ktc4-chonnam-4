package com.neuringo.neuringobe.ai.eval;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.GoalObservationInput;
import com.neuringo.neuringobe.ai.application.prompt.PreparedPrompt;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayPromptFactory;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.structured.IndeterminateLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/** 동일한 합성 후보를 운영 평가로 최대 두 번 검수하고 관찰 판정과 서버 수용을 분리한다. */
public final class IntegratedEvaluationDiagnostic {
    private final List<Sample> samples;

    IntegratedEvaluationDiagnostic() {
        samples = new ObservationPairDiagnostic().cases().stream().map(this::prepare).toList();
    }

    IntegratedEvaluationDiagnostic(List<Sample> samples) {
        if (samples.size() != 2
                || !samples.stream().map(Sample::id).toList().equals(List.of("A", "B"))) {
            throw new IllegalArgumentException("고정 A/B 두 사례만 허용합니다.");
        }
        this.samples = List.copyOf(samples);
    }

    List<Sample> samples() {
        return samples;
    }

    Sample prepare(ObservationPairDiagnostic.Case sample) {
        var source = sample.source();
        var original = source.path("input");
        var state = original.path("state");
        // 평가 요청에는 마스코트 카운터가 없으므로 기존 생성자의 기본값을 사용하고 요청 전체를 대조한다.
        var restoredState =
                new RoleplayTurnInput.State(
                        uuid(state, "current_micro_goal_id"),
                        state.path("current_support_level").asString(),
                        state.path("current_micro_goal_support_turn").asInt(),
                        state.path("current_micro_goal_prompt_count").asInt(),
                        state.path("answer_revealed").asBoolean(),
                        state.path("turn_count").asInt(),
                        state.path("maximum_turn_count").asInt(),
                        state.path("maximum_support_turns_per_micro_goal").asInt(),
                        state.path("current_micro_goal_learning_support_turn").asInt(),
                        state.path("learning_turn_count").asInt());
        var dialogue = new ArrayList<RoleplayTurnInput.DialogueLine>();
        for (var line : original.path("recent_dialogue")) {
            dialogue.add(EvalReport.JSON.treeToValue(line, RoleplayTurnInput.DialogueLine.class));
        }
        var input =
                new RoleplayTurnInput(
                        EvalReport.JSON.treeToValue(
                                original.path("scenario"), RoleplayTurnInput.Scenario.class),
                        restoredState,
                        EvalReport.JSON.treeToValue(
                                original.path("learner_turn"), RoleplayTurnInput.LearnerTurn.class),
                        dialogue);
        var analysis =
                EvalReport.JSON.treeToValue(original.path("analysis_result"), AnalysisResult.class);
        var observations =
                EvalReport.JSON.treeToValue(
                        original.path("goal_observation_context"), GoalObservationInput.class);
        var rawCandidate = original.path("candidate_response");
        var candidate =
                new CandidateResponse(
                        uuid(rawCandidate, "candidate_id"), input.learnerTurn().turnId(),
                        rawCandidate.path("text").asString(),
                                rawCandidate.path("response_type").asString(),
                        rawCandidate.path("strategy_used").asString(),
                                uuid(rawCandidate, "target_micro_goal_id"),
                        rawCandidate.path("support_level").asString(), List.of());
        UUID session =
                UUID.nameUUIDFromBytes("integrated-evaluation-p1".getBytes(StandardCharsets.UTF_8));
        var trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        session,
                        null,
                        null,
                        null,
                        null,
                        session,
                        input.learnerTurn().turnId(),
                        uuid(source, "analysis_id"),
                        candidate.candidateId());
        var prepared =
                new RoleplayPromptFactory(EvalReport.JSON, null)
                        .responseEvaluation(
                                trace,
                                new AiAttemptContext(0, 0, 1),
                                1,
                                input,
                                analysis,
                                candidate,
                                observations);
        var actual =
                (tools.jackson.databind.node.ObjectNode)
                        EvalReport.JSON.readTree(prepared.request().userPrompt());
        var scoped =
                new com.neuringo.neuringobe.ai.application.prompt.ScopedAnalysisContext(
                        original, input, analysis, observations);
        var restored = actual.deepCopy();
        for (String key :
                List.of(
                        "observation_tasks",
                        "current_goal_assessment",
                        "activity_assessment",
                        "interpretation_strategy")) restored.remove(key);
        restored.set("analysis_result", scoped.restoreAnalysis(actual));
        if (!restored.equals(original)) {
            throw new IllegalArgumentException("고정 입력과 운영 평가 요청이 다릅니다. 호출하지 않습니다.");
        }
        return new Sample(sample.id(), source, prepared);
    }

    Report run(LlmProvider provider, RoleplayTurnDeadline deadline, Consumer<Entry> completed) {
        var entries = new ArrayList<Entry>();
        String stopped = null;
        for (var sample : samples) {
            var result = call(sample, provider, deadline);
            entries.add(result);
            completed.accept(result);
            if (java.util.Set.of("PROVIDER_FAILURE", "TIMED_OUT", "EXECUTION_FAILURE")
                            .contains(result.outcome())
                    || !result.schemaValid()) {
                stopped = result.outcome();
                break;
            }
        }
        return new Report(
                List.copyOf(entries),
                entries.stream().mapToInt(Entry::physicalCalls).sum(),
                entries.size() == 2 && entries.stream().allMatch(Entry::observationVerdictsMatch),
                entries.size() == 2
                        && entries.stream().allMatch(e -> e.outcome().equals("ACCEPTED")),
                stopped);
    }

    private Entry call(Sample sample, LlmProvider provider, RoleplayTurnDeadline deadline) {
        long started = System.nanoTime();
        var calls = new AtomicInteger();
        var raw = new AtomicReference<String>();
        var metadata = new AtomicReference<AiCallMetadata>();
        var rejection = new AtomicReference<String>();
        var executor =
                new StructuredLlmExecutor(
                        request -> {
                            calls.incrementAndGet();
                            var result = provider.complete(request);
                            if (result instanceof AiCallResult.Success<LlmCompletion> success) {
                                raw.set(success.data().content());
                                metadata.set(success.metadata());
                            } else if (result
                                    instanceof AiCallResult.Failure<LlmCompletion> failure) {
                                metadata.set(failure.metadata());
                            }
                            return result;
                        });
        String outcome;
        String failureType = null;
        EvaluationResult accepted = null;
        try {
            var result =
                    deadline.withinBudget(
                            () ->
                                    executor.execute(
                                            sample.prepared()
                                                    .request()
                                                    .withCallBudget(deadline.callBudget()),
                                            text -> {
                                                try {
                                                    return sample.prepared().parser().parse(text);
                                                } catch (InvalidLlmOutputException
                                                        | IndeterminateLlmOutputException failure) {
                                                    rejection.set(failure.getMessage());
                                                    throw failure;
                                                }
                                            }));
            if (result instanceof AiCallResult.Success<EvaluationResult> success) {
                outcome = "ACCEPTED";
                accepted = success.data();
            } else {
                var failure = (AiCallResult.Failure<EvaluationResult>) result;
                failureType = failure.failure().type().name();
                outcome =
                        switch (failure.failure().type()) {
                            case INVALID_OUTPUT_FORMAT, INDETERMINATE_OUTPUT -> failureType;
                            default -> "PROVIDER_FAILURE";
                        };
            }
        } catch (RoleplayTurnDeadline.Expired failure) {
            outcome = "TIMED_OUT";
        } catch (RuntimeException failure) {
            outcome = "EXECUTION_FAILURE";
            rejection.set("diagnostic execution failed");
        }
        JsonNode output = null;
        boolean schemaValid = false;
        try {
            output = EvalReport.JSON.readTree(raw.get());
            schemaValid =
                    output != null
                            && SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                                    .getSchema(
                                            EvalReport.JSON.readTree(
                                                    sample.prepared()
                                                            .request()
                                                            .responseJsonSchema()))
                                    .validate(output)
                                    .isEmpty();
        } catch (RuntimeException ignored) {
            // 실 API 호출을 더 하지 않고 원문과 고정된 실패 분류만 보존한다.
        }
        var scores = new ArrayList<Score>();
        if (schemaValid) {
            String eventReference =
                    new ObservationPairDiagnostic().cases().getFirst().expectedReference();
            int observationIndex = 0;
            for (var observation :
                    sample.source()
                            .path("input")
                            .path("analysis_result")
                            .path("goal_observation")
                            .path("observations")) {
                var ref = observation.path("reference");
                String criterion = ref.path("criterion_reference").asString();
                String expected = criterion.equals(eventReference) ? "SUPPORTED" : "UNSUPPORTED";
                var review = output.path("observation_reviews").path("o" + observationIndex++);
                String actual = review.path("verdict").asString();
                scores.add(new Score(criterion, expected, actual, expected.equals(actual)));
            }
        }
        return new Entry(
                sample.id(),
                outcome,
                calls.get(),
                (System.nanoTime() - started) / 1_000_000,
                metadata.get(),
                raw.get(),
                schemaValid,
                List.copyOf(scores),
                schemaValid && !scores.isEmpty() && scores.stream().allMatch(Score::matches),
                accepted,
                failureType,
                rejection.get());
    }

    private static UUID uuid(JsonNode node, String key) {
        return UUID.fromString(node.path(key).asString());
    }

    static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    record Sample(String id, JsonNode source, PreparedPrompt<EvaluationResult> prepared) {}

    record Score(String criterionReference, String expected, String actual, boolean matches) {}

    record Entry(
            String caseId,
            String outcome,
            int physicalCalls,
            long totalMs,
            AiCallMetadata metadata,
            String rawOutput,
            boolean schemaValid,
            List<Score> scores,
            boolean observationVerdictsMatch,
            EvaluationResult acceptedResult,
            String failureType,
            String rejectionReason) {}

    record Report(
            List<Entry> cases,
            int physicalCalls,
            boolean observationDiscriminationSuccess,
            boolean bothServerAccepted,
            String stoppedReason) {}

    public static void main(String[] args) throws IOException {
        if (args.length != 0) {
            throw new IllegalArgumentException("승인된 고정 A/B만 허용합니다.");
        }
        String model = RoleplayEvalMain.requireEnv("AI_MODEL");
        Path output = RoleplayEvalMain.diagnosticOutput("scoped-integrated", model);
        var diagnostic = new IntegratedEvaluationDiagnostic();
        for (var sample : diagnostic.samples()) {
            var dir = output.resolve(sample.id());
            Files.createDirectory(dir);
            var request = sample.prepared().request();
            Files.writeString(dir.resolve("source-fixture.json"), sample.source().toString());
            Files.writeString(dir.resolve("request.json"), request.userPrompt());
            Files.writeString(dir.resolve("schema.json"), request.responseJsonSchema());
            Files.writeString(dir.resolve("system-prompt.md"), request.systemPrompt());
            Files.writeString(
                    dir.resolve("source.json"),
                    EvalReport.JSON.writeValueAsString(
                            Map.of(
                                    "fixture_sha256", hash(sample.source().toString()),
                                    "request_sha256", hash(request.userPrompt()),
                                    "prompt_sha256", hash(request.systemPrompt()),
                                    "schema_sha256", hash(request.responseJsonSchema()),
                                    "prompt_version", request.promptVersion(),
                                    "schema_version", request.responseSchemaVersion())));
        }
        Files.writeString(
                output.resolve("source.json"),
                EvalReport.JSON.writeValueAsString(
                        Map.of(
                                "requested_model",
                                model,
                                "maximum_calls",
                                2,
                                "shared_budget_ms",
                                60000,
                                "sdk_max_retries",
                                0,
                                "source_resource",
                                ObservationPairDiagnostic.FIXTURE,
                                "baseline_run",
                                "elice-p1-observation-evidence-01",
                                "git_commit",
                                String.valueOf(RoleplayEvalMain.gitCommit()),
                                "uncommitted_changes",
                                true)));
        try (var context = RoleplayEvalMain.openAiContext(model)) {
            var report =
                    diagnostic.run(
                            context.getBean(LlmProvider.class),
                            RoleplayTurnDeadline.start(),
                            entry -> {
                                try {
                                    Files.writeString(
                                            output.resolve(entry.caseId()).resolve("result.json"),
                                            EvalReport.JSON.writeValueAsString(entry));
                                } catch (IOException failure) {
                                    throw new UncheckedIOException(failure);
                                }
                            });
            Files.writeString(
                    output.resolve("result.json"), EvalReport.JSON.writeValueAsString(report));
            System.out.printf(
                    "통합 평가 진단: calls=%d, observation_match=%s, server_accepted=%s, stopped=%s%n결과: %s%n",
                    report.physicalCalls(),
                    report.observationDiscriminationSuccess(),
                    report.bothServerAccepted(),
                    report.stoppedReason(),
                    output.toAbsolutePath());
        }
    }
}
