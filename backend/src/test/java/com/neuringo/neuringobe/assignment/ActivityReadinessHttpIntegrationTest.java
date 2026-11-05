package com.neuringo.neuringobe.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.LocalProfileIntegrationTest;
import com.neuringo.neuringobe.TestFixtures;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@LocalProfileIntegrationTest
@TestPropertySource(
        properties =
                "neuringo.child-access.hmac-secret=synthetic-readiness-test-key-not-for-production")
class ActivityReadinessHttpIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private TestFixtures fixtures;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private LlmProvider llm;

    private record Context(TestFixtures.Instructor owner, UUID child, UUID activity) {}

    @Test
    void creationResponseIsUnchangedAndListAndDetailAddMatchingReadiness() throws Exception {
        var ctx = context();
        readList(ctx)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].activityId").value(ctx.activity().toString()))
                .andExpect(jsonPath("$.data[0].nextAction").value("QUIZ"))
                .andExpect(jsonPath("$.data[0].blockedReason").value(nullValue()))
                .andExpect(jsonPath("$.meta.traceId").isNotEmpty());
        readDetail(ctx)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activity.nextAction").value("QUIZ"))
                .andExpect(jsonPath("$.data.activity.blockedReason").value(nullValue()))
                .andExpect(jsonPath("$.data.quizItems.length()").value(3))
                .andExpect(jsonPath("$.data.sessionSummary").value(nullValue()))
                .andExpect(jsonPath("$.data.learningRecord").value(nullValue()));
        verifyNoInteractions(llm);
    }

    @Test
    void teacherApprovalWaitIsNotAnErrorAndRepeatedGetsDoNotStartAReview() throws Exception {
        var ctx = context();
        result(ctx);
        for (int i = 0; i < 2; i++) {
            readList(ctx)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].nextAction").value("WAIT_FOR_SCENARIO"))
                    .andExpect(jsonPath("$.data[0].blockedReason").value(nullValue()));
            readDetail(ctx)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.activity.nextAction").value("WAIT_FOR_SCENARIO"));
        }
        assertThat(
                        jdbc.queryForObject(
                                "select scenario_id from activity where activity_id=?",
                                UUID.class,
                                ctx.activity()))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from scenario_review where activity_id=?",
                                Integer.class,
                                ctx.activity()))
                .isZero();
        verifyNoInteractions(llm);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SCENARIO", "QUIZ"})
    void badPreparationIsReturnedAsBlockedWithoutBreakingTheActivityListOrDetail(String kind)
            throws Exception {
        var ctx = context();
        String reason;
        if (kind.equals("SCENARIO")) {
            result(ctx);
            jdbc.update(
                    "update activity set scenario_id=? where activity_id=?",
                    UUID.randomUUID(),
                    ctx.activity());
            reason = "SCENARIO_NOT_READY";
        } else {
            jdbc.update(
                    "update activity_quiz set item_version=item_version+1 where activity_id=? and question_order=1",
                    ctx.activity());
            reason = "QUIZ_NOT_READY";
        }
        readList(ctx)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].nextAction").value("BLOCKED"))
                .andExpect(jsonPath("$.data[0].blockedReason").value(reason));
        var detail =
                readDetail(ctx)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.activity.nextAction").value("BLOCKED"))
                        .andExpect(jsonPath("$.data.activity.blockedReason").value(reason));
        if (kind.equals("QUIZ")) detail.andExpect(jsonPath("$.data.quizItems").isEmpty());
    }

    @Test
    void completedStatusEndpointStillReturnsOnlyCompletionStatus() throws Exception {
        var ctx = context();
        jdbc.update("update activity set status='COMPLETED' where activity_id=?", ctx.activity());
        readList(ctx)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].nextAction").value("NONE"))
                .andExpect(jsonPath("$.data[0].blockedReason").value(nullValue()));
        mvc.perform(
                        get("/api/v1/activities/{id}/status", ctx.activity())
                                .header("Authorization", "Bearer " + ctx.owner().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.nextAction").doesNotExist())
                .andExpect(jsonPath("$.data.blockedReason").doesNotExist());
    }

    @Test
    void childReadsOwnListButNotInstructorDetailOrAnotherChildList() throws Exception {
        var ctx = context();
        var session = childSession(ctx);
        mvc.perform(get("/api/v1/children/{id}/activities", ctx.child()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].nextAction").value("QUIZ"));
        mvc.perform(get("/api/v1/activities/{id}", ctx.activity()).session(session))
                .andExpect(status().isForbidden());
        var other = context();
        mvc.perform(get("/api/v1/children/{id}/activities", other.child()).session(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingAuthenticationAndDifferentInstructorRemainHidden() throws Exception {
        var ctx = context();
        mvc.perform(get("/api/v1/children/{id}/activities", ctx.child()))
                .andExpect(status().isUnauthorized());
        var other = fixtures.signUpInstructor();
        mvc.perform(
                        get("/api/v1/children/{id}/activities", ctx.child())
                                .header("Authorization", "Bearer " + other.accessToken()))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get("/api/v1/activities/{id}", ctx.activity())
                                .header("Authorization", "Bearer " + other.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidPagingIsRejectedAndEmptyPageKeepsArrayResponse() throws Exception {
        var ctx = context();
        mvc.perform(
                        get("/api/v1/children/{id}/activities", ctx.child())
                                .param("size", "101")
                                .header("Authorization", "Bearer " + ctx.owner().accessToken()))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/v1/children/{id}/activities", ctx.child())
                                .param("page", "1")
                                .param("size", "1")
                                .header("Authorization", "Bearer " + ctx.owner().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAUSED", "RECOVERY_NEEDED"})
    void readDoesNotNormalizePausedOrRecoveryState(String state) throws Exception {
        var ctx = context();
        jdbc.update("update activity set status=? where activity_id=?", state, ctx.activity());
        readList(ctx)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value(state))
                .andExpect(jsonPath("$.data[0].nextAction").value("QUIZ"));
        readDetail(ctx)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activity.status").value(state));
        assertThat(
                        jdbc.queryForObject(
                                "select status from activity where activity_id=?",
                                String.class,
                                ctx.activity()))
                .isEqualTo(state);
        verifyNoInteractions(llm);
    }

    private Context context() {
        var owner = fixtures.instructor();
        UUID classroom = fixtures.createClassroom(owner, "준비 상태 학급");
        UUID child = fixtures.createChild(owner, classroom, "검사 아동");
        var created =
                fixtures.postJsonWithKey(
                        owner,
                        "/api/v1/activities",
                        Map.of("childId", child, "goal", Map.of("title", "차례를 기다려요")),
                        UUID.randomUUID());
        String body = TestFixtures.body(created);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        Map<String, Object> data = JsonPath.read(body, "$.data");
        assertThat(data).doesNotContainKeys("nextAction", "blockedReason");
        return new Context(owner, child, UUID.fromString(data.get("activityId").toString()));
    }

    private ResultActions readList(Context ctx) throws Exception {
        return mvc.perform(
                get("/api/v1/children/{id}/activities", ctx.child())
                        .header("Authorization", "Bearer " + ctx.owner().accessToken()));
    }

    private ResultActions readDetail(Context ctx) throws Exception {
        return mvc.perform(
                get("/api/v1/activities/{id}", ctx.activity())
                        .header("Authorization", "Bearer " + ctx.owner().accessToken()));
    }

    private void result(Context ctx) {
        jdbc.update(
                "update activity set status='IN_PROGRESS', initial_support_level='S1' where activity_id=?",
                ctx.activity());
        jdbc.update(
                "insert into quiz_result(activity_id,valid_question_count,correct_question_count,overall_accuracy,total_hint_count,resolved_after_hint_count,scenario_level,initial_support_level,difficulty_fallback_applied,initial_difficulty_used,policy_version,snapshotted_at) values(?,3,2,0.666667,0,0,'L2','S1',false,true,'INITIAL_QUIZ_V2',now())",
                ctx.activity());
    }

    private MockHttpSession childSession(Context ctx) throws Exception {
        var csrf = fixtures.fetchCsrf();
        String issued =
                mvc.perform(
                                post("/api/v1/children/{id}/access-codes", ctx.child())
                                        .header(
                                                "Authorization",
                                                "Bearer " + ctx.owner().accessToken())
                                        .header(csrf.headerName(), csrf.token())
                                        .cookie(csrf.cookies())
                                        .header("Idempotency-Key", UUID.randomUUID()))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String code = JsonPath.read(issued, "$.data.accessCode");
        var enterCsrf = fixtures.fetchCsrf();
        return (MockHttpSession)
                mvc.perform(
                                post("/api/v1/child-access-sessions")
                                        .header(enterCsrf.headerName(), enterCsrf.token())
                                        .cookie(enterCsrf.cookies())
                                        .contentType("application/json")
                                        .content("{\"accessCode\":\"" + code + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }
}
