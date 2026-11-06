package com.neuringo.neuringobe.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;

import com.neuringo.neuringobe.IntegrationTest;
import com.neuringo.neuringobe.activity.domain.*;
import com.neuringo.neuringobe.activity.repository.ActivityReadinessRepository;
import com.neuringo.neuringobe.assignment.service.ActivityReadinessQueryService;
import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.quiz.service.QuizAssignmentService;
import com.neuringo.neuringobe.scenario.support.ScenarioPoolTestIsolation;
import com.neuringo.neuringobe.user.domain.UserRole;
import java.util.*;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@ExtendWith(ScenarioPoolTestIsolation.class)
class ActivityReadinessQueryIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private JsonMapper json;
    @Autowired private ActivityReadinessQueryService service;

    @Test
    void incompleteQuizNeedsNoDifficultyOrScenarioAndDoesNotChangePausedState() {
        var f = fixture();
        UUID activity = activity(f, ActivityType.FIRST_COMBINED, "PAUSED");
        quiz(activity, ActivityType.FIRST_COMBINED);
        assertAction(f, activity, ActivityNextAction.QUIZ, null);
        assertThat(
                        jdbc.queryForObject(
                                "select status from activity where activity_id=?",
                                String.class,
                                activity))
                .isEqualTo("PAUSED");
    }

    @Test
    void detectsIncompleteOrChangedQuizCompositionWithoutRegeneratingAssignments() {
        var f = fixture();
        UUID activity = activity(f, ActivityType.FIRST_COMBINED, "IN_PROGRESS");
        quiz(activity, ActivityType.FIRST_COMBINED);
        jdbc.update(
                "update activity_quiz set item_version=item_version+1 where activity_id=? and question_order=1",
                activity);
        assertAction(f, activity, ActivityNextAction.BLOCKED, ActivityBlockedReason.QUIZ_NOT_READY);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from activity_quiz where activity_id=?",
                                Integer.class,
                                activity))
                .isEqualTo(3);
    }

    @Test
    void completedQuizWaitsForTeacherOnlyWhenDifficultyIsAppliedConsistently() {
        var f = fixture();
        UUID activity = activity(f, ActivityType.FIRST_COMBINED, "IN_PROGRESS");
        result(activity);
        assertAction(f, activity, ActivityNextAction.WAIT_FOR_SCENARIO, null);
        jdbc.update("update activity set initial_support_level='S2' where activity_id=?", activity);
        assertAction(
                f,
                activity,
                ActivityNextAction.BLOCKED,
                ActivityBlockedReason.DIFFICULTY_NOT_READY);
    }

    @Test
    void existingAssignmentUsesLegacyApprovalViewButDoesNotInventApprovalRows() {
        var f = fixture();
        UUID activity = activity(f, ActivityType.FIRST_COMBINED, "IN_PROGRESS");
        result(activity);
        scenario(f, activity);
        assertAction(f, activity, ActivityNextAction.ROLEPLAY, null);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from activity_scenario_approval where activity_id=?",
                                Integer.class,
                                activity))
                .isZero();
        jdbc.update(
                "update learning_goal set content_hash=? where goal_id=(select goal_id from activity where activity_id=?)",
                "b".repeat(64),
                activity);
        assertAction(
                f, activity, ActivityNextAction.BLOCKED, ActivityBlockedReason.SCENARIO_NOT_READY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "PAUSED", "RECOVERY_NEEDED"})
    void readsResumableSessionWithoutOldQuizAndWithoutReleasingStoppedState(String status) {
        var f = fixture();
        UUID activity = activity(f, ActivityType.FIRST_COMBINED, status);
        var scenario = scenario(f, activity);
        UUID session = session(f, activity, scenario);
        var before = jdbc.queryForMap("select * from roleplay_session where session_id=?", session);
        assertAction(f, activity, ActivityNextAction.ROLEPLAY, null);
        assertThat(jdbc.queryForMap("select * from roleplay_session where session_id=?", session))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "select status from activity where activity_id=?",
                                String.class,
                                activity))
                .isEqualTo(status);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_start_message where session_id=?",
                                Integer.class,
                                session))
                .isZero();
    }

    @Test
    void inconsistentCommittedCheckpointIsBlockedInsteadOfBeingReplacedByAnOpeningQuestion() {
        var f = fixture();
        UUID activity = activity(f, ActivityType.FIRST_COMBINED, "RECOVERY_NEEDED");
        var scenario = scenario(f, activity);
        UUID session = session(f, activity, scenario);
        UUID turn = UUID.randomUUID();
        jdbc.update(
                "insert into roleplay_turn(turn_id,session_id,request_id,idempotency_key,input_fingerprint,turn_number,candidate_id,micro_goal_id,support_level,response_text,checkpoint_version,committed_at) values(?,?,?,?,?,1,?,?,'S1','synthetic',1,now())",
                turn,
                session,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "c".repeat(64),
                UUID.randomUUID(),
                scenario.goal());
        jdbc.update(
                "update roleplay_session set last_turn_number=1,last_turn_id=? where session_id=?",
                turn,
                session);
        assertAction(
                f,
                activity,
                ActivityNextAction.BLOCKED,
                ActivityBlockedReason.RESUME_DATA_NOT_READY);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=?",
                                Integer.class,
                                session))
                .isEqualTo(1);
    }

    @Test
    void doesNotSelectOneOfMultipleSessionsOrReactivateCompletedSessions() {
        var f = fixture();
        UUID activity = activity(f, ActivityType.FIRST_COMBINED, "RECOVERY_NEEDED");
        var scenario = scenario(f, activity);
        UUID first = session(f, activity, scenario);
        jdbc.update(
                "update roleplay_session set status='COMPLETED', completion_kind='NORMAL' where session_id=?",
                first);
        assertAction(
                f,
                activity,
                ActivityNextAction.BLOCKED,
                ActivityBlockedReason.RESUME_DATA_NOT_READY);
        session(f, activity, scenario);
        assertAction(
                f,
                activity,
                ActivityNextAction.BLOCKED,
                ActivityBlockedReason.RESUME_DATA_NOT_READY);
    }

    @Test
    void completedHistoryNeedsNoHistoricalQuizOrScenario() {
        var f = fixture();
        UUID activity = activity(f, ActivityType.FIRST_COMBINED, "COMPLETED");
        assertAction(f, activity, ActivityNextAction.NONE, null);
    }

    @Test
    void oneAndTwentyActivityPagesUseOneEvidenceStatementAndOnlyRequestedIds() {
        var f = fixture();
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 20; i++) {
            UUID activity = activity(f, ActivityType.SELF_EMOTION_QUIZ, "IN_PROGRESS");
            quiz(activity, ActivityType.SELF_EMOTION_QUIZ);
            ids.add(activity);
        }
        var template = spy(new NamedParameterJdbcTemplate(dataSource));
        var repository = new ActivityReadinessRepository(template, json);
        var composition =
                Arrays.stream(ActivityType.values())
                        .collect(
                                Collectors.toMap(
                                        Enum::name,
                                        type ->
                                                QuizAssignmentService.composition(type).stream()
                                                        .map(Enum::name)
                                                        .sorted()
                                                        .toList()));
        assertThat(repository.load(f.child(), ids.subList(0, 1), composition))
                .containsOnlyKeys(ids.getFirst());
        assertThat(repository.load(f.child(), ids, composition)).hasSize(20);
        assertThat(
                        mockingDetails(template).getInvocations().stream()
                                .filter(i -> i.getMethod().getName().equals("query")))
                .hasSize(2);
        assertThat(service.read(f.child(), ids, auth(f)).values())
                .containsOnly(ActivityReadiness.available(ActivityNextAction.QUIZ));
    }

    @Test
    void emptyPageIsEmptyAndForeignChildOrActivityIsHidden() {
        var f = fixture();
        var other = fixture();
        UUID mine = activity(f, ActivityType.FIRST_COMBINED, "NOT_STARTED");
        UUID foreign = activity(other, ActivityType.FIRST_COMBINED, "NOT_STARTED");
        assertThat(service.read(f.child(), List.of(), auth(f))).isEmpty();
        assertThatThrownBy(() -> service.read(other.child(), List.of(foreign), auth(f)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.read(f.child(), List.of(mine, foreign), auth(f)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "MISSING_ITEM",
                "WRONG_TYPE",
                "EMPTY_CHOICES",
                "SCALAR_CHOICES",
                "WRONG_ORDER"
            })
    void rejectsInvalidAssignedQuizWithoutConvertingItToACompletedQuiz(String change) {
        var f = fixture();
        UUID activity = activity(f, ActivityType.FIRST_COMBINED, "IN_PROGRESS");
        quiz(activity, ActivityType.FIRST_COMBINED);
        switch (change) {
            case "MISSING_ITEM" ->
                    jdbc.update(
                            "delete from activity_quiz where activity_id=? and question_order=3",
                            activity);
            case "WRONG_TYPE" ->
                    jdbc.update(
                            "update quiz_item set quiz_type='SELF_EMOTION_SITUATION' where item_id=(select item_id from activity_quiz where activity_id=? and question_order=3)",
                            activity);
            case "EMPTY_CHOICES" ->
                    jdbc.update(
                            "update quiz_item set choices_json='[]' where item_id=(select item_id from activity_quiz where activity_id=? and question_order=1)",
                            activity);
            case "SCALAR_CHOICES" ->
                    jdbc.update(
                            "update quiz_item set choices_json='42' where item_id=(select item_id from activity_quiz where activity_id=? and question_order=1)",
                            activity);
            case "WRONG_ORDER" ->
                    jdbc.update(
                            "update activity_quiz set question_order=4 where activity_id=? and question_order=1",
                            activity);
            default -> throw new IllegalArgumentException(change);
        }
        assertAction(f, activity, ActivityNextAction.BLOCKED, ActivityBlockedReason.QUIZ_NOT_READY);
    }

    @Test
    void sharedAssignmentUsesItsExplicitProofInsteadOfLegacyClassAndHash() throws Exception {
        var f = fixture();
        UUID activity = activity(f, ActivityType.FIRST_COMBINED, "IN_PROGRESS");
        result(activity);
        var scenario = scenario(f, activity);
        jdbc.update("update scenario set class_id=null where scenario_id=?", scenario.id());
        jdbc.update(
                "update learning_goal set content_hash=? where goal_id=(select goal_id from activity where activity_id=?)",
                "b".repeat(64),
                activity);
        assertAction(
                f, activity, ActivityNextAction.BLOCKED, ActivityBlockedReason.SCENARIO_NOT_READY);
        jdbc.update(
                "insert into scenario_approval(scenario_id,instructor_id,goal_title,scenario_level,verification_rule_version,approved_at) values(?,?,'synthetic','L2',1,now())",
                scenario.id(),
                f.user());
        jdbc.update(
                "insert into activity_scenario_approval(activity_id,review_id,child_id,goal_id,goal_content_hash,instructor_id,scenario_id,scenario_version,scenario_level,content_fingerprint,approved_at) select activity_id,?,child_id,goal_id,?, ?,?,1,'L2',?,now() from activity where activity_id=?",
                UUID.randomUUID(),
                "b".repeat(64),
                f.user(),
                scenario.id(),
                "c".repeat(64),
                activity);
        assertAction(f, activity, ActivityNextAction.ROLEPLAY, null);
    }

    private void assertAction(
            Fixture f, UUID activity, ActivityNextAction action, ActivityBlockedReason reason) {
        assertThat(service.read(f.child(), List.of(activity), auth(f)))
                .containsEntry(activity, new ActivityReadiness(action, reason));
    }

    private Authentication auth(Fixture f) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(f.user(), UUID.randomUUID(), UserRole.INSTRUCTOR),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_INSTRUCTOR")));
    }

    private record Fixture(UUID user, UUID classroom, UUID child) {}

    private record Scenario(UUID id, UUID goal) {}

    private Fixture fixture() {
        UUID user = UUID.randomUUID(), classroom = UUID.randomUUID(), child = UUID.randomUUID();
        jdbc.update(
                "insert into user_account(user_id,email,password_hash,name,role,status,created_at) values(?,?,?,'synthetic','INSTRUCTOR','ACTIVE',now())",
                user,
                user + "@example.com",
                "test-hash");
        jdbc.update(
                "insert into classroom(class_id,instructor_id,name,status) values(?,?,'synthetic','ACTIVE')",
                classroom,
                user);
        jdbc.update(
                "insert into child(child_id,class_id,display_name,status) values(?,?,'synthetic','ACTIVE')",
                child,
                classroom);
        return new Fixture(user, classroom, child);
    }

    private UUID activity(Fixture f, ActivityType type, String status) {
        UUID id = UUID.randomUUID(), goal = type.requiresGoal() ? UUID.randomUUID() : null;
        if (goal != null)
            jdbc.update(
                    "insert into learning_goal(goal_id,child_id,instructor_id,title,content_hash,created_at) values(?,?,?,'synthetic',?,now())",
                    goal,
                    f.child(),
                    f.user(),
                    "a".repeat(64));
        jdbc.update(
                "insert into activity(activity_id,child_id,goal_id,activity_type,status,assigned_at) values(?,?,?,?,?,now())",
                id,
                f.child(),
                goal,
                type.name(),
                status);
        return id;
    }

    private void quiz(UUID activity, ActivityType type) {
        int order = 0;
        for (var quizType : QuizAssignmentService.composition(type)) {
            UUID item = UUID.randomUUID();
            jdbc.update(
                    "insert into quiz_item(item_id,quiz_type,emotion,question_text,image_url,choices_json,correct_answer,acceptable_answers_json,hints_json,status,item_version) values(?,?,'JOY','synthetic','/synthetic-image','[\"기쁨\",\"슬픔\",\"화남\"]','기쁨','[\"기쁨\"]','[]','APPROVED',1)",
                    item,
                    quizType.name());
            jdbc.update(
                    "insert into activity_quiz(activity_quiz_id,activity_id,item_id,item_version,question_order) values(?,?,?,1,?)",
                    UUID.randomUUID(),
                    activity,
                    item,
                    ++order);
        }
    }

    private void result(UUID activity) {
        jdbc.update("update activity set initial_support_level='S1' where activity_id=?", activity);
        jdbc.update(
                "insert into quiz_result(activity_id,valid_question_count,correct_question_count,overall_accuracy,total_hint_count,resolved_after_hint_count,scenario_level,initial_support_level,difficulty_fallback_applied,initial_difficulty_used,policy_version,snapshotted_at) values(?,3,2,0.666667,0,0,'L2','S1',false,true,'INITIAL_QUIZ_V2',now())",
                activity);
    }

    private Scenario scenario(Fixture f, UUID activity) {
        UUID id = UUID.randomUUID(), goal = UUID.randomUUID();
        jdbc.update(
                "insert into scenario(scenario_id,class_id,status,scenario_level,goal_content_hash,title,scenario_facts,opening_prompts,origin,created_at) values(?,?,'DRAFT','L2',?,'synthetic','[\"사실\"]','{\"S1\":\"어떻게 할까?\"}','PREPARED',now())",
                id,
                f.classroom(),
                "a".repeat(64));
        jdbc.update(
                "insert into scenario_micro_goal(micro_goal_id,scenario_id,order_no,description,required_evidence) values(?,?,1,'synthetic','[\"말한다\"]')",
                goal,
                id);
        jdbc.update(
                "insert into scenario_opening_focus(scenario_id,support_level,micro_goal_id) values(?,'S1',?)",
                id,
                goal);
        jdbc.update("update scenario set status='APPROVED' where scenario_id=?", id);
        jdbc.update("update activity set scenario_id=? where activity_id=?", id, activity);
        return new Scenario(id, goal);
    }

    private UUID session(Fixture f, UUID activity, Scenario scenario) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into roleplay_session(session_id,activity_id,child_id,scenario_id,scenario_version,status,current_micro_goal_id,current_support_level,last_activity_at) values(?,?,?,?,1,'PAUSED',?,'S1',now())",
                id,
                activity,
                f.child(),
                scenario.id(),
                scenario.goal());
        return id;
    }
}
