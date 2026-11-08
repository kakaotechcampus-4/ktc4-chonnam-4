package com.neuringo.neuringobe.ai.application.prompt;

import static com.neuringo.neuringobe.ai.application.prompt.RoleplayFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.roleplay.*;
import com.neuringo.neuringobe.ai.application.structured.*;
import com.neuringo.neuringobe.ai.application.structured.output.*;
import com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class RoleplayLatencyContractTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RoleplayTurnInput input = characterInput("도와줄까");
    private final GoalObservationInput observations =
            RoleplayObservationTestPlans.inputForScenario(input.scenario());
    private final RoleplayPromptFactory compact = new RoleplayPromptFactory(JSON, null, true);
    private final RoleplayPromptFactory legacy = new RoleplayPromptFactory(JSON, null);

    @Test
    void compactAnalysisRestoresOnlyRequestOwnedReferencesAndStillRunsBusinessValidation() {
        var prepared = analyze(compact, trace(ANALYSIS_ID, null));
        String raw = compactAnalysis(prepared.request());
        var result = prepared.parser().parse(raw);
        assertThat(result.turnId()).isEqualTo(TURN_ID);
        assertThat(result.goalObservation().analysisId()).isEqualTo(ANALYSIS_ID);
        assertThat(result.goalObservation().observations())
                .hasSize(observations.approvedCriteria().size());
        assertThat(result.goalObservation().observations())
                .allSatisfy(
                        row -> {
                            assertThat(row.reference().turnId()).isEqualTo(TURN_ID);
                            assertThat(
                                            observations.contains(
                                                    row.reference().microGoalId(),
                                                    row.reference().criterionReference()))
                                    .isTrue();
                        });
        assertThat(prepared.request().responseSchemaVersion())
                .isEqualTo(CharacterModelOutput.ANALYSIS_VERSION);
        var illegal = (ObjectNode) JSON.readTree(raw);
        ((ObjectNode) illegal.path("next_strategy")).put("recommended_support_level", "S3");
        assertThatThrownBy(() -> prepared.parser().parse(illegal.toString()))
                .isInstanceOf(InvalidLlmOutputException.class);
        var forged = (ObjectNode) JSON.readTree(raw);
        ((ObjectNode) forged.path("activity_goal"))
                .putArray("basis")
                .addObject()
                .put("turn_id", UUID.randomUUID().toString())
                .put("micro_goal_id", MG_EMOTION.toString())
                .put("criterion_reference", "invented");
        assertThatThrownBy(() -> prepared.parser().parse(forged.toString()))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void compactAnalysisRejectsMissingExtraOrInventedKeysAndServerFields() {
        var prepared = analyze(compact, trace(ANALYSIS_ID, null));
        for (String mode : List.of("missing", "extra", "status", "id", "target")) {
            var body = (ObjectNode) JSON.readTree(compactAnalysis(prepared.request()));
            var rows = (ObjectNode) body.path("observations");
            switch (mode) {
                case "missing" -> rows.remove("o0");
                case "extra" -> rows.put("o999", "EXPLICIT");
                case "status" -> rows.put("o0", "ACHIEVED");
                case "id" -> body.put("turn_id", UUID.randomUUID().toString());
                case "target" ->
                        ((ObjectNode) body.path("next_strategy"))
                                .put("target_micro_goal_id", UUID.randomUUID().toString());
            }
            assertThatThrownBy(() -> prepared.parser().parse(body.toString()))
                    .as(mode)
                    .isInstanceOf(InvalidLlmOutputException.class);
        }
    }

    @Test
    void compactCandidateDoesNotAcceptModelOwnedMetadataOrBypassLengthAndHintChecks() {
        var analysis =
                analyze(compact, trace(ANALYSIS_ID, null))
                        .parser()
                        .parse(
                                compactAnalysis(
                                        analyze(compact, trace(ANALYSIS_ID, null)).request()));
        var prepared = generate(compact, analysis, CANDIDATE_ID);
        var candidate = prepared.parser().parse(candidateWire("응… 손을 꼭 잡았어."));
        assertThat(candidate.candidateId()).isEqualTo(CANDIDATE_ID);
        assertThat(candidate.turnId()).isEqualTo(TURN_ID);
        assertThat(candidate.responseType()).isEqualTo("GUIDING_QUESTION");
        assertThat(candidate.targetMicroGoalId()).isEqualTo(MG_EMOTION);
        assertThat(candidate.previousFailedCandidateIds()).isEmpty();
        for (String field :
                List.of(
                        "candidate_id",
                        "response_type",
                        "strategy_used",
                        "support_level",
                        "previous_failed_candidate_ids")) {
            var forged = (ObjectNode) JSON.readTree(candidateWire("응…"));
            forged.put(field, "invented");
            assertThatThrownBy(() -> prepared.parser().parse(forged.toString()))
                    .isInstanceOf(InvalidLlmOutputException.class);
        }
        assertThatThrownBy(() -> prepared.parser().parse(candidateWire("가".repeat(81))))
                .isInstanceOf(InvalidLlmOutputException.class);
        assertThatThrownBy(() -> prepared.parser().parse(candidateWire("왜? 정말?")))
                .isInstanceOf(InvalidLlmOutputException.class);
        assertThatThrownBy(
                        () -> prepared.parser().parse("{\"text\":\"응…\",\"mascot_hint\":\"정답\"}"))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void nativeS3CandidateStillRequiresIndependentSemanticApproval() {
        var analysis =
                JSON.readValue(
                        analysisJson("ADVANCE_GOAL", MG_EMOTION, "S3"), AnalysisResult.class);
        var candidate =
                generate(compact, analysis, CANDIDATE_ID).parser().parse(candidateWire("응…"));
        assertThat(candidate.responseType()).isEqualTo("ANSWER_REVEAL");
        var prepared = evaluate(analysis, candidate);
        var body = evaluation(prepared.request(), "REGENERATE", "[\"SUPPORT_LEVEL_MISMATCH\"]");
        ((ObjectNode) body.path("checks")).put("difficulty_appropriate", false);
        assertThat(prepared.parser().parse(body.toString()).canDeliver()).isFalse();
    }

    @Test
    void focusChangeAloneCannotBeRejectedAsAnAchievementClaimButActualCandidateClaimsCan() {
        var analysis =
                JSON.readValue(analysisJson("ADVANCE_GOAL", MG_EVENT, "S1"), AnalysisResult.class);
        var candidate =
                generate(compact, analysis, CANDIDATE_ID)
                        .parser()
                        .parse(candidateWire("응… 이제 다 끝났어."));
        var prepared = evaluate(analysis, candidate);
        var body = evaluation(prepared.request(), "REGENERATE", "[\"GOAL_SKIPPED\"]");
        ((ObjectNode) body.path("checks")).put("goal_aligned", false);
        body.set(
                "goal_progress_violation",
                JSON.valueToTree(
                        new GoalProgressViolation(
                                "UNSUPPORTED_ACHIEVEMENT_CLAIM", "ANALYSIS", null)));
        assertThatThrownBy(() -> prepared.parser().parse(body.toString()))
                .hasMessage(GoalProgressViolation.CONFLICT);
        body.set(
                "goal_progress_violation",
                JSON.valueToTree(
                        new GoalProgressViolation("PREMATURE_CLOSING", "CANDIDATE", "다 끝났어")));
        assertThat(prepared.parser().parse(body.toString()).canDeliver()).isFalse();
        ((ObjectNode) body.path("goal_progress_violation")).put("quote", "존재하지 않는 말");
        assertThatThrownBy(() -> prepared.parser().parse(body.toString()))
                .hasMessage(GoalProgressViolation.CONFLICT);
        assertThat(
                        prepared.parser()
                                .parse(evaluation(prepared.request(), "PASS", "[]").toString())
                                .canDeliver())
                .isTrue();
    }

    @Test
    void rejectedReviewAreasAndReasonsReachTheNextAnalysisWithoutChangingPublicResultJson() {
        var requests = new ArrayList<LlmRequest>();
        int[] evaluations = {0};
        LlmProvider provider =
                request -> {
                    requests.add(request);
                    String content;
                    if (request.operation() == AiOperation.CAUSE_ANALYSIS)
                        content = compactAnalysis(request);
                    else if (request.operation() == AiOperation.RESPONSE_GENERATION)
                        content = candidateWire("응… 손을 꼭 잡았어.");
                    else {
                        var body =
                                evaluation(
                                        request,
                                        evaluations[0]++ == 0 ? "REANALYZE" : "PASS",
                                        "[]");
                        if (evaluations[0] == 1) {
                            ((ObjectNode) body.path("current_goal_review"))
                                    .put("verdict", "UNSUPPORTED")
                                    .put("reason_code", "LEARNING_STATE_MISMATCH")
                                    .put("explanation", "부분 이해의 근거를 다시 판단한다.");
                            ((ObjectNode) body.path("interpretation_strategy_review"))
                                    .put("verdict", "UNSUPPORTED")
                                    .put("reason_code", "RESPONSE_ACT_MISMATCH")
                                    .put("explanation", "도움 제공과 요청을 구분한다.");
                        }
                        content = body.toString();
                    }
                    return success(request, content);
                };
        var steps =
                new StructuredRoleplayTurnSteps(
                        compact,
                        new StructuredLlmExecutor(provider),
                        trace(null, null),
                        input,
                        observations);
        assertThat(new RetryingRoleplayTurnExecutor().execute(TURN_ID, steps))
                .isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(requests.stream().map(r -> r.operation()))
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION);
        var feedback =
                JSON.readTree(requests.get(3).userPrompt()).path("retry").path("review_issues");
        assertThat(feedback.size()).isEqualTo(2);
        assertThat(feedback.get(1).path("reviewed_field").asString()).isEqualTo("response_act");
        assertThat(feedback.toString())
                .contains(
                        "CURRENT_GOAL",
                        "LEARNING_STATE_MISMATCH",
                        "INTERPRETATION_STRATEGY",
                        "RESPONSE_ACT_MISMATCH");
        var firstResult = evaluateParsedIssue();
        assertThat(firstResult.analysisReviewIssues()).hasSize(1);
        assertThat(JSON.writeValueAsString(firstResult))
                .doesNotContain(
                        "analysisReviewIssues", "analysis_review_issues", "RESPONSE_ACT_MISMATCH");
    }

    @Test
    void compactInputRetainsCanonicalEvidenceAndReducesTheWholeSerializedRequest() {
        var legacyAnalysis = analyze(legacy, trace(ANALYSIS_ID, null));
        var compactAnalysis = analyze(compact, trace(ANALYSIS_ID, null));
        var analysis = compactAnalysis.parser().parse(compactAnalysis(compactAnalysis.request()));
        var legacyGen = generate(legacy, analysis, CANDIDATE_ID);
        var compactGen = generate(compact, analysis, CANDIDATE_ID);
        var candidate = compactGen.parser().parse(candidateWire("응… 손을 꼭 잡았어."));
        var compactEval = evaluate(analysis, candidate);
        var legacyEval =
                legacy.responseEvaluation(
                        trace(ANALYSIS_ID, CANDIDATE_ID),
                        attempts(),
                        1,
                        input,
                        analysis,
                        candidate,
                        observations);
        for (var pair :
                List.of(
                        List.of(legacyAnalysis.request(), compactAnalysis.request()),
                        List.of(legacyGen.request(), compactGen.request()),
                        List.of(legacyEval.request(), compactEval.request()))) {
            var before = pair.get(0);
            var after = pair.get(1);
            assertThat(size(after)).isLessThan(size(before));
            System.out.printf(
                    "PAYLOAD_SIZE operation=%s before_chars=%d after_chars=%d before_system=%d after_system=%d before_user=%d after_user=%d before_schema=%d after_schema=%d%n",
                    after.operation(),
                    size(before),
                    size(after),
                    before.systemPrompt().length(),
                    after.systemPrompt().length(),
                    before.userPrompt().length(),
                    after.userPrompt().length(),
                    schemaLength(before),
                    schemaLength(after));
        }
        var before = JSON.readTree(legacyEval.request().userPrompt());
        var after = JSON.readTree(compactEval.request().userPrompt());
        assertThat(after.path("goal_observation_context"))
                .isEqualTo(before.path("goal_observation_context"));
        assertThat(after.path("independence_review_context"))
                .isEqualTo(before.path("independence_review_context"));
        assertThat(after.path("goal_evidence_so_far"))
                .isEqualTo(before.path("goal_evidence_so_far"));
        for (String key : after.path("observation_tasks").propertyNames()) {
            assertThat(after.path("observation_tasks").path(key).path("reference"))
                    .isEqualTo(before.path("observation_tasks").path(key).path("reference"));
            assertThat(after.path("observation_tasks").path(key).path("status"))
                    .isEqualTo(before.path("observation_tasks").path(key).path("status"));
        }
        String full = fullAnalysis();
        String small = compactAnalysis(compactAnalysis.request());
        assertThat(small.length()).isLessThan(full.length());
        System.out.printf(
                "OUTPUT_SIZE operation=CAUSE_ANALYSIS before_chars=%d after_chars=%d%n",
                full.length(), small.length());
        String fullCandidate = JSON.writeValueAsString(candidate);
        System.out.printf(
                "OUTPUT_SIZE operation=RESPONSE_GENERATION before_chars=%d after_chars=%d%n",
                fullCandidate.length(), candidateWire(candidate.text()).length());
    }

    @Test
    void requiredMascotHintIsExplicitlyBoundByTheNativeCandidateSchema() {
        var analysis =
                JSON.readValue(
                        analysisJson("PROBE_EMOTION", MG_EMOTION, "S2")
                                .replace("ANSWER_ATTEMPT", "DONT_KNOW"),
                        AnalysisResult.class);
        var prepared = generate(compact, analysis, CANDIDATE_ID);
        assertThatThrownBy(() -> prepared.parser().parse(candidateWire("응…")))
                .isInstanceOf(InvalidLlmOutputException.class);
        var body = (ObjectNode) JSON.readTree(candidateWire("응… 손을 꼭 잡았어."));
        body.put("mascot_hint", "친구에게 어디가 아픈지 물어볼까?");
        assertThat(prepared.parser().parse(body.toString()).mascotHint())
                .isEqualTo("친구에게 어디가 아픈지 물어볼까?");
    }

    @Test
    void observationBindingsRemainLocalWhenApprovedCriteriaOrderChanges() {
        var reversed = new ArrayList<>(observations.approvedCriteria());
        Collections.reverse(reversed);
        var reordered =
                new GoalObservationInput(
                        reversed,
                        observations.confirmedObservations(),
                        observations.confirmedObservationContexts(),
                        observations.currentGoalExposures(),
                        observations.supportAdjustment());
        var otherId = UUID.randomUUID();
        var other =
                compact.causeAnalysis(trace(otherId, null), attempts(), 1, input, null, reordered);
        var first = analyze(compact, trace(ANALYSIS_ID, null));
        var firstResult = first.parser().parse(compactAnalysis(first.request()));
        var otherResult = other.parser().parse(compactAnalysis(other.request()));
        assertThat(
                        firstResult
                                .goalObservation()
                                .observations()
                                .getFirst()
                                .reference()
                                .microGoalId())
                .isEqualTo(observations.approvedCriteria().getFirst().microGoalId());
        assertThat(
                        otherResult
                                .goalObservation()
                                .observations()
                                .getFirst()
                                .reference()
                                .microGoalId())
                .isEqualTo(reversed.getFirst().microGoalId());
        assertThat(firstResult.goalObservation().analysisId()).isEqualTo(ANALYSIS_ID);
        assertThat(otherResult.goalObservation().analysisId()).isEqualTo(otherId);
    }

    private EvaluationResult evaluateParsedIssue() {
        var analysis =
                JSON.readValue(
                        analysisJson("PROBE_EMOTION", MG_EMOTION, "S1"), AnalysisResult.class);
        var prepared =
                evaluate(
                        analysis,
                        generate(compact, analysis, CANDIDATE_ID)
                                .parser()
                                .parse(candidateWire("응…")));
        var body = evaluation(prepared.request(), "REANALYZE", "[]");
        ((ObjectNode) body.path("interpretation_strategy_review"))
                .put("verdict", "UNSUPPORTED")
                .put("reason_code", "RESPONSE_ACT_MISMATCH")
                .put("explanation", "응답 행위를 다시 판단한다.");
        return prepared.parser().parse(body.toString());
    }

    private PreparedPrompt<AnalysisResult> analyze(
            RoleplayPromptFactory factory, AiTraceContext trace) {
        return factory.causeAnalysis(trace, attempts(), 1, input, null, observations);
    }

    private PreparedPrompt<CandidateResponse> generate(
            RoleplayPromptFactory factory, AnalysisResult analysis, UUID id) {
        return factory.responseGeneration(
                trace(ANALYSIS_ID, id), attempts(), 1, input, analysis, id, null);
    }

    private PreparedPrompt<EvaluationResult> evaluate(
            AnalysisResult analysis, CandidateResponse candidate) {
        return compact.responseEvaluation(
                trace(ANALYSIS_ID, candidate.candidateId()),
                attempts(),
                1,
                input,
                analysis,
                candidate,
                observations);
    }

    private String fullAnalysis() {
        var body = (ObjectNode) JSON.readTree(analysisJson("PROBE_EMOTION", MG_EMOTION, "S1"));
        var rows = ((ObjectNode) body.path("goal_observation")).putArray("observations");
        for (var criterion : observations.approvedCriteria())
            rows.addObject()
                    .set(
                            "reference",
                            JSON.valueToTree(
                                    new GoalObservationOutput.Reference(
                                            TURN_ID,
                                            criterion.microGoalId(),
                                            criterion.reference())))
                    .put("status", "NOT_EXPRESSED");
        return body.toString();
    }

    private String compactAnalysis(LlmRequest request) {
        return CharacterWireFixtures.forRequest(fullAnalysis(), request);
    }

    private static String candidateWire(String text) {
        var body = JSON.createObjectNode();
        body.put("text", text);
        body.putNull("mascot_hint");
        return body.toString();
    }

    private static ObjectNode evaluation(LlmRequest request, String decision, String codes) {
        var body =
                (ObjectNode)
                        JSON.readTree(
                                EvaluationWireFixtures.forRequest(
                                        evaluationOutputJson(
                                                decision,
                                                codes,
                                                decision.equals("PASS")
                                                        ? "null"
                                                        : "{\"keep\":[],\"change\":[],\"avoid\":[],\"required\":[]}"),
                                        JSON.readTree(request.userPrompt())));
        body.put("candidate_id", request.traceContext().candidateId().toString());
        return body;
    }

    private static AiCallResult<LlmCompletion> success(LlmRequest r, String content) {
        return new AiCallResult.Success<>(
                new LlmCompletion(content, "fake", "fake", "STOP", null, null),
                new AiCallMetadata(
                        r.traceContext().requestId(),
                        r.operation(),
                        "fake",
                        "fake",
                        r.promptVersion(),
                        r.responseSchemaVersion(),
                        null,
                        0,
                        r.currentAttempt(),
                        null,
                        null,
                        "STOP"));
    }

    private static int schemaLength(LlmRequest r) {
        return r.responseJsonSchema() == null ? 0 : r.responseJsonSchema().length();
    }

    private static int size(LlmRequest r) {
        return r.systemPrompt().length() + r.userPrompt().length() + schemaLength(r);
    }
}
