package com.neuringo.neuringobe.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.IntegrationTest;
import com.neuringo.neuringobe.scenario.domain.ScenarioApprovalConditions;
import com.neuringo.neuringobe.scenario.dto.ScenarioApprovalRecord;
import com.neuringo.neuringobe.scenario.service.ScenarioApprovalRegistry;
import com.neuringo.neuringobe.scenario.support.ScenarioPoolTestIsolation;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@org.junit.jupiter.api.extension.ExtendWith(ScenarioPoolTestIsolation.class)
class ScenarioApprovalIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ScenarioApprovalRegistry approvals;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void recordsConfirmedConditionsAndScopesReadsToApprovingInstructor() {
        UUID instructor = instructor();
        UUID other = instructor();
        UUID scenario = scenario("APPROVED");
        var conditions =
                new ScenarioApprovalConditions(
                        "  차례를\u2003 기다려요\n",
                        "L2",
                        " 일상 ",
                        null,
                        List.of(" 친구 ", "교사", "친구"),
                        List.of(" 기다리기 ", "기다리기"));

        var saved = approve(scenario, instructor, conditions);

        assertThat(saved.conditions().goalTitle()).isEqualTo("차례를 기다려요");
        assertThat(saved.conditions().characterRoles()).containsExactly("교사", "친구", "친구");
        assertThat(saved.conditions().requiredActions()).containsExactly("기다리기");
        assertThat(saved.verificationRuleVersion()).isEqualTo(1);
        assertThat(approvals.findOwnedApproved(scenario, instructor)).contains(saved);
        assertThat(approvals.findOwnedApproved(scenario, other)).isEmpty();
    }

    @Test
    void distinguishesUnknownConditionsFromConfirmedEmptyLists() {
        UUID instructor = instructor();
        var unknown = approve(scenario("APPROVED"), instructor, conditions(null, null));
        var empty = approve(scenario("APPROVED"), instructor, conditions(List.of(), List.of()));

        assertThat(unknown.conditions().characterRoles()).isNull();
        assertThat(unknown.conditions().requiredActions()).isNull();
        assertThat(empty.conditions().characterRoles()).isEmpty();
        assertThat(empty.conditions().requiredActions()).isEmpty();
    }

    @Test
    void repeatApprovalPreservesOriginalTimestampAndRejectsChangedOwnerOrConditions() {
        UUID scenario = scenario("APPROVED");
        UUID instructor = instructor();
        var original = approve(scenario, instructor, conditions(null, null));

        assertThat(approve(scenario, instructor, conditions(null, null))).isEqualTo(original);
        assertThatThrownBy(() -> approve(scenario, instructor(), conditions(null, null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approve(scenario, instructor, conditions(List.of("친구"), null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(approvals.findOwnedApproved(scenario, instructor)).contains(original);
        assertThat(count(scenario)).isEqualTo(1);
    }

    @Test
    void draftRetiredOrWrongLevelCannotBeRecordedAndLegacyApprovedHasNoInferredOwner() {
        UUID instructor = instructor();
        for (String status : List.of("DRAFT", "RETIRED")) {
            UUID scenario = scenario(status);
            assertThatThrownBy(() -> approve(scenario, instructor, conditions(null, null)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(count(scenario)).isZero();
        }
        UUID legacy = scenario("APPROVED");
        assertThat(approvals.findOwnedApproved(legacy, instructor)).isEmpty();
        var wrongLevel = new ScenarioApprovalConditions("차례를 기다려요", "L1", null, null, null, null);
        assertThatThrownBy(() -> approve(legacy, instructor, wrongLevel))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count(legacy)).isZero();
    }

    @Test
    void retiredScenarioKeepsApprovalButIsNoLongerEligible() {
        UUID instructor = instructor();
        UUID scenario = scenario("APPROVED");
        approve(scenario, instructor, conditions(null, null));

        jdbc.update("UPDATE scenario SET status = 'RETIRED' WHERE scenario_id = ?", scenario);

        assertThat(approvals.findOwnedApproved(scenario, instructor)).isEmpty();
        assertThat(count(scenario)).isEqualTo(1);
    }

    @Test
    void approvalParticipatesInFinalAssignmentTransactionAndCannotCommitAlone() {
        UUID instructor = instructor();
        UUID scenario = scenario("DRAFT");
        assertThatThrownBy(
                        () ->
                                approvals.recordApproval(
                                        scenario, instructor, conditions(null, null)))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .executeWithoutResult(
                                                ignored -> {
                                                    jdbc.update(
                                                            "UPDATE scenario SET status = 'APPROVED' WHERE scenario_id = ?",
                                                            scenario);
                                                    approvals.recordApproval(
                                                            scenario,
                                                            instructor,
                                                            conditions(null, null));
                                                    throw new IllegalStateException("배정 실패를 가정한다.");
                                                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count(scenario)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM scenario WHERE scenario_id = ?",
                                String.class,
                                scenario))
                .isEqualTo("DRAFT");
    }

    @Test
    void databaseRejectsApprovalRewriteAndInvalidScenarioEvenWithoutRegistry() {
        UUID instructor = instructor();
        UUID scenario = scenario("APPROVED");
        var saved = approve(scenario, instructor, conditions(null, null));
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE scenario_approval SET goal_title = '다른 목표' WHERE scenario_id = ?",
                                        scenario))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                        INSERT INTO scenario_approval
                            (scenario_id, instructor_id, goal_title, scenario_level, verification_rule_version, approved_at)
                        VALUES (?, ?, '차례를 기다려요', 'L2', 1, now())
                        """,
                                        scenario("DRAFT"),
                                        instructor))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(approvals.findOwnedApproved(scenario, instructor)).contains(saved);
    }

    @Test
    void simultaneousRepeatApprovalsCreateOneRecord() throws Exception {
        UUID scenario = scenario("APPROVED");
        UUID instructor = instructor();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var calls =
                    List.of(1, 2).stream()
                            .map(
                                    ignored ->
                                            pool.submit(
                                                    () -> {
                                                        ready.countDown();
                                                        if (!start.await(10, TimeUnit.SECONDS)) {
                                                            throw new IllegalStateException(
                                                                    "동시 요청 시작 대기 시간 초과");
                                                        }
                                                        return approve(
                                                                scenario,
                                                                instructor,
                                                                conditions(null, null));
                                                    }))
                            .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var first = calls.get(0).get(10, TimeUnit.SECONDS);
            assertThat(calls.get(1).get(10, TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(count(scenario)).isEqualTo(1);
    }

    @Test
    void deletingInstructorAnonymizesApprovalWithoutDeletingScenario() {
        UUID scenario = scenario("APPROVED");
        UUID instructor = instructor();
        approve(scenario, instructor, conditions(null, null));
        jdbc.update("DELETE FROM user_account WHERE user_id = ?", instructor);
        assertThat(count(scenario)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT instructor_id FROM scenario_approval WHERE scenario_id = ?",
                                UUID.class,
                                scenario))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario WHERE scenario_id = ?",
                                Integer.class,
                                scenario))
                .isEqualTo(1);
    }

    private ScenarioApprovalRecord approve(
            UUID scenario, UUID instructor, ScenarioApprovalConditions conditions) {
        return new TransactionTemplate(transactions)
                .execute(ignored -> approvals.recordApproval(scenario, instructor, conditions));
    }

    private ScenarioApprovalConditions conditions(List<String> roles, List<String> actions) {
        return new ScenarioApprovalConditions("차례를 기다려요", "L2", null, null, roles, actions);
    }

    private int count(UUID scenario) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM scenario_approval WHERE scenario_id = ?",
                Integer.class,
                scenario);
    }

    private UUID instructor() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO user_account (user_id, email, password_hash, name, role, status, created_at)
                VALUES (?, ?, 'test-hash', '승인 검사 강사', 'INSTRUCTOR', 'ACTIVE', now())
                """,
                id,
                id + "@example.com");
        return id;
    }

    private UUID scenario(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO scenario (scenario_id, status, scenario_level, title, scenario_facts, origin, created_at)
                VALUES (?, ?, 'L2', '차례를 기다리는 상황', '[]', 'AI_GENERATED', now())
                """,
                id,
                status);
        return id;
    }
}
