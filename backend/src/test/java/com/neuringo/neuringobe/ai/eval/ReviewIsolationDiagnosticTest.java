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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

class ReviewIsolationDiagnosticTest {
    private final ReviewIsolationDiagnostic diagnostic = new ReviewIsolationDiagnostic();

    @Test
    void variesOnlyTheCandidateInputAndProjectsExactlyTheProductionReviewSchemas() {
        var absent = diagnostic.samples().getFirst().request();
        var present = diagnostic.samples().get(1).request();
        var actual = (ObjectNode) EvalReport.JSON.readTree(absent.userPrompt());
        var original = EvalReport.JSON.readTree(present.userPrompt());
        assertThat(actual.has("candidate_response")).isFalse();
        assertThat(absent.userPrompt())
                .doesNotContain(original.path("candidate_response").path("text").asString());
        actual.set("candidate_response", original.path("candidate_response"));
        assertThat(actual).isEqualTo(original);
        assertThat(absent.systemPrompt()).isEqualTo(present.systemPrompt());
        assertThat(absent.responseJsonSchema()).isEqualTo(present.responseJsonSchema());
        var sourceSchema =
                EvalReport.JSON.readTree(
                        com.neuringo.neuringobe.ai.application.prompt.AnalysisIsolationValidation
                                .schema());
        var projected = EvalReport.JSON.readTree(absent.responseJsonSchema());
        assertThat(projected.path("properties").propertyNames())
                .containsExactlyInAnyOrder(
                        "observation_reviews", "goal_observation_review", "analysis_logic_review");
        projected
                .path("properties")
                .propertyNames()
                .forEach(
                        key ->
                                assertThat(projected.path("properties").path(key))
                                        .isEqualTo(sourceSchema.path("properties").path(key)));
        assertThat(absent.userPrompt())
                .doesNotContain("expected_verdict", "expected_status", "case_id");
    }

    @Test
    void performsAtMostTwoIndependentCallsAndSavesEachResult() {
        var calls = new AtomicInteger();
        var saved = new ArrayList<ReviewIsolationDiagnostic.Entry>();
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
        assertThat(saved).containsExactlyElementsOf(report.cases());
        assertThat(report.stoppedReason()).isNull();
        assertThat(report.cases())
                .allSatisfy(
                        entry -> {
                            assertThat(entry.outcome()).isEqualTo("VALID_REVIEW");
                            assertThat(entry.checks().expectedAssessmentMatches()).isTrue();
                            assertThat(entry.metadata().inputTokens()).isEqualTo(10);
                        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "quote",
                "extra",
                "rejected",
                "indeterminate",
                "analysis-id",
                "activity-reference"
            })
    void recordsSemanticEvidenceFailuresAndStillRunsTheOtherCondition(String mode) {
        var calls = new AtomicInteger();
        var report =
                diagnostic.run(
                        request -> {
                            var body = output(request);
                            if (calls.getAndIncrement() == 0) {
                                var row = (ObjectNode) body.path("observation_reviews").get(0);
                                switch (mode) {
                                    case "quote" -> row.put("evidence_quote", "응… 발표하다가 멈췄어.");
                                    case "extra" ->
                                            ((tools.jackson.databind.node.ArrayNode)
                                                            body.path("observation_reviews"))
                                                    .add(row.deepCopy());
                                    case "rejected" ->
                                            row.put("verdict", "UNSUPPORTED")
                                                    .put("reason_code", "NO_MATCHING_EXPRESSION")
                                                    .putNull("evidence_quote");
                                    case "indeterminate" ->
                                            ((ObjectNode) body.path("analysis_logic_review"))
                                                    .put("verdict", "INSUFFICIENT_CONTEXT")
                                                    .put("reason_code", "INSUFFICIENT_CONTEXT");
                                    case "analysis-id" ->
                                            ((ObjectNode) body.path("goal_observation_review"))
                                                    .put(
                                                            "analysis_id",
                                                            java.util.UUID.randomUUID().toString());
                                    case "activity-reference" ->
                                            ((ObjectNode)
                                                            body.path("goal_observation_review")
                                                                    .path("activity_review")
                                                                    .path("evidence_references")
                                                                    .get(0))
                                                    .put(
                                                            "turn_id",
                                                            java.util.UUID.randomUUID().toString());
                                    default -> throw new AssertionError();
                                }
                            }
                            return success(request, body.toString());
                        },
                        RoleplayTurnDeadline.start(),
                        ignored -> {});
        assertThat(calls).hasValue(2);
        assertThat(report.stoppedReason()).isNull();
        assertThat(report.cases().getFirst().checks().expectedAssessmentMatches()).isFalse();
        assertThat(report.cases().get(1).checks().expectedAssessmentMatches()).isTrue();
        assertThat(report.cases().getFirst().outcome())
                .isEqualTo(
                        mode.equals("rejected")
                                ? "VALID_REVIEW"
                                : mode.equals("indeterminate")
                                        ? "INDETERMINATE_OUTPUT"
                                        : "INVALID_EVIDENCE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "schema", "unexpected"})
    void stopsOnTransportSchemaOrExecutionFailure(String mode) {
        var calls = new AtomicInteger();
        var report =
                diagnostic.run(
                        request -> {
                            calls.incrementAndGet();
                            if (mode.equals("provider"))
                                return new AiCallResult.Failure<>(
                                        new AiFailure(AiFailureType.TIMEOUT, null),
                                        metadata(request));
                            if (mode.equals("unexpected")) throw new IllegalStateException("내부 오류");
                            var body = output(request);
                            body.put("decision", "PASS");
                            return success(request, body.toString());
                        },
                        RoleplayTurnDeadline.start(),
                        ignored -> {});
        assertThat(calls).hasValue(1);
        assertThat(report.physicalCalls()).isEqualTo(1);
        assertThat(report.stoppedReason())
                .isEqualTo(
                        mode.equals("provider")
                                ? "PROVIDER_FAILURE"
                                : mode.equals("schema") ? "SCHEMA_FAILURE" : "EXECUTION_FAILURE");
    }

    @Test
    void sharesTheBudgetAndRejectsLateOrCancelledWork() {
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
        assertThat(result.cases().getFirst().checks()).isNull();
    }

    private ObjectNode output(LlmRequest request) {
        var input = EvalReport.JSON.readTree(request.userPrompt());
        var body = EvalReport.JSON.createObjectNode();
        body.set(
                "observation_reviews",
                EvalReport.JSON.valueToTree(EvaluationWireFixtures.reviews(input, true)));
        body.putObject("goal_observation_review")
                .put("analysis_id", input.path("analysis_id").asString())
                .set(
                        "activity_review",
                        EvalReport.JSON.valueToTree(
                                EvaluationWireFixtures.activityReview(input, true)));
        body.set(
                "analysis_logic_review",
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
