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
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

class ScopedReviewDiagnosticTest {
    private final ScopedReviewDiagnostic diagnostic = new ScopedReviewDiagnostic();

    @Test
    void preservesEveryAnalysisFieldAndKeepsCurrentNextAndActivityScopesDistinct() {
        for (var sample : diagnostic.samples()) {
            var view = sample.validation().input();
            var original = sample.source().path("input");
            assertThat(sample.validation().restoreAnalysis(view))
                    .isEqualTo(original.path("analysis_result"));
            assertThat(view.has("analysis_result")).isFalse();
            assertThat(view.has("candidate_response")).isFalse();
            var restored = (ObjectNode) view.deepCopy();
            for (String key :
                    List.of(
                            "observation_tasks",
                            "current_goal_assessment",
                            "activity_assessment",
                            "interpretation_strategy")) restored.remove(key);
            var baseline = (ObjectNode) original.deepCopy();
            baseline.remove("candidate_response");
            baseline.remove("analysis_result");
            assertThat(restored).isEqualTo(baseline);
            assertThat(view.path("current_goal_assessment").path("micro_goal_id"))
                    .isEqualTo(original.path("state").path("current_micro_goal_id"));
            assertThat(view.path("current_goal_assessment").path("micro_goal_id"))
                    .isNotEqualTo(
                            view.path("interpretation_strategy")
                                    .path("next_strategy")
                                    .path("target_micro_goal_id"));
            assertThat(view.path("activity_assessment").path("proposed_decision").asString())
                    .isEqualTo("IN_PROGRESS");
            assertThat(
                            view.path("current_goal_assessment")
                                    .path("proposed_learning_state")
                                    .asString())
                    .isEqualTo("GOAL_ACHIEVED");
            assertThat(sample.request().userPrompt())
                    .doesNotContain("expected_verdict", "expected_status", "case_id");
        }
        assertThat(diagnostic.samples().getFirst().request().systemPrompt())
                .isEqualTo(diagnostic.samples().get(1).request().systemPrompt());
        assertThat(diagnostic.samples().getFirst().validation().bindings().propertyNames())
                .containsExactly("o0");
        assertThat(diagnostic.samples().get(1).validation().bindings().propertyNames())
                .containsExactly("o0", "o1");
    }

    @Test
    void mapsOnlyRequestedReviewKeysAndAcceptsAWhileRejectingOnlyTheFalseObservationInB() {
        var calls = new AtomicInteger();
        var saved = new ArrayList<ScopedReviewDiagnostic.Entry>();
        var report =
                diagnostic.run(
                        request -> {
                            calls.incrementAndGet();
                            return success(request, output(request).toString());
                        },
                        RoleplayTurnDeadline.start(),
                        saved::add);
        assertThat(report.physicalCalls()).isEqualTo(2);
        assertThat(calls).hasValue(2);
        assertThat(saved).containsExactlyElementsOf(report.cases());
        assertThat(report.stoppedReason()).isNull();
        assertThat(report.cases())
                .allSatisfy(
                        entry -> {
                            assertThat(entry.outcome()).isEqualTo("VALID_REVIEW");
                            assertThat(entry.checks().currentGoalSupported()).isTrue();
                            assertThat(entry.checks().activitySupported()).isTrue();
                            assertThat(entry.checks().interpretationStrategySupported()).isTrue();
                        });
        assertThat(report.cases().getFirst().checks().observationsSupported()).isTrue();
        assertThat(report.cases().get(1).checks().observationsSupported()).isFalse();
        assertThat(report.cases().get(1).checks().observationVerdicts())
                .extracting("verdict")
                .containsExactly("SUPPORTED", "UNSUPPORTED");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "extra",
                "missing",
                "analysis-id",
                "current-id",
                "current-state",
                "activity-state",
                "strategy-state-reason"
            })
    void rejectsOutOfScopeKeysIdsAndStatesAtTheSchemaBoundary(String mode) {
        var sample = diagnostic.samples().getFirst();
        var body = output(sample.request());
        switch (mode) {
            case "extra" ->
                    ((ObjectNode) body.path("observation_reviews"))
                            .set("o1", body.path("observation_reviews").path("o0").deepCopy());
            case "missing" -> ((ObjectNode) body.path("observation_reviews")).remove("o0");
            case "analysis-id" -> body.put("analysis_id", java.util.UUID.randomUUID().toString());
            case "current-id" ->
                    ((ObjectNode) body.path("current_goal_review"))
                            .put("reviewed_micro_goal_id", java.util.UUID.randomUUID().toString());
            case "current-state" ->
                    ((ObjectNode) body.path("current_goal_review"))
                            .put("reviewed_learning_state", "IN_PROGRESS");
            case "activity-state" ->
                    ((ObjectNode) body.path("activity_review"))
                            .put("reviewed_decision", "ACHIEVED");
            case "strategy-state-reason" ->
                    ((ObjectNode) body.path("interpretation_strategy_review"))
                            .put("reason_code", "LEARNING_STATE_MISMATCH");
            default -> throw new AssertionError();
        }
        assertThat(sample.validation().validate(body.toString()).outcome())
                .isEqualTo("SCHEMA_FAILURE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"quote", "reference", "reason", "indeterminate", "rejected"})
    void reusesEvidenceRulesAndContinuesThePairAfterSemanticResults(String mode) {
        var calls = new AtomicInteger();
        var report =
                diagnostic.run(
                        request -> {
                            var body = output(request);
                            if (calls.getAndIncrement() == 0) {
                                switch (mode) {
                                    case "quote" ->
                                            ((ObjectNode)
                                                            body.path("observation_reviews")
                                                                    .path("o0"))
                                                    .put("evidence_quote", "응… 발표하다가 멈췄어.");
                                    case "reference" ->
                                            ((ObjectNode)
                                                            body.path("activity_review")
                                                                    .path("evidence_references")
                                                                    .get(0))
                                                    .put(
                                                            "turn_id",
                                                            java.util.UUID.randomUUID().toString());
                                    case "reason" ->
                                            ((ObjectNode) body.path("current_goal_review"))
                                                    .put("reason_code", "LEARNING_STATE_MISMATCH");
                                    case "indeterminate" ->
                                            ((ObjectNode) body.path("current_goal_review"))
                                                    .put("verdict", "INSUFFICIENT_CONTEXT")
                                                    .put("reason_code", "INSUFFICIENT_CONTEXT");
                                    case "rejected" ->
                                            ((ObjectNode) body.path("current_goal_review"))
                                                    .put("verdict", "UNSUPPORTED")
                                                    .put("reason_code", "LEARNING_STATE_MISMATCH");
                                    default -> throw new AssertionError();
                                }
                            }
                            return success(request, body.toString());
                        },
                        RoleplayTurnDeadline.start(),
                        ignored -> {});
        assertThat(calls).hasValue(2);
        assertThat(report.stoppedReason()).isNull();
        assertThat(report.cases().getFirst().outcome())
                .isEqualTo(
                        mode.equals("rejected")
                                ? "VALID_REVIEW"
                                : mode.equals("indeterminate")
                                        ? "INDETERMINATE_OUTPUT"
                                        : "INVALID_EVIDENCE");
        if (mode.equals("rejected"))
            assertThat(report.cases().getFirst().checks().currentGoalSupported()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "schema"})
    void stopsWithoutRetryAfterTransportOrSchemaFailure(String mode) {
        var calls = new AtomicInteger();
        var report =
                diagnostic.run(
                        request -> {
                            calls.incrementAndGet();
                            if (mode.equals("provider"))
                                return new AiCallResult.Failure<>(
                                        new AiFailure(AiFailureType.TIMEOUT, null),
                                        metadata(request));
                            var body = output(request);
                            body.put("decision", "PASS");
                            return success(request, body.toString());
                        },
                        RoleplayTurnDeadline.start(),
                        ignored -> {});
        assertThat(calls).hasValue(1);
        assertThat(report.stoppedReason())
                .isEqualTo(mode.equals("provider") ? "PROVIDER_FAILURE" : "SCHEMA_FAILURE");
    }

    @Test
    void usesTheSameDeadlineForBothCallsAndNeverAcceptsALateResult() {
        var deadline = RoleplayTurnDeadline.start();
        var requests = new ArrayList<LlmRequest>();
        diagnostic.run(
                request -> {
                    requests.add(request);
                    assertThat(request.callBudget().limit(Duration.ofSeconds(90)))
                            .isLessThanOrEqualTo(Duration.ofSeconds(60));
                    return success(request, output(request).toString());
                },
                deadline,
                ignored -> {});
        deadline.cancel();
        assertThat(requests)
                .hasSize(2)
                .allSatisfy(
                        request ->
                                assertThatThrownBy(
                                                () ->
                                                        request.callBudget()
                                                                .limit(Duration.ofSeconds(90)))
                                        .isInstanceOf(RoleplayTurnDeadline.Expired.class));
        var cancelled =
                diagnostic.run(
                        request -> {
                            throw new AssertionError("기한 종료 후 호출 금지");
                        },
                        deadline,
                        ignored -> {});
        assertThat(cancelled.physicalCalls()).isZero();
        var late = RoleplayTurnDeadline.start();
        var result =
                diagnostic.run(
                        request -> {
                            late.cancel();
                            return success(request, output(request).toString());
                        },
                        late,
                        ignored -> {});
        assertThat(result.cases().getFirst().outcome()).isEqualTo("TIMED_OUT");
        assertThat(result.cases().getFirst().checks()).isNull();
    }

    private ObjectNode output(LlmRequest request) {
        var input = EvalReport.JSON.readTree(request.userPrompt());
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

    private AiCallMetadata metadata(LlmRequest request) {
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

    private AiCallResult<LlmCompletion> success(LlmRequest request, String body) {
        return new AiCallResult.Success<>(
                new LlmCompletion(body, "scripted", "scripted", "stop", 10, 10), metadata(request));
    }
}
