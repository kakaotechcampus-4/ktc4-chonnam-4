package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointCommand;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointStore;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import com.neuringo.neuringobe.deletion.service.DeletionService;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationMapper;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan.Scope;
import com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans;
import com.neuringo.neuringobe.roleplay.service.JpaRoleplayCheckpointStore;
import com.neuringo.neuringobe.roleplay.service.RoleplayCompletionService;
import com.neuringo.neuringobe.roleplay.service.RoleplayGoalObservationStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 목표 판정은 테스트 입력으로 주고, 완료·보상·기록·삭제에는 실제 서비스와 테이블을 사용한다. */
@IntegrationTest
@TestPropertySource(properties = "roleplay.retention.cleanup-enabled=false")
class RoleplayCompletionIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    @MockitoBean Clock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired JpaRoleplayCheckpointStore checkpoints;
    @Autowired RoleplayCompletionService completion;
    @Autowired RoleplayGoalObservationStore observations;
    @Autowired DeletionService deletion;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired PasswordEncoder passwords;

    @BeforeEach
    void fixedClock() {
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void achievedActivityGoalCommitsTheFinalTurnRewardRecordAndDeletion() {
        var f = fixture(ActivityType.ROLEPLAY);
        var command = command(f, UUID.randomUUID(), "a".repeat(64), 1);
        var result = complete(command, true, RoleplayTurnDeadline.start());

        assertThat(result).isInstanceOf(RoleplayCheckpointStore.Committed.class);
        assertCompleted(f);
        assertThat(
                        jdbc.queryForObject(
                                "select last_turn_id from roleplay_session where session_id=?",
                                UUID.class,
                                f.session))
                .isEqualTo(command.turnId());
    }

    @Test
    void closingCandidateWithoutActivityGoalAchievementDoesNotCompleteOrDelete() {
        var f = fixture(ActivityType.ROLEPLAY);
        assertThat(
                        complete(
                                command(f, UUID.randomUUID(), "a".repeat(64), 1),
                                false,
                                RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayCheckpointStore.Committed.class);
        assertThat(turns(f)).isEqualTo(2);
        assertThat(canonicalTurns(f)).isEqualTo(2);
        assertThat(records(f)).isZero();
        assertThat(activity(f)).containsEntry("status", "IN_PROGRESS");
        assertThat(activity(f).get("reward_issued_at")).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstCombinedCompletesOnlyAfterItsQuizIsFinished(boolean quizFinished) {
        var f = fixture(ActivityType.FIRST_COMBINED);
        if (quizFinished) {
            jdbc.update(
                    "insert into quiz_result(activity_id,valid_question_count,correct_question_count,total_hint_count,resolved_after_hint_count,difficulty_fallback_applied,initial_difficulty_used,policy_version,snapshotted_at) values(?,3,2,0,0,false,true,'synthetic-policy',?)",
                    f.activity,
                    Timestamp.from(NOW));
        }
        var result =
                complete(
                        command(f, UUID.randomUUID(), "a".repeat(64), 1),
                        true,
                        RoleplayTurnDeadline.start());
        if (quizFinished) {
            assertThat(result).isInstanceOf(RoleplayCheckpointStore.Committed.class);
            assertCompleted(f);
        } else {
            assertThat(result).isInstanceOf(RoleplayCheckpointStore.Conflict.class);
            assertUnchanged(f);
        }
    }

    @Test
    void replayAndInputConflictKeepTheOriginalCompletionAndRecord() {
        var f = fixture(ActivityType.ROLEPLAY);
        UUID key = UUID.randomUUID();
        var first =
                (RoleplayCheckpointStore.Committed)
                        complete(
                                command(f, key, "a".repeat(64), 1),
                                true,
                                RoleplayTurnDeadline.start());
        var snapshot = completionSnapshot(f);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        var replay =
                (RoleplayCheckpointStore.Committed)
                        complete(
                                command(f, key, "a".repeat(64), 1),
                                false,
                                RoleplayTurnDeadline.start());
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.turnId()).isEqualTo(first.turnId());
        assertThat(replay.candidateId()).isEqualTo(first.candidateId());
        assertThat(replay.version()).isEqualTo(first.version());
        assertThat(complete(command(f, key, "b".repeat(64), 1), true, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayCheckpointStore.Conflict.class);
        assertThat(completionSnapshot(f)).isEqualTo(snapshot);
        assertCompleted(f);
    }

    @Test
    void recordFailureRollsBackTheFinalTurnAndRetryCreatesOneRecord() {
        var f = fixture(ActivityType.ROLEPLAY);
        var command = command(f, UUID.randomUUID(), "a".repeat(64), 1);
        String constraint =
                "test_completion_record_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                "alter table learning_record add constraint "
                        + constraint
                        + " check (activity_id <> '"
                        + f.activity
                        + "'::uuid)");
        try {
            assertThatThrownBy(() -> complete(command, true, RoleplayTurnDeadline.start()))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertUnchanged(f);
        } finally {
            jdbc.execute("alter table learning_record drop constraint " + constraint);
        }
        complete(command, true, RoleplayTurnDeadline.start());
        assertCompleted(f);
    }

    @Test
    void deletionFailureAlsoRollsBackTheActualActivityAndLearningRecord() {
        var f = fixture(ActivityType.ROLEPLAY);
        var command = command(f, UUID.randomUUID(), "a".repeat(64), 1);
        String constraint =
                "test_completion_delete_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                "alter table roleplay_turn add constraint "
                        + constraint
                        + " check (session_id <> '"
                        + f.session
                        + "'::uuid or canonical_utterance is not null)");
        try {
            assertThatThrownBy(() -> complete(command, true, RoleplayTurnDeadline.start()))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertUnchanged(f);
        } finally {
            jdbc.execute("alter table roleplay_turn drop constraint " + constraint);
        }
        complete(command, true, RoleplayTurnDeadline.start());
        assertCompleted(f);
    }

    @Test
    void cancellationDuringCompletionRollsBackAllWrites() {
        var f = fixture(ActivityType.ROLEPLAY);
        var deadline = RoleplayTurnDeadline.start();
        var calls = new AtomicInteger();
        when(clock.instant())
                .thenAnswer(
                        ignored -> {
                            if (calls.incrementAndGet() == 2) deadline.cancel();
                            return NOW;
                        });
        assertThat(complete(command(f, UUID.randomUUID(), "a".repeat(64), 1), true, deadline))
                .isInstanceOf(RoleplayCheckpointStore.Expired.class);
        assertUnchanged(f);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SESSION", "TURN", "VERSION"})
    void goalDecisionForAnotherCheckpointCannotCompleteTheActivity(String mismatch) {
        var f = fixture(ActivityType.ROLEPLAY);
        var command = command(f, UUID.randomUUID(), "a".repeat(64), 1);
        var decision =
                RoleplayObservationTestPlans.forScope(
                        new Scope(
                                mismatch.equals("SESSION")
                                        ? UUID.randomUUID()
                                        : command.sessionId(),
                                command.scenarioId(),
                                command.scenarioVersion(),
                                "test-approval",
                                mismatch.equals("TURN") ? UUID.randomUUID() : command.turnId(),
                                command.candidateId(),
                                mismatch.equals("VERSION") ? 0 : command.expectedVersion()),
                        command.microGoalId(),
                        true);
        assertThat(completion.commitApprovedTurn(command, decision, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayCheckpointStore.Conflict.class);
        assertUnchanged(f);
    }

    @Test
    void anotherChildAndAStaleCheckpointCannotWriteCompletion() {
        var f = fixture(ActivityType.ROLEPLAY);
        var foreign = new Fixture(f.session, f.activity, UUID.randomUUID(), f.scenario);
        assertThat(
                        complete(
                                command(foreign, UUID.randomUUID(), "a".repeat(64), 1),
                                true,
                                RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayCheckpointStore.Conflict.class);
        assertThat(
                        complete(
                                command(f, UUID.randomUUID(), "a".repeat(64), 0),
                                true,
                                RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayCheckpointStore.Conflict.class);
        assertUnchanged(f);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELF_EMOTION_QUIZ", "OTHER_EMOTION_QUIZ"})
    void independentQuizzesCannotBeCompletedThroughTheRoleplayPath(String type) {
        var f = fixture(ActivityType.valueOf(type));
        assertThat(
                        complete(
                                command(f, UUID.randomUUID(), "a".repeat(64), 1),
                                true,
                                RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayCheckpointStore.Conflict.class);
        assertUnchanged(f);
    }

    @Test
    void concurrentSameRequestCreatesOneFinalTurnAndOneActualLearningRecord() throws Exception {
        var f = fixture(ActivityType.ROLEPLAY);
        UUID key = UUID.randomUUID();
        var start = new CountDownLatch(1);
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first =
                    callers.submit(
                            () -> {
                                start.await();
                                return complete(
                                        command(f, key, "a".repeat(64), 1),
                                        true,
                                        RoleplayTurnDeadline.start());
                            });
            var second =
                    callers.submit(
                            () -> {
                                start.await();
                                return complete(
                                        command(f, key, "a".repeat(64), 1),
                                        true,
                                        RoleplayTurnDeadline.start());
                            });
            start.countDown();
            var results =
                    List.of(
                            (RoleplayCheckpointStore.Committed) first.get(10, TimeUnit.SECONDS),
                            (RoleplayCheckpointStore.Committed) second.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(RoleplayCheckpointStore.Committed::replayed).count())
                    .isEqualTo(1);
            assertThat(
                            results.stream()
                                    .map(RoleplayCheckpointStore.Committed::candidateId)
                                    .distinct())
                    .hasSize(1);
        }
        assertCompleted(f);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "roleplay_goal_assessment",
                "roleplay_goal_observation",
                "roleplay_goal_decision_basis"
            })
    void observationWriteFailureRollsBackTheTurnAndCanRetry(String table) {
        var f = fixture(ActivityType.ROLEPLAY);
        var command = command(f, UUID.randomUUID(), "a".repeat(64), 1);
        var plan = reviewedPlan(command, true, GoalObservationOutput.Status.EXPLICIT, List.of());
        var constraint = "test_observation_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                "alter table "
                        + table
                        + " add constraint "
                        + constraint
                        + " check (session_id <> '"
                        + f.session
                        + "'::uuid)");
        try {
            assertThatThrownBy(
                            () ->
                                    completion.commitApprovedTurn(
                                            command, plan, RoleplayTurnDeadline.start()))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertUnchanged(f);
        } finally {
            jdbc.execute("alter table " + table + " drop constraint " + constraint);
        }
        completion.commitApprovedTurn(command, plan, RoleplayTurnDeadline.start());
        assertCompleted(f);
    }

    @Test
    void latestEvidenceComesFromTheLatestCommittedTurnWithoutErasingHistory() {
        var f = fixture(ActivityType.ROLEPLAY);
        var first = command(f, UUID.randomUUID(), "a".repeat(64), 1);
        var firstPlan =
                reviewedPlan(first, false, GoalObservationOutput.Status.EXPLICIT, List.of());
        completion.commitApprovedTurn(first, firstPlan, RoleplayTurnDeadline.start());
        var second = command(f, UUID.randomUUID(), "b".repeat(64), 2);
        var secondPlan =
                reviewedPlan(
                        second,
                        false,
                        GoalObservationOutput.Status.CONTRADICTORY,
                        firstPlan.proposal().observations());
        completion.commitApprovedTurn(second, secondPlan, RoleplayTurnDeadline.start());

        assertThat(observationCount(f, "roleplay_goal_assessment")).isEqualTo(2);
        assertThat(observationCount(f, "roleplay_goal_observation")).isEqualTo(2);
        assertThat(observationCount(f, "roleplay_goal_evidence_current")).isEqualTo(1);
        assertThat(
                        jdbc.queryForMap(
                                "select observation_status, turn_id, checkpoint_version from roleplay_goal_evidence_current where session_id=?",
                                f.session))
                .containsEntry("observation_status", "CONTRADICTORY")
                .containsEntry("turn_id", second.turnId())
                .containsEntry("checkpoint_version", 3L);
        assertThat(records(f)).isZero();
        assertThat(activity(f)).containsEntry("status", "IN_PROGRESS");
    }

    @Test
    void finalAssessmentCanReferenceEarlierStoredEvidenceAfterCanonicalDeletion() {
        var f = fixture(ActivityType.ROLEPLAY);
        var first = command(f, UUID.randomUUID(), "a".repeat(64), 1);
        var firstPlan =
                reviewedPlan(first, false, GoalObservationOutput.Status.EXPLICIT, List.of());
        completion.commitApprovedTurn(first, firstPlan, RoleplayTurnDeadline.start());
        var second = command(f, UUID.randomUUID(), "b".repeat(64), 2);
        var secondPlan =
                reviewedPlan(
                        second,
                        true,
                        GoalObservationOutput.Status.IMPLICIT,
                        firstPlan.proposal().observations());
        completion.commitApprovedTurn(second, secondPlan, RoleplayTurnDeadline.start());

        assertThat(canonicalTurns(f)).isZero();
        assertThat(records(f)).isEqualTo(1);
        assertThat(activity(f)).containsEntry("status", "COMPLETED");
        assertThat(observationCount(f, "roleplay_goal_observation")).isEqualTo(2);
        assertThat(observationCount(f, "roleplay_goal_decision_basis")).isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "select observation_status from roleplay_goal_evidence_current where session_id=?",
                                String.class,
                                f.session))
                .isEqualTo("IMPLICIT");
    }

    @Test
    void replayDoesNotReplaceAnObservationWithANewAssessment() {
        var f = fixture(ActivityType.ROLEPLAY);
        var key = UUID.randomUUID();
        var original = command(f, key, "a".repeat(64), 1);
        completion.commitApprovedTurn(
                original,
                reviewedPlan(original, false, GoalObservationOutput.Status.EXPLICIT, List.of()),
                RoleplayTurnDeadline.start());
        var retry = command(f, key, "a".repeat(64), 1);
        var result =
                (RoleplayCheckpointStore.Committed)
                        completion.commitApprovedTurn(
                                retry,
                                reviewedPlan(
                                        retry,
                                        true,
                                        GoalObservationOutput.Status.CONTRADICTORY,
                                        List.of()),
                                RoleplayTurnDeadline.start());

        assertThat(result.replayed()).isTrue();
        assertThat(result.turnId()).isEqualTo(original.turnId());
        assertThat(observationCount(f, "roleplay_goal_observation")).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select observation_status from roleplay_goal_evidence_current where session_id=?",
                                String.class,
                                f.session))
                .isEqualTo("EXPLICIT");
        assertThat(records(f)).isZero();
        assertThat(activity(f)).containsEntry("status", "IN_PROGRESS");
    }

    @Test
    void databaseRejectsBasisFromAnotherSessionEvenIfTheSuppliedHistoryIsWrong() {
        var other = fixture(ActivityType.ROLEPLAY);
        var otherCommand = command(other, UUID.randomUUID(), "b".repeat(64), 1);
        var otherPlan =
                reviewedPlan(otherCommand, false, GoalObservationOutput.Status.EXPLICIT, List.of());
        completion.commitApprovedTurn(otherCommand, otherPlan, RoleplayTurnDeadline.start());
        var f = fixture(ActivityType.ROLEPLAY);
        var command = command(f, UUID.randomUUID(), "a".repeat(64), 1);
        var invalid =
                reviewedPlan(
                        command,
                        true,
                        GoalObservationOutput.Status.EXPLICIT,
                        otherPlan.proposal().observations());

        assertThatThrownBy(
                        () ->
                                completion.commitApprovedTurn(
                                        command, invalid, RoleplayTurnDeadline.start()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertUnchanged(f);
        assertThat(observationCount(other, "roleplay_goal_observation")).isEqualTo(1);
    }

    @Test
    void observationStoreCannotCreateItsOwnIndependentTransaction() {
        var f = fixture(ActivityType.ROLEPLAY);
        var command = command(f, UUID.randomUUID(), "a".repeat(64), 1);
        assertThatThrownBy(
                        () ->
                                observations.save(
                                        reviewedPlan(
                                                command,
                                                true,
                                                GoalObservationOutput.Status.EXPLICIT,
                                                List.of())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertUnchanged(f);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHILD", "CLASSROOM", "INSTRUCTOR"})
    void permanentDeletionRemovesObservationGraphAndLeavesOtherChildrenAlone(String scope) {
        var f = fixture(ActivityType.ROLEPLAY);
        var other = fixture(ActivityType.ROLEPLAY);
        complete(
                command(f, UUID.randomUUID(), "a".repeat(64), 1),
                true,
                RoleplayTurnDeadline.start());
        complete(
                command(other, UUID.randomUUID(), "a".repeat(64), 1),
                true,
                RoleplayTurnDeadline.start());
        var owner =
                jdbc.queryForMap(
                        "select c.class_id, r.instructor_id from child c join classroom r on r.class_id=c.class_id where c.child_id=?",
                        f.child);
        var instructor = (UUID) owner.get("instructor_id");
        switch (scope) {
            case "CHILD" -> deletion.deleteChild(instructor, f.child);
            case "CLASSROOM" -> deletion.deleteClassroom(instructor, (UUID) owner.get("class_id"));
            case "INSTRUCTOR" -> {
                jdbc.update(
                        "update user_account set password_hash=? where user_id=?",
                        passwords.encode("synthetic-password"),
                        instructor);
                deletion.deleteInstructor(instructor, "synthetic-password");
            }
            default -> throw new AssertionError();
        }
        for (var table :
                List.of(
                        "roleplay_goal_assessment",
                        "roleplay_goal_observation",
                        "roleplay_goal_decision_basis",
                        "roleplay_goal_evidence_current")) {
            assertThat(observationCount(f, table)).isZero();
            assertThat(observationCount(other, table)).isEqualTo(1);
        }
        assertThat(turns(f)).isZero();
        assertThat(records(f)).isZero();
    }

    @Test
    void enclosingDeletionFailureRestoresTheObservationGraphAndCompletion() {
        var f = fixture(ActivityType.ROLEPLAY);
        complete(
                command(f, UUID.randomUUID(), "a".repeat(64), 1),
                true,
                RoleplayTurnDeadline.start());
        var instructor =
                jdbc.queryForObject(
                        "select r.instructor_id from child c join classroom r on r.class_id=c.class_id where c.child_id=?",
                        UUID.class,
                        f.child);
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactionManager)
                                        .executeWithoutResult(
                                                tx -> {
                                                    deletion.deleteChild(instructor, f.child);
                                                    assertThat(
                                                                    observationCount(
                                                                            f,
                                                                            "roleplay_goal_observation"))
                                                            .isZero();
                                                    throw new IllegalStateException("테스트용 삭제 실패");
                                                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("테스트용 삭제 실패");
        assertCompleted(f);
    }

    private int observationCount(Fixture f, String table) {
        return jdbc.queryForObject(
                "select count(*) from " + table + " where session_id=?", Integer.class, f.session);
    }

    private RoleplayGoalObservationPlan reviewedPlan(
            RoleplayCheckpointCommand command,
            boolean achieved,
            GoalObservationOutput.Status status,
            List<RoleplayGoalObservationPlan.Observation> prior) {
        var template = RoleplayObservationTestPlans.forCommand(command, achieved).proposal();
        var ref = template.observations().getFirst().reference();
        var current =
                new GoalObservationOutput.Reference(
                        ref.turnId(), ref.microGoalId(), ref.criterionReference());
        var basis = new ArrayList<GoalObservationOutput.Reference>();
        basis.add(current);
        var criteria = new HashMap<UUID, Set<String>>();
        criteria.put(command.microGoalId(), Set.of("next-focus-evidence"));
        criteria.put(ref.microGoalId(), Set.of(ref.criterionReference()));
        for (var observation : prior) {
            var p = observation.reference();
            criteria.put(p.microGoalId(), Set.of(p.criterionReference()));
            basis.add(
                    new GoalObservationOutput.Reference(
                            p.turnId(), p.microGoalId(), p.criterionReference()));
        }
        var analysisId = UUID.randomUUID();
        var extension =
                new GoalObservationOutput.Analysis(
                        GoalObservationOutput.ANALYSIS_VERSION,
                        analysisId,
                        List.of(new GoalObservationOutput.Observation(current, status)),
                        new GoalObservationOutput.Assessment(
                                achieved
                                        ? GoalObservationOutput.ActivityDecision.ACHIEVED
                                        : GoalObservationOutput.ActivityDecision.IN_PROGRESS,
                                basis));
        var analysis =
                new AnalysisResult(
                        command.turnId(),
                        "ANSWER_ATTEMPT",
                        "RELEVANT",
                        "GOAL_ACHIEVED",
                        new AnalysisResult.PrimaryGap("NONE", null),
                        new AnalysisResult.NextStrategy(
                                "CLOSE_ROLEPLAY",
                                command.microGoalId(),
                                null,
                                command.supportLevel()),
                        0.9,
                        extension);
        var candidate =
                new CandidateResponse(
                        command.candidateId(),
                        command.turnId(),
                        command.responseText(),
                        "CLOSING",
                        "CLOSE_ROLEPLAY",
                        command.microGoalId(),
                        command.supportLevel(),
                        List.of());
        var evaluation =
                new EvaluationResult(
                        command.candidateId(),
                        true,
                        EvaluationDecision.PASS,
                        null,
                        0,
                        List.of(),
                        null,
                        new GoalObservationOutput.Review(
                                GoalObservationOutput.EVALUATION_VERSION, analysisId, true, true));
        var context =
                new RoleplayGoalObservationPlan.Context(
                        template.scope(),
                        criteria,
                        prior,
                        template.analysisContractVersion(),
                        template.evaluationContractVersion());
        return RoleplayGoalObservationMapper.toPlan(
                context, command, new RoleplayTurnResult.Ready(analysis, candidate, evaluation));
    }

    private RoleplayCheckpointStore.CommitResult complete(
            RoleplayCheckpointCommand command, boolean achieved, RoleplayTurnDeadline deadline) {
        return completion.commitApprovedTurn(
                command, RoleplayObservationTestPlans.forCommand(command, achieved), deadline);
    }

    private void assertUnchanged(Fixture f) {
        assertThat(observationCount(f, "roleplay_goal_assessment")).isZero();
        assertThat(observationCount(f, "roleplay_goal_observation")).isZero();
        assertThat(observationCount(f, "roleplay_goal_decision_basis")).isZero();
        assertThat(turns(f)).isEqualTo(1);
        assertThat(canonicalTurns(f)).isEqualTo(1);
        assertThat(records(f)).isZero();
        assertThat(activity(f)).containsEntry("status", "IN_PROGRESS");
        assertThat(activity(f).get("completed_at")).isNull();
        assertThat(activity(f).get("reward_issued_at")).isNull();
        assertThat(
                        jdbc.queryForMap(
                                "select status, last_turn_number, row_version from roleplay_session where session_id=?",
                                f.session))
                .containsEntry("status", "IN_PROGRESS")
                .containsEntry("last_turn_number", 1)
                .containsEntry("row_version", 1L);
    }

    private void assertCompleted(Fixture f) {
        assertThat(observationCount(f, "roleplay_goal_assessment")).isEqualTo(1);
        assertThat(observationCount(f, "roleplay_goal_observation")).isEqualTo(1);
        assertThat(observationCount(f, "roleplay_goal_decision_basis")).isEqualTo(1);
        assertThat(turns(f)).isEqualTo(2);
        assertThat(canonicalTurns(f)).isZero();
        assertThat(records(f)).isEqualTo(1);
        assertThat(activity(f))
                .containsEntry("status", "COMPLETED")
                .containsEntry("completed_at", Timestamp.from(NOW))
                .containsEntry("reward_issued_at", Timestamp.from(NOW));
        assertThat(
                        jdbc.queryForObject(
                                "select status from roleplay_session where session_id=?",
                                String.class,
                                f.session))
                .isEqualTo("COMPLETED");
    }

    private java.util.Map<String, Object> activity(Fixture f) {
        return jdbc.queryForMap(
                "select status, completed_at, reward_issued_at from activity where activity_id=?",
                f.activity);
    }

    private java.util.Map<String, Object> completionSnapshot(Fixture f) {
        return jdbc.queryForMap(
                "select a.completed_at, a.reward_issued_at, r.record_id, s.row_version from activity a join learning_record r on r.activity_id=a.activity_id join roleplay_session s on s.activity_id=a.activity_id where s.session_id=?",
                f.session);
    }

    private int turns(Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from roleplay_turn where session_id=?", Integer.class, f.session);
    }

    private int canonicalTurns(Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from roleplay_turn where session_id=? and canonical_utterance is not null",
                Integer.class,
                f.session);
    }

    private int records(Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from learning_record where activity_id=?",
                Integer.class,
                f.activity);
    }

    private RoleplayCheckpointCommand command(
            Fixture f, UUID key, String fingerprint, long version) {
        var trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        f.activity,
                        null,
                        f.child,
                        f.scenario,
                        1,
                        f.session,
                        UUID.randomUUID(),
                        null,
                        null);
        UUID goal = UUID.randomUUID(), candidate = UUID.randomUUID();
        var analysis =
                new AnalysisResult(
                        trace.turnId(),
                        "ANSWER_ATTEMPT",
                        "RELEVANT",
                        "GOAL_ACHIEVED",
                        new AnalysisResult.PrimaryGap("NONE", null),
                        new AnalysisResult.NextStrategy("CLOSE_ROLEPLAY", goal, null, "S1"),
                        0.9);
        var response =
                new CandidateResponse(
                        candidate,
                        trace.turnId(),
                        "synthetic approved response",
                        "CLOSING",
                        "CLOSE_ROLEPLAY",
                        goal,
                        "S1",
                        List.of());
        var evaluation =
                new EvaluationResult(
                        candidate, true, EvaluationDecision.PASS, null, 0, List.of(), null);
        var spoken =
                new RoleplayTurnResult.SpokenReady(
                        new RoleplayTurnResult.Ready(analysis, response, evaluation),
                        "synthetic canonical input",
                        null);
        return RoleplayCheckpointCommand.fromApproved(
                trace, version, Math.toIntExact(version) + 1, key, fingerprint, spoken);
    }

    private Fixture fixture(ActivityType type) {
        UUID user = UUID.randomUUID(), classroom = UUID.randomUUID(), child = UUID.randomUUID();
        UUID goal = UUID.randomUUID(), activity = UUID.randomUUID(), session = UUID.randomUUID();
        var f = new Fixture(session, activity, child, UUID.randomUUID());
        Timestamp now = Timestamp.from(NOW);
        jdbc.update(
                "insert into user_account(user_id,email,password_hash,name,role,status,created_at) values(?,?,?,'synthetic','INSTRUCTOR','ACTIVE',?)",
                user,
                user + "@example.com",
                "test-hash",
                now);
        jdbc.update(
                "insert into classroom(class_id,instructor_id,name,status) values(?,?,'synthetic','ACTIVE')",
                classroom,
                user);
        jdbc.update(
                "insert into child(child_id,class_id,display_name,status) values(?,?,'synthetic','ACTIVE')",
                child,
                classroom);
        jdbc.update(
                "insert into learning_goal(goal_id,child_id,instructor_id,title,content_hash,created_at) values(?,?,?,'synthetic',?,?)",
                goal,
                child,
                user,
                "a".repeat(64),
                now);
        jdbc.update(
                "insert into activity(activity_id,child_id,goal_id,scenario_id,activity_type,status,assigned_at) values(?,?,?,?,?,'IN_PROGRESS',?)",
                activity,
                child,
                type.requiresGoal() ? goal : null,
                f.scenario,
                type.name(),
                now);
        jdbc.update(
                "insert into roleplay_session(session_id,activity_id,child_id,scenario_id,scenario_version,status,last_activity_at) values(?,?,?,?,1,'IN_PROGRESS',?)",
                session,
                activity,
                child,
                f.scenario,
                now);
        checkpoints.commit(
                command(f, UUID.randomUUID(), "c".repeat(64), 0), RoleplayTurnDeadline.start());
        return f;
    }

    private record Fixture(UUID session, UUID activity, UUID child, UUID scenario) {}
}
