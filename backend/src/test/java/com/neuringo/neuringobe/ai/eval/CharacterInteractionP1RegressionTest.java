package com.neuringo.neuringobe.ai.eval;

import static org.assertj.core.api.Assertions.*;

import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.*;
import com.neuringo.neuringobe.ai.application.roleplay.*;
import com.neuringo.neuringobe.ai.application.structured.*;
import com.neuringo.neuringobe.ai.application.structured.output.*;
import com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class CharacterInteractionP1RegressionTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode OBSERVED = loadObserved();
    private final RegressionSet set = RegressionSet.load(RegressionSet.CHARACTER_P1_RESOURCE);
    private final RoleplayTurnInput input = set.toInput(set.cases().getFirst());
    private final GoalObservationInput observations =
            RoleplayObservationTestPlans.inputForScenario(input.scenario());
    private final RoleplayPromptFactory prompts = new RoleplayPromptFactory(JSON, null, true);
    private final UUID analysisId = UUID.randomUUID();
    private final UUID candidateId = UUID.randomUUID();
    private final AiAttemptContext attempts = new AiAttemptContext(0, 0, 0);

    @Test
    void actualMissingQuestionRejectionIsInvalidAndCannotReachRegeneration() {
        var prepared = evaluation(candidate(OBSERVED.path("candidates").get(0).asString()));
        var raw = (ObjectNode) OBSERVED.path("first_evaluation").deepCopy();
        raw.put("analysis_id", analysisId.toString());
        raw.put("candidate_id", candidateId.toString());
        assertThatThrownBy(() -> prepared.parser().parse(raw.toString()))
                .isInstanceOfSatisfying(
                        InvalidLlmOutputException.class,
                        e ->
                                assertThat(EvaluationRetry.from(e))
                                        .isEqualTo(EvaluationRetry.CHARACTER_QUESTION_CONFLICT));
        assertThat(
                        JSON.readTree(prepared.request().responseJsonSchema())
                                .path("properties")
                                .path("failure_codes")
                                .path("items")
                                .path("enum")
                                .toString())
                .doesNotContain("MISSING_QUESTION");
        assertThat(
                        JSON.readTree(prepared.request().userPrompt())
                                .path("character_response_contract")
                                .path("question_required")
                                .asBoolean())
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"응… (고개를 숙이고 손을 꼭 쥔다)", "응… 잠깐 옆에 있어 줄래?"})
    void approvedCharacterReactionAndNaturalQuestionCanPassWithoutBypassingOtherChecks(
            String text) {
        var prepared = evaluation(candidate(text));
        var body = accepted(prepared.request());
        ((ObjectNode) body.path("character_interaction_review"))
                .put("mode", text.contains("?") ? "CHARACTER_QUESTION" : "CHARACTER_REACTION");
        var accepted = prepared.parser().parse(body.toString());
        assertThat(accepted.canDeliver()).isTrue();
        assertThat(JSON.writeValueAsString(accepted))
                .doesNotContain("character_interaction_review", "characterInteractionReview");
        ((ObjectNode) body.path("checks")).put("safety_valid", false);
        assertThatThrownBy(() -> prepared.parser().parse(body.toString()))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void reactionWithInsufficientCueRemainsRejectedEvenThoughQuestionIsOptional() {
        var prepared = evaluation(candidate(OBSERVED.path("candidates").get(0).asString()));
        var body = rejected(prepared.request());
        assertThat(prepared.parser().parse(body.toString()).canDeliver()).isFalse();
        assertThat(prepared.parser().parse(body.toString()).retryTarget())
                .isEqualTo(RetryTarget.RESPONSE_GENERATION);
    }

    @Test
    void observedFinalPassCannotHideAnExplicitLearnerQuizReview() {
        var prepared = evaluation(candidate(OBSERVED.path("candidates").get(2).asString()));
        var body = accepted(prepared.request());
        ((ObjectNode) body.path("character_interaction_review")).put("mode", "LEARNER_QUIZ");
        assertThatThrownBy(() -> prepared.parser().parse(body.toString()))
                .isInstanceOf(InvalidLlmOutputException.class);
        body.put("decision", "REGENERATE");
        body.putArray("failure_codes").add("CHARACTER_BREAK");
        ((ObjectNode) body.path("checks")).put("conversation_coherent", false);
        revision(body);
        assertThat(prepared.parser().parse(body.toString()).canDeliver()).isFalse();
        ((ObjectNode) body.path("character_interaction_review")).put("quote", "원문에 없는 말");
        assertThatThrownBy(() -> prepared.parser().parse(body.toString()))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void invalidReviewRechecksSameCandidateThenPreservesValidRejectionAndCharacterRetryScope() {
        List<LlmRequest> requests = new ArrayList<>();
        int[] evaluations = {0};
        LlmProvider provider =
                request -> {
                    requests.add(request);
                    String raw;
                    if (request.operation() == AiOperation.CAUSE_ANALYSIS)
                        raw = OBSERVED.path("analysis").toString();
                    else if (request.operation() == AiOperation.RESPONSE_GENERATION) {
                        boolean retry = JSON.readTree(request.userPrompt()).has("retry");
                        raw =
                                JSON.writeValueAsString(
                                                Map.of(
                                                        "text",
                                                        retry ? "응… (손을 꼭 쥐고 고개를 숙인다)" : "응…"))
                                        .replace("}", ",\"mascot_hint\":null}");
                    } else {
                        int n = ++evaluations[0];
                        ObjectNode body = n < 3 ? rejected(request) : accepted(request);
                        if (n == 1) body.withArray("failure_codes").add("MISSING_QUESTION");
                        raw = body.toString();
                    }
                    return success(request, raw);
                };
        var steps =
                new StructuredRoleplayTurnSteps(
                        prompts,
                        new StructuredLlmExecutor(provider),
                        trace(null, null),
                        input,
                        observations);
        assertThat(new RetryingRoleplayTurnExecutor().execute(input.learnerTurn().turnId(), steps))
                .isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(requests.stream().map(LlmRequest::operation))
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS, AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION, AiOperation.RESPONSE_EVALUATION,
                        AiOperation.RESPONSE_GENERATION, AiOperation.RESPONSE_EVALUATION);
        assertThat(requests.get(2).traceContext().candidateId())
                .isEqualTo(requests.get(3).traceContext().candidateId());
        assertThat(
                        JSON.readTree(requests.get(3).userPrompt())
                                .path("retry")
                                .path("error_code")
                                .asString())
                .isEqualTo("CHARACTER_QUESTION_CONFLICT");
        var retry = JSON.readTree(requests.get(4).userPrompt()).path("retry");
        assertThat(retry.path("failure_codes").toString())
                .contains("NOT_ANSWERABLE")
                .doesNotContain("MISSING_QUESTION");
        assertThat(retry.path("instruction_scope").asString())
                .isEqualTo("WITHIN_CHARACTER_CONTRACT");
    }

    @Test
    void characterSchemaDoesNotRemoveNarratorQuestionRuleFromSharedSchema() {
        evaluation(candidate("응…"));
        var scenario = input.scenario();
        var narrator =
                new RoleplayTurnInput(
                        new RoleplayTurnInput.Scenario(
                                scenario.learningGoal(),
                                scenario.scenarioLevel(),
                                scenario.scenarioFacts(),
                                scenario.prohibitedInferences(),
                                scenario.microGoals()),
                        input.state(),
                        input.learnerTurn(),
                        input.recentDialogue());
        var prepared =
                prompts.responseEvaluation(
                        trace(analysisId, candidateId),
                        attempts,
                        1,
                        narrator,
                        analysis(),
                        candidate("왜 그랬을까?"),
                        observations,
                        EvaluationRetry.UNKNOWN_FAILURE_CODE);
        assertThat(
                        JSON.readTree(prepared.request().responseJsonSchema())
                                .path("properties")
                                .path("failure_codes")
                                .path("items")
                                .path("enum")
                                .toString())
                .contains("MISSING_QUESTION");
        assertThat(
                        JSON.readTree(prepared.request().userPrompt())
                                .path("retry")
                                .path("allowed_failure_codes")
                                .toString())
                .contains("MISSING_QUESTION");
        var body = accepted(prepared.request());
        body.put("decision", "REGENERATE");
        body.withArray("failure_codes").add("MISSING_QUESTION");
        ((ObjectNode) body.path("checks")).put("format_valid", false);
        revision(body);
        assertThat(prepared.parser().parse(body.toString()).canDeliver()).isFalse();
    }

    @Test
    void priorityCanSkipCharacterReviewButNormalPassCannot() {
        var prepared = evaluation(candidate("응…"));
        var body = accepted(prepared.request());
        body.putNull("character_interaction_review");
        assertThatThrownBy(() -> prepared.parser().parse(body.toString()))
                .isInstanceOf(InvalidLlmOutputException.class);
        body.put("decision", "SAFETY_ESCALATION");
        body.withArray("failure_codes").add("IMMEDIATE_RISK_IN_INPUT");
        ((ObjectNode) body.path("checks")).put("safety_valid", false);
        assertThat(prepared.parser().parse(body.toString()).retryTarget())
                .isEqualTo(RetryTarget.SAFETY_ESCALATION);
    }

    @Test
    void repeatedPolicyConflictsStopAfterExistingEvaluationLimitWithoutNewCandidate() {
        List<LlmRequest> requests = new ArrayList<>();
        LlmProvider provider =
                request -> {
                    requests.add(request);
                    if (request.operation() == AiOperation.CAUSE_ANALYSIS)
                        return success(request, OBSERVED.path("analysis").toString());
                    if (request.operation() == AiOperation.RESPONSE_GENERATION)
                        return success(request, "{\"text\":\"응…\",\"mascot_hint\":null}");
                    var body = rejected(request);
                    body.withArray("failure_codes").add("MISSING_QUESTION");
                    return success(request, body.toString());
                };
        var steps =
                new StructuredRoleplayTurnSteps(
                        prompts,
                        new StructuredLlmExecutor(provider),
                        trace(null, null),
                        input,
                        observations);
        assertThat(new RetryingRoleplayTurnExecutor().execute(input.learnerTurn().turnId(), steps))
                .isInstanceOf(RoleplayTurnResult.RecoveryRequired.class);
        assertThat(requests.stream().map(LlmRequest::operation))
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.RESPONSE_EVALUATION);
        assertThat(
                        requests.stream()
                                .filter(r -> r.operation() == AiOperation.RESPONSE_EVALUATION)
                                .map(r -> r.traceContext().candidateId())
                                .distinct()
                                .count())
                .isEqualTo(1);
    }

    @Test
    void successKeepsCueEvidenceNullAndDoesNotRepeatTargetId() {
        var prepared = evaluation(candidate("응… (손을 만지작거리며 작게 숨을 내쉰다)"));
        var body = accepted(prepared.request());
        assertThat(body.path("target_cue_violation").isNull()).isTrue();
        var schema = JSON.readTree(prepared.request().responseJsonSchema());
        var fields = new ArrayList<String>();
        schema.path("properties")
                .path("target_cue_violation")
                .path("properties")
                .propertyNames()
                .forEach(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("reason", "quote");
        assertThat(prepared.parser().parse(body.toString()).canDeliver()).isTrue();
        assertThat(
                        JSON.readTree(prepared.request().userPrompt())
                                .path("character_response_contract")
                                .path("cue_policy")
                                .asString())
                .isEqualTo("S0_OBSERVABLE_CUE_V1");
    }

    @Test
    void serverRestoresRequiredOnlyAfterRejectingModelOwnedRequired() {
        var prepared = evaluation(candidate("응."));
        var body = rejected(prepared.request());
        var revisionSchema =
                JSON.readTree(prepared.request().responseJsonSchema())
                        .path("properties")
                        .path("revision_instruction");
        assertThat(revisionSchema.path("properties").has("required")).isFalse();
        var result = prepared.parser().parse(body.toString());
        assertThat(result.canDeliver()).isFalse();
        assertThat(result.revisionInstruction().required())
                .hasSize(3)
                .anyMatch(s -> s.contains("목표·지원"));
        assertThat(body.path("revision_instruction").has("required")).isFalse();
        assertThat(JSON.writeValueAsString(result))
                .contains("required")
                .doesNotContain("target_cue_violation");
        ((ObjectNode) body.path("revision_instruction"))
                .putArray("required")
                .add("current_goal_review");
        assertThatThrownBy(() -> prepared.parser().parse(body.toString()))
                .isInstanceOfSatisfying(
                        InvalidLlmOutputException.class,
                        e ->
                                assertThat(EvaluationRetry.from(e))
                                        .isEqualTo(EvaluationRetry.SERVER_REQUIRED_CONFLICT));
    }

    @Test
    void cueRejectionRequiresTheActualFullCandidateForAnAbsenceClaim() {
        var prepared = evaluation(candidate("응."));
        for (String quote : List.of("응", "없는 대사", input.learnerTurn().canonicalUtterance(), " ")) {
            var body = rejected(prepared.request());
            ((ObjectNode) body.path("target_cue_violation")).put("quote", quote);
            assertThatThrownBy(() -> prepared.parser().parse(body.toString()))
                    .isInstanceOfSatisfying(
                            InvalidLlmOutputException.class,
                            e ->
                                    assertThat(EvaluationRetry.from(e))
                                            .isEqualTo(EvaluationRetry.TARGET_CUE_CONFLICT));
        }
        var missing = rejected(prepared.request());
        missing.putNull("target_cue_violation");
        assertThatThrownBy(() -> prepared.parser().parse(missing.toString()))
                .isInstanceOf(InvalidLlmOutputException.class);
        var extra = rejected(prepared.request());
        ((ObjectNode) extra.path("target_cue_violation"))
                .put("target_micro_goal_id", UUID.randomUUID().toString());
        assertThatThrownBy(() -> prepared.parser().parse(extra.toString()))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void cueReasonAndCheckCannotContradictPassOrDisappearBehindAnotherFailure() {
        var prepared = evaluation(candidate("응."));
        var passWithRejection = accepted(prepared.request());
        passWithRejection
                .putObject("target_cue_violation")
                .put("reason", "NO_TARGET_CUE")
                .put("quote", "응.");
        assertThatThrownBy(() -> prepared.parser().parse(passWithRejection.toString()))
                .isInstanceOf(InvalidLlmOutputException.class);
        var noCode = rejected(prepared.request());
        noCode.putArray("failure_codes").add("NOT_ANSWERABLE");
        assertThatThrownBy(() -> prepared.parser().parse(noCode.toString()))
                .isInstanceOf(InvalidLlmOutputException.class);
        var unknown = rejected(prepared.request());
        ((ObjectNode) unknown.path("target_cue_violation")).put("reason", "NEEDS_EMOTION_WORD");
        assertThatThrownBy(() -> prepared.parser().parse(unknown.toString()))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    private AnalysisResult analysis() {
        return prompts.causeAnalysis(
                        trace(analysisId, null), attempts, 1, input, null, observations)
                .parser()
                .parse(OBSERVED.path("analysis").toString());
    }

    private CandidateResponse candidate(String text) {
        var body = JSON.createObjectNode().put("text", text).putNull("mascot_hint");
        return prompts.responseGeneration(
                        trace(analysisId, candidateId),
                        attempts,
                        1,
                        input,
                        analysis(),
                        candidateId,
                        null)
                .parser()
                .parse(body.toString());
    }

    private PreparedPrompt<EvaluationResult> evaluation(CandidateResponse candidate) {
        return prompts.responseEvaluation(
                trace(analysisId, candidateId),
                attempts,
                1,
                input,
                analysis(),
                candidate,
                observations);
    }

    private static ObjectNode accepted(LlmRequest request) {
        var body = JSON.createObjectNode();
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
        return (ObjectNode)
                JSON.readTree(
                        EvaluationWireFixtures.forRequest(
                                body.toString(), JSON.readTree(request.userPrompt())));
    }

    private static ObjectNode rejected(LlmRequest request) {
        var body = accepted(request);
        body.put("decision", "REGENERATE");
        body.withArray("failure_codes").add("ANALYSIS_RESPONSE_MISMATCH").add("NOT_ANSWERABLE");
        ((ObjectNode) body.path("checks"))
                .put("analysis_response_consistent", false)
                .put("answerable", false);
        revision(body);
        return EvaluationWireFixtures.characterContract(body, JSON.readTree(request.userPrompt()));
    }

    private static void revision(ObjectNode body) {
        var revision = body.putObject("revision_instruction");
        for (String key : List.of("keep", "change", "avoid")) revision.putArray(key);
        if (body.path("character_interaction_review").isNull()) revision.putArray("required");
        revision.withArray("change").add("해당 목표의 행동 단서를 보완한다.");
    }

    private AiTraceContext trace(UUID analysis, UUID candidate) {
        return new AiTraceContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                input.learnerTurn().turnId(),
                analysis,
                candidate);
    }

    private static AiCallResult<LlmCompletion> success(LlmRequest request, String raw) {
        return new AiCallResult.Success<>(
                new LlmCompletion(raw, "fake", "fake", "STOP", null, null),
                new AiCallMetadata(
                        request.traceContext().requestId(),
                        request.operation(),
                        "fake",
                        "fake",
                        request.promptVersion(),
                        request.responseSchemaVersion(),
                        null,
                        0,
                        request.currentAttempt(),
                        null,
                        null,
                        "STOP"));
    }

    private static JsonNode loadObserved() {
        try (var stream =
                CharacterInteractionP1RegressionTest.class.getResourceAsStream(
                        "/eval/character-interaction-p1-observed.json")) {
            return JSON.readTree(Objects.requireNonNull(stream).readAllBytes());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
