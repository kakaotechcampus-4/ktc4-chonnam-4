package com.neuringo.neuringobe.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.neuringo.neuringobe.LocalProfileIntegrationTest;
import com.neuringo.neuringobe.TestFixtures;
import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.scenario.dto.ScenarioReviewResponse;
import com.neuringo.neuringobe.scenario.support.ScenarioPoolTestIsolation;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@LocalProfileIntegrationTest
@org.junit.jupiter.api.extension.ExtendWith(ScenarioPoolTestIsolation.class)
class ScenarioReviewHttpIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private TestFixtures fixtures;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @MockitoBean private LlmProvider llm;

    @BeforeEach
    void generatedScenario() throws Exception {
        String content =
                new ClassPathResource("scenario/generation-valid-l2.json")
                        .getContentAsString(StandardCharsets.UTF_8);
        doAnswer(
                        call -> {
                            LlmRequest request = call.getArgument(0);
                            return new AiCallResult.Success<>(
                                    new LlmCompletion(content, "fake", "fixture", "stop", 10, 20),
                                    new AiCallMetadata(
                                            request.traceContext().requestId(),
                                            request.operation(),
                                            "fake",
                                            "fixture",
                                            request.promptVersion(),
                                            request.responseSchemaVersion(),
                                            request.policyVersion(),
                                            5,
                                            1,
                                            10,
                                            20,
                                            "stop"));
                        })
                .when(llm)
                .complete(any());
    }

    @Test
    void preparesOneCompleteScenarioAndRestoresWithoutAssigningOrRegenerating() throws Exception {
        var ctx = context(fixtures.signUpInstructor());
        UUID key = UUID.randomUUID();
        var first = prepare(ctx, key);
        assertThat(first.conditions().title()).isEqualTo("차례를 기다려요");
        assertThat(first.conditions().scenarioLevel()).isEqualTo("L2");
        assertThat(first.scenario().scenarioFacts()).hasSize(4);
        assertThat(first.scenario().microGoals()).hasSize(3);
        assertThat(first.scenario().startingQuestionGoalIds()).containsOnlyKeys("S0", "S1", "S2");
        assertThat(first.scenario().startingQuestionGoalIds().values())
                .allMatch(
                        id ->
                                first.scenario().microGoals().stream()
                                        .anyMatch(g -> g.microGoalId().equals(id)));
        assertThat(prepare(ctx, key)).isEqualTo(first);
        assertThat(prepare(ctx, UUID.randomUUID())).isEqualTo(first);
        assertThat(assigned(ctx)).isNull();
        assertThat(
                        count(
                                "scenario_presentation_stat",
                                "scenario_id",
                                first.scenario().scenarioId()))
                .isEqualTo(1);
        verify(llm, times(1)).complete(any());
    }

    @Test
    void confirmationAttachesOnlyThenAndReplaysWithoutDuplicatingApproval() throws Exception {
        var ctx = context(fixtures.signUpInstructor());
        var offered = prepare(ctx, UUID.randomUUID());
        UUID key = UUID.randomUUID();
        var body = confirmation(offered);
        var first =
                send(ctx, HttpMethod.PUT, "/scenario", body, key)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.outcome").value("CONFIRMED"))
                        .andReturn();
        send(ctx, HttpMethod.PUT, "/scenario", body, key)
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.closedAt")
                                .value(
                                        json.readTree(first.getResponse().getContentAsString())
                                                .path("data")
                                                .path("closedAt")
                                                .asText()));
        assertThat(assigned(ctx)).isEqualTo(offered.scenario().scenarioId());
        assertThat(count("scenario_review", "activity_id", ctx.activity())).isZero();
        assertThat(count("activity_scenario_approval", "activity_id", ctx.activity())).isEqualTo(1);
        send(
                        ctx,
                        HttpMethod.POST,
                        "/scenario-review",
                        Map.of("action", "START_OR_RESUME"),
                        UUID.randomUUID())
                .andExpect(status().isConflict());
        body.put("edits", Map.of("title", "다른 제목"));
        send(ctx, HttpMethod.PUT, "/scenario", body, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void reusedScenarioAlsoNeedsApprovalAndNextAutomaticallyGeneratesOnExhaustion()
            throws Exception {
        var owner = fixtures.signUpInstructor();
        var source = context(owner);
        var original = prepare(source, UUID.randomUUID());
        send(source, HttpMethod.PUT, "/scenario", confirmation(original), UUID.randomUUID())
                .andExpect(status().isOk());
        var ctx = context(owner);
        var reused = prepare(ctx, UUID.randomUUID());
        assertThat(reused.scenario().scenarioId()).isEqualTo(original.scenario().scenarioId());
        assertThat(assigned(ctx)).isNull();
        verify(llm, times(1)).complete(any());
        UUID nextKey = UUID.randomUUID();
        var body =
                Map.of(
                        "action",
                        "NEXT",
                        "reviewId",
                        reused.reviewId(),
                        "revision",
                        reused.revision());
        var replacement =
                response(
                        send(ctx, HttpMethod.POST, "/scenario-review", body, nextKey)
                                .andExpect(status().isOk()));
        assertThat(replacement.scenario().scenarioId())
                .isNotEqualTo(reused.scenario().scenarioId());
        assertThat(replacement.revision()).isGreaterThan(reused.revision());
        assertThat(
                        response(
                                send(ctx, HttpMethod.POST, "/scenario-review", body, nextKey)
                                        .andExpect(status().isOk())))
                .isEqualTo(replacement);
        verify(llm, times(2)).complete(any());
        assertThat(assigned(ctx)).isNull();
        send(ctx, HttpMethod.POST, "/scenario-review", body, UUID.randomUUID())
                .andExpect(status().isConflict());
    }

    @Test
    void allInstructorsReuseApprovedContentButApproveTheirOwnAssignments() throws Exception {
        var owner = fixtures.signUpInstructor();
        var source = context(owner);
        var original = prepare(source, UUID.randomUUID());
        send(source, HttpMethod.PUT, "/scenario", confirmation(original), UUID.randomUUID())
                .andExpect(status().isOk());
        var ctx = context(owner);
        var reused = prepare(ctx, UUID.randomUUID());
        send(ctx, HttpMethod.PUT, "/scenario", confirmation(reused), UUID.randomUUID())
                .andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reuse_count FROM scenario_presentation_stat WHERE instructor_id=? AND scenario_id=?",
                                Long.class,
                                owner.userId(),
                                original.scenario().scenarioId()))
                .isEqualTo(1);
        var other = context(fixtures.signUpInstructor());
        var offered = prepare(other, UUID.randomUUID());
        assertThat(offered.scenario().scenarioId()).isEqualTo(original.scenario().scenarioId());
        assertThat(assigned(other)).isNull();
        var impersonating = new Context(owner, other.activity(), other.goal());
        send(impersonating, HttpMethod.PUT, "/scenario", confirmation(offered), UUID.randomUUID())
                .andExpect(status().isNotFound());
        send(other, HttpMethod.PUT, "/scenario", confirmation(offered), UUID.randomUUID())
                .andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT instructor_id FROM activity_scenario_approval WHERE activity_id=?",
                                UUID.class,
                                other.activity()))
                .isEqualTo(other.owner().userId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT instructor_id FROM scenario_approval WHERE scenario_id=?",
                                UUID.class,
                                original.scenario().scenarioId()))
                .isEqualTo(owner.userId());
        verify(llm, times(1)).complete(any());
    }

    @Test
    void editedConfirmationCreatesNewVersionAndUuidGoalPatchWorks() throws Exception {
        var ctx = context(fixtures.signUpInstructor());
        var offered = prepare(ctx, UUID.randomUUID());
        var body = confirmation(offered);
        body.put(
                "edits",
                Map.of(
                        "title",
                        "친구와 함께 기다려요",
                        "microGoals",
                        List.of(
                                Map.of(
                                        "microGoalId",
                                        offered.scenario().microGoals().getFirst().microGoalId(),
                                        "description",
                                        "친구의 표정을 설명한다."))));
        send(ctx, HttpMethod.PUT, "/scenario", body, UUID.randomUUID()).andExpect(status().isOk());
        assertThat(assigned(ctx)).isNotEqualTo(offered.scenario().scenarioId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT source_scenario_id FROM scenario WHERE scenario_id=?",
                                UUID.class,
                                assigned(ctx)))
                .isEqualTo(offered.scenario().scenarioId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT scenario_version FROM scenario WHERE scenario_id=?",
                                Integer.class,
                                assigned(ctx)))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForList(
                                "SELECT description FROM scenario_micro_goal WHERE scenario_id=?",
                                String.class,
                                assigned(ctx)))
                .contains("친구의 표정을 설명한다.");
    }

    @Test
    void cancelDeletesTemporaryReviewAndDraftAndReplaysClosure() throws Exception {
        var ctx = context(fixtures.signUpInstructor());
        UUID startKey = UUID.randomUUID();
        var offered = prepare(ctx, startKey);
        UUID key = UUID.randomUUID();
        var body = Map.of("reviewId", offered.reviewId(), "revision", offered.revision());
        for (int i = 0; i < 2; i++)
            send(ctx, HttpMethod.DELETE, "/scenario-review", body, key)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.outcome").value("CANCELLED"));
        assertThat(count("scenario_review", "activity_id", ctx.activity())).isZero();
        assertThat(count("scenario_review_candidate", "review_id", offered.reviewId())).isZero();
        assertThat(count("scenario", "scenario_id", offered.scenario().scenarioId())).isZero();
        send(
                        ctx,
                        HttpMethod.POST,
                        "/scenario-review",
                        Map.of("action", "START_OR_RESUME"),
                        startKey)
                .andExpect(status().isConflict());
        assertThat(prepare(ctx, UUID.randomUUID()).reviewId()).isNotEqualTo(offered.reviewId());
    }

    @Test
    void requestsEnforceAuthenticationCsrfAndInstructorOwnership() throws Exception {
        var ctx = context(fixtures.signUpInstructor());
        var offered = prepare(ctx, UUID.randomUUID());
        var other = new Context(fixtures.signUpInstructor(), ctx.activity(), ctx.goal());
        for (var method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
            String path = method == HttpMethod.PUT ? "/scenario" : "/scenario-review";
            Object body =
                    method == HttpMethod.POST
                            ? Map.of("action", "START_OR_RESUME")
                            : method == HttpMethod.PUT
                                    ? confirmation(offered)
                                    : Map.of(
                                            "reviewId",
                                            offered.reviewId(),
                                            "revision",
                                            offered.revision());
            var csrf = fixtures.fetchCsrf();
            mvc.perform(
                            request(method, url(ctx, path))
                                    .cookie(csrf.cookies())
                                    .header(csrf.headerName(), csrf.token())
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(body)))
                    .andExpect(status().isUnauthorized());
            mvc.perform(
                            request(method, url(ctx, path))
                                    .header("Authorization", "Bearer " + ctx.owner().accessToken())
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
            send(other, method, path, body, UUID.randomUUID()).andExpect(status().isNotFound());
        }
        assertThat(assigned(ctx)).isNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"action\":\"NEXT\"}",
                "{\"action\":\"NEXT\",\"reviewId\":\"%s\",\"revision\":0}",
                "{\"action\":\"START_OR_RESUME\",\"revision\":1}"
            })
    void invalidPreparationFieldsReturn422(String body) throws Exception {
        var ctx = context(fixtures.signUpInstructor());
        send(
                        ctx,
                        HttpMethod.POST,
                        "/scenario-review",
                        body.formatted(UUID.randomUUID()),
                        UUID.randomUUID())
                .andExpect(status().isUnprocessableContent());
        verifyNoInteractions(llm);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{",
                "{\"action\":\"UNKNOWN\"}",
                "{\"action\":\"START_OR_RESUME\",\"scenarioId\":\"ignored\"}"
            })
    void malformedOrUnknownPreparationFieldsReturn400(String body) throws Exception {
        var ctx = context(fixtures.signUpInstructor());
        send(ctx, HttpMethod.POST, "/scenario-review", body, UUID.randomUUID())
                .andExpect(status().isBadRequest());
        verifyNoInteractions(llm);
    }

    @ParameterizedTest
    @ValueSource(strings = {"characters", "scenarioFacts", "achievementExample"})
    void forbiddenEditFieldsAreRejectedInsteadOfSilentlyIgnored(String field) throws Exception {
        var ctx = context(fixtures.signUpInstructor());
        var offered = prepare(ctx, UUID.randomUUID());
        var body = confirmation(offered);
        body.put("edits", Map.of(field, List.of("변경 시도")));
        send(ctx, HttpMethod.PUT, "/scenario", body, UUID.randomUUID())
                .andExpect(status().isBadRequest());
        body.put(
                "edits",
                Map.of(
                        "microGoals",
                        List.of(
                                Map.of(
                                        "microGoalId",
                                        offered.scenario().microGoals().getFirst().microGoalId(),
                                        field,
                                        "변경 시도"))));
        send(ctx, HttpMethod.PUT, "/scenario", body, UUID.randomUUID())
                .andExpect(status().isBadRequest());
        assertThat(assigned(ctx)).isNull();
    }

    @Test
    void missingKeysInvalidVersionsAndChangedContentCannotConfirm() throws Exception {
        var ctx = context(fixtures.signUpInstructor());
        var offered = prepare(ctx, UUID.randomUUID());
        send(ctx, HttpMethod.POST, "/scenario-review", Map.of("action", "START_OR_RESUME"), null)
                .andExpect(status().isBadRequest());
        var body = confirmation(offered);
        body.put("scenarioVersion", 0);
        send(ctx, HttpMethod.PUT, "/scenario", body, UUID.randomUUID())
                .andExpect(status().isUnprocessableContent());
        body.put("scenarioVersion", 2);
        send(ctx, HttpMethod.PUT, "/scenario", body, UUID.randomUUID())
                .andExpect(status().isConflict());
        body = confirmation(offered);
        body.put("edits", Map.of("title", "   "));
        send(ctx, HttpMethod.PUT, "/scenario", body, UUID.randomUUID())
                .andExpect(status().isUnprocessableContent());
        assertThat(assigned(ctx)).isNull();
    }

    @Test
    void noDifficultyEvidenceDoesNotStartReviewOrGeneration() throws Exception {
        var ctx = context(fixtures.signUpInstructor());
        jdbc.update("DELETE FROM quiz_result WHERE activity_id=?", ctx.activity());
        send(
                        ctx,
                        HttpMethod.POST,
                        "/scenario-review",
                        Map.of("action", "START_OR_RESUME"),
                        UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INITIAL_DIFFICULTY_NOT_READY"));
        verifyNoInteractions(llm);
    }

    @Test
    void invalidGenerationReturnsGatewayErrorAndRetryResumesReview() throws Exception {
        var ctx = context(fixtures.signUpInstructor());
        doAnswer(
                        call -> {
                            LlmRequest request = call.getArgument(0);
                            return new AiCallResult.Success<>(
                                    new LlmCompletion("{}", "fake", "fixture", "stop", 1, 1),
                                    new AiCallMetadata(
                                            request.traceContext().requestId(),
                                            request.operation(),
                                            "fake",
                                            "fixture",
                                            request.promptVersion(),
                                            request.responseSchemaVersion(),
                                            request.policyVersion(),
                                            1,
                                            1,
                                            1,
                                            1,
                                            "stop"));
                        })
                .when(llm)
                .complete(any());
        UUID key = UUID.randomUUID();
        send(ctx, HttpMethod.POST, "/scenario-review", Map.of("action", "START_OR_RESUME"), key)
                .andExpect(status().isBadGateway());
        assertThat(assigned(ctx)).isNull();
        generatedScenario();
        assertThat(prepare(ctx, key).scenario()).isNotNull();
    }

    @Test
    void sharedApprovalRemainsReusableThroughApiAfterFirstApproverWithdraws() throws Exception {
        var a = context(fixtures.signUpInstructor());
        var original = prepare(a, UUID.randomUUID());
        send(a, HttpMethod.PUT, "/scenario", confirmation(original), UUID.randomUUID())
                .andExpect(status().isOk());
        withdraw(a);
        var b = context(fixtures.signUpInstructor());
        var offered = prepare(b, UUID.randomUUID());
        assertThat(offered.scenario().scenarioId()).isEqualTo(original.scenario().scenarioId());
        send(b, HttpMethod.PUT, "/scenario", confirmation(offered), UUID.randomUUID())
                .andExpect(status().isOk());
        assertThat(assigned(b)).isEqualTo(original.scenario().scenarioId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT instructor_id FROM scenario_approval WHERE scenario_id=?",
                                UUID.class,
                                assigned(b)))
                .isNull();
        verify(llm, times(1)).complete(any());
    }

    @Test
    void rejectionAndRotationAreInstructorSpecificEvenAfterFirstApproverWithdraws()
            throws Exception {
        var a = context(fixtures.signUpInstructor());
        var first = prepare(a, UUID.randomUUID());
        send(a, HttpMethod.PUT, "/scenario", confirmation(first), UUID.randomUUID())
                .andExpect(status().isOk());
        var a2 = context(a.owner());
        var rejected = prepare(a2, UUID.randomUUID());
        var second = next(a2, rejected);
        send(a2, HttpMethod.PUT, "/scenario", confirmation(second), UUID.randomUUID())
                .andExpect(status().isOk());
        var b = context(fixtures.signUpInstructor());
        var c = context(fixtures.signUpInstructor());
        var offeredB = prepare(b, UUID.randomUUID());
        var offeredC = prepare(c, UUID.randomUUID());
        assertThat(offeredC.scenario().scenarioId()).isEqualTo(offeredB.scenario().scenarioId());
        withdraw(a);
        var nextB = next(b, offeredB);
        assertThat(nextB.scenario().scenarioId()).isNotEqualTo(offeredB.scenario().scenarioId());
        assertThat(prepare(c, UUID.randomUUID())).isEqualTo(offeredC);
        send(b, HttpMethod.PUT, "/scenario", confirmation(nextB), UUID.randomUUID())
                .andExpect(status().isOk());
        var c2 = context(c.owner());
        assertThat(prepare(c2, UUID.randomUUID()).scenario().scenarioId())
                .isEqualTo(nextB.scenario().scenarioId());
        var b2 = context(b.owner());
        assertThat(prepare(b2, UUID.randomUUID()).scenario().scenarioId())
                .isEqualTo(offeredB.scenario().scenarioId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM scenario WHERE scenario_id=?",
                                String.class,
                                offeredB.scenario().scenarioId()))
                .isEqualTo("APPROVED");
        verify(llm, times(2)).complete(any());
    }

    @Test
    void editingAnotherInstructorsCandidateRegistersNewSharedVersionWithoutChangingOriginal()
            throws Exception {
        var a = context(fixtures.signUpInstructor());
        var original = prepare(a, UUID.randomUUID());
        send(a, HttpMethod.PUT, "/scenario", confirmation(original), UUID.randomUUID())
                .andExpect(status().isOk());
        var b = context(fixtures.signUpInstructor());
        var offered = prepare(b, UUID.randomUUID());
        var body = confirmation(offered);
        body.put("edits", Map.of("title", "함께 친구를 도와요"));
        send(b, HttpMethod.PUT, "/scenario", body, UUID.randomUUID()).andExpect(status().isOk());
        UUID edited = assigned(b);
        assertThat(edited).isNotEqualTo(original.scenario().scenarioId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT source_scenario_id FROM scenario WHERE scenario_id=?",
                                UUID.class,
                                edited))
                .isEqualTo(original.scenario().scenarioId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT title FROM scenario WHERE scenario_id=?",
                                String.class,
                                original.scenario().scenarioId()))
                .isEqualTo(original.scenario().title());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT instructor_id FROM scenario_approval WHERE scenario_id=?",
                                UUID.class,
                                edited))
                .isEqualTo(b.owner().userId());
        var c = context(fixtures.signUpInstructor());
        var firstC = prepare(c, UUID.randomUUID());
        var nextC = next(c, firstC);
        assertThat(List.of(firstC.scenario().scenarioId(), nextC.scenario().scenarioId()))
                .containsExactlyInAnyOrder(edited, original.scenario().scenarioId());
        verify(llm, times(1)).complete(any());
    }

    private ScenarioReviewResponse next(Context ctx, ScenarioReviewResponse current)
            throws Exception {
        return response(
                send(
                                ctx,
                                HttpMethod.POST,
                                "/scenario-review",
                                Map.of(
                                        "action",
                                        "NEXT",
                                        "reviewId",
                                        current.reviewId(),
                                        "revision",
                                        current.revision()),
                                UUID.randomUUID())
                        .andExpect(status().isOk()));
    }

    private void withdraw(Context ctx) throws Exception {
        var csrf = fixtures.fetchCsrf();
        mvc.perform(
                        post("/api/v1/users/me/withdrawal")
                                .header("Authorization", "Bearer " + ctx.owner().accessToken())
                                .header(csrf.headerName(), csrf.token())
                                .cookie(csrf.cookies())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of("password", ctx.owner().password()))))
                .andExpect(status().isNoContent());
    }

    private Context context(TestFixtures.Instructor owner) {
        UUID classroom = fixtures.createClassroom(owner, "검토 학급");
        UUID child = fixtures.createChild(owner, classroom, "검토 아동");
        UUID goal = UUID.randomUUID();
        UUID activity = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO learning_goal (goal_id,child_id,instructor_id,title,content_hash,created_at) VALUES (?,?,?,'차례를 기다려요',?,now())",
                goal,
                child,
                owner.userId(),
                goal.toString().replace("-", "").repeat(2));
        jdbc.update(
                "INSERT INTO activity (activity_id,child_id,goal_id,activity_type,initial_support_level,difficulty_fallback_applied,assigned_at) VALUES (?,?,?,'FIRST_COMBINED','S1',false,now())",
                activity,
                child,
                goal);
        jdbc.update(
                "INSERT INTO quiz_result (activity_id,valid_question_count,correct_question_count,overall_accuracy,total_hint_count,resolved_after_hint_count,scenario_level,initial_support_level,difficulty_fallback_applied,initial_difficulty_used,policy_version,snapshotted_at) VALUES (?,10,5,0.5,0,0,'L2','S1',false,true,'INITIAL_QUIZ_V2',now())",
                activity);
        return new Context(owner, activity, goal);
    }

    private ScenarioReviewResponse prepare(Context ctx, UUID key) throws Exception {
        return response(
                send(
                                ctx,
                                HttpMethod.POST,
                                "/scenario-review",
                                Map.of("action", "START_OR_RESUME"),
                                key)
                        .andExpect(status().isOk()));
    }

    private ScenarioReviewResponse response(ResultActions result) throws Exception {
        return json.readValue(
                json.readTree(result.andReturn().getResponse().getContentAsString())
                        .path("data")
                        .toString(),
                ScenarioReviewResponse.class);
    }

    private Map<String, Object> confirmation(ScenarioReviewResponse offered) {
        return new HashMap<>(
                Map.of(
                        "reviewId",
                        offered.reviewId(),
                        "revision",
                        offered.revision(),
                        "scenarioId",
                        offered.scenario().scenarioId(),
                        "scenarioVersion",
                        offered.scenario().scenarioVersion()));
    }

    private ResultActions send(Context ctx, HttpMethod method, String path, Object body, UUID key)
            throws Exception {
        var csrf = fixtures.fetchCsrf();
        MockHttpServletRequestBuilder request =
                request(method, url(ctx, path))
                        .header("Authorization", "Bearer " + ctx.owner().accessToken())
                        .cookie(csrf.cookies())
                        .header(csrf.headerName(), csrf.token())
                        .contentType("application/json")
                        .content(
                                body instanceof String text ? text : json.writeValueAsString(body));
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }

    private String url(Context ctx, String path) {
        return "/api/v1/activities/" + ctx.activity() + path;
    }

    private UUID assigned(Context ctx) {
        return jdbc.queryForObject(
                "SELECT scenario_id FROM activity WHERE activity_id=?", UUID.class, ctx.activity());
    }

    private long count(String table, String column, UUID id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + "=?", Long.class, id);
    }

    private record Context(TestFixtures.Instructor owner, UUID activity, UUID goal) {}
}
