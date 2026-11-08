package com.neuringo.neuringobe.ai.application.prompt;

import static com.neuringo.neuringobe.ai.application.prompt.RoleplayFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.roleplay.RetryingRoleplayTurnExecutor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.roleplay.StructuredRoleplayTurnSteps;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

class CharacterResponseContractTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RoleplayPromptFactory factory = new RoleplayPromptFactory(JSON, null);

    static Stream<Arguments> learningStrategies() {
        return Stream.of(
                        "PROBE_EVENT",
                        "PROBE_EMOTION",
                        "PROBE_CAUSE",
                        "PROBE_ACTION",
                        "ADVANCE_GOAL")
                .flatMap(
                        strategy ->
                                Stream.of("S0", "S1", "S2", "S3")
                                        .map(support -> Arguments.of(strategy, support)));
    }

    @ParameterizedTest
    @MethodSource("learningStrategies")
    void generationEnforcesAppliedSupportAndSharesContractWithEvaluation(
            String strategy, String support) {
        var analysis = analysis(strategy, support);
        var input = characterInput("도와줘야해?");
        var expected = support.equals("S3") ? "ANSWER_REVEAL" : "GUIDING_QUESTION";
        var generation =
                factory.responseGeneration(
                        trace(ANALYSIS_ID, CANDIDATE_ID),
                        attempts(),
                        1,
                        input,
                        analysis,
                        CANDIDATE_ID,
                        null);
        String raw =
                candidateJson("응… 내 옆에 있어 줘.", expected)
                        .replace("PROBE_EMOTION", strategy)
                        .replace("\"S1\"", "\"" + support + "\"");
        var candidate = generation.parser().parse(raw);
        var evaluation =
                factory.responseEvaluation(
                        trace(ANALYSIS_ID, CANDIDATE_ID),
                        attempts(),
                        1,
                        input,
                        analysis,
                        candidate,
                        RoleplayObservationTestPlans.inputForScenario(input.scenario()));
        assertThat(
                        JSON.readTree(generation.request().userPrompt())
                                .path("character_response_contract"))
                .isEqualTo(
                        JSON.readTree(evaluation.request().userPrompt())
                                .path("character_response_contract"));
        String wrong =
                raw.replace(
                        expected,
                        expected.equals("ANSWER_REVEAL") ? "GUIDING_QUESTION" : "ANSWER_REVEAL");
        assertThatThrownBy(() -> generation.parser().parse(wrong))
                .isInstanceOf(InvalidLlmOutputException.class)
                .hasMessage("응답 유형이 캐릭터 지원 계약과 다릅니다.");
    }

    @Test
    void s3WithoutHintCannotRejectTargetAnswerAsTooDirect() {
        var prepared = evaluation("S3", null);
        var wire = rejected(prepared, "TARGET_ANSWER_BEFORE_S3", "DIALOGUE", "내 옆에 있어 줘");
        assertConflict(prepared, wire);
        assertThat(prepared.request().responseJsonSchema())
                .doesNotContain("TARGET_ANSWER_BEFORE_S3", "MASCOT_ANSWER_REVEAL");
    }

    @Test
    void s1TargetAnswerAndS3MascotAnswerCanStillBeRejected() {
        var s1 = evaluation("S1", null);
        assertThat(
                        s1.parser()
                                .parse(
                                        rejected(
                                                s1,
                                                "TARGET_ANSWER_BEFORE_S3",
                                                "DIALOGUE",
                                                "내 옆에 있어 줘"))
                                .canDeliver())
                .isFalse();
        var s3 = evaluation("S3", "친구에게 도와달라고 말해.");
        assertThat(
                        s3.parser()
                                .parse(rejected(s3, "MASCOT_ANSWER_REVEAL", "HINT", "도와달라고 말해"))
                                .canDeliver())
                .isFalse();
    }

    @Test
    void directnessNeedsMatchingSpeakerQuoteCheckAndFailureCode() {
        var prepared = evaluation("S1", "친구에게 가 볼까?");
        for (var pair :
                new String[][] {
                    {"HINT", "내 옆에 있어 줘"}, {"DIALOGUE", "존재하지 않는 말"}, {"DIALOGUE", " "}
                })
            assertConflict(
                    prepared, rejected(prepared, "TARGET_ANSWER_BEFORE_S3", pair[0], pair[1]));
        var body =
                JSON.readTree(
                        rejected(prepared, "TARGET_ANSWER_BEFORE_S3", "DIALOGUE", "내 옆에 있어 줘"));
        ((tools.jackson.databind.node.ObjectNode) body.path("checks"))
                .put("difficulty_appropriate", true);
        assertConflict(prepared, body.toString());
        var pass =
                (tools.jackson.databind.node.ObjectNode)
                        JSON.readTree(wire(prepared, "PASS", "[]"));
        pass.set("answer_directness_violation", body.path("answer_directness_violation"));
        assertConflict(prepared, pass.toString());
        var missing =
                (tools.jackson.databind.node.ObjectNode)
                        JSON.readTree(wire(prepared, "REGENERATE", "[\"ANSWER_TOO_DIRECT\"]"));
        assertConflict(prepared, missing.toString());
    }

    @Test
    void s3DoesNotOverrideOtherFailuresReanalysisOrSafetyPriority() {
        var prepared = evaluation("S3", null);
        for (String code :
                new String[] {"ANALYSIS_RESPONSE_MISMATCH", "GOAL_SKIPPED", "UNSAFE_EXPRESSION"})
            assertThat(
                            prepared.parser()
                                    .parse(wire(prepared, "REGENERATE", "[\"" + code + "\"]"))
                                    .canDeliver())
                    .isFalse();
        assertThat(prepared.parser().parse(wire(prepared, "PASS", "[]")).canDeliver()).isTrue();
        for (String decision : new String[] {"SAFETY_ESCALATION", "CONFIRM_INPUT"}) {
            var body =
                    (tools.jackson.databind.node.ObjectNode)
                            JSON.readTree(wire(prepared, decision, "[\"ANSWER_TOO_DIRECT\"]"));
            for (String key :
                    new String[] {
                        "observation_reviews",
                        "current_goal_review",
                        "activity_review",
                        "interpretation_strategy_review"
                    }) body.putNull(key);
            assertThat(prepared.parser().parse(body.toString()).canDeliver()).isFalse();
        }
        var body =
                (tools.jackson.databind.node.ObjectNode)
                        JSON.readTree(wire(prepared, "REANALYZE", "[]"));
        body.set(
                "interpretation_strategy_review",
                JSON.valueToTree(
                        Map.of(
                                "verdict",
                                "UNSUPPORTED",
                                "reason_code",
                                "RESPONSE_ACT_MISMATCH",
                                "explanation",
                                "발화가 질문인데 진술로 해석했다.")));
        assertThat(prepared.parser().parse(body.toString()).decision().name())
                .isEqualTo("REANALYZE");
    }

    @Test
    void invalidS3GenerationAndDirectnessReviewRetryWithinExistingStages() {
        var base = characterInput("도와줘야해?");
        var input =
                new RoleplayTurnInput(
                        base.scenario(),
                        new RoleplayTurnInput.State(MG_EMOTION, "S2", 1, 0, false, 2, 6, 4),
                        base.learnerTurn(),
                        base.recentDialogue());
        var requests = new java.util.ArrayList<LlmRequest>();
        LlmProvider provider =
                request -> {
                    requests.add(request);
                    String content =
                            switch (request.operation()) {
                                case CAUSE_ANALYSIS ->
                                        bindAnalysis(
                                                analysisJson("PROBE_EMOTION", MG_EMOTION, "S3"),
                                                request.traceContext().analysisId());
                                case RESPONSE_GENERATION ->
                                        candidateJson(
                                                        "응… 속상해.",
                                                        request.currentAttempt() == 1
                                                                ? "GUIDING_QUESTION"
                                                                : "ANSWER_REVEAL")
                                                .replace(
                                                        CANDIDATE_ID.toString(),
                                                        request.traceContext()
                                                                .candidateId()
                                                                .toString())
                                                .replace("\"S1\"", "\"S3\"");
                                case RESPONSE_EVALUATION -> {
                                    String raw =
                                            evaluationOutputJson(
                                                    request.currentAttempt() == 1
                                                            ? "REGENERATE"
                                                            : "PASS",
                                                    request.currentAttempt() == 1
                                                            ? "[\"ANSWER_TOO_DIRECT\"]"
                                                            : "[]",
                                                    request.currentAttempt() == 1
                                                            ? "{\"keep\":[],\"change\":[],\"avoid\":[],\"required\":[]}"
                                                            : "null");
                                    var body =
                                            (tools.jackson.databind.node.ObjectNode)
                                                    JSON.readTree(
                                                            EvaluationWireFixtures.forRequest(
                                                                    raw,
                                                                    JSON.readTree(
                                                                            request.userPrompt())));
                                    body.put(
                                            "candidate_id",
                                            request.traceContext().candidateId().toString());
                                    if (request.currentAttempt() == 1) {
                                        body.set(
                                                "answer_directness_violation",
                                                JSON.valueToTree(
                                                        Map.of(
                                                                "reason",
                                                                "TARGET_ANSWER_BEFORE_S3",
                                                                "speaker_role",
                                                                "DIALOGUE",
                                                                "quote",
                                                                "속상해")));
                                        ((tools.jackson.databind.node.ObjectNode)
                                                        body.path("checks"))
                                                .put("difficulty_appropriate", false);
                                    }
                                    yield body.toString();
                                }
                                default -> throw new AssertionError(request.operation());
                            };
                    return new AiCallResult.Success<>(
                            new LlmCompletion(content, "fake", "fake-model", "STOP", null, null),
                            new AiCallMetadata(
                                    request.traceContext().requestId(),
                                    request.operation(),
                                    "fake",
                                    "fake-model",
                                    request.promptVersion(),
                                    request.responseSchemaVersion(),
                                    null,
                                    0,
                                    request.currentAttempt(),
                                    null,
                                    null,
                                    "STOP"));
                };
        var steps =
                new StructuredRoleplayTurnSteps(
                        factory,
                        new StructuredLlmExecutor(provider),
                        trace(null, null),
                        input,
                        RoleplayObservationTestPlans.inputForScenario(input.scenario()));
        var result = new RetryingRoleplayTurnExecutor().execute(TURN_ID, steps);
        assertThat(result).isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(requests.stream().map(r -> r.operation().name()))
                .containsExactly(
                        "CAUSE_ANALYSIS",
                        "RESPONSE_GENERATION",
                        "RESPONSE_GENERATION",
                        "RESPONSE_EVALUATION",
                        "RESPONSE_EVALUATION");
        assertThat(
                        JSON.readTree(requests.get(2).userPrompt())
                                .path("output_retry")
                                .path("error_code")
                                .asString())
                .isEqualTo("RESPONSE_CONTRACT_CONFLICT");
        assertThat(
                        JSON.readTree(requests.get(4).userPrompt())
                                .path("retry")
                                .path("error_code")
                                .asString())
                .isEqualTo("DIRECTNESS_CONTRACT_CONFLICT");
        assertThat(
                        requests.subList(1, 5).stream()
                                .map(r -> r.traceContext().candidateId())
                                .distinct())
                .hasSize(1);
        assertThat(requests).allSatisfy(r -> assertThat(r.callBudget()).isNotNull());
    }

    private PreparedPrompt<EvaluationResult> evaluation(String support, String hint) {
        var input = characterInput("도와줘야해?");
        var analysis = analysis("ADVANCE_GOAL", support);
        var candidate =
                new CandidateResponse(
                        CANDIDATE_ID,
                        TURN_ID,
                        "응… 내 옆에 있어 줘.",
                        support.equals("S3") ? "ANSWER_REVEAL" : "GUIDING_QUESTION",
                        "ADVANCE_GOAL",
                        MG_EMOTION,
                        support,
                        java.util.List.of(),
                        hint);
        return factory.responseEvaluation(
                trace(ANALYSIS_ID, CANDIDATE_ID),
                attempts(),
                1,
                input,
                analysis,
                candidate,
                RoleplayObservationTestPlans.inputForScenario(input.scenario()));
    }

    private static AnalysisResult analysis(String strategy, String support) {
        return JSON.readValue(analysisJson(strategy, MG_EMOTION, support), AnalysisResult.class);
    }

    private static String wire(PreparedPrompt<?> prepared, String decision, String codes) {
        return EvaluationWireFixtures.forRequest(
                evaluationOutputJson(
                        decision,
                        codes,
                        decision.equals("PASS")
                                ? "null"
                                : "{\"keep\":[],\"change\":[],\"avoid\":[],\"required\":[]}"),
                JSON.readTree(prepared.request().userPrompt()));
    }

    private static String rejected(
            PreparedPrompt<?> prepared, String reason, String speaker, String quote) {
        var body =
                (tools.jackson.databind.node.ObjectNode)
                        JSON.readTree(wire(prepared, "REGENERATE", "[\"ANSWER_TOO_DIRECT\"]"));
        body.set(
                "answer_directness_violation",
                JSON.valueToTree(
                        Map.of("reason", reason, "speaker_role", speaker, "quote", quote)));
        ((tools.jackson.databind.node.ObjectNode) body.path("checks"))
                .put("difficulty_appropriate", false);
        return body.toString();
    }

    private static void assertConflict(PreparedPrompt<?> prepared, String body) {
        assertThatThrownBy(() -> prepared.parser().parse(body))
                .isInstanceOf(InvalidLlmOutputException.class)
                .hasMessage(AnswerDirectnessViolation.CONFLICT);
    }
}
