package com.neuringo.neuringobe.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.IntegrationTest;
import com.neuringo.neuringobe.scenario.repository.ScenarioRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 재사용 후보는 최근에 덜 쓴 것부터, 같으면 최근 승인본부터 나온다. 시나리오는 학급에 묶지 않는다. */
@IntegrationTest
class ScenarioReuseCandidatesIntegrationTest {
    private static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired ScenarioRepository scenarios;

    private UUID instructor;
    private UUID child;
    // 테스트마다 다른 목표 내용으로 다른 테스트의 승인본과 섞이지 않게 한다.
    private String goalHash;

    @BeforeEach
    void setUp() {
        instructor = instructor();
        child = child(classroom(instructor));
        byte[] bytes = new byte[32];
        ThreadLocalRandom.current().nextBytes(bytes);
        goalHash = HexFormat.of().formatHex(bytes);
    }

    @Test
    void neverUsedComeFirstThenLeastRecentlyUsedAndTiesPreferLatestApproval() {
        UUID usedLong = approved(day(1));
        UUID neverOld = approved(day(2));
        UUID usedRecently = approved(day(3));
        UUID neverNew = approved(day(4));
        UUID otherChild = child(classroom(instructor));
        useBy(otherChild, usedLong, day(10));
        useBy(otherChild, usedRecently, day(20));
        // 여러 번 쓰였으면 가장 늦게 쓰인 때로 비교한다.
        useBy(child(classroom(instructor)), usedLong, day(5));

        assertThat(scenarios.findReuseCandidates(goalHash, "L2", child))
                .containsExactly(neverNew, neverOld, usedLong, usedRecently);
    }

    @Test
    void excludesScenariosThisChildAlreadyHasCompletedOrInProgress() {
        UUID completed = approved(day(1));
        UUID inProgress = approved(day(2));
        UUID free = approved(day(3));
        jdbc.update(
                "update activity set status = 'COMPLETED' where activity_id = ?",
                useBy(child, completed, day(5)));
        useBy(child, inProgress, day(6));

        assertThat(scenarios.findReuseCandidates(goalHash, "L2", child)).containsExactly(free);
    }

    @Test
    void onlyApprovedWithSameGoalAndLevelAreCandidatesRegardlessOfClass() {
        UUID approved = approved(day(1));
        for (String status : new String[] {"DRAFT", "REVISION_REQUIRED", "REJECTED"}) {
            UUID judged = scenario(goalHash, "L2", day(2));
            if (!status.equals("DRAFT")) {
                jdbc.update("update scenario set status = ? where scenario_id = ?", status, judged);
            }
        }
        scenario(goalHash, "L1", day(3));
        approve(scenario(goalHash, "L1", day(3)), day(3));
        approve(scenario("f".repeat(64), "L2", day(4)), day(4));
        // 다른 반 아동이 쓴 승인본도 후보다. 학급으로 거르지 않는다.
        UUID otherClassUsed = approved(day(5));
        useBy(child(classroom(instructor())), otherClassUsed, day(6));

        assertThat(scenarios.findReuseCandidates(goalHash, "L2", child))
                .containsExactly(approved, otherClassUsed);
    }

    private UUID approved(Instant approvedAt) {
        UUID id = scenario(goalHash, "L2", approvedAt);
        approve(id, approvedAt);
        return id;
    }

    private void approve(UUID scenarioId, Instant approvedAt) {
        jdbc.update(
                "update scenario set status = 'APPROVED', approved_at = ? where scenario_id = ?",
                Timestamp.from(approvedAt),
                scenarioId);
    }

    private UUID scenario(String hash, String level, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into scenario(scenario_id, status, scenario_level, goal_content_hash, title,
                    scenario_facts, origin, created_at)
                values(?, 'DRAFT', ?, ?, 'synthetic', '["fact"]'::jsonb, 'PREPARED', ?)
                """,
                id,
                level,
                hash,
                Timestamp.from(createdAt));
        return id;
    }

    /** 그 아동에게 시나리오가 붙은 활동을 만든다. 후보 순서는 배정 시각으로 정한다. */
    private UUID useBy(UUID owner, UUID scenarioId, Instant assignedAt) {
        UUID activity = UUID.randomUUID();
        jdbc.update(
                """
                insert into activity(activity_id, child_id, activity_type, scenario_id, status, assigned_at)
                values(?, ?, 'SELF_EMOTION_QUIZ', ?, 'IN_PROGRESS', ?)
                """,
                activity,
                owner,
                scenarioId,
                Timestamp.from(assignedAt));
        return activity;
    }

    private UUID instructor() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into user_account(user_id,email,password_hash,name,role,status,created_at)
                values(?,?,'test-hash','synthetic','INSTRUCTOR','ACTIVE',now())
                """,
                id,
                id + "@example.com");
        return id;
    }

    private UUID classroom(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into classroom(class_id,instructor_id,name,status) values(?,?,'synthetic','ACTIVE')",
                id,
                owner);
        return id;
    }

    private UUID child(UUID classroom) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into child(child_id,class_id,display_name,status) values(?,?,'synthetic','ACTIVE')",
                id,
                classroom);
        return id;
    }

    private static Instant day(int n) {
        return BASE.plus(n, ChronoUnit.DAYS);
    }
}
