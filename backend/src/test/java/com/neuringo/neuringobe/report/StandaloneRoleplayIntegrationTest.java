package com.neuringo.neuringobe.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.LocalProfileIntegrationTest;
import com.neuringo.neuringobe.TestFixtures;
import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.child.security.ChildPrincipal;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.report.service.ReportPreparationService;
import com.neuringo.neuringobe.roleplay.service.RoleplayStartService;
import com.neuringo.neuringobe.scenario.service.FollowUpDifficultyService;
import com.neuringo.neuringobe.scenario.support.ScenarioPoolTestIsolation;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * 역할극 단독 활동(ROLEPLAY)은 최초 결합과 같은 공통 시나리오 검토(PR #101)로 시나리오를 확정한다.
 *
 * <p>최초 결합 역할극을 마친 기록에서 리포트를 준비하면 다음 난이도(L2·S1 유지)가 생긴다. 강사가 역할극 단독을 배정하면 그 난이도를 활동에 고정하고 시나리오는 비워
 * 둔다. 활동은 승인 대기함에 올라오고, 강사가 검토를 시작하면 고정 레벨을 입력으로 후보를 찾거나(없으면) 새로 만들며, 확정 PUT 이 활동별 확정 근거와 함께 연결한다.
 * 아동은 그 확정 시나리오로만 시작한다.
 */
@LocalProfileIntegrationTest
@ExtendWith(ScenarioPoolTestIsolation.class)
class StandaloneRoleplayIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-31T05:00:00Z");

    // 공통 후보는 목표 문구로 고르므로, 테스트끼리 후보가 섞이지 않게 테스트마다 목표 제목을 다르게 한다.
    private final String goalTitle = "넘어진 친구의 속상한 마음 알아보기 " + UUID.randomUUID();

    @Autowired private TestFixtures fixtures;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @Autowired private ReportPreparationService preparation;
    @Autowired private FollowUpDifficultyService followUpDifficulties;
    @Autowired private RoleplayStartService starts;
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
    void standaloneRoleplayWaitsInTheInboxIsConfirmedThroughTheSharedReviewAndStartsOnIt()
            throws Exception {
        var owner = fixtures.signUpInstructor();
        UUID classId = fixtures.createClassroom(owner, "역할극 단독 학급");
        UUID childId = fixtures.createChild(owner, classId, "역할극 단독 아동");
        var seed = new RoleplayReportSeed(jdbc);
        var first = seed.completedRoleplay(owner.userId(), classId, childId, NOW, "FIRST_COMBINED");
        assertThat(preparation.prepare(first.activity())).isPresent();

        // 배정: 시나리오 없이 만들고 다음 난이도를 고정한다. 퀴즈 문항은 붙이지 않는다.
        var assigned = assign(owner, childId);
        assertThat(assigned).hasStatus(HttpStatus.CREATED);
        String body = TestFixtures.body(assigned);
        UUID activity = UUID.fromString(JsonPath.read(body, "$.data.activityId"));
        assertThat((Object) JsonPath.read(body, "$.data.scenarioId")).isNull();
        assertThat((String) JsonPath.read(body, "$.data.initialSupportLevel")).isEqualTo("S1");
        assertThat(
                        jdbc.queryForMap(
                                "select scenario_level, initial_support_level from roleplay_activity_difficulty where activity_id=?",
                                activity))
                .containsEntry("scenario_level", "L2")
                .containsEntry("initial_support_level", "S1");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from activity_quiz where activity_id=?",
                                Integer.class,
                                activity))
                .isZero();

        // 확정 전: 아동은 기다리고, 승인 대기함에 고정 레벨로 올라온다.
        assertThat(nextAction(owner, childId, activity)).isEqualTo("WAIT_FOR_SCENARIO");
        assertThat(inboxRow(owner, activity))
                .containsEntry("scenarioLevel", "L2")
                .containsEntry("goalTitle", goalTitle)
                .containsEntry("state", "WAITING");
        try {
            starts.startForDelivery(activity, UUID.randomUUID(), child(childId));
            Assertions.fail("확정 전에는 시작되면 안 된다");
        } catch (ApiException expected) {
            assertThat(expected.getCode()).isEqualTo("INITIAL_ROLEPLAY_NOT_READY");
        }

        // 검토: 고정 레벨이 입력이다. 맞는 공통 승인본이 없어 같은 요청에서 새로 만든다.
        var started = start(owner, activity);
        assertThat(JsonPath.<String>read(started, "$.data.conditions.scenarioLevel"))
                .isEqualTo("L2");
        assertThat(JsonPath.<String>read(started, "$.data.conditions.title")).isEqualTo(goalTitle);
        assertThat(inboxRow(owner, activity)).containsEntry("state", "IN_REVIEW");

        String scenarioId = JsonPath.read(started, "$.data.scenario.scenarioId");
        confirm(owner, activity, started, HttpStatus.OK);

        assertThat(inboxRows(owner, activity)).isEmpty();
        assertThat(
                        jdbc.queryForMap(
                                "select scenario_id::text as scenario_id, scenario_source from activity where activity_id=?",
                                activity))
                .containsEntry("scenario_id", scenarioId)
                .containsEntry("scenario_source", "GENERATED");
        assertThat(
                        jdbc.queryForObject(
                                "select scenario_level from activity_scenario_approval where activity_id=?",
                                String.class,
                                activity))
                .isEqualTo("L2");
        assertThat(nextAction(owner, childId, activity)).isEqualTo("ROLEPLAY");

        var session = starts.startForDelivery(activity, UUID.randomUUID(), child(childId));
        assertThat(session.created()).isTrue();
        assertThat(session.response().scenarioId()).isEqualTo(UUID.fromString(scenarioId));
        assertThat(
                        jdbc.queryForObject(
                                "select current_support_level from roleplay_session where activity_id=?",
                                String.class,
                                activity))
                .isEqualTo("S1");
    }

    /**
     * 검토 입력 지문에는 활동에 고정한 결정 ID 가 들어간다. 그 활동의 고정값이 바뀌면 확정을 거부하고, 다른 역할극이 끝나 아동의 최신 결정만 바뀐 경우는 그대로
     * 확정한다.
     */
    @Test
    void confirmationFollowsThePinnedDecisionNotTheChildsLatestOne() throws Exception {
        var owner = fixtures.signUpInstructor();
        UUID classId = fixtures.createClassroom(owner, "고정 난이도 학급");
        UUID childId = fixtures.createChild(owner, classId, "고정 난이도 아동");
        var seed = new RoleplayReportSeed(jdbc);
        var first = seed.completedRoleplay(owner.userId(), classId, childId, NOW, "FIRST_COMBINED");
        assertThat(preparation.prepare(first.activity())).isPresent();
        var assigned = assign(owner, childId);
        assertThat(assigned).hasStatus(HttpStatus.CREATED);
        UUID activity =
                UUID.fromString(JsonPath.read(TestFixtures.body(assigned), "$.data.activityId"));
        UUID pinnedDecision =
                jdbc.queryForObject(
                        "select source_decision_id from roleplay_activity_difficulty where activity_id=?",
                        UUID.class,
                        activity);
        var started = start(owner, activity);

        // 다른 역할극이 끝나 이 아동의 최신 결정이 바뀐다.
        UUID newer = newerDecision(owner, classId, childId, seed);
        assertThat(followUpDifficulties.latestForChild(childId).orElseThrow().getDecisionId())
                .isEqualTo(newer);

        // 이 활동의 고정값이 바뀌면 이전 검토로는 확정하지 않는다.
        jdbc.update(
                "update roleplay_activity_difficulty set source_decision_id=? where activity_id=?",
                newer,
                activity);
        String rejected = confirm(owner, activity, started, HttpStatus.CONFLICT);
        assertThat(JsonPath.<String>read(rejected, "$.error.code"))
                .isEqualTo("SCENARIO_REVIEW_INPUT_CHANGED");
        assertThat(
                        jdbc.queryForObject(
                                "select scenario_id from activity where activity_id=?",
                                UUID.class,
                                activity))
                .isNull();

        // 고정값이 그대로면 아동의 최신 결정이 바뀌었어도 같은 검토로 확정한다.
        jdbc.update(
                "update roleplay_activity_difficulty set source_decision_id=? where activity_id=?",
                pinnedDecision,
                activity);
        confirm(owner, activity, started, HttpStatus.OK);
        assertThat(
                        jdbc.queryForObject(
                                "select scenario_id from activity where activity_id=?",
                                UUID.class,
                                activity))
                .isNotNull();
    }

    @Test
    void aScenarioRetiredAfterConfirmationDoesNotStart() throws Exception {
        var owner = fixtures.signUpInstructor();
        UUID classId = fixtures.createClassroom(owner, "폐기 학급");
        UUID childId = fixtures.createChild(owner, classId, "폐기 아동");
        var seed = new RoleplayReportSeed(jdbc);
        var first = seed.completedRoleplay(owner.userId(), classId, childId, NOW, "FIRST_COMBINED");
        assertThat(preparation.prepare(first.activity())).isPresent();
        var assigned = assign(owner, childId);
        UUID activity =
                UUID.fromString(JsonPath.read(TestFixtures.body(assigned), "$.data.activityId"));
        var started = start(owner, activity);
        confirm(owner, activity, started, HttpStatus.OK);
        UUID confirmed =
                jdbc.queryForObject(
                        "select scenario_id from activity where activity_id=?",
                        UUID.class,
                        activity);
        jdbc.update("update scenario set status='RETIRED' where scenario_id=?", confirmed);

        try {
            starts.startForDelivery(activity, UUID.randomUUID(), child(childId));
            Assertions.fail("폐기된 시나리오로 시작되면 안 된다");
        } catch (ApiException expected) {
            assertThat(expected.getCode()).isEqualTo("INITIAL_ROLEPLAY_NOT_READY");
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_session where activity_id=?",
                                Integer.class,
                                activity))
                .isZero();
    }

    private MvcTestResult assign(TestFixtures.Instructor owner, UUID childId) {
        var body = new HashMap<String, Object>();
        body.put("childId", childId);
        body.put("activityType", "ROLEPLAY");
        body.put("goal", Map.of("title", goalTitle));
        return fixtures.postJsonWithKey(owner, "/api/v1/activities", body, UUID.randomUUID());
    }

    private String start(TestFixtures.Instructor owner, UUID activity) throws Exception {
        var result =
                send(
                        owner,
                        activity,
                        HttpMethod.POST,
                        "/scenario-review",
                        Map.of("action", "START_OR_RESUME"));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String confirm(
            TestFixtures.Instructor owner, UUID activity, String started, HttpStatus expected)
            throws Exception {
        var result =
                send(
                        owner,
                        activity,
                        HttpMethod.PUT,
                        "/scenario",
                        Map.of(
                                "reviewId", JsonPath.read(started, "$.data.reviewId"),
                                "revision", JsonPath.read(started, "$.data.revision"),
                                "scenarioId", JsonPath.read(started, "$.data.scenario.scenarioId"),
                                "scenarioVersion",
                                        JsonPath.read(started, "$.data.scenario.scenarioVersion")));
        assertThat(result.getResponse().getStatus()).isEqualTo(expected.value());
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private MvcResult send(
            TestFixtures.Instructor owner,
            UUID activity,
            HttpMethod method,
            String path,
            Object body)
            throws Exception {
        var csrf = fixtures.fetchCsrf();
        return mvc.perform(
                        request(method, "/api/v1/activities/" + activity + path)
                                .header("Authorization", "Bearer " + owner.accessToken())
                                .cookie(csrf.cookies())
                                .header(csrf.headerName(), csrf.token())
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(json.writeValueAsString(body)))
                .andReturn();
    }

    private List<Map<String, Object>> inboxRows(TestFixtures.Instructor owner, UUID activity) {
        var listed = fixtures.get(owner, "/api/v1/scenario-approvals");
        assertThat(listed).hasStatus(HttpStatus.OK);
        return JsonPath.read(
                TestFixtures.body(listed), "$.data[?(@.activityId == '" + activity + "')]");
    }

    private Map<String, Object> inboxRow(TestFixtures.Instructor owner, UUID activity) {
        var rows = inboxRows(owner, activity);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private String nextAction(TestFixtures.Instructor owner, UUID childId, UUID activity) {
        var listed = fixtures.get(owner, "/api/v1/children/{id}/activities", childId);
        assertThat(listed).hasStatus(HttpStatus.OK);
        List<String> actions =
                JsonPath.read(
                        TestFixtures.body(listed),
                        "$.data[?(@.activityId == '" + activity + "')].nextAction");
        assertThat(actions).hasSize(1);
        return actions.getFirst();
    }

    /** 이 아동이 다른 역할극 단독을 마쳐 생긴 더 새 결정. 이 활동에 고정한 값과는 다른 결정이다. */
    private UUID newerDecision(
            TestFixtures.Instructor owner, UUID classId, UUID childId, RoleplayReportSeed seed) {
        var other =
                seed.completedRoleplay(
                        owner.userId(), classId, childId, NOW.plusSeconds(3_600), "ROLEPLAY");
        UUID decision = UUID.randomUUID();
        jdbc.update(
                """
                insert into follow_up_difficulty_decision(decision_id,activity_id,child_id,previous_level,next_level,
                    next_support_level,direction,reason,score_numerator,score_denominator,consecutive_upgrade_candidates,
                    policy_version,decided_at)
                values(?,?,?,'L2','L3','S0','RAISE','UPGRADE_CANDIDATE_CONFIRMED',9,10,0,'FOLLOW_UP_ROLEPLAY_V1',?)
                """,
                decision,
                other.activity(),
                childId,
                Timestamp.from(Instant.now().plusSeconds(60)));
        return decision;
    }

    private static Authentication child(UUID childId) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new ChildPrincipal(childId), "", List.of(new SimpleGrantedAuthority("ROLE_CHILD")));
    }
}
