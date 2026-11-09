package com.neuringo.neuringobe.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

class NegativeEvaluationDiagnosticTest {
    private final NegativeEvaluationDiagnostic diagnostic = new NegativeEvaluationDiagnostic();

    @Test
    void changesOnlyTheIntendedAnalysisOrCandidateAndDoesNotSendExpectedAnswers() {
        var baseline = new ObservationPairDiagnostic().cases();
        assertThat(diagnostic.samples().getFirst().source()).isEqualTo(baseline.get(1).source());
        var c = diagnostic.samples().getLast().source().deepCopy();
        ((ObjectNode) c.path("input").path("candidate_response"))
                .set(
                        "text",
                        baseline.getFirst()
                                .source()
                                .path("input")
                                .path("candidate_response")
                                .path("text"));
        assertThat(c).isEqualTo(baseline.getFirst().source());
        assertThat(diagnostic.samples())
                .allSatisfy(
                        s ->
                                assertThat(s.prepared().request().userPrompt())
                                        .doesNotContain(
                                                "\"expected\":", "REANALYZE_FALSE_EMOTION_ONLY"));
    }

    @Test
    void acceptsOnlyTheIntendedRejectionReasonsInTwoCalls() {
        var calls = new AtomicInteger();
        var report =
                diagnostic.run(
                        request -> {
                            calls.incrementAndGet();
                            return success(request, correct(request).toString());
                        },
                        RoleplayTurnDeadline.start(),
                        ignored -> {});
        assertThat(calls).hasValue(2);
        assertThat(report.bothExpectedRejections()).isTrue();
        assertThat(report.execution().cases())
                .allSatisfy(e -> assertThat(e.acceptedResult().canDeliver()).isFalse());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-reason", "pass", "quote"})
    void doesNotCountAnAccidentalRejectionOrTechnicalFailureAsCorrect(String mode) {
        var report =
                diagnostic.run(
                        request -> {
                            var body =
                                    mode.equals("wrong-reason")
                                            ? IntegratedEvaluationDiagnosticTest.output(
                                                    request, "all-unsupported")
                                            : correct(request);
                            if (mode.equals("quote"))
                                ((ObjectNode) body.path("observation_reviews").path("o0"))
                                        .put("evidence_quote", "아동이 말하지 않은 문장");
                            if (mode.equals("pass")) {
                                body.put("decision", "PASS");
                                body.putArray("failure_codes");
                                body.putNull("revision_instruction");
                                ((ObjectNode) body.path("checks"))
                                        .propertyNames()
                                        .forEach(
                                                k ->
                                                        ((ObjectNode) body.path("checks"))
                                                                .put(k, true));
                                for (String key : body.path("observation_reviews").propertyNames())
                                    ((ObjectNode) body.path("observation_reviews").path(key))
                                            .put("verdict", "SUPPORTED")
                                            .put("reason_code", "MATCHED_EXPRESSION")
                                            .set(
                                                    "evidence_quote",
                                                    EvalReport.JSON
                                                            .readTree(request.userPrompt())
                                                            .path("learner_turn")
                                                            .path("canonical_utterance"));
                            }
                            return success(request, body.toString());
                        },
                        RoleplayTurnDeadline.start(),
                        ignored -> {});
        assertThat(report.execution().physicalCalls()).isEqualTo(2);
        assertThat(report.bothExpectedRejections()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema", "provider", "timeout"})
    void stopsOnTechnicalFailureWithoutAnExtraCall(String mode) {
        var deadline = RoleplayTurnDeadline.start();
        var report =
                diagnostic.run(
                        request -> {
                            if (mode.equals("provider"))
                                return new AiCallResult.Failure<>(
                                        new AiFailure(AiFailureType.TIMEOUT, null),
                                        metadata(request));
                            var body = correct(request);
                            if (mode.equals("schema")) body.put("unknown", true);
                            if (mode.equals("timeout")) deadline.cancel();
                            return success(request, body.toString());
                        },
                        deadline,
                        ignored -> {});
        assertThat(report.execution().physicalCalls()).isEqualTo(1);
        assertThat(report.execution().stoppedReason()).isNotNull();
        assertThat(report.bothExpectedRejections()).isFalse();
    }

    private static ObjectNode correct(LlmRequest request) {
        var body = IntegratedEvaluationDiagnosticTest.output(request, "gold");
        if (EvalReport.JSON.readTree(request.userPrompt()).path("observation_tasks").size() == 1) {
            body.put("decision", "REGENERATE");
            body.putArray("failure_codes").add("SCENARIO_CONTRADICTION");
            ((ObjectNode) body.path("checks")).put("scenario_consistent", false);
            body.set(
                    "revision_instruction",
                    EvalReport.JSON.valueToTree(
                            java.util.Map.of(
                                    "keep",
                                    List.of(),
                                    "change",
                                    List.of("의도를 단정하지 않는다."),
                                    "avoid",
                                    List.of(),
                                    "required",
                                    List.of())));
        }
        return com.neuringo.neuringobe.ai.application.prompt.EvaluationWireFixtures
                .characterContract(body, EvalReport.JSON.readTree(request.userPrompt()));
    }

    private static AiCallMetadata metadata(LlmRequest r) {
        return new AiCallMetadata(
                r.traceContext().requestId(),
                r.operation(),
                "scripted",
                "scripted",
                r.promptVersion(),
                r.responseSchemaVersion(),
                null,
                0,
                1,
                10,
                10,
                "stop");
    }

    private static AiCallResult<LlmCompletion> success(LlmRequest r, String body) {
        return new AiCallResult.Success<>(
                new LlmCompletion(body, "scripted", "scripted", "stop", 10, 10), metadata(r));
    }
}
