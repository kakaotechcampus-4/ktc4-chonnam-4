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
import tools.jackson.databind.node.ObjectNode;

class IntegratedEvaluationDiagnosticTest {
    private final IntegratedEvaluationDiagnostic diagnostic = new IntegratedEvaluationDiagnostic();

    @Test
    void reconstructsProductionRequestsWithoutChangingTheFrozenInputsOrSendingExpectations() {
        var a = diagnostic.samples().getFirst();
        var b = diagnostic.samples().get(1);
        for (var sample : diagnostic.samples()) {
            var request = sample.prepared().request();
            assertThat(
                            EvalReport.JSON
                                    .readTree(request.userPrompt())
                                    .path("current_goal_assessment")
                                    .path("micro_goal_id"))
                    .isEqualTo(
                            sample.source()
                                    .path("input")
                                    .path("state")
                                    .path("current_micro_goal_id"));
            assertThat(request.promptVersion())
                    .isEqualTo("roleplay-response-evaluation-character/v8");
            assertThat(request.responseSchemaVersion()).isEqualTo("evaluation-output/v6");
            assertThat(request.userPrompt())
                    .doesNotContain("expected_verdict", "case_id", "expected_status");
        }
        assertThat(a.prepared().request().systemPrompt())
                .isEqualTo(b.prepared().request().systemPrompt());
        assertThat(a.prepared().request().responseJsonSchema())
                .isNotEqualTo(b.prepared().request().responseJsonSchema());
        var changed = b.source().path("input").deepCopy();
        ((tools.jackson.databind.node.ArrayNode)
                        changed.path("analysis_result")
                                .path("goal_observation")
                                .path("observations"))
                .remove(1);
        assertThat(changed).isEqualTo(a.source().path("input"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void separatesObservationCorrectnessFromValidServerDecisions(boolean correct) {
        var calls = new AtomicInteger();
        var saved = new ArrayList<IntegratedEvaluationDiagnostic.Entry>();
        var report =
                diagnostic.run(
                        request -> {
                            calls.incrementAndGet();
                            return success(
                                    request,
                                    output(request, correct ? "gold" : "all-unsupported")
                                            .toString());
                        },
                        RoleplayTurnDeadline.start(),
                        saved::add);
        assertThat(calls).hasValue(2);
        assertThat(report.physicalCalls()).isEqualTo(2);
        assertThat(report.observationDiscriminationSuccess()).isEqualTo(correct);
        assertThat(report.bothServerAccepted()).isTrue();
        assertThat(report.stoppedReason()).isNull();
        assertThat(saved).containsExactlyElementsOf(report.cases());
        assertThat(report.cases())
                .allSatisfy(
                        entry -> {
                            assertThat(entry.schemaValid()).isTrue();
                            assertThat(entry.metadata().inputTokens()).isEqualTo(10);
                            assertThat(entry.acceptedResult()).isNotNull();
                        });
        if (correct) {
            assertThat(
                            report.cases()
                                    .getFirst()
                                    .acceptedResult()
                                    .goalObservationReview()
                                    .observationsSupported())
                    .isTrue();
            assertThat(
                            report.cases()
                                    .get(1)
                                    .acceptedResult()
                                    .goalObservationReview()
                                    .observationsSupported())
                    .isFalse();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "malformed,INVALID_OUTPUT_FORMAT,false",
        "quote,INVALID_OUTPUT_FORMAT,true",
        "contradiction,INVALID_OUTPUT_FORMAT,true",
        "unknown,INDETERMINATE_OUTPUT,true",
        "provider,PROVIDER_FAILURE,false"
    })
    void stopsAfterTheFirstFailureWithoutLosingItsClassification(
            String mode, String outcome, boolean schemaValid) {
        var calls = new AtomicInteger();
        var report =
                diagnostic.run(
                        request -> {
                            calls.incrementAndGet();
                            if (mode.equals("provider"))
                                return new AiCallResult.Failure<>(
                                        new AiFailure(AiFailureType.TIMEOUT, null),
                                        metadata(request));
                            var body = output(request, mode);
                            return success(
                                    request,
                                    mode.equals("malformed") ? "not JSON" : body.toString());
                        },
                        RoleplayTurnDeadline.start(),
                        ignored -> {});
        assertThat(calls).hasValue(mode.equals("malformed") || mode.equals("provider") ? 1 : 2);
        assertThat(report.physicalCalls())
                .isEqualTo(mode.equals("malformed") || mode.equals("provider") ? 1 : 2);
        assertThat(report.cases())
                .hasSize(mode.equals("malformed") || mode.equals("provider") ? 1 : 2);
        var entry = report.cases().getFirst();
        assertThat(entry.outcome()).isEqualTo(outcome);
        assertThat(entry.schemaValid()).isEqualTo(schemaValid);
        assertThat(entry.metadata()).isNotNull();
        assertThat(entry.acceptedResult()).isNull();
        assertThat(report.stoppedReason())
                .isEqualTo(mode.equals("malformed") || mode.equals("provider") ? outcome : null);
        if (mode.equals("contradiction")) {
            assertThat(entry.observationVerdictsMatch()).isTrue();
            assertThat(entry.rejectionReason())
                    .isEqualTo("failure code contradicts evaluation check");
        }
    }

    @Test
    void sharesOneBudgetAndDoesNotRetryOrAcceptALateOutput() {
        var deadline = RoleplayTurnDeadline.start();
        var requests = new ArrayList<LlmRequest>();
        var report =
                diagnostic.run(
                        request -> {
                            requests.add(request);
                            assertThat(request.callBudget().limit(Duration.ofSeconds(90)))
                                    .isLessThanOrEqualTo(Duration.ofSeconds(60));
                            return success(request, output(request, "gold").toString());
                        },
                        deadline,
                        ignored -> {});
        assertThat(report.physicalCalls()).isEqualTo(2);
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

        var late = RoleplayTurnDeadline.start();
        var result =
                diagnostic.run(
                        request -> {
                            late.cancel();
                            return success(request, output(request, "gold").toString());
                        },
                        late,
                        ignored -> {});
        assertThat(result.physicalCalls()).isEqualTo(1);
        assertThat(result.cases().getFirst().outcome()).isEqualTo("TIMED_OUT");
        assertThat(result.cases().getFirst().acceptedResult()).isNull();
        var cancelled =
                diagnostic.run(
                        request -> {
                            throw new AssertionError("기한 종료 후 호출하면 안 됩니다.");
                        },
                        late,
                        ignored -> {});
        assertThat(cancelled.physicalCalls()).isZero();
    }

    static ObjectNode output(LlmRequest request, String mode) {
        var input = EvalReport.JSON.readTree(request.userPrompt());
        var body = EvalReport.JSON.createObjectNode();
        body.put("candidate_id", request.traceContext().candidateId().toString());
        body.putNull("revision_instruction");
        body.putNull("answer_directness_violation");
        body.put("analysis_id", request.traceContext().analysisId().toString());
        body.set(
                "activity_review",
                EvalReport.JSON.valueToTree(
                        com.neuringo.neuringobe.ai.application.prompt.EvaluationWireFixtures
                                .activityReview(input, true)));
        body.set(
                "current_goal_review",
                EvalReport.JSON.valueToTree(
                        com.neuringo.neuringobe.ai.application.prompt.EvaluationWireFixtures
                                .currentGoalReview(input, true)));
        body.set(
                "interpretation_strategy_review",
                EvalReport.JSON.valueToTree(
                        com.neuringo.neuringobe.ai.application.prompt.EvaluationWireFixtures
                                .logicReview(true)));
        var checks = body.putObject("checks");
        EvalReport.JSON
                .readTree(request.responseJsonSchema())
                .path("properties")
                .path("checks")
                .path("properties")
                .propertyNames()
                .forEach(key -> checks.put(key, true));
        var codes = body.putArray("failure_codes");
        var rows = body.putObject("observation_reviews");
        var observations = input.path("observation_tasks");
        boolean rejected = false;
        for (int index = 0; index < observations.size(); index++) {
            var reference = observations.path("o" + index).path("reference");
            boolean supported = index == 0 && !mode.equals("all-unsupported");
            var row = rows.putObject("o" + index);
            if (mode.equals("unknown")) {
                row.put("verdict", "INSUFFICIENT_CONTEXT")
                        .put("reason_code", "INSUFFICIENT_CONTEXT")
                        .putNull("evidence_quote")
                        .put("explanation", "문맥 부족");
            } else if (supported) {
                row.put("verdict", "SUPPORTED")
                        .put("reason_code", "MATCHED_EXPRESSION")
                        .put(
                                "evidence_quote",
                                mode.equals("quote")
                                        ? "발화에 없는 문장"
                                        : input.path("learner_turn")
                                                .path("canonical_utterance")
                                                .asString())
                        .put("explanation", "사건을 직접 표현했다.");
            } else {
                rejected = true;
                row.put("verdict", "UNSUPPORTED")
                        .put("reason_code", "NO_MATCHING_EXPRESSION")
                        .putNull("evidence_quote")
                        .put("explanation", "해당 표현이 없다.");
            }
        }
        body.put("decision", rejected ? "REANALYZE" : "PASS");
        if (rejected) {}
        if (mode.equals("contradiction")) {
            body.put("decision", "REGENERATE");
            body.set(
                    "revision_instruction",
                    EvalReport.JSON.valueToTree(
                            java.util.Map.of(
                                    "keep",
                                    java.util.List.of(),
                                    "change",
                                    java.util.List.of(),
                                    "avoid",
                                    java.util.List.of(),
                                    "required",
                                    java.util.List.of())));
            codes.add("GOAL_SKIPPED");
        }
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
                "diagnostic-only",
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
