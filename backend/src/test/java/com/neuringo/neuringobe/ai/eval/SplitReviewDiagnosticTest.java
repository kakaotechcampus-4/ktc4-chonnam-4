package com.neuringo.neuringobe.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.prompt.EvaluationWireFixtures;
import com.neuringo.neuringobe.ai.application.prompt.SplitReviewValidation;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class SplitReviewDiagnosticTest {
    private final SplitReviewDiagnostic diagnostic = new SplitReviewDiagnostic();

    @Test
    void whitelistsAnalysisDataAndPreservesSourceContextWithoutCurrentCandidate() {
        for (var sample : diagnostic.samples()) {
            var original = sample.source().path("input");
            var modified = (ObjectNode) original.deepCopy();
            modified.put("candidate_response", "현재후보전용표식");
            modified.put("candidate_id", "현재식별자전용표식");
            modified.putObject("retry").put("correction", "후보인용재시도전용표식");
            var alternate = new SplitReviewValidation(modified);
            assertThat(alternate.analysisInput()).isEqualTo(sample.validation().analysisInput());
            assertThat(alternate.analysisSchema()).isEqualTo(sample.validation().analysisSchema());
            assertThat(alternate.analysisInput().toString())
                    .doesNotContain("전용표식", "candidate_response", "candidate_id", "retry");
            var request = diagnostic.analysisRequest(sample);
            assertThat(request.traceContext().candidateId().toString())
                    .isNotEqualTo(
                            original.path("candidate_response").path("candidate_id").asString());
            assertThat(request.systemPrompt()).doesNotContain("현재후보전용표식", "후보인용재시도전용표식");
            assertThat(sample.validation().restoreAnalysis(alternate.analysisInput()))
                    .isEqualTo(original.path("analysis_result"));
            for (String key :
                    List.of(
                            "scenario",
                            "state",
                            "learner_turn",
                            "recent_dialogue",
                            "goal_observation_context",
                            "independence_review_context",
                            "goal_evidence_so_far"))
                assertThat(alternate.analysisInput().path(key)).isEqualTo(original.path(key));
        }
    }

    @Test
    void runsTwoAnalysisCallsThenOneCandidateCallAndCombinesActualResults() {
        var requests = new ArrayList<LlmRequest>();
        var saved = new ArrayList<SplitReviewDiagnostic.Entry>();
        var report =
                diagnostic.run(
                        request -> {
                            requests.add(request);
                            return success(request, output(request).toString());
                        },
                        RoleplayTurnDeadline.start(),
                        ignored -> {},
                        saved::add);
        assertThat(report.physicalCalls()).isEqualTo(3);
        assertThat(report.analysisPairMatchesExpected()).isTrue();
        assertThat(report.candidateSkippedReason()).isNull();
        assertThat(report.stoppedReason()).isNull();
        assertThat(saved).containsExactlyElementsOf(report.stages());
        assertThat(report.stages())
                .extracting("stage")
                .containsExactly("A-analysis", "B-analysis", "A-candidate");
        assertThat(report.stages().getLast().combinedResult().canDeliver()).isTrue();
        assertThat(
                        report.stages()
                                .getLast()
                                .combinedResult()
                                .goalObservationReview()
                                .analysisId()
                                .toString())
                .isEqualTo(
                        diagnostic
                                .samples()
                                .getFirst()
                                .source()
                                .path("input")
                                .path("analysis_id")
                                .asString());
        assertThat(requests).hasSize(3);
        assertThat(requests.getLast().traceContext().candidateId()).isNotNull();
        assertThat(
                        EvalReport.JSON
                                .readTree(requests.getLast().userPrompt())
                                .path("analysis_review_approved")
                                .asBoolean())
                .isTrue();
        assertThat(requests).allSatisfy(r -> assertThat(r.currentAttempt()).isEqualTo(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false-emotion", "quote", "reference", "unknown", "wrong-goal"})
    void suppressesCandidateOnAnyAnalysisMismatchWithoutRepairOrRetry(String mode) {
        var calls = new AtomicInteger();
        var report =
                diagnostic.run(
                        request -> {
                            var body = output(request);
                            if (calls.getAndIncrement() == 0 && !mode.equals("false-emotion")) {
                                var row = (ObjectNode) body.path("observation_reviews").path("o0");
                                switch (mode) {
                                    case "quote" -> row.put("evidence_quote", "그때 좀 멍해졌어.");
                                    case "reference" ->
                                            ((ObjectNode)
                                                            body.path("activity_review")
                                                                    .path("evidence_references")
                                                                    .get(0))
                                                    .put(
                                                            "turn_id",
                                                            java.util.UUID.randomUUID().toString());
                                    case "unknown" ->
                                            ((ObjectNode) body.path("current_goal_review"))
                                                    .put("verdict", "INSUFFICIENT_CONTEXT")
                                                    .put("reason_code", "INSUFFICIENT_CONTEXT");
                                    case "wrong-goal" ->
                                            ((ObjectNode) body.path("current_goal_review"))
                                                    .put("verdict", "UNSUPPORTED")
                                                    .put("reason_code", "LEARNING_STATE_MISMATCH");
                                    default -> throw new AssertionError();
                                }
                            } else if (mode.equals("false-emotion")
                                    && body.path("observation_reviews").has("o1")) {
                                ((ObjectNode) body.path("observation_reviews").path("o1"))
                                        .put("verdict", "SUPPORTED")
                                        .put("reason_code", "MATCHED_EXPRESSION")
                                        .put("evidence_quote", "발표하다가");
                            }
                            return success(request, body.toString());
                        },
                        RoleplayTurnDeadline.start());
        assertThat(calls).hasValue(2);
        assertThat(report.physicalCalls()).isEqualTo(2);
        assertThat(report.analysisPairMatchesExpected()).isFalse();
        assertThat(report.candidateSkippedReason())
                .isEqualTo("ANALYSIS_EXPECTATION_OR_VALIDATION_FAILED");
        assertThat(report.stoppedReason()).isNull();
        assertThat(report.stages())
                .noneSatisfy(e -> assertThat(e.stage()).isEqualTo("A-candidate"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "schema", "execution"})
    void stopsImmediatelyOnTechnicalFailure(String mode) {
        var calls = new AtomicInteger();
        var report =
                diagnostic.run(
                        request -> {
                            calls.incrementAndGet();
                            if (mode.equals("execution")) throw new IllegalStateException();
                            if (mode.equals("provider"))
                                return new AiCallResult.Failure<>(
                                        new AiFailure(AiFailureType.TIMEOUT, null),
                                        metadata(request));
                            return success(
                                    request, output(request).put("decision", "PASS").toString());
                        },
                        RoleplayTurnDeadline.start());
        assertThat(calls).hasValue(1);
        assertThat(report.stoppedReason())
                .isEqualTo(
                        mode.equals("provider")
                                ? "PROVIDER_FAILURE"
                                : mode.equals("schema") ? "SCHEMA_FAILURE" : "EXECUTION_FAILURE");
        assertThat(report.physicalCalls()).isEqualTo(1);
    }

    @Test
    void sharesDeadlineAcrossCallsAndDiscardsLateCandidateResult() {
        var deadline = RoleplayTurnDeadline.start();
        var requests = new ArrayList<LlmRequest>();
        var report =
                diagnostic.run(
                        request -> {
                            requests.add(request);
                            request.callBudget().limit(java.time.Duration.ofSeconds(60));
                            if (requests.size() == 3) deadline.cancel();
                            return success(request, output(request).toString());
                        },
                        deadline);
        assertThat(report.physicalCalls()).isEqualTo(3);
        assertThat(report.stoppedReason()).isEqualTo("TIMED_OUT");
        assertThat(report.stages().getLast().combinedResult()).isNull();
        assertThat(report.stages().getLast().rawOutput()).isNotNull();
        assertThat(requests)
                .allSatisfy(
                        r ->
                                assertThatThrownBy(
                                                () ->
                                                        r.callBudget()
                                                                .limit(
                                                                        java.time.Duration
                                                                                .ofSeconds(60)))
                                        .isInstanceOf(RoleplayTurnDeadline.Expired.class));
        var cancelled =
                diagnostic.run(
                        request -> {
                            throw new AssertionError("추가 호출 금지");
                        },
                        deadline);
        assertThat(cancelled.physicalCalls()).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "analysis-id",
                "candidate-id",
                "reanalyze",
                "extra-review",
                "analysis-code",
                "pass-check",
                "pass-code",
                "failure-empty",
                "missing-revision",
                "code-check"
            })
    void rejectsCandidateScopeOrGateViolations(String mode) {
        var review = candidateReview();
        var body = candidateOutput(review.input());
        switch (mode) {
            case "analysis-id" -> body.put("analysis_id", java.util.UUID.randomUUID().toString());
            case "candidate-id" -> body.put("candidate_id", java.util.UUID.randomUUID().toString());
            case "reanalyze" -> body.put("decision", "REANALYZE");
            case "extra-review" -> body.putObject("observation_reviews");
            case "analysis-code" ->
                    body.putArray("failure_codes").add("UNSUPPORTED_CAUSE_ANALYSIS");
            case "pass-check" ->
                    ((ObjectNode) body.path("checks")).put("analysis_response_consistent", false);
            case "pass-code" -> body.putArray("failure_codes").add("TOO_LONG");
            case "failure-empty" -> body.put("decision", "CONFIRM_INPUT");
            case "missing-revision" -> {
                body.put("decision", "REGENERATE");
                body.putArray("failure_codes").add("TOO_LONG");
            }
            case "code-check" -> {
                body.put("decision", "REGENERATE");
                body.putArray("failure_codes").add("ANALYSIS_RESPONSE_MISMATCH");
                body.set("revision_instruction", revision());
            }
            default -> throw new AssertionError();
        }
        assertThatThrownBy(() -> review.validate(body.toString()))
                .isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"REGENERATE", "SAFETY_REGENERATE", "CONFIRM_INPUT", "SAFETY_ESCALATION"})
    void preservesCandidateRecoveryAndSafetyPriority(String decision) {
        var review = candidateReview();
        var body = candidateOutput(review.input());
        body.put("decision", decision);
        String code =
                switch (decision) {
                    case "REGENERATE" -> "ANALYSIS_RESPONSE_MISMATCH";
                    case "SAFETY_REGENERATE" -> "UNSAFE_EXPRESSION";
                    case "CONFIRM_INPUT" -> "INPUT_MEANING_UNCLEAR";
                    default -> "IMMEDIATE_RISK_IN_INPUT";
                };
        body.putArray("failure_codes").add(code);
        ((ObjectNode) body.path("checks"))
                .put(
                        decision.equals("REGENERATE")
                                ? "analysis_response_consistent"
                                : "safety_valid",
                        false);
        if (decision.endsWith("REGENERATE")) body.set("revision_instruction", revision());
        var result = review.validate(body.toString());
        assertThat(result.canDeliver()).isFalse();
        assertThat(result.decision().name()).isEqualTo(decision);
        assertThat(result.failureCodes()).containsExactly(code);
        assertThat(result.goalObservationReview().supportsAnalysis())
                .isEqualTo(decision.endsWith("REGENERATE"));
    }

    @Test
    void requiresRealAnalysisApprovalAndCopiesInputs() {
        var sample = diagnostic.samples().get(1);
        assertThatThrownBy(
                        () ->
                                sample.validation()
                                        .candidateReview(
                                                output(diagnostic.analysisRequest(sample))
                                                        .toString(),
                                                sample.source()
                                                        .path("input")
                                                        .path("candidate_response")))
                .isInstanceOf(RuntimeException.class);
        var review = candidateReview();
        ((ObjectNode) review.input()).put("analysis_id", "변경");
        assertThat(review.validate(candidateOutput(review.input()).toString()).canDeliver())
                .isTrue();
    }

    private SplitReviewValidation.CandidateReview candidateReview() {
        var a = diagnostic.samples().getFirst();
        return a.validation()
                .candidateReview(
                        output(diagnostic.analysisRequest(a)).toString(),
                        a.source().path("input").path("candidate_response"));
    }

    private static ObjectNode output(LlmRequest request) {
        var input = EvalReport.JSON.readTree(request.userPrompt());
        return request.promptVersion().equals(SplitReviewDiagnostic.CANDIDATE_VERSION)
                ? candidateOutput(input)
                : analysisOutput(input);
    }

    private static ObjectNode analysisOutput(JsonNode input) {
        var body = EvalReport.JSON.createObjectNode();
        body.set("analysis_id", input.path("analysis_id"));
        var rows = body.putObject("observation_reviews");
        for (String key : input.path("observation_tasks").propertyNames()) {
            var row = rows.putObject(key);
            boolean supported = key.equals("o0");
            row.put("verdict", supported ? "SUPPORTED" : "UNSUPPORTED");
            row.put("reason_code", supported ? "MATCHED_EXPRESSION" : "NO_MATCHING_EXPRESSION");
            if (supported)
                row.set("evidence_quote", input.path("learner_turn").path("canonical_utterance"));
            else row.putNull("evidence_quote");
            row.put("explanation", "합성 기준에 따라 관찰을 검수했다.");
        }
        var current =
                (ObjectNode) EvalReport.JSON.valueToTree(EvaluationWireFixtures.logicReview(true));
        current.set(
                "reviewed_micro_goal_id",
                input.path("current_goal_assessment").path("micro_goal_id"));
        current.set(
                "reviewed_learning_state",
                input.path("current_goal_assessment").path("proposed_learning_state"));
        body.set("current_goal_review", current);
        var activity =
                (ObjectNode)
                        EvalReport.JSON.valueToTree(
                                EvaluationWireFixtures.activityReview(
                                        EvalReport.JSON.createObjectNode(), true));
        activity.set(
                "reviewed_decision", input.path("activity_assessment").path("proposed_decision"));
        activity.set("evidence_references", input.path("activity_assessment").path("basis"));
        body.set("activity_review", activity);
        body.set(
                "interpretation_strategy_review",
                EvalReport.JSON.valueToTree(EvaluationWireFixtures.logicReview(true)));
        return body;
    }

    private static ObjectNode candidateOutput(JsonNode input) {
        var body = EvalReport.JSON.createObjectNode();
        body.set("analysis_id", input.path("analysis_id"));
        body.set("candidate_id", input.path("candidate_response").path("candidate_id"));
        var checks = body.putObject("checks");
        for (String key :
                List.of(
                        "format_valid",
                        "safety_valid",
                        "privacy_valid",
                        "scenario_consistent",
                        "analysis_response_consistent",
                        "goal_aligned",
                        "educationally_correct",
                        "difficulty_appropriate",
                        "conversation_coherent",
                        "answerable",
                        "non_repetitive")) checks.put(key, true);
        body.put("decision", "PASS");
        body.putArray("failure_codes");
        body.putNull("revision_instruction");
        return body;
    }

    private static ObjectNode revision() {
        var node = EvalReport.JSON.createObjectNode();
        for (String key : List.of("keep", "change", "avoid", "required"))
            node.putArray(key).add("후보의 실제 문제를 수정한다.");
        return node;
    }

    private static AiCallMetadata metadata(LlmRequest request) {
        return new AiCallMetadata(
                request.traceContext().requestId(),
                request.operation(),
                "scripted",
                "scripted",
                request.promptVersion(),
                request.responseSchemaVersion(),
                null,
                0,
                1,
                10,
                10,
                "stop");
    }

    private static AiCallResult<LlmCompletion> success(LlmRequest request, String raw) {
        return new AiCallResult.Success<>(
                new LlmCompletion(raw, "scripted", "scripted", "stop", 10, 10), metadata(request));
    }
}
