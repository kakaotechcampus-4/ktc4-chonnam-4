package com.neuringo.neuringobe.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

class ObservationPairDiagnosticTest {
    private final ObservationPairDiagnostic diagnostic = new ObservationPairDiagnostic();

    @Test
    void preservesTheMeasuredInputAndAddsOnlyOneApprovedEmotionObservation() throws Exception {
        var a = diagnostic.cases().get(0);
        var b = diagnostic.cases().get(1);
        var ra = new AnalysisReviewDiagnostic(a.source()).request(RoleplayTurnDeadline.start());
        var rb = new AnalysisReviewDiagnostic(b.source()).request(RoleplayTurnDeadline.start());
        assertThat(
                        HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(
                                                        ra.userPrompt()
                                                                .getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(a.source().path("baseline_request_sha256").asString());
        assertThat(ra.systemPrompt()).isEqualTo(rb.systemPrompt());
        assertThat(ra.responseJsonSchema()).isEqualTo(rb.responseJsonSchema());
        assertThat(ra.userPrompt())
                .doesNotContain("candidate_response", "expected_status", "baseline_run", "case_id");
        assertThat(rb.userPrompt())
                .doesNotContain("candidate_response", "expected_status", "baseline_run", "case_id");
        var restored = b.source().deepCopy();
        var observations =
                (ArrayNode)
                        restored.path("input")
                                .path("analysis_result")
                                .path("goal_observation")
                                .path("observations");
        assertThat(observations).hasSize(2);
        var added = observations.remove(1);
        assertThat(restored).isEqualTo(a.source());
        assertThat(added.path("status").asString()).isEqualTo("EXPLICIT");
        assertThat(added.path("reference").path("turn_id"))
                .isEqualTo(a.source().path("input").path("learner_turn").path("turn_id"));
        boolean approved = false;
        for (var criterion :
                b.source()
                        .path("input")
                        .path("goal_observation_context")
                        .path("approved_criteria")) {
            if (criterion.path("reference").asString().equals(b.expectedReference())) {
                approved = true;
                assertThat(added.path("reference").path("micro_goal_id"))
                        .isEqualTo(criterion.path("micro_goal_id"));
                assertThat(criterion.path("description").asString())
                        .isEqualTo("부끄러움·불안·속상함처럼 상황에 맞는 감정을 말한다.");
            }
        }
        assertThat(approved).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "SUPPORTED,UNSUPPORTED,true",
        "UNSUPPORTED,UNSUPPORTED,false",
        "SUPPORTED,SUPPORTED,false",
        "UNSUPPORTED,SUPPORTED,false",
        "INSUFFICIENT_CONTEXT,UNSUPPORTED,false"
    })
    void scoresBothSidesAndCallsEachOnlyOnce(String a, String b, boolean expected) {
        var calls = new AtomicInteger();
        var report =
                diagnostic.run(
                        request -> {
                            int index = calls.getAndIncrement();
                            return completion(
                                    request, output(index == 0 ? a : b, index, true).toString());
                        });
        assertThat(calls).hasValue(2);
        assertThat(report.physicalCalls()).isEqualTo(2);
        assertThat(report.discriminationSuccess()).isEqualTo(expected);
        assertThat(report.stoppedReason()).isNull();
        assertThat(report.cases()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "malformed", "execution"})
    void stopsAfterAnErrorWithoutRetryingOrCallingTheOtherCase(String mode) {
        var calls = new AtomicInteger();
        var report =
                diagnostic.run(
                        request -> {
                            calls.incrementAndGet();
                            if (mode.equals("execution"))
                                throw new IllegalStateException("provider detail");
                            if (mode.equals("provider"))
                                return new AiCallResult.Failure<>(
                                        new AiFailure(AiFailureType.TIMEOUT, null),
                                        metadata(request));
                            return completion(request, "invalid JSON");
                        });
        assertThat(calls).hasValue(1);
        assertThat(report.physicalCalls()).isEqualTo(1);
        assertThat(report.cases()).hasSize(1);
        assertThat(report.stoppedReason()).isNotNull();
        assertThat(report.discriminationSuccess()).isFalse();
    }

    @Test
    void bothCallsUseTheSameDeadlineOwner() {
        var deadline = RoleplayTurnDeadline.start();
        var requests = new ArrayList<LlmRequest>();
        var report =
                diagnostic.run(
                        request -> {
                            requests.add(request);
                            assertThat(request.callBudget().limit(Duration.ofSeconds(90)))
                                    .isLessThanOrEqualTo(Duration.ofSeconds(60));
                            return completion(
                                    request,
                                    output(
                                                    requests.size() == 1
                                                            ? "SUPPORTED"
                                                            : "UNSUPPORTED",
                                                    requests.size() - 1,
                                                    true)
                                            .toString());
                        },
                        deadline,
                        ignored -> {});
        assertThat(report.physicalCalls()).isEqualTo(2);
        deadline.cancel();
        assertThat(requests)
                .allSatisfy(
                        request ->
                                assertThatThrownBy(
                                                () ->
                                                        request.callBudget()
                                                                .limit(Duration.ofSeconds(90)))
                                        .isInstanceOf(RoleplayTurnDeadline.Expired.class));
    }

    @Test
    void cancellationOrExpiryCannotStartAnotherCall() {
        var deadline = RoleplayTurnDeadline.start();
        var calls = new AtomicInteger();
        var report =
                diagnostic.run(
                        request -> {
                            calls.incrementAndGet();
                            deadline.cancel();
                            return completion(request, output("SUPPORTED", 0, true).toString());
                        },
                        deadline,
                        ignored -> {});
        assertThat(calls).hasValue(1);
        assertThat(report.physicalCalls()).isEqualTo(1);
        assertThat(report.stoppedReason()).isEqualTo("TIMED_OUT");
        var result =
                new AnalysisReviewDiagnostic()
                        .run(
                                request -> {
                                    throw new AssertionError("취소된 기한에 호출하면 안 됩니다.");
                                },
                                deadline);
        assertThat(result.physicalCalls()).isZero();
    }

    @Test
    void emptyReferencesAreReportedSeparatelyFromTheVerdict() {
        var calls = new AtomicInteger();
        var report =
                diagnostic.run(
                        request -> {
                            int index = calls.getAndIncrement();
                            return completion(
                                    request,
                                    output(index == 0 ? "SUPPORTED" : "UNSUPPORTED", index, false)
                                            .toString());
                        });
        assertThat(report.discriminationSuccess()).isTrue();
        assertThat(report.cases())
                .allSatisfy(
                        entry -> {
                            assertThat(entry.score().emptyReferences()).isTrue();
                            assertThat(entry.score().expectedReferenceCited()).isFalse();
                        });
    }

    private JsonNode output(String status, int index, boolean references) {
        var sample = diagnostic.cases().get(index);
        var node =
                EvalReport.JSON
                        .createObjectNode()
                        .put("analysis_id", sample.source().path("analysis_id").asString());
        for (String field : AnalysisReviewDiagnostic.REVIEWS) {
            var review = node.putObject(field);
            String chosen = field.equals("observation_review") ? status : "SUPPORTED";
            review.put("status", chosen)
                    .put(
                            "reason_code",
                            switch (chosen) {
                                case "UNSUPPORTED" -> "OBSERVATION_NOT_GROUNDED";
                                case "INSUFFICIENT_CONTEXT" -> "MISSING_OBSERVATION_CONTEXT";
                                default -> "NONE";
                            });
            var refs = review.putArray("criterion_references");
            if (references) refs.add(sample.expectedReference());
        }
        return node;
    }

    private AiCallMetadata metadata(LlmRequest request) {
        return new AiCallMetadata(
                request.traceContext().requestId(),
                request.operation(),
                "scripted",
                "scripted",
                request.promptVersion(),
                request.responseSchemaVersion(),
                "diagnostic-only",
                0,
                1,
                10,
                10,
                "stop");
    }

    private AiCallResult<LlmCompletion> completion(LlmRequest request, String content) {
        return new AiCallResult.Success<>(
                new LlmCompletion(content, "scripted", "scripted", "stop", 10, 10),
                metadata(request));
    }
}
