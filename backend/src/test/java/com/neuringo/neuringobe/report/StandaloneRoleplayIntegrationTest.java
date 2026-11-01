package com.neuringo.neuringobe.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.LocalProfileIntegrationTest;
import com.neuringo.neuringobe.TestFixtures;
import com.neuringo.neuringobe.child.security.ChildPrincipal;
import com.neuringo.neuringobe.report.service.ReportPreparationService;
import com.neuringo.neuringobe.roleplay.service.RoleplayStartService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * 역할극 단독 활동(ROLEPLAY)을 다음 역할극 난이도로 배정하고 시작한다. 최초 결합 역할극을 마친 기록에서 리포트를 준비하면 다음 난이도(L2·S1 유지)가 생기고, 그
 * 값이 새 활동에 고정되어 시작 지원·승인 시나리오 선택에 쓰이는지 본다.
 */
@LocalProfileIntegrationTest
class StandaloneRoleplayIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-31T05:00:00Z");

    @Autowired private TestFixtures fixtures;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReportPreparationService preparation;
    @Autowired private RoleplayStartService starts;

    @Test
    void assignsWithThePinnedNextDifficultyAndStartsOnAMatchingApprovedScenario() {
        var owner = fixtures.signUpInstructor();
        UUID classId = fixtures.createClassroom(owner, "역할극 단독 학급");
        UUID childId = fixtures.createChild(owner, classId, "역할극 단독 아동");
        var seed = new RoleplayReportSeed(jdbc);
        var first = seed.completedRoleplay(owner.userId(), classId, childId, NOW, "FIRST_COMBINED");
        assertThat(preparation.prepare(first.activity())).isPresent();

        var assigned =
                fixtures.postJsonWithKey(
                        owner,
                        "/api/v1/activities",
                        Map.of(
                                "childId",
                                childId,
                                "activityType",
                                "ROLEPLAY",
                                "goal",
                                Map.of(
                                        "title",
                                        "넘어진 친구의 속상한 마음 알아보기",
                                        "parentGoalId",
                                        first.goal())),
                        UUID.randomUUID());
        assertThat(assigned).hasStatus(HttpStatus.CREATED);
        String body = TestFixtures.body(assigned);
        UUID activity = UUID.fromString(JsonPath.read(body, "$.data.activityId"));
        assertThat((String) JsonPath.read(body, "$.data.initialSupportLevel")).isEqualTo("S1");
        assertThat((Object) JsonPath.read(body, "$.data.scenarioId")).isNull();
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

        // 시나리오는 시작할 때 붙인다. 새 목표의 내용 해시·고정 레벨에 맞는 승인본을 넣는다(L3 는 맞지 않아 고르지 않는다).
        String hash =
                jdbc.queryForObject(
                        "select g.content_hash from activity a join learning_goal g on g.goal_id=a.goal_id where a.activity_id=?",
                        String.class,
                        activity);
        seed.approvedScenario(classId, hash, "L3");
        UUID matching = seed.approvedScenario(classId, hash, "L2");

        var child =
                UsernamePasswordAuthenticationToken.authenticated(
                        new ChildPrincipal(childId),
                        "",
                        List.of(new SimpleGrantedAuthority("ROLE_CHILD")));
        var started = starts.startForDelivery(activity, UUID.randomUUID(), child);

        assertThat(started.created()).isTrue();
        assertThat(started.response().scenarioId()).isEqualTo(matching);
        assertThat(
                        jdbc.queryForMap(
                                "select current_support_level, scenario_id from roleplay_session where activity_id=?",
                                activity))
                .containsEntry("current_support_level", "S1")
                .containsEntry("scenario_id", matching);
        assertThat(
                        jdbc.queryForObject(
                                "select scenario_id from activity where activity_id=?",
                                UUID.class,
                                activity))
                .isEqualTo(matching);
        assertThat(started.response().delivery().messages().getFirst().text())
                .isEqualTo("친구가 무릎을 잡고 있어. 어떤 마음일까?");
    }

    @Test
    void withoutAMatchingApprovedScenarioTheStartIsNotReady() {
        var owner = fixtures.signUpInstructor();
        UUID classId = fixtures.createClassroom(owner, "시나리오 없는 학급");
        UUID childId = fixtures.createChild(owner, classId, "시나리오 없는 아동");
        var first =
                new RoleplayReportSeed(jdbc)
                        .completedRoleplay(owner.userId(), classId, childId, NOW, "FIRST_COMBINED");
        assertThat(preparation.prepare(first.activity())).isPresent();
        var assigned =
                fixtures.postJsonWithKey(
                        owner,
                        "/api/v1/activities",
                        Map.of(
                                "childId",
                                childId,
                                "activityType",
                                "ROLEPLAY",
                                "goal",
                                Map.of("title", "처음 보는 목표")),
                        UUID.randomUUID());
        assertThat(assigned).hasStatus(HttpStatus.CREATED);
        UUID activity =
                UUID.fromString(JsonPath.read(TestFixtures.body(assigned), "$.data.activityId"));
        var child =
                UsernamePasswordAuthenticationToken.authenticated(
                        new ChildPrincipal(childId),
                        "",
                        List.of(new SimpleGrantedAuthority("ROLE_CHILD")));

        try {
            starts.startForDelivery(activity, UUID.randomUUID(), child);
            org.junit.jupiter.api.Assertions.fail("승인 시나리오 없이 시작되면 안 된다");
        } catch (com.neuringo.neuringobe.common.ApiException expected) {
            assertThat(expected.getCode()).isEqualTo("INITIAL_ROLEPLAY_NOT_READY");
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_session where activity_id=?",
                                Integer.class,
                                activity))
                .isZero();
    }
}
