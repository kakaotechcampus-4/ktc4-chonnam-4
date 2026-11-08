package com.neuringo.neuringobe.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.prompt.EvaluationWireFixtures;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

class EvidenceSelectionDiagnosticTest {
    private final EvidenceSelectionDiagnostic diagnostic = new EvidenceSelectionDiagnostic();

    @Test
    void changesOnlyTheEvidenceRepresentationAndPreservesAllOriginalDecisionsDuringResolution() {
        var original = new IntegratedEvaluationDiagnostic().samples();
        for (int index = 0; index < 2; index++) {
            var c = diagnostic.cases().get(index);
            var request = c.sample().prepared().request();
            var source = (ObjectNode) EvalReport.JSON.readTree(request.userPrompt());
            source.remove("evidence_catalog");
            assertThat(source)
                    .isEqualTo(
                            EvalReport.JSON.readTree(
                                    original.get(index).prepared().request().userPrompt()));
            assertThat(request.userPrompt())
                    .doesNotContain("expected_verdict", "expected_status", "case_id");
            var raw = output(request);
            var resolved = EvalReport.JSON.readTree(c.resolve(raw.toString()));
            assertThat(raw.path("observation_reviews").path("o0").has("evidence_quote")).isFalse();
            assertThat(resolved.path("observation_reviews").path("o0").path("evidence_quote"))
                    .isEqualTo(source.path("learner_turn").path("canonical_utterance"));
            for (String field :
                    List.of(
                            "candidate_id",
                            "analysis_id",
                            "current_goal_review",
                            "interpretation_strategy_review",
                            "checks",
                            "decision",
                            "failure_codes",
                            "revision_instruction"))
                assertThat(resolved.path(field)).isEqualTo(raw.path(field));
            var ref = c.catalog().path("activity_references").path("r0").path("reference");
            assertThat(resolved.path("activity_review").path("evidence_references").get(0))
                    .isEqualTo(ref);
            var evaluated = c.sample().prepared().parser().parse(raw.toString());
            assertThat(evaluated.canDeliver()).isEqualTo(index == 0);
            assertThat(evaluated.goalObservationReview().observationsSupported())
                    .isEqualTo(index == 0);
            if (index == 1)
                assertThat(
                                resolved.path("observation_reviews")
                                        .path("o1")
                                        .path("evidence_quote")
                                        .isNull())
                        .isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"EXPLICIT", "IMPLICIT", "NOT_EXPRESSED", "MISUNDERSTOOD", "CONTRADICTORY"})
    void constrainsVerdictReasonAndSourceTogetherWithoutFixingTheVerdictToApproval(String status) {
        var validator =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                        .getSchema(EvidenceSelectionDiagnostic.observationSchema(status));
        boolean absent = status.equals("NOT_EXPRESSED");
        var approved =
                EvalReport.JSON
                        .createObjectNode()
                        .put("verdict", "SUPPORTED")
                        .put("reason_code", absent ? "ABSENCE_CONFIRMED" : "MATCHED_EXPRESSION")
                        .put("evidence_source", absent ? "NONE" : "u0")
                        .put("explanation", "분류의 의미를 확인했다.");
        assertThat(validator.validate(approved)).isEmpty();
        approved.put("evidence_source", absent ? "u0" : "NONE");
        assertThat(validator.validate(approved)).isNotEmpty();
        var rejected =
                approved.deepCopy()
                        .put("verdict", "UNSUPPORTED")
                        .put("reason_code", "STATUS_MISMATCH")
                        .put("evidence_source", "u0");
        assertThat(validator.validate(rejected)).isEmpty();
        var unknown =
                approved.deepCopy()
                        .put("verdict", "INSUFFICIENT_CONTEXT")
                        .put("reason_code", "INSUFFICIENT_CONTEXT")
                        .put("evidence_source", "NONE");
        assertThat(validator.validate(unknown)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing-source",
                "unknown-source",
                "none-for-supported",
                "old-quote",
                "unknown-reference",
                "missing-reference",
                "reference-string"
            })
    void rejectsMissingOrInventedSelectionsInsteadOfRepairingThem(String mode) {
        var c = diagnostic.cases().getFirst();
        var body = output(c.sample().prepared().request());
        var row = (ObjectNode) body.path("observation_reviews").path("o0");
        var selection = (ObjectNode) body.path("activity_review").path("evidence_selection");
        switch (mode) {
            case "missing-source" -> row.remove("evidence_source");
            case "unknown-source" -> row.put("evidence_source", "candidate");
            case "none-for-supported" -> row.put("evidence_source", "NONE");
            case "old-quote" -> {
                row.remove("evidence_source");
                row.putNull("evidence_quote");
            }
            case "unknown-reference" -> selection.put("r99", true);
            case "missing-reference" -> selection.remove("r0");
            case "reference-string" -> selection.put("r0", "true");
            default -> throw new AssertionError();
        }
        assertThatThrownBy(() -> c.resolve(body.toString()))
                .isInstanceOf(InvalidLlmOutputException.class);
        assertThat(c.resolvedOutput()).isNull();
    }

    @Test
    void onlyUsesPrivateCatalogBindingsAndDoesNotMixTwoRequests() {
        var a = diagnostic.cases().getFirst();
        var b = diagnostic.cases().get(1);
        var body = output(a.sample().prepared().request());
        var copy = (ObjectNode) a.catalog();
        ((ObjectNode) copy.path("activity_references").path("r0").path("reference"))
                .put("criterion_reference", "허구 참조");
        assertThat(
                        EvalReport.JSON
                                .readTree(a.resolve(body.toString()))
                                .path("activity_review")
                                .path("evidence_references")
                                .get(0)
                                .path("criterion_reference")
                                .asString())
                .isNotEqualTo("허구 참조");
        assertThatThrownBy(() -> a.resolve(output(b.sample().prepared().request()).toString()))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void preservesSafetyPriorityAndDoesNotInventEvidenceForNullLearningReviews() {
        var c = diagnostic.cases().getFirst();
        var body = output(c.sample().prepared().request());
        body.put("decision", "SAFETY_ESCALATION");
        body.putArray("failure_codes").add("IMMEDIATE_RISK_IN_INPUT");
        for (String key :
                List.of(
                        "observation_reviews",
                        "current_goal_review",
                        "activity_review",
                        "interpretation_strategy_review")) body.putNull(key);
        var result = c.sample().prepared().parser().parse(body.toString());
        assertThat(result.canDeliver()).isFalse();
        assertThat(result.goalObservationReview().supportsAnalysis()).isFalse();
        assertThat(
                        EvalReport.JSON
                                .readTree(c.resolvedOutput())
                                .path("observation_reviews")
                                .isNull())
                .isTrue();
    }

    @Test
    void callsOnlyTwiceAndRetainsUsageAndIndependentNegativeControl() {
        var calls = new AtomicInteger();
        var saved = new ArrayList<IntegratedEvaluationDiagnostic.Entry>();
        var report =
                diagnostic.run(
                        request -> {
                            calls.incrementAndGet();
                            return success(request, output(request).toString());
                        },
                        RoleplayTurnDeadline.start(),
                        saved::add);
        assertThat(calls).hasValue(2);
        assertThat(report.physicalCalls()).isEqualTo(2);
        assertThat(report.bothServerAccepted()).isTrue();
        assertThat(saved).containsExactlyElementsOf(report.cases());
        assertThat(report.cases())
                .allSatisfy(c -> assertThat(c.metadata().inputTokens()).isEqualTo(10));
        assertThat(report.cases().get(1).acceptedResult().failureCodes())
                .contains("UNSUPPORTED_CAUSE_ANALYSIS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "schema", "meaning", "indeterminate"})
    void stopsOnTransportOrSchemaFailureAndContinuesOnlyPlannedSemanticComparison(String mode) {
        var calls = new AtomicInteger();
        var report =
                diagnostic.run(
                        request -> {
                            int index = calls.getAndIncrement();
                            if (index == 0 && mode.equals("provider"))
                                return new AiCallResult.Failure<>(
                                        new AiFailure(AiFailureType.TIMEOUT, null),
                                        metadata(request));
                            var body = output(request);
                            if (index == 0) {
                                if (mode.equals("schema"))
                                    ((ObjectNode) body.path("observation_reviews").path("o0"))
                                            .put("evidence_source", "NONE");
                                else if (mode.equals("meaning"))
                                    ((ObjectNode) body.path("current_goal_review"))
                                            .put("verdict", "UNSUPPORTED")
                                            .put("reason_code", "LEARNING_STATE_MISMATCH");
                                else if (mode.equals("indeterminate"))
                                    ((ObjectNode) body.path("current_goal_review"))
                                            .put("verdict", "INSUFFICIENT_CONTEXT")
                                            .put("reason_code", "INSUFFICIENT_CONTEXT");
                            }
                            return success(request, body.toString());
                        },
                        RoleplayTurnDeadline.start(),
                        ignored -> {});
        assertThat(calls).hasValue(mode.equals("provider") || mode.equals("schema") ? 1 : 2);
        assertThat(report.stoppedReason() != null)
                .isEqualTo(mode.equals("provider") || mode.equals("schema"));
        if (mode.equals("meaning") || mode.equals("indeterminate"))
            assertThat(report.cases().getFirst().acceptedResult()).isNull();
    }

    @Test
    void sharesTheBudgetAndRejectsLateWorkWithoutRetry() {
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
                            throw new AssertionError("취소 후 호출 금지");
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
    }

    private ObjectNode output(LlmRequest request) {
        var input = EvalReport.JSON.readTree(request.userPrompt());
        var body = EvalReport.JSON.createObjectNode();
        body.set("analysis_id", input.path("analysis_id"));
        body.put("candidate_id", request.traceContext().candidateId().toString());
        var rows = body.putObject("observation_reviews");
        for (String key : input.path("observation_tasks").propertyNames()) {
            boolean supported = key.equals("o0");
            rows.putObject(key)
                    .put("verdict", supported ? "SUPPORTED" : "UNSUPPORTED")
                    .put("reason_code", supported ? "MATCHED_EXPRESSION" : "NO_MATCHING_EXPRESSION")
                    .put("evidence_source", supported ? "u0" : "NONE")
                    .put("explanation", "원문을 근거로 분류를 검수했다.");
        }
        body.set(
                "current_goal_review",
                EvalReport.JSON.valueToTree(EvaluationWireFixtures.currentGoalReview(input, true)));
        var activity =
                (ObjectNode)
                        EvalReport.JSON.valueToTree(
                                EvaluationWireFixtures.activityReview(input, true));
        activity.remove("evidence_references");
        var selection = activity.putObject("evidence_selection");
        input.path("evidence_catalog")
                .path("activity_references")
                .propertyNames()
                .forEach(key -> selection.put(key, key.equals("r0")));
        body.set("activity_review", activity);
        body.set(
                "interpretation_strategy_review",
                EvalReport.JSON.valueToTree(EvaluationWireFixtures.logicReview(true)));
        var checks = body.putObject("checks");
        EvalReport.JSON
                .readTree(request.responseJsonSchema())
                .path("properties")
                .path("checks")
                .path("properties")
                .propertyNames()
                .forEach(key -> checks.put(key, true));
        body.put("decision", input.path("observation_tasks").size() == 1 ? "PASS" : "REANALYZE");
        body.putArray("failure_codes");
        body.putNull("revision_instruction");
        body.putNull("answer_directness_violation");
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
