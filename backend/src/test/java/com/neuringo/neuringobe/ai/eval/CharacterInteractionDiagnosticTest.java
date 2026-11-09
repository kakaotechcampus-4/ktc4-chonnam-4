package com.neuringo.neuringobe.ai.eval;

import static org.assertj.core.api.Assertions.*;

import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.prompt.EvaluationWireFixtures;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayPromptFactory;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

class CharacterInteractionDiagnosticTest {
    private final CharacterInteractionDiagnostic diagnostic = new CharacterInteractionDiagnostic();

    @Test
    void usesCurrentCompactPolicyAndSameAnalysisWithoutSendingExpectations() {
        assertThat(diagnostic.samples()).hasSize(3);
        assertThat(
                        diagnostic.samples().stream()
                                .map(s -> s.prepared().request().traceContext().analysisId())
                                .distinct()
                                .count())
                .isEqualTo(1);
        assertThat(diagnostic.samples())
                .allSatisfy(
                        sample -> {
                            var request = sample.prepared().request();
                            assertThat(request.operation())
                                    .isEqualTo(AiOperation.RESPONSE_EVALUATION);
                            assertThat(request.currentAttempt()).isEqualTo(1);
                            assertThat(request.promptVersion())
                                    .isEqualTo(
                                            RoleplayPromptFactory
                                                    .COMPACT_CHARACTER_EVALUATION_PROMPT_VERSION);
                            assertThat(request.responseSchemaVersion())
                                    .isEqualTo(RoleplayPromptFactory.EVALUATION_SCHEMA_VERSION);
                            assertThat(request.userPrompt())
                                    .doesNotContain(
                                            "\"expected\":",
                                            "PASS_CHARACTER_REACTION",
                                            "REGENERATE_LEARNER_QUIZ",
                                            "REGENERATE_PROHIBITED_INFERENCE");
                        });
    }

    @Test
    void countsOnlyValidSpecificDecisionsAndMakesExactlyThreeCalls() {
        var report =
                diagnostic.run(
                        request -> success(request, output(request, true).toString()),
                        RoleplayTurnDeadline.start(),
                        ignored -> {});
        assertThat(report.physicalCalls()).isEqualTo(3);
        assertThat(report.allExpected()).isTrue();
        assertThat(report.stoppedReason()).isNull();
        assertThat(report.cases().stream().map(e -> e.evaluation().canDeliver()))
                .containsExactly(true, false, false);
    }

    @Test
    void passingEverythingDoesNotValidateNegativeControls() {
        var report =
                diagnostic.run(
                        request -> success(request, output(request, false).toString()),
                        RoleplayTurnDeadline.start(),
                        ignored -> {});
        assertThat(report.physicalCalls()).isEqualTo(3);
        assertThat(report.allExpected()).isFalse();
        assertThat(report.cases().stream().map(CharacterInteractionDiagnostic.Entry::expectedMatch))
                .containsExactly(true, false, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema", "provider", "late"})
    void stopsOnTechnicalFailureWithoutRetryOrTreatingItAsCorrectRejection(String mode) {
        var deadline = RoleplayTurnDeadline.start();
        var report =
                diagnostic.run(
                        request -> {
                            if (mode.equals("provider"))
                                return new AiCallResult.Failure<>(
                                        new AiFailure(AiFailureType.TIMEOUT, null),
                                        metadata(request));
                            if (mode.equals("late")) deadline.cancel();
                            return success(
                                    request,
                                    mode.equals("schema")
                                            ? "{}"
                                            : output(request, true).toString());
                        },
                        deadline,
                        ignored -> {});
        assertThat(report.physicalCalls()).isEqualTo(1);
        assertThat(report.cases()).hasSize(1);
        assertThat(report.allExpected()).isFalse();
        assertThat(report.cases().getFirst().evaluation()).isNull();
        assertThat(report.stoppedReason()).isNotNull();
    }

    @Test
    void expiredBudgetDoesNotCallProvider() {
        var deadline = RoleplayTurnDeadline.start();
        deadline.cancel();
        var calls = new AtomicInteger();
        var report =
                diagnostic.run(
                        request -> {
                            calls.incrementAndGet();
                            return success(request, "{}");
                        },
                        deadline,
                        ignored -> {});
        assertThat(calls).hasValue(0);
        assertThat(report.physicalCalls()).isZero();
        assertThat(report.allExpected()).isFalse();
        assertThat(report.stoppedReason()).isEqualTo("TIMED_OUT");
    }

    private static ObjectNode output(LlmRequest request, boolean distinguish) {
        var json = EvalReport.JSON;
        var body = json.createObjectNode();
        body.put("candidate_id", request.traceContext().candidateId().toString())
                .put("decision", "PASS");
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
        body.putArray("failure_codes");
        body.putNull("revision_instruction");
        var input = json.readTree(request.userPrompt());
        body =
                (ObjectNode)
                        json.readTree(EvaluationWireFixtures.forRequest(body.toString(), input));
        String text = input.path("candidate_response").path("text").asString();
        if (distinguish && !text.startsWith("응…")) {
            body.put("decision", "REGENERATE");
            var revision = body.putObject("revision_instruction");
            for (String key : List.of("keep", "change", "avoid", "required"))
                revision.putArray(key);
            if (text.startsWith("정답")) {
                body.withArray("failure_codes").add("CHARACTER_BREAK");
                ((ObjectNode) body.path("checks")).put("conversation_coherent", false);
                ((ObjectNode) body.path("character_interaction_review"))
                        .put("mode", "LEARNER_QUIZ");
            } else {
                body.withArray("failure_codes").add("SCENARIO_CONTRADICTION");
                ((ObjectNode) body.path("checks")).put("scenario_consistent", false);
            }
        }
        return body;
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
                "STOP");
    }

    private static AiCallResult<LlmCompletion> success(LlmRequest request, String raw) {
        return new AiCallResult.Success<>(
                new LlmCompletion(raw, "scripted", "scripted", "STOP", 10, 10), metadata(request));
    }
}
