package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.activity.domain.ActivityNextAction;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointCommand;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointStore;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.assignment.service.ActivityReadinessQueryService;
import com.neuringo.neuringobe.child.security.ChildPrincipal;
import com.neuringo.neuringobe.roleplay.service.JpaRoleplayCheckpointStore;
import com.neuringo.neuringobe.roleplay.service.RoleplayStartService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@LocalProfileIntegrationTest
class RoleplayResumeIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired TestFixtures fixtures;
    @Autowired RoleplayStartService starts;
    @Autowired ActivityReadinessQueryService readiness;
    @Autowired JpaRoleplayCheckpointStore checkpoints;

    @ParameterizedTest
    @CsvSource({
        "PAUSED, PAUSED, false",
        "PAUSED, IN_PROGRESS, true",
        "RECOVERY_NEEDED, IN_PROGRESS, true",
        "RECOVERY_NEEDED, PAUSED, false",
        "IN_PROGRESS, PAUSED, true"
    })
    void resumesWithTheSameProgressAndReplaysWithoutAnotherVersionChange(
            String activityStatus, String sessionStatus, boolean progressed) throws Exception {
        var f = fixture(activityStatus, sessionStatus, progressed, true);
        var before =
                jdbc.queryForMap("select * from roleplay_session where session_id=?", f.session());
        var startedAt =
                jdbc.queryForObject(
                        "select started_at from activity where activity_id=?",
                        Object.class,
                        f.activity());
        assertThat(
                        readiness
                                .read(f.child(), List.of(f.activity()), auth(f))
                                .get(f.activity())
                                .nextAction())
                .isEqualTo(ActivityNextAction.ROLEPLAY);
        for (var key : List.of(f.key(), f.key(), UUID.randomUUID())) {
            request(f, key)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sessionId").value(f.session().toString()))
                    .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.data.activityStatus").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.data.checkpointVersion").value(5))
                    .andExpect(
                            jsonPath("$.data.delivery.messages[0].messageId")
                                    .value(f.message().toString()))
                    .andExpect(
                            jsonPath("$.data.delivery.messages[0].purpose")
                                    .value(progressed ? "RESUME" : "QUESTION"))
                    .andExpect(
                            jsonPath("$.data.delivery.messages[0].text")
                                    .value(progressed ? "이어갈 확정 응답" : "저장한 첫 질문"));
        }
        var after =
                jdbc.queryForMap("select * from roleplay_session where session_id=?", f.session());
        for (var field : List.of("status", "row_version", "last_activity_at")) {
            before.remove(field);
            after.remove(field);
        }
        assertThat(after).isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "select started_at from activity where activity_id=?",
                                Object.class,
                                f.activity()))
                .isEqualTo(startedAt);
        assertThat(count("roleplay_turn", f)).isEqualTo(progressed ? 1 : 0);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from quiz_result where activity_id=?",
                                Integer.class,
                                f.activity()))
                .isZero();
        assertThat(
                        readiness
                                .read(f.child(), List.of(f.activity()), auth(f))
                                .get(f.activity())
                                .nextAction())
                .isEqualTo(ActivityNextAction.ROLEPLAY);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "RETIRED",
                "VERSION",
                "GOAL",
                "EVIDENCE",
                "FOCUS",
                "OPENING",
                "FUTURE_CHECKPOINT",
                "TURN_NUMBER"
            })
    void invalidEvidenceDoesNotResumeOrRecordTheNewRequest(String invalid) throws Exception {
        boolean progressed = invalid.equals("FUTURE_CHECKPOINT") || invalid.equals("TURN_NUMBER");
        var f = fixture("RECOVERY_NEEDED", "PAUSED", progressed, false, invalid);
        switch (invalid) {
            case "RETIRED" ->
                    jdbc.update(
                            "update scenario set status='RETIRED' where scenario_id=?",
                            f.scenario());
            case "VERSION" ->
                    jdbc.update(
                            "update roleplay_session set scenario_version=2 where session_id=?",
                            f.session());
            case "GOAL" ->
                    jdbc.update(
                            "update roleplay_session set current_micro_goal_id=? where session_id=?",
                            UUID.randomUUID(),
                            f.session());
            case "EVIDENCE", "FOCUS", "OPENING" -> {}
            case "FUTURE_CHECKPOINT" ->
                    jdbc.update(
                            "update roleplay_turn set checkpoint_version=5 where session_id=?",
                            f.session());
            case "TURN_NUMBER" ->
                    jdbc.update(
                            "update roleplay_session set last_turn_number=2 where session_id=?",
                            f.session());
            default -> throw new IllegalArgumentException(invalid);
        }
        var before =
                jdbc.queryForMap("select * from roleplay_session where session_id=?", f.session());
        UUID key = UUID.randomUUID();
        request(f, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROLEPLAY_RESUME_NOT_READY"));
        assertThat(
                        jdbc.queryForMap(
                                "select * from roleplay_session where session_id=?", f.session()))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "select status from activity where activity_id=?",
                                String.class,
                                f.activity()))
                .isEqualTo("RECOVERY_NEEDED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_start_request where request_key=?",
                                Integer.class,
                                key))
                .isZero();
    }

    @Test
    void openingWriteFailureRollsBackResumeAndTheSameKeyCanRetry() {
        var f = fixture("PAUSED", "PAUSED", false, false);
        var before =
                jdbc.queryForMap("select * from roleplay_session where session_id=?", f.session());
        UUID key = UUID.randomUUID();
        String constraint = "test_resume_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                "alter table roleplay_start_message add constraint "
                        + constraint
                        + " check (session_id <> '"
                        + f.session()
                        + "'::uuid)");
        try {
            assertThatThrownBy(() -> starts.startForDelivery(f.activity(), key, auth(f)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(
                            jdbc.queryForMap(
                                    "select * from roleplay_session where session_id=?",
                                    f.session()))
                    .isEqualTo(before);
            assertThat(
                            jdbc.queryForObject(
                                    "select status from activity where activity_id=?",
                                    String.class,
                                    f.activity()))
                    .isEqualTo("PAUSED");
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from roleplay_start_request where request_key=?",
                                    Integer.class,
                                    key))
                    .isZero();
            assertThat(count("roleplay_start_message", f)).isZero();
        } finally {
            jdbc.execute("alter table roleplay_start_message drop constraint " + constraint);
        }
        var result = starts.startForDelivery(f.activity(), key, auth(f));
        assertThat(result.created()).isFalse();
        assertThat(result.response().checkpointVersion()).isEqualTo(5);
        assertThat(result.response().delivery().messages()).hasSize(1);
        assertThat(count("roleplay_start_message", f)).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED, PAUSED", "PAUSED, COMPLETED", "COMPLETED, COMPLETED"})
    void completedActivityOrSessionNeverRestarts(String activityStatus, String sessionStatus)
            throws Exception {
        var f = fixture(activityStatus, sessionStatus, true, true);
        var before =
                jdbc.queryForMap("select * from roleplay_session where session_id=?", f.session());
        request(f, UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(sessionStatus))
                .andExpect(jsonPath("$.data.activityStatus").value(activityStatus))
                .andExpect(jsonPath("$.data.delivery.messages").isEmpty());
        assertThat(
                        jdbc.queryForMap(
                                "select * from roleplay_session where session_id=?", f.session()))
                .isEqualTo(before);
    }

    @Test
    void pausedChildIsNotReactivatedByStartingTheActivity() throws Exception {
        var f = fixture("PAUSED", "PAUSED", false, true);
        jdbc.update("update child set status='PAUSED' where child_id=?", f.child());
        request(f, UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(
                        jdbc.queryForObject(
                                "select status from child where child_id=?",
                                String.class,
                                f.child()))
                .isEqualTo("PAUSED");
        assertThat(
                        jdbc.queryForObject(
                                "select row_version from roleplay_session where session_id=?",
                                Long.class,
                                f.session()))
                .isEqualTo(4);
    }

    @Test
    void concurrentResumeIncrementsTheVersionOnlyOnce() throws Exception {
        var f = fixture("RECOVERY_NEEDED", "IN_PROGRESS", true, true);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks =
                    java.util.stream.IntStream.range(0, 2)
                            .mapToObj(
                                    i ->
                                            pool.submit(
                                                    () -> {
                                                        ready.countDown();
                                                        if (!go.await(10, TimeUnit.SECONDS))
                                                            throw new IllegalStateException(
                                                                    "재개 요청 대기 시간 초과");
                                                        return starts.startForDelivery(
                                                                f.activity(),
                                                                UUID.randomUUID(),
                                                                auth(f));
                                                    }))
                            .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var first = tasks.getFirst().get(15, TimeUnit.SECONDS);
            var second = tasks.getLast().get(15, TimeUnit.SECONDS);
            assertThat(first.response()).isEqualTo(second.response());
            assertThat(first.response().checkpointVersion()).isEqualTo(5);
            assertThat(count("roleplay_turn", f)).isEqualTo(1);
        }
    }

    @Test
    void aTurnComputedBeforeResumeCannotOverwriteTheResumedSession() {
        var f = fixture("RECOVERY_NEEDED", "IN_PROGRESS", true, true);
        var command = mock(RoleplayCheckpointCommand.class);
        when(command.activityId()).thenReturn(f.activity());
        when(command.childId()).thenReturn(f.child());
        when(command.sessionId()).thenReturn(f.session());
        when(command.scenarioId()).thenReturn(f.scenario());
        when(command.scenarioVersion()).thenReturn(1);
        when(command.idempotencyKey()).thenReturn(UUID.randomUUID());
        when(command.expectedVersion()).thenReturn(4L);
        when(command.turnNumber()).thenReturn(2);
        starts.startForDelivery(f.activity(), f.key(), auth(f));
        assertThat(checkpoints.commit(command, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayCheckpointStore.Conflict.class);
        assertThat(count("roleplay_turn", f)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select row_version from roleplay_session where session_id=?",
                                Long.class,
                                f.session()))
                .isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void startIntroducesTheCharacterBeforeTheFirstQuestionAndKeepsItsNameOnResume(
            boolean progressed) throws Exception {
        var f = fixture("IN_PROGRESS", "IN_PROGRESS", progressed, true, "NONE", "넘어진 친구 지우");
        var first =
                request(f, f.key())
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.characterName").value("지우"));
        if (progressed) {
            first.andExpect(jsonPath("$.data.delivery.messages.length()").value(1))
                    .andExpect(jsonPath("$.data.delivery.messages[0].purpose").value("RESUME"));
            return;
        }
        first.andExpect(jsonPath("$.data.delivery.messages.length()").value(2))
                .andExpect(jsonPath("$.data.delivery.messages[0].speakerRole").value("HINT"))
                .andExpect(jsonPath("$.data.delivery.messages[0].purpose").value("INTRO"))
                .andExpect(
                        jsonPath("$.data.delivery.messages[0].text")
                                .value("저기 지우 보이지? 지우 말을 잘 듣고 이야기를 나눠 보자."))
                .andExpect(jsonPath("$.data.delivery.messages[0].speech.available").value(false))
                .andExpect(jsonPath("$.data.delivery.messages[1].speakerRole").value("DIALOGUE"))
                .andExpect(jsonPath("$.data.delivery.messages[1].purpose").value("QUESTION"))
                .andExpect(
                        jsonPath("$.data.delivery.messages[1].messageId")
                                .value(f.message().toString()));
        String introId =
                JsonPath.read(
                        first.andReturn().getResponse().getContentAsString(),
                        "$.data.delivery.messages[0].messageId");
        // 다른 요청 키로 다시 불러도 소개 ID 는 같다(화면이 중복으로 보여 주지 않는다).
        request(f, UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delivery.messages[0].messageId").value(introId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAUSED", "RECOVERY_NEEDED"})
    void readyActivityWithoutASessionCanStartAfterCheckingTheSavedInitialEvidence(String state)
            throws Exception {
        var f = fixture(state, "PAUSED", false, false);
        jdbc.update("delete from roleplay_session where session_id=?", f.session());
        jdbc.update(
                "update activity set initial_support_level='S1' where activity_id=?", f.activity());
        jdbc.update(
                "insert into quiz_result(activity_id,valid_question_count,correct_question_count,overall_accuracy,total_hint_count,resolved_after_hint_count,scenario_level,initial_support_level,difficulty_fallback_applied,initial_difficulty_used,policy_version,snapshotted_at) values(?,3,2,0.666667,0,0,'L2','S1',false,true,'INITIAL_QUIZ_V2',now())",
                f.activity());
        request(f, UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.activityStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.delivery.messages[0].text").value("승인된 첫 질문"));
    }

    private ResultActions request(Fixture f, UUID key) throws Exception {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth(f));
        var session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        var csrf = fixtures.fetchCsrf();
        return mvc.perform(
                post("/api/v1/activities/{id}/roleplay-sessions", f.activity())
                        .session(session)
                        .header(csrf.headerName(), csrf.token())
                        .cookie(csrf.cookies())
                        .header("Idempotency-Key", key));
    }

    private Authentication auth(Fixture f) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new ChildPrincipal(f.child()),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_CHILD")));
    }

    private int count(String table, Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from " + table + " where session_id=?",
                Integer.class,
                f.session());
    }

    private record Fixture(
            UUID child,
            UUID activity,
            UUID scenario,
            UUID goal,
            UUID session,
            UUID key,
            UUID message) {}

    private Fixture fixture(
            String activityStatus, String sessionStatus, boolean progressed, boolean savedOpening) {
        return fixture(activityStatus, sessionStatus, progressed, savedOpening, "NONE");
    }

    private Fixture fixture(
            String activityStatus,
            String sessionStatus,
            boolean progressed,
            boolean savedOpening,
            String missingLegacyData) {
        return fixture(
                activityStatus, sessionStatus, progressed, savedOpening, missingLegacyData, null);
    }

    /** characterRole 이 있으면 AI 가 연기할 등장인물(FRIEND)을 그 이름표로 둔다. */
    private Fixture fixture(
            String activityStatus,
            String sessionStatus,
            boolean progressed,
            boolean savedOpening,
            String missingLegacyData,
            String characterRole) {
        UUID user = UUID.randomUUID(),
                classroom = UUID.randomUUID(),
                child = UUID.randomUUID(),
                learningGoal = UUID.randomUUID();
        UUID activity = UUID.randomUUID(),
                scenario = UUID.randomUUID(),
                goal = UUID.randomUUID(),
                session = UUID.randomUUID(),
                key = UUID.randomUUID(),
                message = UUID.randomUUID();
        jdbc.update(
                "insert into user_account(user_id,email,password_hash,name,role,status,created_at) values(?,?,?,'합성 강사','INSTRUCTOR','ACTIVE',now())",
                user,
                user + "@example.com",
                "test-hash");
        jdbc.update(
                "insert into classroom(class_id,instructor_id,name,status) values(?,?,'합성 학급','ACTIVE')",
                classroom,
                user);
        jdbc.update(
                "insert into child(child_id,class_id,display_name,status) values(?,?,'합성 아동','ACTIVE')",
                child,
                classroom);
        jdbc.update(
                "insert into learning_goal(goal_id,child_id,instructor_id,title,content_hash,created_at) values(?,?,?,'합성 목표',?,now())",
                learningGoal,
                child,
                user,
                "a".repeat(64));
        jdbc.update(
                "insert into activity(activity_id,child_id,goal_id,activity_type,status,assigned_at,started_at) values(?,?,?,'FIRST_COMBINED',?,now()-interval '1 day',now()-interval '1 day')",
                activity,
                child,
                learningGoal,
                activityStatus);
        jdbc.update(
                "insert into scenario(scenario_id,class_id,status,scenario_level,goal_content_hash,title,ai_role,characters,scenario_facts,opening_prompts,origin,created_at) values(?,?,'DRAFT','L2',?,'합성 시나리오',?,cast(? as jsonb),'[\"사실\"]','{\"S1\":\"승인된 첫 질문\"}','PREPARED',now())",
                scenario,
                classroom,
                "a".repeat(64),
                characterRole == null ? null : "FRIEND",
                characterRole == null
                        ? "[]"
                        : "[{\"id\":\"LEARNER\",\"role\":\"학습자\"},{\"id\":\"FRIEND\",\"role\":\""
                                + characterRole
                                + "\"}]");
        jdbc.update(
                "insert into scenario_micro_goal(micro_goal_id,scenario_id,order_no,description,required_evidence) values(?,?,1,'합성 작은 목표','[\"말한다\"]')",
                goal,
                scenario);
        jdbc.update(
                "insert into scenario_opening_focus(scenario_id,support_level,micro_goal_id) values(?,'S1',?)",
                scenario,
                goal);
        // 과거 승인본의 누락 자료를 구성하며 승인 후 변경 금지 제약은 유지한다.
        switch (missingLegacyData) {
            case "EVIDENCE" ->
                    jdbc.update(
                            "update scenario_micro_goal set required_evidence='[]' where micro_goal_id=?",
                            goal);
            case "FOCUS" ->
                    jdbc.update("delete from scenario_opening_focus where scenario_id=?", scenario);
            case "OPENING" ->
                    jdbc.update(
                            "update scenario set opening_prompts='{}' where scenario_id=?",
                            scenario);
            default -> {}
        }
        jdbc.update("update scenario set status='APPROVED' where scenario_id=?", scenario);
        jdbc.update("update activity set scenario_id=? where activity_id=?", scenario, activity);
        jdbc.update(
                "insert into roleplay_session(session_id,activity_id,child_id,scenario_id,scenario_version,status,row_version,current_micro_goal_id,current_support_level,last_activity_at) values(?,?,?,?,1,?,4,?,'S1',now()-interval '5 minutes')",
                session,
                activity,
                child,
                scenario,
                sessionStatus,
                goal);
        if (progressed) {
            jdbc.update(
                    "insert into roleplay_turn(turn_id,session_id,request_id,idempotency_key,input_fingerprint,turn_number,candidate_id,micro_goal_id,support_level,response_text,checkpoint_version,committed_at) values(?,?,?,?,?,1,?,?,'S1','이어갈 확정 응답',3,now())",
                    message,
                    session,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "b".repeat(64),
                    UUID.randomUUID(),
                    goal);
            jdbc.update(
                    "update roleplay_session set last_turn_number=1,last_turn_id=? where session_id=?",
                    message,
                    session);
        } else if (savedOpening) {
            jdbc.update(
                    "insert into roleplay_start_message(session_id,scenario_id,scenario_version,message_id,opening_text,created_at) values(?,?,1,?,'저장한 첫 질문',now())",
                    session,
                    scenario,
                    message);
        }
        jdbc.update(
                "insert into roleplay_start_request(request_key,activity_id,child_id,session_id,created_at) values(?,?,?,?,now())",
                key,
                activity,
                child,
                session);
        return new Fixture(child, activity, scenario, goal, session, key, message);
    }
}
