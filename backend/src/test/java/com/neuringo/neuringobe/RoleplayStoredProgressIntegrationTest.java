package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.roleplay.application.RoleplayContextSource;
import com.neuringo.neuringobe.roleplay.application.RoleplayProgressPolicy;
import com.neuringo.neuringobe.roleplay.service.JdbcRoleplayContextSource;
import com.neuringo.neuringobe.roleplay.service.JdbcRoleplayProgressSource;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class RoleplayStoredProgressIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcRoleplayContextSource contexts;
    @Autowired JdbcRoleplayProgressSource progress;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean RoleplayProgressPolicy policy;

    @Test
    void actualContextUsesStoredFocusAndSupportWithExplicitLimits() {
        var f = fixture();
        select(f.first(), f);
        var snapshot = contexts.load(f.key(), RoleplayTurnDeadline.start()).orElseThrow();
        assertThat(snapshot.state().currentSupportLevel()).isEqualTo("S2");
        assertThat(snapshot.state().currentMicroGoalId()).isEqualTo(f.first());
        assertThat(snapshot.state().turnCount()).isOne();
        assertThat(snapshot.state().maximumTurnCount()).isEqualTo(13);
        assertThat(snapshot.state().maximumSupportTurnsPerMicroGoal()).isEqualTo(5);
        assertThat(
                        jdbc.queryForObject(
                                "select current_micro_goal_id from roleplay_session where session_id=?",
                                UUID.class,
                                f.key().sessionId()))
                .isEqualTo(f.first());
    }

    @Test
    void openingFocusCannotReferToAnotherScenariosGoal() {
        var f = fixture(false);
        var other = fixture(false);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "insert into scenario_opening_focus(scenario_id,support_level,micro_goal_id) values(?,'S2',?)",
                                        f.key().scenarioId(),
                                        other.first()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"insert", "update", "delete"})
    void approvedOpeningFocusIsImmutable(String operation) {
        var f = fixture(false);
        jdbc.update(
                "insert into scenario_opening_focus(scenario_id,support_level,micro_goal_id) values(?,'S2',?)",
                f.key().scenarioId(),
                f.second());
        jdbc.update(
                "update scenario set status='APPROVED' where scenario_id=?", f.key().scenarioId());
        assertThatThrownBy(
                        () -> {
                            switch (operation) {
                                case "insert" ->
                                        jdbc.update(
                                                "insert into scenario_opening_focus(scenario_id,support_level,micro_goal_id) values(?,'S1',?)",
                                                f.key().scenarioId(),
                                                f.first());
                                case "update" ->
                                        jdbc.update(
                                                "update scenario_opening_focus set micro_goal_id=? where scenario_id=?",
                                                f.first(),
                                                f.key().scenarioId());
                                case "delete" ->
                                        jdbc.update(
                                                "delete from scenario_opening_focus where scenario_id=?",
                                                f.key().scenarioId());
                            }
                        })
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(
                        jdbc.queryForObject(
                                "select micro_goal_id from scenario_opening_focus where scenario_id=? and support_level='S2'",
                                UUID.class,
                                f.key().scenarioId()))
                .isEqualTo(f.second());
    }

    @Test
    void restoresTheLatestCheckpointWithoutReapplyingInitialSupport() {
        var f = fixture();
        UUID turn = UUID.randomUUID();
        jdbc.update(
                """
                insert into roleplay_turn(turn_id,session_id,request_id,idempotency_key,input_fingerprint,
                    turn_number,candidate_id,micro_goal_id,support_level,response_text,checkpoint_version,committed_at,
                    response_type,strategy_used,input_micro_goal_id,input_support_level,input_support_turn,input_prompt_count,input_answer_revealed)
                values(?,?,?,?,?,1,?,?,'S1','synthetic response?',1,now(),'GUIDING_QUESTION','PROBE_EVENT',?,'S2',1,0,false)
                """,
                turn,
                f.key().sessionId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a".repeat(64),
                UUID.randomUUID(),
                f.second(),
                f.first());
        jdbc.update(
                "update roleplay_session set current_micro_goal_id=?, current_support_level='S1', last_turn_number=1, last_turn_id=?, row_version=1 where session_id=?",
                f.second(),
                turn,
                f.key().sessionId());
        select(f.second(), f);
        var key =
                new RoleplayContextSource.Key(
                        f.key().childId(),
                        f.key().activityId(),
                        f.key().goalId(),
                        f.key().sessionId(),
                        f.key().scenarioId(),
                        3,
                        1);
        var snapshot = contexts.load(key, RoleplayTurnDeadline.start()).orElseThrow();
        assertThat(snapshot.state().currentSupportLevel()).isEqualTo("S1");
        assertThat(snapshot.state().currentMicroGoalId()).isEqualTo(f.second());
        assertThat(snapshot.state().turnCount()).isEqualTo(2);
        assertThat(snapshot.state().currentMicroGoalPromptCount()).isOne();
        assertThat(contexts.load(f.key(), RoleplayTurnDeadline.start())).isEmpty();
    }

    @Test
    void aPolicyCannotReplaceAnAlreadyStoredQuestionFocus() {
        var f = fixture();
        jdbc.update(
                "update roleplay_session set current_micro_goal_id=? where session_id=?",
                f.second(),
                f.key().sessionId());
        select(f.first(), f);
        assertThat(contexts.load(f.key(), RoleplayTurnDeadline.start())).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "select current_micro_goal_id from roleplay_session where session_id=?",
                                UUID.class,
                                f.key().sessionId()))
                .isEqualTo(f.second());
    }

    @ParameterizedTest
    @ValueSource(strings = {"outsideGoal", "missingStatus", "absent"})
    void doesNotSupplyInventedProgressWhenPolicyIsIncomplete(String invalid) {
        var f = fixture();
        var selection =
                switch (invalid) {
                    case "outsideGoal" ->
                            Optional.of(
                                    new RoleplayProgressPolicy.Selection(
                                            UUID.randomUUID(),
                                            13,
                                            5,
                                            Map.of(
                                                    f.first(),
                                                    "NOT_OBSERVED",
                                                    f.second(),
                                                    "NOT_OBSERVED")));
                    case "missingStatus" ->
                            Optional.of(
                                    new RoleplayProgressPolicy.Selection(
                                            f.first(), 13, 5, Map.of(f.first(), "NOT_OBSERVED")));
                    default -> Optional.<RoleplayProgressPolicy.Selection>empty();
                };
        when(policy.select(any(), any())).thenReturn(selection);
        assertThat(contexts.load(f.key(), RoleplayTurnDeadline.start())).isEmpty();
    }

    @Test
    void missingPolicyKeepsTheOperationalContextUnavailable() {
        var f = fixture();
        var source = new JdbcRoleplayProgressSource(jdbc, Optional.empty());
        var result =
                new TransactionTemplate(transactions)
                        .execute(status -> source.load(f.key(), RoleplayTurnDeadline.start()));
        assertThat(result).isEmpty();
        verifyNoInteractions(policy);
    }

    @Test
    void refusesAnIndependentReadOutsideTheContextTransaction() {
        var f = fixture();
        assertThatThrownBy(() -> progress.load(f.key(), RoleplayTurnDeadline.start()))
                .isInstanceOf(
                        org.springframework.transaction.IllegalTransactionStateException.class);
        verifyNoInteractions(policy);
    }

    @Test
    void expiredRequestsDoNotCallThePolicy() {
        var f = fixture();
        var deadline = RoleplayTurnDeadline.start();
        deadline.cancel();
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .execute(status -> progress.load(f.key(), deadline)))
                .isInstanceOf(RoleplayTurnDeadline.Expired.class);
        verifyNoInteractions(policy);
    }

    @ParameterizedTest
    @ValueSource(strings = {"paused", "class", "hash", "support", "version", "missingGoal"})
    void rejectsInvalidStoredScopeBeforeCallingThePolicy(String invalid) {
        var f = fixture();
        switch (invalid) {
            case "missingGoal" ->
                    jdbc.update(
                            "update roleplay_session set current_micro_goal_id=null where session_id=?",
                            f.key().sessionId());
            case "paused" ->
                    jdbc.update(
                            "update activity set status='PAUSED' where activity_id=?",
                            f.key().activityId());
            case "class" ->
                    jdbc.update(
                            "update child set class_id=? where child_id=?",
                            anotherClass(),
                            f.key().childId());
            case "hash" ->
                    jdbc.update(
                            "update learning_goal set content_hash=? where goal_id=?",
                            "b".repeat(64),
                            f.key().goalId());
            case "support" ->
                    jdbc.update(
                            "update roleplay_session set current_support_level=null where session_id=?",
                            f.key().sessionId());
            case "version" ->
                    jdbc.update(
                            "update roleplay_session set row_version=1 where session_id=?",
                            f.key().sessionId());
        }
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var restored =
                transaction.execute(status -> progress.load(f.key(), RoleplayTurnDeadline.start()));
        assertThat(restored).isEmpty();
        verifyNoInteractions(policy);
    }

    private void select(UUID goal, Fixture f) {
        when(policy.select(any(), any()))
                .thenReturn(
                        Optional.of(
                                new RoleplayProgressPolicy.Selection(
                                        goal,
                                        13,
                                        5,
                                        Map.of(
                                                f.first(),
                                                "NOT_OBSERVED",
                                                f.second(),
                                                "NOT_OBSERVED"))));
    }

    private UUID anotherClass() {
        UUID teacher = UUID.randomUUID(), classroom = UUID.randomUUID();
        jdbc.update(
                "insert into user_account(user_id,email,password_hash,name,role,status,created_at) values(?,?,?,'synthetic','INSTRUCTOR','ACTIVE',now())",
                teacher,
                teacher + "@example.com",
                "test-hash");
        jdbc.update(
                "insert into classroom(class_id,instructor_id,name,status) values(?,?,'synthetic','ACTIVE')",
                classroom,
                teacher);
        return classroom;
    }

    private Fixture fixture() {
        return fixture(true);
    }

    private Fixture fixture(boolean approved) {
        UUID classroom = anotherClass();
        UUID teacher =
                jdbc.queryForObject(
                        "select instructor_id from classroom where class_id=?",
                        UUID.class,
                        classroom);
        UUID child = UUID.randomUUID(),
                goal = UUID.randomUUID(),
                activity = UUID.randomUUID(),
                scenario = UUID.randomUUID(),
                session = UUID.randomUUID(),
                first = UUID.randomUUID(),
                second = UUID.randomUUID();
        jdbc.update(
                "insert into child(child_id,class_id,display_name,status) values(?,?,'synthetic','ACTIVE')",
                child,
                classroom);
        jdbc.update(
                "insert into learning_goal(goal_id,child_id,instructor_id,title,content_hash,created_at) values(?,?,?,'synthetic',?,now())",
                goal,
                child,
                teacher,
                "a".repeat(64));
        jdbc.update(
                "insert into activity(activity_id,child_id,goal_id,scenario_id,activity_type,status,assigned_at) values(?,?,?,?,'ROLEPLAY','IN_PROGRESS',now())",
                activity,
                child,
                goal,
                scenario);
        jdbc.update(
                "insert into scenario(scenario_id,class_id,goal_content_hash,scenario_version,status,scenario_level,title,scenario_facts,origin,created_at) values(?,?,?,3,'DRAFT','L1','synthetic','[\"fact\"]'::jsonb,'PREPARED',now())",
                scenario,
                classroom,
                "a".repeat(64));
        jdbc.update(
                "insert into scenario_micro_goal(micro_goal_id,scenario_id,order_no,description,required_evidence) values(?,?,1,'first','[\"first evidence\"]'::jsonb),(?,?,2,'second','[\"second evidence\"]'::jsonb)",
                first,
                scenario,
                second,
                scenario);
        if (approved)
            jdbc.update("update scenario set status='APPROVED' where scenario_id=?", scenario);
        jdbc.update(
                "insert into roleplay_session(session_id,activity_id,child_id,scenario_id,scenario_version,status,current_support_level,current_micro_goal_id,last_activity_at) values(?,?,?,?,3,'IN_PROGRESS','S2',?,now())",
                session,
                activity,
                child,
                scenario,
                first);
        return new Fixture(
                new RoleplayContextSource.Key(child, activity, goal, session, scenario, 3, 0),
                first,
                second);
    }

    private record Fixture(RoleplayContextSource.Key key, UUID first, UUID second) {}
}
