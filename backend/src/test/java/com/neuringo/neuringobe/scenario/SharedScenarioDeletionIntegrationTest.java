package com.neuringo.neuringobe.scenario;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.neuringo.neuringobe.LocalProfileIntegrationTest;
import com.neuringo.neuringobe.TestFixtures;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import com.neuringo.neuringobe.child.security.ChildPrincipal;
import com.neuringo.neuringobe.roleplay.application.RoleplayContextSource;
import com.neuringo.neuringobe.roleplay.application.RoleplayProgressSource;
import com.neuringo.neuringobe.roleplay.service.InitialRoleplayStartQueryService;
import com.neuringo.neuringobe.roleplay.service.RoleplayStartService;
import com.neuringo.neuringobe.scenario.domain.ScenarioApprovalConditions;
import com.neuringo.neuringobe.scenario.repository.*;
import com.neuringo.neuringobe.scenario.service.ScenarioApprovalRegistry;
import com.neuringo.neuringobe.scenario.support.ScenarioPoolTestIsolation;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@LocalProfileIntegrationTest
@org.junit.jupiter.api.extension.ExtendWith(ScenarioPoolTestIsolation.class)
class SharedScenarioDeletionIntegrationTest {
    @Autowired private TestFixtures fixtures;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private ScenarioDraftRepository drafts;
    @Autowired private ScenarioApprovalRegistry approvals;
    @Autowired private ScenarioConfirmationRepository assignments;
    @Autowired private ScenarioReviewRepository reviews;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private InitialRoleplayStartQueryService starts;
    @Autowired private RoleplayStartService roleplay;

    @Autowired private RoleplayContextSource contexts;
    @MockitoBean private RoleplayProgressSource progress;

    @BeforeEach
    void progressPolicy() {
        when(progress.load(any(), any()))
                .thenAnswer(
                        call -> {
                            RoleplayContextSource.Key key = call.getArgument(0);
                            var session =
                                    jdbc.queryForMap(
                                            "SELECT current_micro_goal_id,current_support_level,last_turn_number FROM roleplay_session WHERE session_id=?",
                                            key.sessionId());
                            var statuses = new java.util.HashMap<UUID, String>();
                            jdbc.queryForList(
                                            "SELECT micro_goal_id FROM scenario_micro_goal WHERE scenario_id=?",
                                            UUID.class,
                                            key.scenarioId())
                                    .forEach(id -> statuses.put(id, "NOT_OBSERVED"));
                            return Optional.of(
                                    new RoleplayProgressSource.Progress(
                                            (UUID) session.get("current_micro_goal_id"),
                                            (String) session.get("current_support_level"),
                                            ((Number) session.get("last_turn_number")).intValue()
                                                    + 1,
                                            77,
                                            9,
                                            statuses));
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSTRUCTOR", "CLASSROOM", "CHILD"})
    void permanentDeletionPreservesSharedContentAndOtherInstructorsAssignment(String scope)
            throws Exception {
        var a = context(fixtures.signUpInstructor());
        var b = context(fixtures.signUpInstructor());
        UUID source = sharedScenario(a, b);
        var contentBody = assignments.contentBody(source);
        var approval =
                jdbc.queryForMap("SELECT * FROM scenario_approval WHERE scenario_id=?", source);
        var content = jdbc.queryForMap("SELECT * FROM scenario WHERE scenario_id=?", source);
        var proof =
                jdbc.queryForMap(
                        "SELECT * FROM activity_scenario_approval WHERE activity_id=?",
                        b.activity());

        var started = roleplay.start(b.activity(), UUID.randomUUID(), childAuth(b));
        var key =
                new RoleplayContextSource.Key(
                        b.child(),
                        b.activity(),
                        b.goal(),
                        started.session().sessionId(),
                        source,
                        1,
                        0);
        var before = contexts.load(key, RoleplayTurnDeadline.start()).orElseThrow();
        assertThat(before.approvalReference()).startsWith("scenario-context/v2:");

        remove(a, scope).andExpect(status().isNoContent());

        assertThat(count("child", "child_id", a.child())).isZero();
        assertThat(count("activity", "activity_id", a.activity())).isZero();
        assertThat(count("learning_goal", "goal_id", a.goal())).isZero();
        assertThat(count("quiz_result", "activity_id", a.activity())).isZero();
        assertThat(count("activity_scenario_approval", "activity_id", a.activity())).isZero();
        assertThat(count("user_account", "user_id", a.owner().userId()))
                .isEqualTo(scope.equals("INSTRUCTOR") ? 0 : 1);
        assertThat(count("classroom", "class_id", a.classroom()))
                .isEqualTo(scope.equals("CHILD") ? 1 : 0);
        var afterContent = jdbc.queryForMap("SELECT * FROM scenario WHERE scenario_id=?", source);
        var afterApproval =
                jdbc.queryForMap("SELECT * FROM scenario_approval WHERE scenario_id=?", source);
        if (!scope.equals("CHILD")) content.put("class_id", null);
        if (scope.equals("INSTRUCTOR")) approval.put("instructor_id", null);
        assertThat(afterContent).isEqualTo(content);
        assertThat(assignments.contentBody(source)).isEqualTo(contentBody);
        assertThat(afterApproval).isEqualTo(approval);
        assertThat(count("scenario_micro_goal", "scenario_id", source)).isEqualTo(3);
        assertThat(count("scenario_opening_focus", "scenario_id", source)).isEqualTo(3);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM activity_scenario_approval WHERE activity_id=?",
                                b.activity()))
                .isEqualTo(proof);
        assertThat(count("scenario_presentation_stat", "instructor_id", a.owner().userId()))
                .isEqualTo(scope.equals("INSTRUCTOR") ? 0 : 1);
        assertThat(count("scenario_presentation_stat", "instructor_id", b.owner().userId()))
                .isEqualTo(1);
        var restored = roleplay.start(b.activity(), UUID.randomUUID(), childAuth(b));
        assertThat(restored.created()).isFalse();
        assertThat(restored.session().sessionId()).isEqualTo(started.session().sessionId());
        var after = contexts.load(key, RoleplayTurnDeadline.start()).orElseThrow();
        assertThat(after.approvalReference()).isEqualTo(before.approvalReference());
        assertThat(after.scenario()).isEqualTo(before.scenario());
    }

    @Test
    void withdrawalAlsoCleansPendingDraftButKeepsApprovedSharedSource() throws Exception {
        var a = context(fixtures.signUpInstructor());
        var b = context(fixtures.signUpInstructor());
        UUID source = sharedScenario(a, b);
        var pending = context(a.owner());
        UUID draft = draft();
        UUID review = UUID.randomUUID();
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        tx -> {
                            jdbc.update(
                                    "INSERT INTO scenario_review (review_id,activity_id,instructor_id,input_fingerprint) VALUES (?,?,?,?)",
                                    review,
                                    pending.activity(),
                                    a.owner().userId(),
                                    "0".repeat(64));
                            jdbc.update(
                                    "INSERT INTO scenario_review_candidate (review_id,position,scenario_id,scenario_version,candidate_source) VALUES (?,1,?,1,'GENERATED')",
                                    review,
                                    draft);
                            jdbc.update(
                                    "UPDATE scenario_review SET current_position=1 WHERE review_id=?",
                                    review);
                        });

        remove(a, "INSTRUCTOR").andExpect(status().isNoContent());

        assertThat(count("scenario", "scenario_id", draft)).isZero();
        assertThat(count("scenario_review", "review_id", review)).isZero();
        assertThat(count("scenario", "scenario_id", source)).isEqualTo(1);
        assertThat(count("scenario_approval", "scenario_id", source)).isEqualTo(1);
        assertThat(count("child", "child_id", pending.child())).isZero();
    }

    @Test
    void incorrectPasswordDoesNotDetachOrDeleteAnything() throws Exception {
        var a = context(fixtures.signUpInstructor());
        var b = context(fixtures.signUpInstructor());
        UUID source = sharedScenario(a, b);
        var content = jdbc.queryForMap("SELECT * FROM scenario WHERE scenario_id=?", source);
        send(a.owner(), post("/api/v1/users/me/withdrawal"), Map.of("password", "wrong-password"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForMap("SELECT * FROM scenario WHERE scenario_id=?", source))
                .isEqualTo(content);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT instructor_id FROM scenario_approval WHERE scenario_id=?",
                                UUID.class,
                                source))
                .isEqualTo(a.owner().userId());
        assertThat(count("activity", "activity_id", a.activity())).isEqualTo(1);
    }

    @Test
    void anonymizationDoesNotPermitContentConditionsOrNewOwnershipChanges() throws Exception {
        var a = context(fixtures.signUpInstructor());
        var b = context(fixtures.signUpInstructor());
        UUID source = sharedScenario(a, b);
        remove(a, "INSTRUCTOR").andExpect(status().isNoContent());
        for (String sql :
                List.of(
                        "UPDATE scenario SET title='다른 내용' WHERE scenario_id=?",
                        "UPDATE scenario SET class_id='" + b.classroom() + "' WHERE scenario_id=?",
                        "UPDATE scenario_approval SET instructor_id='"
                                + b.owner().userId()
                                + "' WHERE scenario_id=?",
                        "UPDATE scenario_approval SET goal_title='다른 조건' WHERE scenario_id=?",
                        "UPDATE scenario_micro_goal SET description='다른 목표' WHERE scenario_id=?")) {
            assertThatThrownBy(() -> jdbc.update(sql, source))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .executeWithoutResult(
                                                tx ->
                                                        approvals.recordApproval(
                                                                source,
                                                                b.owner().userId(),
                                                                conditions())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureAtAccountDeletionRollsBackEarlierDetachmentsAndPersonalDataDeletion()
            throws Exception {
        var a = context(fixtures.signUpInstructor());
        var b = context(fixtures.signUpInstructor());
        UUID source = sharedScenario(a, b);
        String name = "test_shared_delete_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                "CREATE FUNCTION "
                        + name
                        + "() RETURNS trigger AS $$ BEGIN IF OLD.user_id='"
                        + a.owner().userId()
                        + "'::uuid THEN RAISE EXCEPTION 'Synthetic withdrawal failure'; END IF; RETURN OLD; END; $$ LANGUAGE plpgsql");
        jdbc.execute(
                "CREATE TRIGGER "
                        + name
                        + " BEFORE DELETE ON user_account FOR EACH ROW EXECUTE FUNCTION "
                        + name
                        + "()");
        try {
            remove(a, "INSTRUCTOR").andExpect(status().isInternalServerError());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT class_id FROM scenario WHERE scenario_id=?",
                                    UUID.class,
                                    source))
                    .isEqualTo(a.classroom());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT instructor_id FROM scenario_approval WHERE scenario_id=?",
                                    UUID.class,
                                    source))
                    .isEqualTo(a.owner().userId());
            assertThat(count("child", "child_id", a.child())).isEqualTo(1);
            assertThat(count("activity", "activity_id", a.activity())).isEqualTo(1);
            assertThat(
                            starts.requireForActivity(b.activity(), childAuth(b))
                                    .scenario()
                                    .scenarioId())
                    .isEqualTo(source);
        } finally {
            jdbc.execute("DROP TRIGGER " + name + " ON user_account");
            jdbc.execute("DROP FUNCTION " + name + "()");
        }
    }

    private UUID sharedScenario(Context a, Context b) throws Exception {
        UUID id = draft();
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        tx -> {
                            jdbc.update(
                                    "UPDATE scenario SET class_id=?,goal_content_hash=?,status='APPROVED' WHERE scenario_id=?",
                                    a.classroom(),
                                    a.goal().toString().replace("-", "").repeat(2),
                                    id);
                            approvals.recordApproval(id, a.owner().userId(), conditions());
                            // 공통 후보 API 전환 전, 서로 다른 강사의 배정 관계를 DB 픽스처로 구성한다.
                            for (var ctx : List.of(a, b)) {
                                assignments.assign(ctx.activity(), id, "REUSE");
                                assignments.recordAssignment(
                                        ctx.activity(),
                                        UUID.randomUUID(),
                                        ctx.owner().userId(),
                                        id,
                                        1,
                                        "L2",
                                        "0".repeat(64),
                                        Instant.now());
                                reviews.recordPresentation(ctx.owner().userId(), id);
                            }
                        });
        return id;
    }

    private UUID draft() throws Exception {
        var body =
                new ClassPathResource("scenario/generation-valid-l2.json")
                        .getContentAsString(StandardCharsets.UTF_8);
        return drafts.insert(json.readValue(body, GeneratedScenario.class));
    }

    private ScenarioApprovalConditions conditions() {
        return new ScenarioApprovalConditions("차례를 기다려요", "L2", null, null, null, null);
    }

    private ResultActions remove(Context ctx, String scope) throws Exception {
        return switch (scope) {
            case "INSTRUCTOR" ->
                    send(
                            ctx.owner(),
                            post("/api/v1/users/me/withdrawal"),
                            Map.of("password", ctx.owner().password()));
            case "CLASSROOM" ->
                    send(ctx.owner(), delete("/api/v1/classrooms/" + ctx.classroom()), null);
            default -> send(ctx.owner(), delete("/api/v1/children/" + ctx.child()), null);
        };
    }

    private ResultActions send(
            TestFixtures.Instructor owner, MockHttpServletRequestBuilder request, Object body)
            throws Exception {
        var csrf = fixtures.fetchCsrf();
        request.header("Authorization", "Bearer " + owner.accessToken())
                .header(csrf.headerName(), csrf.token())
                .cookie(csrf.cookies());
        if (body != null)
            request.contentType("application/json").content(json.writeValueAsString(body));
        return mvc.perform(request);
    }

    private Authentication childAuth(Context ctx) {
        return new UsernamePasswordAuthenticationToken(
                new ChildPrincipal(ctx.child()),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_CHILD")));
    }

    private long count(String table, String column, UUID id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + "=?", Long.class, id);
    }

    private Context context(TestFixtures.Instructor owner) {
        UUID classroom = fixtures.createClassroom(owner, "삭제 검사 학급");
        UUID child = fixtures.createChild(owner, classroom, "검사 아동");
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
        return new Context(owner, classroom, child, goal, activity);
    }

    private record Context(
            TestFixtures.Instructor owner, UUID classroom, UUID child, UUID goal, UUID activity) {}
}
