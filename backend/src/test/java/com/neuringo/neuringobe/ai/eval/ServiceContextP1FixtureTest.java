package com.neuringo.neuringobe.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.IntegrationTest;
import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayPromptFactory;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.roleplay.application.RoleplayContextAssembler;
import com.neuringo.neuringobe.roleplay.service.JdbcRoleplayContextSource;
import com.neuringo.neuringobe.roleplay.service.RoleplayRequestScopeService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** 실제 문맥·진행·지원 정책을 거친 합성 첫 입력을 내보낸다. 외부 AI는 호출하지 않는다. */
@IntegrationTest
@TestPropertySource(
        properties = {
            "roleplay.progress.maximum-turn-count=6",
            "roleplay.progress.maximum-support-turns-per-micro-goal=4",
            "roleplay.retention.cleanup-enabled=false"
        })
class ServiceContextP1FixtureTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcRoleplayContextSource contexts;
    @Autowired RoleplayRequestScopeService scopes;

    @Test
    void exportsFirstInputFromRealServicesWithoutReplacingProgressOrInventingHistory()
            throws Exception {
        JsonNode original;
        try (var stream = getClass().getResourceAsStream("/" + AnalysisReviewDiagnostic.FIXTURE)) {
            original =
                    EvalReport.JSON.readTree(
                            new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        var oldInput = original.path("input");
        var ids = seed(oldInput.path("scenario"));
        UUID turn = UUID.fromString(oldInput.path("learner_turn").path("turn_id").asString());
        var scope = scopes.load(ids.child(), ids.activity(), ids.session());
        var prepared =
                new RoleplayContextAssembler(contexts)
                        .prepare(scope, UUID.randomUUID(), turn, RoleplayTurnDeadline.start());
        var context = prepared.input();
        var input =
                new RoleplayTurnInput(
                        context.scenario(),
                        context.state(),
                        new RoleplayTurnInput.LearnerTurn(
                                turn,
                                oldInput.path("learner_turn")
                                        .path("canonical_utterance")
                                        .asString()),
                        context.recentDialogue());
        var observations = prepared.observations();
        assertThat(input.state().turnCount()).isEqualTo(1);
        assertThat(input.state().currentMicroGoalPromptCount()).isZero();
        assertThat(input.state().learningTurnCount()).isEqualTo(1);
        assertThat(input.state().currentMicroGoalLearningSupportTurn()).isEqualTo(1);
        assertThat(input.state().currentSupportLevel()).isEqualTo("S0");
        assertThat(input.recentDialogue()).isEmpty();
        assertThat(observations.confirmedObservations()).isEmpty();
        assertThat(observations.supportAdjustment().status().name()).isEqualTo("NOT_ELIGIBLE");
        assertThat(observations.supportAdjustment().policyVersion())
                .isEqualTo("SUPPORT_DECREASE_V1");
        assertThat(observations.supportAdjustment().requiredIndependentGoals()).isEqualTo(2);
        assertThat(observations.supportAdjustment().inProgressHandling())
                .isEqualTo("RESET_SEQUENCE");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=?",
                                Integer.class,
                                ids.session()))
                .isZero();

        var referenceBindings = new LinkedHashMap<String, String>();
        for (var old : oldInput.path("goal_observation_context").path("approved_criteria")) {
            UUID mapped = ids.goals().get(old.path("micro_goal_id").asString());
            var criterion =
                    observations.approvedCriteria().stream()
                            .filter(
                                    c ->
                                            c.microGoalId().equals(mapped)
                                                    && c.description()
                                                            .equals(
                                                                    old.path("description")
                                                                            .asString()))
                            .findFirst()
                            .orElseThrow();
            referenceBindings.put(old.path("reference").asString(), criterion.reference());
        }
        String rebound = oldInput.path("analysis_result").toString();
        for (var binding : referenceBindings.entrySet())
            rebound = rebound.replace(binding.getKey(), binding.getValue());
        for (var binding : ids.goals().entrySet())
            rebound = rebound.replace(binding.getKey(), binding.getValue().toString());
        var expectedAnalysis = EvalReport.JSON.readTree(rebound);
        var trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        ids.activity(),
                        null,
                        ids.child(),
                        ids.scenario(),
                        1,
                        ids.session(),
                        turn,
                        UUID.fromString(original.path("analysis_id").asString()),
                        UUID.fromString(original.path("candidate_id").asString()));
        var prompts = new RoleplayPromptFactory(EvalReport.JSON, null);
        var analysis =
                prompts.causeAnalysis(
                                trace, new AiAttemptContext(0, 0, 0), 1, input, null, observations)
                        .parser()
                        .parse(rebound);
        var oldCandidate = oldInput.path("candidate_response");
        var candidate =
                new CandidateResponse(
                        trace.candidateId(),
                        turn,
                        oldCandidate.path("text").asString(),
                        oldCandidate.path("response_type").asString(),
                        oldCandidate.path("strategy_used").asString(),
                        ids.goals().get(oldCandidate.path("target_micro_goal_id").asString()),
                        oldCandidate.path("support_level").asString(),
                        List.of());
        var payload =
                EvalReport.JSON.readTree(
                        prompts.responseEvaluation(
                                        trace,
                                        new AiAttemptContext(0, 0, 0),
                                        1,
                                        input,
                                        analysis,
                                        candidate,
                                        observations)
                                .request()
                                .userPrompt());
        var reconstructed =
                com.neuringo.neuringobe.ai.application.prompt.EvaluationWireFixtures
                        .analysisForRequest(payload);
        var legacyPayload = (tools.jackson.databind.node.ObjectNode) payload.deepCopy();
        for (String key :
                List.of(
                        "observation_tasks",
                        "current_goal_assessment",
                        "activity_assessment",
                        "interpretation_strategy")) legacyPayload.remove(key);
        legacyPayload.set("analysis_result", reconstructed);
        payload = legacyPayload;
        assertThat(payload.path("analysis_result")).isEqualTo(expectedAnalysis);
        assertThat(payload.path("scenario").path("learning_goal"))
                .isEqualTo(oldInput.path("scenario").path("learning_goal"));
        assertThat(payload.path("scenario").path("scenario_facts"))
                .isEqualTo(oldInput.path("scenario").path("scenario_facts"));
        assertThat(payload.path("scenario").path("prohibited_inferences"))
                .isEqualTo(oldInput.path("scenario").path("prohibited_inferences"));
        assertThat(payload.path("scenario").path("ai_character"))
                .isEqualTo(oldInput.path("scenario").path("ai_character"));
        assertThat(payload.path("learner_turn")).isEqualTo(oldInput.path("learner_turn"));

        var exported = EvalReport.JSON.createObjectNode();
        exported.put("source_run", "service-context-synthetic-p1-v1");
        exported.put("source_sequence", 1);
        exported.put("analysis_id", trace.analysisId().toString());
        exported.put("candidate_id", trace.candidateId().toString());
        exported.set("input", payload);
        exported.set(
                "assembly_evidence",
                EvalReport.JSON.valueToTree(
                        Map.of(
                                "synthetic_only",
                                true,
                                "real_progress_policy",
                                true,
                                "assembler",
                                RoleplayContextAssembler.class.getSimpleName(),
                                "context_source",
                                JdbcRoleplayContextSource.class.getSimpleName(),
                                "context_fingerprint",
                                prepared.contextFingerprint(),
                                "id_bindings",
                                ids.goals(),
                                "reference_bindings",
                                referenceBindings)));
        var diagnostic = new AnalysisReviewDiagnostic(exported);
        var request = diagnostic.request(RoleplayTurnDeadline.start());
        assertThat(request.userPrompt())
                .doesNotContain("candidate_response", oldCandidate.path("text").asString());
        assertThat(request.promptVersion()).isEqualTo(AnalysisReviewDiagnostic.PROMPT_VERSION);
        var output =
                EvalReport.JSON
                        .createObjectNode()
                        .put("analysis_id", trace.analysisId().toString());
        for (String field : AnalysisReviewDiagnostic.REVIEWS) {
            output.putObject(field)
                    .put("status", "SUPPORTED")
                    .put("reason_code", "NONE")
                    .putArray("criterion_references")
                    .add(referenceBindings.values().iterator().next());
        }
        assertThat(diagnostic.validate(output.toString())).isEqualTo(output);
        Path dir = Path.of("build/eval/service-context-p1-fixture");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("source.json"), exported.toString());
    }

    private Ids seed(JsonNode scenario) {
        UUID teacher = UUID.randomUUID(),
                classroom = UUID.randomUUID(),
                child = UUID.randomUUID(),
                goal = UUID.randomUUID(),
                activity = UUID.randomUUID(),
                scenarioId = UUID.randomUUID(),
                session = UUID.randomUUID();
        var goals = new LinkedHashMap<String, UUID>();
        jdbc.update(
                "insert into user_account(user_id,email,password_hash,name,role,status,created_at) values(?,?,?,'synthetic','INSTRUCTOR','ACTIVE',now())",
                teacher,
                teacher + "@example.com",
                "test-hash");
        jdbc.update(
                "insert into classroom(class_id,instructor_id,name,status) values(?,?,'synthetic','ACTIVE')",
                classroom,
                teacher);
        jdbc.update(
                "insert into child(child_id,class_id,display_name,status) values(?,?,'synthetic','ACTIVE')",
                child,
                classroom);
        jdbc.update(
                "insert into learning_goal(goal_id,child_id,instructor_id,title,content_hash,created_at) values(?,?,?,?,?,now())",
                goal,
                child,
                teacher,
                scenario.path("learning_goal").asString(),
                "a".repeat(64));
        jdbc.update(
                "insert into activity(activity_id,child_id,goal_id,scenario_id,activity_type,status,assigned_at) values(?,?,?,?,'ROLEPLAY','IN_PROGRESS',now())",
                activity,
                child,
                goal,
                scenarioId);
        jdbc.update(
                "insert into scenario(scenario_id,class_id,goal_content_hash,status,scenario_level,title,ai_role,characters,scenario_facts,prohibited_inferences,origin,created_at) values(?,?,?,'DRAFT',?,'synthetic P1',?,?::jsonb,?::jsonb,?::jsonb,'PREPARED',now())",
                scenarioId,
                classroom,
                "a".repeat(64),
                scenario.path("scenario_level").asString(),
                scenario.path("ai_character").path("id").asString(),
                "[" + scenario.path("ai_character") + "]",
                scenario.path("scenario_facts").toString(),
                scenario.path("prohibited_inferences").toString());
        int order = 0;
        for (var microGoal : scenario.path("micro_goals")) {
            UUID id = UUID.randomUUID();
            goals.put(microGoal.path("id").asString(), id);
            jdbc.update(
                    "insert into scenario_micro_goal(micro_goal_id,scenario_id,order_no,description,required_evidence) values(?,?,?,?,?::jsonb)",
                    id,
                    scenarioId,
                    ++order,
                    microGoal.path("description").asString(),
                    microGoal.path("required_evidence").toString());
        }
        jdbc.update(
                "insert into roleplay_session(session_id,activity_id,child_id,scenario_id,scenario_version,status,last_activity_at,current_micro_goal_id,current_support_level) values(?,?,?,?,1,'IN_PROGRESS',now(),?,'S0')",
                session,
                activity,
                child,
                scenarioId,
                goals.values().iterator().next());
        jdbc.update("update scenario set status='APPROVED' where scenario_id=?", scenarioId);
        return new Ids(child, activity, session, scenarioId, goals);
    }

    private record Ids(
            UUID child, UUID activity, UUID session, UUID scenario, Map<String, UUID> goals) {}
}
