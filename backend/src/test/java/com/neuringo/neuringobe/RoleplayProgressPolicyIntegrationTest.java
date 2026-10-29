package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import com.neuringo.neuringobe.child.security.ChildPrincipal;
import com.neuringo.neuringobe.roleplay.application.RoleplayContextSource;
import com.neuringo.neuringobe.roleplay.service.JdbcRoleplayContextSource;
import com.neuringo.neuringobe.roleplay.service.RoleplayStartService;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@TestPropertySource(
        properties = {
            "roleplay.progress.maximum-turn-count=13",
            "roleplay.progress.maximum-support-turns-per-micro-goal=5"
        })
class RoleplayProgressPolicyIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcRoleplayContextSource contexts;
    @Autowired RoleplayStartService starts;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void actualStartAndContextNeedNoReplacementProgressProvider() {
        var f = fixture();
        var snapshot = snapshot(f);
        assertThat(snapshot.state().currentMicroGoalId()).isEqualTo(f.first());
        assertThat(snapshot.state().currentSupportLevel()).isEqualTo("S2");
        assertThat(snapshot.state().maximumTurnCount()).isEqualTo(13);
        assertThat(snapshot.state().maximumSupportTurnsPerMicroGoal()).isEqualTo(5);
        assertThat(statuses(snapshot))
                .containsEntry(f.first(), "NOT_OBSERVED")
                .containsEntry(f.second(), "NOT_OBSERVED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=?",
                                Integer.class,
                                f.key().sessionId()))
                .isZero();
    }

    @Test
    void achievementBelongsToTheInputGoalAndDoesNotCompleteTheActivity() {
        var f = fixture();
        turn(f, "GOAL_ACHIEVED", f.second(), "GUIDING_QUESTION");
        var snapshot = snapshot(f);
        assertThat(statuses(snapshot))
                .containsEntry(f.first(), "ACHIEVED")
                .containsEntry(f.second(), "NOT_OBSERVED");
        assertThat(snapshot.state().currentMicroGoalId()).isEqualTo(f.second());
        assertThat(
                        jdbc.queryForObject(
                                "select status from activity where activity_id=?",
                                String.class,
                                f.key().activityId()))
                .isEqualTo("IN_PROGRESS");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from learning_record where activity_id=?",
                                Integer.class,
                                f.key().activityId()))
                .isZero();
    }

    @Test
    void observationsOutsideTheQuestionFocusDoNotInventAchievement() {
        var f = fixture();
        UUID turn = turn(f, "PARTIAL_UNDERSTANDING", f.second(), "GUIDING_QUESTION");
        jdbc.update(
                "insert into roleplay_goal_observation(session_id,turn_id,micro_goal_id,criterion_reference,observation_status) values(?,?,?,?,'EXPLICIT')",
                f.key().sessionId(),
                turn,
                f.second(),
                f.references().get(f.second()));
        assertThat(statuses(snapshot(f)))
                .containsEntry(f.first(), "IN_PROGRESS")
                .containsEntry(f.second(), "IN_PROGRESS");
    }

    @Test
    void answerRevealCannotBeRestoredAsAnIndependentAchievement() {
        var f = fixture();
        turn(f, "PARTIAL_UNDERSTANDING", f.second(), "ANSWER_REVEAL");
        assertThat(statuses(snapshot(f))).containsEntry(f.second(), "ANSWER_REVEALED");
        turn(f, "GOAL_ACHIEVED", f.first(), "GUIDING_QUESTION");
        var snapshot = snapshot(f);
        assertThat(statuses(snapshot)).containsEntry(f.second(), "ANSWER_REVEALED");
        jdbc.update(
                "update roleplay_turn set canonical_utterance=null where session_id=?",
                f.key().sessionId());
        assertThat(statuses(snapshot(f))).isEqualTo(statuses(snapshot));
    }

    @Test
    void notAssessableDoesNotEraseAnEarlierReviewedAchievement() {
        var f = fixture();
        turn(f, "GOAL_ACHIEVED", f.first(), "CONFIRMATION");
        turn(f, "NOT_ASSESSABLE", f.second(), "GUIDING_QUESTION");
        assertThat(statuses(snapshot(f))).containsEntry(f.first(), "ACHIEVED");
    }

    @Test
    void notAssessableDoesNotInventAFirstGoalJudgment() {
        var f = fixture();
        turn(f, "NOT_ASSESSABLE", f.second(), "GUIDING_QUESTION");
        assertThat(statuses(snapshot(f))).containsEntry(f.first(), "NOT_OBSERVED");
    }

    @Test
    void achievementWithoutPositiveInputGoalEvidenceCannotSupplyNewContext() {
        var f = fixture();
        UUID turn = turn(f, "GOAL_ACHIEVED", f.second(), "GUIDING_QUESTION");
        jdbc.update("delete from roleplay_goal_observation where turn_id=?", turn);
        assertThat(contexts.load(key(f), RoleplayTurnDeadline.start())).isEmpty();
    }

    @Test
    void anUnknownLegacyJudgmentBlocksNewWorkButKeepsTheStoredResponseReplayable() {
        var f = fixture();
        UUID turn = turn(f, "PARTIAL_UNDERSTANDING", f.second(), "GUIDING_QUESTION");
        jdbc.update(
                "update roleplay_goal_assessment set input_micro_goal_id=null,input_learning_state=null where session_id=?",
                f.key().sessionId());
        assertThat(contexts.load(key(f), RoleplayTurnDeadline.start())).isEmpty();
        var replay =
                starts.startForDelivery(
                        f.key().activityId(), UUID.randomUUID(), f.authentication());
        assertThat(replay.created()).isFalse();
        assertThat(replay.response().delivery().messages().getFirst().messageId()).isEqualTo(turn);
        assertThat(replay.response().delivery().messages().getFirst().purpose())
                .isEqualTo("RESUME");
    }

    @Test
    void aMissingAssessmentOrVersionMismatchDoesNotBecomeUnobservedProgress() {
        var f = fixture();
        UUID turn = turn(f, "PARTIAL_UNDERSTANDING", f.second(), "GUIDING_QUESTION");
        assertThat(contexts.load(f.key(), RoleplayTurnDeadline.start())).isEmpty();
        jdbc.update("delete from roleplay_goal_observation where turn_id=?", turn);
        jdbc.update("delete from roleplay_goal_assessment where turn_id=?", turn);
        assertThat(contexts.load(key(f), RoleplayTurnDeadline.start())).isEmpty();
    }

    private Map<UUID, String> statuses(RoleplayContextSource.Snapshot snapshot) {
        return snapshot.scenario().microGoals().stream()
                .collect(Collectors.toMap(g -> g.id(), g -> g.status()));
    }

    private RoleplayContextSource.Snapshot snapshot(Fixture f) {
        return contexts.load(key(f), RoleplayTurnDeadline.start()).orElseThrow();
    }

    private RoleplayContextSource.Key key(Fixture f) {
        long version =
                jdbc.queryForObject(
                        "select row_version from roleplay_session where session_id=?",
                        Long.class,
                        f.key().sessionId());
        return new RoleplayContextSource.Key(
                f.key().childId(),
                f.key().activityId(),
                f.key().goalId(),
                f.key().sessionId(),
                f.key().scenarioId(),
                3,
                version);
    }

    private UUID turn(Fixture f, String state, UUID nextGoal, String responseType) {
        var before = snapshot(f).state();
        UUID id = UUID.randomUUID();
        long version = key(f).checkpointVersion() + 1;
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            jdbc.update(
                                    """
                    insert into roleplay_turn(turn_id,session_id,request_id,idempotency_key,input_fingerprint,
                        turn_number,candidate_id,micro_goal_id,support_level,response_text,canonical_utterance,
                        checkpoint_version,committed_at,response_type,strategy_used,input_micro_goal_id,
                        input_support_level,input_support_turn,input_prompt_count,input_answer_revealed)
                    values(?,?,?,?,?,?,?,?, 'S1','synthetic response','synthetic input',?,now(),?,'PROBE_EVENT',?,?,?,?,?)
                    """,
                                    id,
                                    f.key().sessionId(),
                                    UUID.randomUUID(),
                                    UUID.randomUUID(),
                                    "a".repeat(64),
                                    before.turnCount(),
                                    UUID.randomUUID(),
                                    nextGoal,
                                    version,
                                    responseType,
                                    before.currentMicroGoalId(),
                                    before.currentSupportLevel(),
                                    before.currentMicroGoalSupportTurn(),
                                    before.currentMicroGoalPromptCount(),
                                    before.answerRevealed());
                            jdbc.update(
                                    """
                    insert into roleplay_goal_assessment(session_id,turn_id,checkpoint_version,scenario_id,
                        scenario_version,approval_reference,analysis_contract_version,evaluation_contract_version,
                        decision,input_micro_goal_id,input_learning_state)
                    values(?,?,?,?,3,?,?,?,'IN_PROGRESS',?,?)
                    """,
                                    f.key().sessionId(),
                                    id,
                                    version,
                                    f.key().scenarioId(),
                                    f.approvalReference(),
                                    GoalObservationOutput.ANALYSIS_VERSION,
                                    GoalObservationOutput.EVALUATION_VERSION,
                                    before.currentMicroGoalId(),
                                    state);
                            jdbc.update(
                                    "insert into roleplay_goal_observation(session_id,turn_id,micro_goal_id,criterion_reference,observation_status) values(?,?,?,?,'EXPLICIT')",
                                    f.key().sessionId(),
                                    id,
                                    before.currentMicroGoalId(),
                                    f.references().get(before.currentMicroGoalId()));
                            jdbc.update(
                                    "update roleplay_session set last_turn_number=?,last_turn_id=?,row_version=?,current_micro_goal_id=?,current_support_level='S1' where session_id=?",
                                    before.turnCount(),
                                    id,
                                    version,
                                    nextGoal,
                                    f.key().sessionId());
                        });
        return id;
    }

    private Fixture fixture() {
        UUID teacher = UUID.randomUUID(),
                classroom = UUID.randomUUID(),
                child = UUID.randomUUID(),
                goal = UUID.randomUUID(),
                activity = UUID.randomUUID(),
                scenario = UUID.randomUUID(),
                first = UUID.randomUUID(),
                second = UUID.randomUUID();
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
                "insert into learning_goal(goal_id,child_id,instructor_id,title,content_hash,created_at) values(?,?,?,'synthetic',?,now())",
                goal,
                child,
                teacher,
                "a".repeat(64));
        jdbc.update(
                "insert into activity(activity_id,child_id,goal_id,scenario_id,activity_type,status,initial_support_level,difficulty_fallback_applied,assigned_at) values(?,?,?,?,'FIRST_COMBINED','IN_PROGRESS','S2',false,now())",
                activity,
                child,
                goal,
                scenario);
        jdbc.update(
                "insert into quiz_result(activity_id,valid_question_count,correct_question_count,overall_accuracy,total_hint_count,resolved_after_hint_count,scenario_level,initial_support_level,difficulty_fallback_applied,initial_difficulty_used,policy_version,snapshotted_at) values(?,1,0,0,0,0,'L1','S2',false,true,'INITIAL_QUIZ_V2',now())",
                activity);
        jdbc.update(
                "insert into scenario(scenario_id,class_id,goal_content_hash,scenario_version,status,scenario_level,title,scenario_facts,opening_prompts,origin,created_at) values(?,?,?,3,'DRAFT','L1','synthetic','[\"fact\"]'::jsonb,'{\"S2\":\"synthetic opening?\"}'::jsonb,'PREPARED',now())",
                scenario,
                classroom,
                "a".repeat(64));
        jdbc.update(
                "insert into scenario_micro_goal(micro_goal_id,scenario_id,order_no,description,required_evidence) values(?,?,1,'first','[\"first evidence\"]'::jsonb),(?,?,2,'second','[\"second evidence\"]'::jsonb)",
                first,
                scenario,
                second,
                scenario);
        jdbc.update(
                "insert into scenario_opening_focus(scenario_id,support_level,micro_goal_id) values(?,'S2',?)",
                scenario,
                first);
        jdbc.update("update scenario set status='APPROVED' where scenario_id=?", scenario);
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        new ChildPrincipal(child),
                        "",
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_CHILD")));
        var started = starts.startForDelivery(activity, UUID.randomUUID(), authentication);
        var key =
                new RoleplayContextSource.Key(
                        child, activity, goal, started.response().sessionId(), scenario, 3, 0);
        var snapshot = contexts.load(key, RoleplayTurnDeadline.start()).orElseThrow();
        var references =
                snapshot.observations().approvedCriteria().stream()
                        .collect(Collectors.toMap(c -> c.microGoalId(), c -> c.reference()));
        return new Fixture(
                key, first, second, references, snapshot.approvalReference(), authentication);
    }

    private record Fixture(
            RoleplayContextSource.Key key,
            UUID first,
            UUID second,
            Map<UUID, String> references,
            String approvalReference,
            Authentication authentication) {}
}
