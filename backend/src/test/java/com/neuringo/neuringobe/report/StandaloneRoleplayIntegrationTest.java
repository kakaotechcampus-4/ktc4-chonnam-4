package com.neuringo.neuringobe.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.LocalProfileIntegrationTest;
import com.neuringo.neuringobe.TestFixtures;
import com.neuringo.neuringobe.child.security.ChildPrincipal;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.report.service.ReportPreparationService;
import com.neuringo.neuringobe.roleplay.service.RoleplayStartService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 역할극 단독 활동(ROLEPLAY). 최초 결합 역할극을 마친 기록에서 리포트를 준비하면 다음 난이도(L2·S1 유지)가 생긴다. 강사는 그 레벨·목표에 맞는 승인 시나리오
 * 후보를 보고 하나를 골라 배정하고, 아동은 그 시나리오로 시작한다. 서버가 시작할 때 시나리오를 대신 고르지 않는다.
 */
@LocalProfileIntegrationTest
class StandaloneRoleplayIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-31T05:00:00Z");
    private static final long DAY = 86_400;

    // 후보는 학급을 보지 않고 목표 내용으로만 고르므로, 테스트끼리 후보가 섞이지 않게 테스트마다 목표 제목을 다르게 한다.
    private final String goalTitle = "넘어진 친구의 속상한 마음 알아보기 " + UUID.randomUUID();

    @Autowired private TestFixtures fixtures;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReportPreparationService preparation;
    @Autowired private RoleplayStartService starts;

    @Test
    void instructorSeesCandidatesInReuseOrderAndTheChildStartsOnTheChosenScenario() {
        var owner = fixtures.signUpInstructor();
        UUID classId = fixtures.createClassroom(owner, "역할극 단독 학급");
        UUID otherClassId = fixtures.createClassroom(owner, "다른 학급");
        UUID childId = fixtures.createChild(owner, classId, "역할극 단독 아동");
        UUID otherChild = fixtures.createChild(owner, otherClassId, "다른 아동");
        var seed = new RoleplayReportSeed(jdbc);
        var first = seed.completedRoleplay(owner.userId(), classId, childId, NOW, "FIRST_COMBINED");
        assertThat(preparation.prepare(first.activity())).isPresent();
        String hash = goalHash(owner, childId);

        // 레벨이 다른 승인본, 다른 아동이 쓴 승인본, 안 쓴 승인본 둘(승인 시각이 다르고 하나는 다른 학급).
        seed.approvedScenario(classId, hash, "L3");
        UUID usedByOther = seed.approvedScenario(classId, hash, "L2", NOW.minusSeconds(DAY * 9));
        useBy(otherChild, usedByOther, NOW.minusSeconds(DAY * 2));
        UUID olderApproved = seed.approvedScenario(classId, hash, "L2", NOW.minusSeconds(DAY * 5));
        UUID newerOtherClass =
                seed.approvedScenario(otherClassId, hash, "L2", NOW.minusSeconds(DAY));

        String list = TestFixtures.body(candidates(owner, childId));
        assertThat((String) JsonPath.read(list, "$.data.scenarioLevel")).isEqualTo("L2");
        assertThat((String) JsonPath.read(list, "$.data.initialSupportLevel")).isEqualTo("S1");
        // 안 쓴 것이 앞(그 안에서는 최근 승인본이 앞), 다른 아동이 쓴 것이 뒤. 학급은 보지 않고 L3 는 빠진다.
        assertThat(JsonPath.<List<String>>read(list, "$.data.items[*].scenarioId"))
                .containsExactly(
                        newerOtherClass.toString(),
                        olderApproved.toString(),
                        usedByOther.toString());
        assertThat(JsonPath.<List<Boolean>>read(list, "$.data.items[*].reuseApprovalRequired"))
                .containsExactly(false, false, true);
        assertThat(JsonPath.<List<String>>read(list, "$.data.items[0].microGoals"))
                .containsExactly("감정 이름 말하기", "감정의 이유 말하기");
        assertThat((Object) JsonPath.read(list, "$.data.items[0].lastUsedAt")).isNull();
        assertThat((Integer) JsonPath.read(list, "$.data.items[2].usedByOtherChildren"))
                .isEqualTo(1);

        var assigned = assign(owner, childId, newerOtherClass, null);
        assertThat(assigned).hasStatus(HttpStatus.CREATED);
        String body = TestFixtures.body(assigned);
        UUID activity = UUID.fromString(JsonPath.read(body, "$.data.activityId"));
        assertThat((String) JsonPath.read(body, "$.data.initialSupportLevel")).isEqualTo("S1");
        assertThat((String) JsonPath.read(body, "$.data.scenarioId"))
                .isEqualTo(newerOtherClass.toString());
        assertThat((String) JsonPath.read(body, "$.data.scenarioSource"))
                .isEqualTo("INSTRUCTOR_SELECTED");
        assertThat(
                        jdbc.queryForMap(
                                "select scenario_level, initial_support_level from roleplay_activity_difficulty where activity_id=?",
                                activity))
                .containsEntry("scenario_level", "L2")
                .containsEntry("initial_support_level", "S1");
        // 퀴즈 문항은 붙이지 않는다.
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from activity_quiz where activity_id=?",
                                Integer.class,
                                activity))
                .isZero();

        // 이 아동이 쓴 승인본은 다음 후보에서 빠진다.
        String after = TestFixtures.body(candidates(owner, childId));
        assertThat(JsonPath.<List<String>>read(after, "$.data.items[*].scenarioId"))
                .containsExactly(olderApproved.toString(), usedByOther.toString());

        var started = starts.startForDelivery(activity, UUID.randomUUID(), child(childId));
        assertThat(started.created()).isTrue();
        assertThat(started.response().scenarioId()).isEqualTo(newerOtherClass);
        assertThat(
                        jdbc.queryForMap(
                                "select current_support_level, scenario_id from roleplay_session where activity_id=?",
                                activity))
                .containsEntry("current_support_level", "S1")
                .containsEntry("scenario_id", newerOtherClass);
        assertThat(started.response().delivery().messages().getFirst().text())
                .isEqualTo("친구가 무릎을 잡고 있어. 어떤 마음일까?");
    }

    @Test
    void assignmentRejectsScenariosTheInstructorCannotUseHere() {
        var owner = fixtures.signUpInstructor();
        UUID classId = fixtures.createClassroom(owner, "배정 검사 학급");
        UUID childId = fixtures.createChild(owner, classId, "배정 검사 아동");
        UUID otherChild = fixtures.createChild(owner, classId, "먼저 쓴 아동");
        var seed = new RoleplayReportSeed(jdbc);
        var first = seed.completedRoleplay(owner.userId(), classId, childId, NOW, "FIRST_COMBINED");
        assertThat(preparation.prepare(first.activity())).isPresent();
        String hash = goalHash(owner, childId);
        UUID wrongLevel = seed.approvedScenario(classId, hash, "L3");
        UUID otherGoal = seed.approvedScenario(classId, "b".repeat(64), "L2");
        UUID draft = draftScenario(classId, hash);
        UUID usedByOther = seed.approvedScenario(classId, hash, "L2");
        useBy(otherChild, usedByOther, NOW.minusSeconds(3_600));

        assertError(assign(owner, childId, null, null), "SCENARIO_REQUIRED");
        assertError(assign(owner, childId, UUID.randomUUID(), null), "UNAPPROVED_SCENARIO");
        assertError(assign(owner, childId, draft, null), "UNAPPROVED_SCENARIO");
        assertError(assign(owner, childId, wrongLevel, null), "SCENARIO_NOT_MATCHED");
        assertError(assign(owner, childId, otherGoal, null), "SCENARIO_NOT_MATCHED");
        assertError(assign(owner, childId, usedByOther, null), "SCENARIO_REUSE_NOT_APPROVED");
        assertError(assign(owner, childId, usedByOther, false), "SCENARIO_REUSE_NOT_APPROVED");
        // 이 아동이 이미 쓴 승인본은 재사용 승인이 있어도 다시 고를 수 없다.
        UUID usedBySelf = seed.approvedScenario(classId, hash, "L2");
        useBy(childId, usedBySelf, NOW.minusSeconds(7_200));
        var again = assign(owner, childId, usedBySelf, true);
        assertThat(again).hasStatus(HttpStatus.CONFLICT);
        assertError(again, "SCENARIO_ALREADY_USED");
        // 다른 유형은 시나리오를 받지 않는다.
        var quiz = new HashMap<String, Object>();
        quiz.put("childId", childId);
        quiz.put("activityType", "SELF_EMOTION_QUIZ");
        quiz.put("scenarioId", usedByOther);
        assertError(
                fixtures.postJsonWithKey(owner, "/api/v1/activities", quiz, UUID.randomUUID()),
                "SCENARIO_NOT_ALLOWED");
        // 거절된 배정 요청은 활동을 남기지 않는다(요청 키가 있는 것이 API 로 배정한 활동이다).
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from activity where child_id=? and activity_type='ROLEPLAY' and idempotency_key is not null",
                                Integer.class,
                                childId))
                .isZero();

        // 강사가 재사용을 승인하면 다른 아동이 쓴 승인본도 배정한다.
        assertThat(assign(owner, childId, usedByOther, true)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void aScenarioRetiredAfterAssignmentDoesNotStart() {
        var owner = fixtures.signUpInstructor();
        UUID classId = fixtures.createClassroom(owner, "폐기 학급");
        UUID childId = fixtures.createChild(owner, classId, "폐기 아동");
        var seed = new RoleplayReportSeed(jdbc);
        var first = seed.completedRoleplay(owner.userId(), classId, childId, NOW, "FIRST_COMBINED");
        assertThat(preparation.prepare(first.activity())).isPresent();
        UUID chosen = seed.approvedScenario(classId, goalHash(owner, childId), "L2");
        var assigned = assign(owner, childId, chosen, null);
        assertThat(assigned).hasStatus(HttpStatus.CREATED);
        UUID activity =
                UUID.fromString(JsonPath.read(TestFixtures.body(assigned), "$.data.activityId"));
        jdbc.update("update scenario set status='RETIRED' where scenario_id=?", chosen);

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

    private MvcTestResult candidates(TestFixtures.Instructor owner, UUID childId) {
        var listed =
                fixtures.get(
                        owner,
                        "/api/v1/children/{id}/roleplay-scenarios?goalTitle={title}",
                        childId,
                        goalTitle);
        assertThat(listed).hasStatus(HttpStatus.OK);
        return listed;
    }

    /** 배정 화면이 보낼 목표와 같은 내용의 해시. 목표 API 로 같은 제목을 저장해 서버가 계산한 값을 읽는다. */
    private String goalHash(TestFixtures.Instructor owner, UUID childId) {
        var created =
                fixtures.postJson(
                        owner,
                        "/api/v1/children/" + childId + "/learning-goals",
                        Map.of("title", goalTitle));
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(TestFixtures.body(created), "$.data.contentHash");
    }

    private MvcTestResult assign(
            TestFixtures.Instructor owner, UUID childId, UUID scenarioId, Boolean reuseApproved) {
        var body = new HashMap<String, Object>();
        body.put("childId", childId);
        body.put("activityType", "ROLEPLAY");
        body.put("goal", Map.of("title", goalTitle));
        if (scenarioId != null) {
            body.put("scenarioId", scenarioId);
        }
        if (reuseApproved != null) {
            body.put("reuseApproved", reuseApproved);
        }
        return fixtures.postJsonWithKey(owner, "/api/v1/activities", body, UUID.randomUUID());
    }

    private static void assertError(MvcTestResult result, String code) {
        assertThat(JsonPath.<String>read(TestFixtures.body(result), "$.error.code"))
                .isEqualTo(code);
    }

    /** 다른 아동이 이 시나리오로 활동을 한 기록. 후보 정렬의 "마지막으로 쓰인 시각"이 된다. */
    private void useBy(UUID childId, UUID scenarioId, Instant assignedAt) {
        // 마친 역할극 단독 활동으로 넣는다. 역할극은 목표가 있어야 한다(ck_activity_goal_by_type).
        UUID goal = UUID.randomUUID();
        jdbc.update(
                "insert into learning_goal(goal_id,child_id,instructor_id,title,content_hash,created_at) select ?,c.child_id,cl.instructor_id::uuid,'쓴 기록용 목표','c',now() from child c join classroom cl on cl.class_id=c.class_id where c.child_id=?",
                goal,
                childId);
        jdbc.update(
                """
                insert into activity(activity_id,child_id,goal_id,scenario_id,activity_type,status,assigned_at)
                values(?,?,?,?,'ROLEPLAY','COMPLETED',?)
                """,
                UUID.randomUUID(),
                childId,
                goal,
                scenarioId,
                Timestamp.from(assignedAt));
    }

    private UUID draftScenario(UUID classId, String hash) {
        UUID scenario = UUID.randomUUID();
        jdbc.update(
                """
                insert into scenario(scenario_id,status,scenario_level,goal_content_hash,title,scenario_facts,origin,created_at,class_id,scenario_version)
                values(?,'DRAFT','L2',?,'검토 중 시나리오','["친구가 넘어졌다"]'::jsonb,'PREPARED',now(),?,1)
                """,
                scenario,
                hash,
                classId);
        return scenario;
    }

    private static Authentication child(UUID childId) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new ChildPrincipal(childId), "", List.of(new SimpleGrantedAuthority("ROLE_CHILD")));
    }
}
