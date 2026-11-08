package com.neuringo.neuringobe.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class AnalysisReviewDiagnosticTest {
    private final AnalysisReviewDiagnostic diagnostic = new AnalysisReviewDiagnostic();
    private final String analysisId = "8731e6c0-2b73-429c-890f-aedd9b156e79";
    private final String reference = "test-evidence:00000000-0000-0000-0000-000000000101:0";

    @Test
    void removesOnlyTheCandidateAndPreservesTheRecordedAnalysisAndAllContext() throws Exception {
        JsonNode original;
        try (var stream = getClass().getResourceAsStream("/" + AnalysisReviewDiagnostic.FIXTURE)) {
            original =
                    EvalReport.JSON.readTree(
                            new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        var request = diagnostic.request(RoleplayTurnDeadline.start(Duration.ofSeconds(60)));
        var expected = ((ObjectNode) original.path("input")).deepCopy();
        var candidate = expected.remove("candidate_response");
        assertThat(EvalReport.JSON.readTree(request.userPrompt())).isEqualTo(expected);
        assertThat(original.path("input").has("candidate_response")).isTrue();
        assertThat(request.userPrompt())
                .doesNotContain(candidate.path("text").asString(), "candidate_response");
        assertThat(request.traceContext().analysisId().toString()).isEqualTo(analysisId);
        assertThat(request.traceContext().candidateId().toString())
                .isEqualTo(original.path("candidate_id").asString());
        assertThat(request.promptVersion()).isEqualTo(AnalysisReviewDiagnostic.PROMPT_VERSION);
        assertThat(request.responseSchemaVersion())
                .isEqualTo(AnalysisReviewDiagnostic.SCHEMA_VERSION);
        assertThat(request.responseJsonSchema()).isNotBlank();
        assertThat(request.currentAttempt()).isEqualTo(1);
        assertThat(request.callBudget().limit(Duration.ofSeconds(90)))
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofSeconds(60));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"valid", "malformed", "conflict", "provider-failure", "execution-failure"})
    void providerIsCalledExactlyOnceWithoutReevaluationOrGeneration(String mode) {
        var calls = new AtomicInteger();
        var body = validOutput();
        if (mode.equals("conflict")) {
            ((ObjectNode) body.path("observation_review")).put("status", "UNSUPPORTED");
        }
        var result =
                diagnostic.run(
                        request -> {
                            calls.incrementAndGet();
                            if (mode.equals("execution-failure"))
                                throw new IllegalStateException("sensitive provider detail");
                            var metadata =
                                    new AiCallMetadata(
                                            request.traceContext().requestId(),
                                            request.operation(),
                                            "scripted",
                                            "scripted-model",
                                            request.promptVersion(),
                                            request.responseSchemaVersion(),
                                            "diagnostic-only",
                                            0,
                                            1,
                                            11,
                                            7,
                                            "stop");
                            if (mode.equals("provider-failure"))
                                return new AiCallResult.Failure<>(
                                        new AiFailure(AiFailureType.TIMEOUT, null), metadata);
                            String content =
                                    mode.equals("malformed") ? "not JSON" : body.toString();
                            return new AiCallResult.Success<>(
                                    new LlmCompletion(
                                            content, "scripted", "scripted-model", "stop", 11, 7),
                                    metadata);
                        });
        assertThat(calls).hasValue(1);
        assertThat(result.physicalCalls()).isEqualTo(1);
        assertThat(result.outcome())
                .isEqualTo(
                        switch (mode) {
                            case "valid" -> "VALID_DIAGNOSTIC_OUTPUT";
                            case "provider-failure" -> "PROVIDER_FAILURE";
                            case "execution-failure" -> "EXECUTION_FAILURE";
                            default -> "INVALID_DIAGNOSTIC_OUTPUT";
                        });
        if (mode.equals("valid")) {
            assertThat(result.review()).isEqualTo(body);
            assertThat(result.metadata().inputTokens()).isEqualTo(11);
            assertThat(result.metadata().outputTokens()).isEqualTo(7);
        } else {
            assertThat(result.review()).isNull();
            assertThat(result.error()).doesNotContain("sensitive provider detail");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "analysis-id",
                "reference",
                "duplicate-reference",
                "extra-field",
                "missing-review",
                "status",
                "reason-conflict"
            })
    void rejectsInvalidIdentityReferenceStructureAndStatusReasonPairs(String failure) {
        var body = validOutput();
        var review = (ObjectNode) body.path("observation_review");
        switch (failure) {
            case "analysis-id" -> body.put("analysis_id", "00000000-0000-0000-0000-000000000000");
            case "reference" -> review.putArray("criterion_references").add("not-approved");
            case "duplicate-reference" ->
                    review.putArray("criterion_references").add(reference).add(reference);
            case "extra-field" -> body.put("decision", "PASS");
            case "missing-review" -> body.remove("activity_review");
            case "status" -> review.put("status", "PASS");
            case "reason-conflict" -> review.put("reason_code", "OBSERVATION_NOT_GROUNDED");
            default -> throw new AssertionError(failure);
        }
        assertThatThrownBy(() -> diagnostic.validate(body.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "observation_review,UNSUPPORTED,OBSERVATION_NOT_GROUNDED",
        "activity_review,UNSUPPORTED,ACTIVITY_ASSESSMENT_NOT_GROUNDED",
        "strategy_review,UNSUPPORTED,STATE_STRATEGY_MISMATCH",
        "strategy_review,UNSUPPORTED,UNSUPPORTED_SUPPORT_LEVEL",
        "observation_review,INSUFFICIENT_CONTEXT,MISSING_SUPPORT_HISTORY",
        "activity_review,INSUFFICIENT_CONTEXT,MISSING_ACTIVITY_CONTEXT",
        "strategy_review,INSUFFICIENT_CONTEXT,MISSING_STRATEGY_CONTEXT"
    })
    void preservesUnsupportedAndUnknownOutcomesWithoutApprovingOrRouting(
            String field, String status, String reason) {
        var body = validOutput();
        ((ObjectNode) body.path(field)).put("status", status).put("reason_code", reason);
        var output = diagnostic.validate(body.toString());
        assertThat(output.path(field).path("status").asString()).isEqualTo(status);
        assertThat(output.has("decision")).isFalse();
        assertThat(output.has("safe_to_send")).isFalse();
    }

    @Test
    void arbitraryArgumentsAreRejectedBeforeStartingAProvider() {
        assertThatThrownBy(() -> AnalysisReviewDiagnostic.main(new String[] {"--repeats", "3"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ObjectNode validOutput() {
        var output = EvalReport.JSON.createObjectNode().put("analysis_id", analysisId);
        for (String key : List.of("observation_review", "activity_review", "strategy_review")) {
            var review =
                    output.putObject(key).put("status", "SUPPORTED").put("reason_code", "NONE");
            review.putArray("criterion_references").add(reference);
        }
        return output;
    }
}
