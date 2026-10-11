package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.roleplay.service.RoleplayRequestScopeService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@IntegrationTest
@TestPropertySource(properties = "roleplay.retention.cleanup-enabled=false")
class RoleplayRequestScopeIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired RoleplayRequestScopeService scopes;

    @Test
    void ownedRequestUsesServerScopeAndDoesNotChangeCheckpoint() {
        var f = fixture();
        var microGoal = UUID.randomUUID();
        var savedTurn = UUID.randomUUID();
        jdbc.update(
                "insert into roleplay_turn(turn_id,session_id,request_id,idempotency_key,input_fingerprint,turn_number,candidate_id,micro_goal_id,support_level,response_text,checkpoint_version,committed_at) values(?,?,?,?,?,2,?,?,'S2','synthetic approved response',4,?)",
                savedTurn,
                f.session,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a".repeat(64),
                UUID.randomUUID(),
                microGoal,
                Timestamp.from(Instant.now()));
        jdbc.update(
                "update roleplay_session set row_version=4,last_turn_number=2,last_turn_id=?,current_micro_goal_id=?,current_support_level='S2' where session_id=?",
                savedTurn,
                microGoal,
                f.session);
        var scope = scopes.load(f.child, f.activity, f.session);
        assertThat(scope.childId()).isEqualTo(f.child);
        assertThat(scope.classId()).isEqualTo(f.classroom);
        assertThat(scope.goalId()).isEqualTo(f.goal);
        assertThat(scope.scenarioId()).isEqualTo(f.scenario);
        assertThat(scope.scenarioVersion()).isEqualTo(1);
        assertThat(scope.checkpointVersion()).isEqualTo(4);
        assertThat(scope.lastTurnNumber()).isEqualTo(2);
        assertThat(scope.currentMicroGoalId()).isEqualTo(microGoal);
        assertThat(scope.currentSupportLevel()).isEqualTo("S2");
        assertThat(scope.acceptsNewTurn()).isTrue();
        var request = UUID.randomUUID();
        var turn = UUID.randomUUID();
        var trace = scope.trace(request, turn);
        assertThat(trace.requestId()).isEqualTo(request);
        assertThat(trace.turnId()).isEqualTo(turn);
        assertThat(trace.childId()).isEqualTo(f.child);
        assertThat(trace.activityId()).isEqualTo(f.activity);
        assertThat(trace.scenarioId()).isEqualTo(f.scenario);
        assertThat(trace.sessionId()).isEqualTo(f.session);
        assertThat(
                        jdbc.queryForObject(
                                "select row_version from roleplay_session where session_id=?",
                                Long.class,
                                f.session))
                .isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=?",
                                Long.class,
                                f.session))
                .isEqualTo(1);
    }

    @Test
    void otherChildOrActivityCannotUseTheSessionAndReceivesSameHiddenError() {
        var a = fixture();
        var b = fixture();
        hidden(() -> scopes.load(b.child, a.activity, a.session));
        hidden(() -> scopes.load(a.child, a.activity, b.session));
        hidden(() -> scopes.load(a.child, b.activity, a.session));
        hidden(() -> scopes.load(a.child, a.activity, UUID.randomUUID()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAUSED", "REMOVED"})
    void inactiveChildCannotLoadEvenTheirOwnSession(String status) {
        var f = fixture();
        jdbc.update("update child set status=? where child_id=?", status, f.child);
        hidden(() -> scopes.load(f.child, f.activity, f.session));
    }

    @Test
    void changedOrUnassignedScenarioIsRejected() {
        var f = fixture();
        jdbc.update(
                "update activity set scenario_id=? where activity_id=?",
                UUID.randomUUID(),
                f.activity);
        assertThatThrownBy(() -> scopes.load(f.child, f.activity, f.session))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error ->
                                assertThat(error.getCode())
                                        .isEqualTo("ROLEPLAY_SCENARIO_CONFLICT"));
        jdbc.update("update activity set scenario_id=null where activity_id=?", f.activity);
        assertThatThrownBy(() -> scopes.load(f.child, f.activity, f.session))
                .isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAUSED", "COMPLETED"})
    void closedSessionStillLoadsForConfirmedReplayButDoesNotAllowNewTurn(String status) {
        var f = fixture();
        jdbc.update("update roleplay_session set status=? where session_id=?", status, f.session);
        assertThat(scopes.load(f.child, f.activity, f.session).acceptsNewTurn()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_STARTED", "PAUSED", "RECOVERY_NEEDED", "COMPLETED"})
    void nonRunningActivityDoesNotAllowNewTurn(String status) {
        var f = fixture();
        jdbc.update("update activity set status=? where activity_id=?", status, f.activity);
        assertThat(scopes.load(f.child, f.activity, f.session).acceptsNewTurn()).isFalse();
    }

    private void hidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ResourceNotFoundException.class,
                        error -> assertThat(error.getCode()).isEqualTo("ROLEPLAY_NOT_FOUND"));
    }

    private record Fixture(
            UUID session, UUID activity, UUID child, UUID scenario, UUID classroom, UUID goal) {}

    private Fixture fixture() {
        UUID user = UUID.randomUUID(),
                classroom = UUID.randomUUID(),
                child = UUID.randomUUID(),
                goal = UUID.randomUUID();
        UUID activity = UUID.randomUUID(),
                session = UUID.randomUUID(),
                scenario = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                "insert into user_account(user_id,email,password_hash,name,role,status,created_at) values(?,?,?,'synthetic','INSTRUCTOR','ACTIVE',?)",
                user,
                user + "@example.com",
                "test-hash",
                now);
        jdbc.update(
                "insert into classroom(class_id,instructor_id,name,status) values(?,?,'synthetic','ACTIVE')",
                classroom,
                user);
        jdbc.update(
                "insert into child(child_id,class_id,display_name,status) values(?,?,'synthetic','ACTIVE')",
                child,
                classroom);
        jdbc.update(
                "insert into learning_goal(goal_id,child_id,instructor_id,title,content_hash,created_at) values(?,?,?,'synthetic',?,?)",
                goal,
                child,
                user,
                "a".repeat(64),
                now);
        jdbc.update(
                "insert into activity(activity_id,child_id,goal_id,scenario_id,status,assigned_at) values(?,?,?,?,'IN_PROGRESS',?)",
                activity,
                child,
                goal,
                scenario,
                now);
        jdbc.update(
                "insert into roleplay_session(session_id,activity_id,child_id,scenario_id,scenario_version,status,last_activity_at) values(?,?,?,?,1,'IN_PROGRESS',?)",
                session,
                activity,
                child,
                scenario,
                now);
        return new Fixture(session, activity, child, scenario, classroom, goal);
    }
}
