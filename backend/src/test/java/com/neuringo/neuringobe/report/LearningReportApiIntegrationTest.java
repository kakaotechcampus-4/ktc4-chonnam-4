package com.neuringo.neuringobe.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.LocalProfileIntegrationTest;
import com.neuringo.neuringobe.TestFixtures;
import com.neuringo.neuringobe.report.service.ReportPreparationService;
import com.neuringo.neuringobe.roleplay.service.RoleplayActivityCompleted;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 역할극을 마친 기록으로 리포트 준비부터 강사 API 까지 확인한다. 이 테스트 설정에는 LLM 이 없어서 AI 요약은 FAILED(AI_UNAVAILABLE)가 되고,
 * 지표·난이도·추천은 그래도 남는지 본다. COMPLETED 화면은 생성 결과를 DB 에 직접 넣어 확인한다(생성 루프 자체는
 * RetryingLearningReportGeneratorTest).
 */
@LocalProfileIntegrationTest
class LearningReportApiIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-31T05:00:00Z");

    @Autowired private MockMvcTester mvc;
    @Autowired private TestFixtures fixtures;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReportPreparationService preparation;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void completionEventPreparesMetricsDifficultyAndRecommendationsEvenWithoutAi() {
        var owner = fixtures.signUpInstructor();
        var seeded = seed(owner);

        // 역할극 완료 트랜잭션처럼 트랜잭션 안에서 발행하고, 커밋 뒤 리스너가 준비하는지 본다.
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status ->
                                events.publishEvent(
                                        new RoleplayActivityCompleted(
                                                seeded.activity(),
                                                childOf(seeded),
                                                seeded.session())));

        await().atMost(Duration.ofSeconds(10))
                .until(() -> "FAILED".equals(reportStatus(seeded.activity())));
        assertThat(
                        jdbc.queryForObject(
                                "select failure_reason from learning_report where activity_id=?",
                                String.class,
                                seeded.activity()))
                .isEqualTo("AI_UNAVAILABLE");

        // 생성에 실패한 리포트는 없는 리포트와 같은 404 다.
        var report =
                fixtures.get(owner, "/api/v1/activities/{id}/learning-record", seeded.activity());
        assertThat(report).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(report)
                .bodyJson()
                .extractingPath("$.error.code")
                .isEqualTo("LEARNING_RECORD_NOT_FOUND");

        var difficulty =
                fixtures.get(owner, "/api/v1/children/{id}/roleplay-difficulty", childOf(seeded));
        assertThat(difficulty).hasStatusOk();
        String body = TestFixtures.body(difficulty);
        assertThat((String) JsonPath.read(body, "$.data.scenarioLevel")).isEqualTo("L2");
        assertThat((String) JsonPath.read(body, "$.data.initialSupportLevel")).isEqualTo("S1");
        assertThat((String) JsonPath.read(body, "$.data.reason")).isEqualTo("SCORE_IN_KEEP_RANGE");
        assertThat((Integer) JsonPath.read(body, "$.data.scoreNumerator")).isEqualTo(9);
        assertThat((Integer) JsonPath.read(body, "$.data.scoreDenominator")).isEqualTo(18);

        var recommendations =
                fixtures.get(
                        owner, "/api/v1/learning-records/{id}/recommendations", seeded.record());
        assertThat(recommendations).hasStatusOk();
        String list = TestFixtures.body(recommendations);
        assertThat((List<String>) JsonPath.read(list, "$.data[*].type"))
                .containsExactly("REINFORCE", "REPEAT", "APPLY");
        assertThat((List<String>) JsonPath.read(list, "$.data[*].basisGoalTitle"))
                .containsExactly(
                        RoleplayReportSeed.COMFORT,
                        RoleplayReportSeed.CAUSE,
                        RoleplayReportSeed.EMOTION);
        assertThat((String) JsonPath.read(list, "$.data[0].suggestedGoal.parentGoalId"))
                .isEqualTo(seeded.goal().toString());
        assertThat((Object) JsonPath.read(list, "$.data[0].decision")).isNull();
    }

    @Test
    void completedReportShowsServerFactsAndHidesFromOtherInstructors() {
        var owner = fixtures.signUpInstructor();
        var seeded = seed(owner);
        assertThat(preparation.prepare(seeded.activity())).isPresent();
        complete(seeded.activity());

        var report =
                fixtures.get(owner, "/api/v1/activities/{id}/learning-record", seeded.activity());
        assertThat(report).hasStatusOk();
        String body = TestFixtures.body(report);
        assertThat((String) JsonPath.read(body, "$.data.summary")).isEqualTo("감정 이름은 혼자 말했어요.");
        assertThat((String) JsonPath.read(body, "$.data.recordId"))
                .isEqualTo(seeded.record().toString());
        assertThat((Object) JsonPath.read(body, "$.data.quiz")).isNull();
        assertThat((List<String>) JsonPath.read(body, "$.data.roleplay.goals[*].status"))
                .containsExactly("REACHED_AT_START", "EXTRA_SUPPORT", "ANSWER_GIVEN");
        assertThat(
                        (List<String>)
                                JsonPath.read(body, "$.data.roleplay.goals[1].criteria[*].status"))
                .containsExactly("EXPLICIT");
        assertThat((String) JsonPath.read(body, "$.data.difficultyDecision.decisionType"))
                .isEqualTo("FOLLOW_UP");
        assertThat((Integer) JsonPath.read(body, "$.data.metrics.validGoalCount")).isEqualTo(3);
        // 발화 원문은 응답 어디에도 없다.
        assertThat(body).doesNotContain("합성 응답");

        var listed = fixtures.get(owner, "/api/v1/learning-records?childId={id}", childOf(seeded));
        assertThat(listed).hasStatusOk();
        assertThat((List<String>) JsonPath.read(TestFixtures.body(listed), "$.data[*].activityId"))
                .containsExactly(seeded.activity().toString());

        var other = fixtures.signUpInstructor();
        UUID recommendation = firstRecommendation(owner, seeded.record());
        assertHidden(
                fixtures.get(other, "/api/v1/activities/{id}/learning-record", seeded.activity()),
                "LEARNING_RECORD_NOT_FOUND");
        assertHidden(
                fixtures.get(
                        other, "/api/v1/learning-records/{id}/recommendations", seeded.record()),
                "LEARNING_RECORD_NOT_FOUND");
        assertHidden(
                fixtures.get(other, "/api/v1/recommendations/{id}", recommendation),
                "RECOMMENDATION_NOT_FOUND");
        assertHidden(
                patch(other, recommendation, UUID.randomUUID(), "{\"decision\":\"ACCEPT\"}"),
                "RECOMMENDATION_NOT_FOUND");
    }

    @Test
    void classroomListShowsReportsWithLabelsAndHidesOtherInstructorsClassrooms() {
        var owner = fixtures.signUpInstructor();
        UUID classId = fixtures.createClassroom(owner, "학급 리포트");
        UUID childId = fixtures.createChild(owner, classId, "학급 리포트 아동");
        var seeded =
                new RoleplayReportSeed(jdbc)
                        .completedRoleplay(owner.userId(), classId, childId, NOW);
        assertThat(preparation.prepare(seeded.activity())).isPresent();
        complete(seeded.activity());
        String goalTitle =
                jdbc.queryForObject(
                        "select title from learning_goal where goal_id=?",
                        String.class,
                        seeded.goal());
        String activityType =
                jdbc.queryForObject(
                        "select activity_type from activity where activity_id=?",
                        String.class,
                        seeded.activity());

        var listed = fixtures.get(owner, "/api/v1/learning-records?classId={id}", classId);
        assertThat(listed).hasStatusOk();
        String body = TestFixtures.body(listed);
        assertThat((List<String>) JsonPath.read(body, "$.data[*].activityId"))
                .containsExactly(seeded.activity().toString());
        assertThat((List<String>) JsonPath.read(body, "$.data[*].childName"))
                .containsExactly("학급 리포트 아동");
        assertThat((List<String>) JsonPath.read(body, "$.data[*].goalTitle"))
                .containsExactly(goalTitle);
        assertThat((List<String>) JsonPath.read(body, "$.data[*].activityType"))
                .containsExactly(activityType);

        // 아동 목록과 단건도 같은 칸을 싣는다.
        var byChild = fixtures.get(owner, "/api/v1/learning-records?childId={id}", childId);
        assertThat((List<String>) JsonPath.read(TestFixtures.body(byChild), "$.data[*].childName"))
                .containsExactly("학급 리포트 아동");
        var single =
                fixtures.get(owner, "/api/v1/activities/{id}/learning-record", seeded.activity());
        assertThat((String) JsonPath.read(TestFixtures.body(single), "$.data.goalTitle"))
                .isEqualTo(goalTitle);

        UUID emptyClass = fixtures.createClassroom(owner, "빈 학급");
        var empty = fixtures.get(owner, "/api/v1/learning-records?classId={id}", emptyClass);
        assertThat((List<String>) JsonPath.read(TestFixtures.body(empty), "$.data")).isEmpty();

        var other = fixtures.signUpInstructor();
        assertHidden(
                fixtures.get(other, "/api/v1/learning-records?classId={id}", classId),
                "CLASSROOM_NOT_FOUND");

        for (var query :
                List.of(
                        "/api/v1/learning-records",
                        "/api/v1/learning-records?childId=" + childId + "&classId=" + classId)) {
            var invalid = fixtures.get(owner, query);
            assertThat(invalid).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(invalid)
                    .bodyJson()
                    .extractingPath("$.error.code")
                    .isEqualTo("INVALID_REQUEST");
        }
    }

    @Test
    void decisionsAreIdempotentAndFinalOnceAccepted() {
        var owner = fixtures.signUpInstructor();
        var seeded = seed(owner);
        assertThat(preparation.prepare(seeded.activity())).isPresent();
        UUID recommendation = firstRecommendation(owner, seeded.record());
        UUID deferKey = UUID.randomUUID();

        var deferred = patch(owner, recommendation, deferKey, "{\"decision\":\"DEFER\"}");
        assertThat(deferred).hasStatusOk();
        assertThat(deferred).bodyJson().extractingPath("$.data.decision").isEqualTo("DEFER");
        assertThat(patch(owner, recommendation, deferKey, "{\"decision\":\"DEFER\"}"))
                .hasStatusOk();
        var reused = patch(owner, recommendation, deferKey, "{\"decision\":\"ACCEPT\"}");
        assertThat(reused).hasStatus(HttpStatus.CONFLICT);
        assertThat(reused)
                .bodyJson()
                .extractingPath("$.error.code")
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");

        var withoutTitle =
                patch(
                        owner,
                        recommendation,
                        UUID.randomUUID(),
                        "{\"decision\":\"MODIFIED_ACCEPT\"}");
        assertThat(withoutTitle).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);

        var modified =
                patch(
                        owner,
                        recommendation,
                        UUID.randomUUID(),
                        "{\"decision\":\"MODIFIED_ACCEPT\",\"goal\":{\"title\":\"놀이터 장면에서 위로하는 말 하기\"}}");
        assertThat(modified).hasStatusOk();
        assertThat(modified)
                .bodyJson()
                .extractingPath("$.data.decidedGoal.title")
                .isEqualTo("놀이터 장면에서 위로하는 말 하기");
        assertThat(modified).bodyJson().extractingPath("$.data.createdGoalId").isNull();

        var again = patch(owner, recommendation, UUID.randomUUID(), "{\"decision\":\"REJECT\"}");
        assertThat(again).hasStatus(HttpStatus.CONFLICT);
        assertThat(again)
                .bodyJson()
                .extractingPath("$.error.code")
                .isEqualTo("RECOMMENDATION_ALREADY_DECIDED");
        // 채택해도 서버는 활동을 만들지 않는다(자동 배정 금지).
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from activity where child_id=?",
                                Integer.class,
                                childOf(seeded)))
                .isEqualTo(1);
    }

    @Test
    void deletingTheChildRemovesReportsRecommendationsAndDifficulty() {
        var owner = fixtures.signUpInstructor();
        var seeded = seed(owner);
        assertThat(preparation.prepare(seeded.activity())).isPresent();
        UUID recommendation = firstRecommendation(owner, seeded.record());
        assertThat(patch(owner, recommendation, UUID.randomUUID(), "{\"decision\":\"DEFER\"}"))
                .hasStatusOk();

        UUID child = childOf(seeded);
        var csrf = fixtures.fetchCsrf();
        var deleted =
                TestFixtures.bearer(
                                mvc.delete()
                                        .uri("/api/v1/children/{id}", child)
                                        .header(csrf.headerName(), csrf.token())
                                        .cookie(csrf.cookies()),
                                owner.accessToken())
                        .exchange();
        assertThat(deleted).hasStatus(HttpStatus.NO_CONTENT);
        for (String table :
                List.of("learning_report", "recommendation", "follow_up_difficulty_decision")) {
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from " + table + " where child_id=?",
                                    Integer.class,
                                    child))
                    .as(table)
                    .isZero();
        }
    }

    private RoleplayReportSeed.Seeded seed(TestFixtures.Instructor owner) {
        UUID classId = fixtures.createClassroom(owner, "리포트 학급");
        UUID childId = fixtures.createChild(owner, classId, "리포트 아동");
        return new RoleplayReportSeed(jdbc)
                .completedRoleplay(owner.userId(), classId, childId, NOW);
    }

    private UUID childOf(RoleplayReportSeed.Seeded seeded) {
        return jdbc.queryForObject(
                "select child_id from activity where activity_id=?", UUID.class, seeded.activity());
    }

    private String reportStatus(UUID activityId) {
        var statuses =
                jdbc.queryForList(
                        "select status from learning_report where activity_id=?",
                        String.class,
                        activityId);
        return statuses.isEmpty() ? null : statuses.getFirst();
    }

    /** 생성 결과를 직접 넣는다. 생성 작업이 이미 FAILED 로 바꿨어도 덮어쓴다. */
    private void complete(UUID activityId) {
        await().atMost(Duration.ofSeconds(10)).until(() -> reportStatus(activityId) != null);
        jdbc.update(
                """
                update learning_report set status='COMPLETED', failure_reason=null, summary=?, completed_at=now(),
                    items='{"observations":[{"text":"상황 단서를 들은 뒤 이유를 말했어요.","evidenceIds":["E2"]}],"effectiveSupports":[],"difficultPoints":[]}'::jsonb
                where activity_id=?
                """,
                "감정 이름은 혼자 말했어요.",
                activityId);
    }

    private UUID firstRecommendation(TestFixtures.Instructor owner, UUID record) {
        var list = fixtures.get(owner, "/api/v1/learning-records/{id}/recommendations", record);
        assertThat(list).hasStatusOk();
        return UUID.fromString(
                JsonPath.read(TestFixtures.body(list), "$.data[0].recommendationId"));
    }

    private MvcTestResult patch(
            TestFixtures.Instructor as, UUID recommendation, UUID key, String json) {
        var csrf = fixtures.fetchCsrf();
        return TestFixtures.bearer(
                        mvc.patch()
                                .uri("/api/v1/recommendations/{id}", recommendation)
                                .header(csrf.headerName(), csrf.token())
                                .cookie(csrf.cookies())
                                .header("Idempotency-Key", key.toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json),
                        as.accessToken())
                .exchange();
    }

    private static void assertHidden(MvcTestResult result, String code) {
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo(code);
    }
}
