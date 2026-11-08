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
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ObservationEvidenceDiagnosticTest {
    private final ObservationEvidenceDiagnostic diagnostic = new ObservationEvidenceDiagnostic();
    private final String quote = "말을 못 찾아서 중간에 멈추고 그냥 앉았지?";

    @Test
    void keepsBothInputsAndUsesTheSameNewPromptAndSchemaWithoutSendingExpectations() {
        var cases = diagnostic.cases();
        var a =
                new ObservationEvidenceReview(cases.get(0).source())
                        .request(RoleplayTurnDeadline.start());
        var b =
                new ObservationEvidenceReview(cases.get(1).source())
                        .request(RoleplayTurnDeadline.start());
        for (int i = 0; i < 2; i++) {
            var original =
                    new AnalysisReviewDiagnostic(cases.get(i).source())
                            .request(RoleplayTurnDeadline.start());
            var request = i == 0 ? a : b;
            assertThat(request.userPrompt()).isEqualTo(original.userPrompt());
            assertThat(request.userPrompt())
                    .doesNotContain("candidate_response", "expected_verdict", "case_id");
            assertThat(request.promptVersion()).isEqualTo(ObservationEvidenceReview.PROMPT_VERSION);
            assertThat(request.responseSchemaVersion())
                    .isEqualTo(ObservationEvidenceReview.SCHEMA_VERSION);
        }
        assertThat(a.systemPrompt()).isEqualTo(b.systemPrompt());
        assertThat(a.responseJsonSchema()).isEqualTo(b.responseJsonSchema());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void acceptsCompleteReviewsWithExactQuotesOrAnAbsenceReason(int index) {
        var body = output(index, "gold");
        assertThat(
                        new ObservationEvidenceReview(diagnostic.cases().get(index).source())
                                .validate(body.toString()))
                .isEqualTo(body);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "analysis-id",
                "foreign-reference",
                "extra-approved",
                "missing",
                "duplicate",
                "quote-not-present",
                "quote-empty",
                "quote-null",
                "explanation-empty",
                "explanation-long",
                "reason-conflict",
                "mismatch-no-quote",
                "extra-field",
                "quote-type"
            })
    void rejectsMissingOrInventedEvidenceAndInconsistentOutputs(String failure) {
        var body = output(0, "gold");
        var reviews = (ArrayNode) body.path("reviews");
        var review = (ObjectNode) reviews.get(0);
        switch (failure) {
            case "analysis-id" -> body.put("analysis_id", "wrong-id");
            case "foreign-reference" -> review.put("criterion_reference", "unapproved");
            case "extra-approved" ->
                    reviews.add(
                            review.deepCopy()
                                    .put(
                                            "criterion_reference",
                                            diagnostic.cases().get(1).expectedReference()));
            case "missing" -> reviews.remove(0);
            case "duplicate" -> reviews.add(review.deepCopy());
            case "quote-not-present" -> review.put("evidence_quote", "부끄럽고 속상했어");
            case "quote-empty" -> review.put("evidence_quote", "");
            case "quote-null" -> review.putNull("evidence_quote");
            case "explanation-empty" -> review.put("explanation", " ");
            case "explanation-long" -> review.put("explanation", "가".repeat(201));
            case "reason-conflict" -> review.put("reason_code", "NO_MATCHING_EXPRESSION");
            case "mismatch-no-quote" ->
                    review.put("verdict", "UNSUPPORTED")
                            .put("reason_code", "STATUS_MISMATCH")
                            .putNull("evidence_quote");
            case "extra-field" -> body.put("decision", "PASS");
            case "quote-type" -> review.put("evidence_quote", true);
            default -> throw new AssertionError(failure);
        }
        assertThatThrownBy(
                        () ->
                                new ObservationEvidenceReview(diagnostic.cases().get(0).source())
                                        .validate(body.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"gold,true", "all-unsupported,false", "all-supported,false", "unknown,false"})
    void scoresAllThreeObservationsWithoutConfusingStructureAndMeaning(
            String mode, boolean expected) {
        var calls = new AtomicInteger();
        var report =
                diagnostic.run(
                        request ->
                                success(request, output(calls.getAndIncrement(), mode).toString()),
                        RoleplayTurnDeadline.start(),
                        ignored -> {});
        assertThat(calls).hasValue(2);
        assertThat(report.physicalCalls()).isEqualTo(2);
        assertThat(report.discriminationSuccess()).isEqualTo(expected);
        assertThat(report.cases().stream().mapToInt(e -> e.scores().size()).sum()).isEqualTo(3);
        assertThat(report.cases())
                .allSatisfy(
                        entry ->
                                assertThat(entry.result().outcome())
                                        .isEqualTo("VALID_DIAGNOSTIC_OUTPUT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "malformed", "invented-quote"})
    void abortsRemainingCallsOnProviderStructureOrQuoteFailure(String mode) {
        var calls = new AtomicInteger();
        var report =
                diagnostic.run(
                        request -> {
                            calls.incrementAndGet();
                            if (mode.equals("provider"))
                                return new AiCallResult.Failure<>(
                                        new AiFailure(AiFailureType.TIMEOUT, null),
                                        metadata(request));
                            var body = output(0, "gold");
                            ((ObjectNode) body.path("reviews").get(0))
                                    .put("evidence_quote", "존재하지 않는 원문");
                            return success(
                                    request,
                                    mode.equals("malformed") ? "not JSON" : body.toString());
                        },
                        RoleplayTurnDeadline.start(),
                        ignored -> {});
        assertThat(calls).hasValue(1);
        assertThat(report.physicalCalls()).isEqualTo(1);
        assertThat(report.cases()).hasSize(1);
        assertThat(report.stoppedReason()).isNotNull();
        assertThat(report.discriminationSuccess()).isFalse();
    }

    @Test
    void sharesOneDeadlineAcrossBothRequests() {
        var deadline = RoleplayTurnDeadline.start();
        var requests = new ArrayList<LlmRequest>();
        diagnostic.run(
                request -> {
                    int index = requests.size();
                    requests.add(request);
                    assertThat(request.callBudget().limit(Duration.ofSeconds(90)))
                            .isLessThanOrEqualTo(Duration.ofSeconds(60));
                    return success(request, output(index, "gold").toString());
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
    }

    private ObjectNode output(int index, String mode) {
        var sample = diagnostic.cases().get(index);
        var result =
                EvalReport.JSON
                        .createObjectNode()
                        .put("analysis_id", sample.source().path("analysis_id").asString());
        var reviews = result.putArray("reviews");
        String event = diagnostic.cases().getFirst().expectedReference();
        for (var observation :
                sample.source()
                        .path("input")
                        .path("analysis_result")
                        .path("goal_observation")
                        .path("observations")) {
            String ref = observation.path("reference").path("criterion_reference").asString();
            boolean supported =
                    mode.equals("all-supported") || (mode.equals("gold") && ref.equals(event));
            var review = reviews.addObject().put("criterion_reference", ref);
            if (supported) {
                review.put("verdict", "SUPPORTED")
                        .put("reason_code", "MATCHED_EXPRESSION")
                        .put("evidence_quote", quote)
                        .put("explanation", "사건을 직접 표현했다.");
            } else if (mode.equals("unknown")) {
                review.put("verdict", "INSUFFICIENT_CONTEXT")
                        .put("reason_code", "INSUFFICIENT_CONTEXT")
                        .putNull("evidence_quote")
                        .put("explanation", "관찰 해석에 필요한 정보가 부족하다.");
            } else {
                review.put("verdict", "UNSUPPORTED")
                        .put("reason_code", "NO_MATCHING_EXPRESSION")
                        .putNull("evidence_quote")
                        .put("explanation", "해당 기준을 표현한 말이 없다.");
            }
        }
        return result;
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

    private AiCallResult<LlmCompletion> success(LlmRequest request, String content) {
        return new AiCallResult.Success<>(
                new LlmCompletion(content, "scripted", "scripted", "stop", 10, 10),
                metadata(request));
    }
}
