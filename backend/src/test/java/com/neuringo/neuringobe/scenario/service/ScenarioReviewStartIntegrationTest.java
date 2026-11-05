package com.neuringo.neuringobe.scenario.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neuringo.neuringobe.IntegrationTest;
import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.quiz.dto.InitialDifficultyEvidence;
import com.neuringo.neuringobe.quiz.service.InitialDifficultyQueryService;
import com.neuringo.neuringobe.scenario.domain.ScenarioApprovalConditions;
import com.neuringo.neuringobe.scenario.dto.InitialScenarioPreparation;
import com.neuringo.neuringobe.scenario.dto.ScenarioCandidateSelection;
import com.neuringo.neuringobe.scenario.dto.ScenarioEdits;
import com.neuringo.neuringobe.scenario.dto.ScenarioReviewClosure;
import com.neuringo.neuringobe.scenario.dto.ScenarioReviewSnapshot;
import com.neuringo.neuringobe.scenario.repository.ScenarioCandidateContentRepository;
import com.neuringo.neuringobe.scenario.repository.ScenarioCandidateRepository;
import com.neuringo.neuringobe.scenario.support.ScenarioPoolTestIsolation;
import com.neuringo.neuringobe.user.domain.UserRole;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@org.junit.jupiter.api.extension.ExtendWith(ScenarioPoolTestIsolation.class)
class ScenarioReviewStartIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ScenarioReviewStartService starts;
    @Autowired private ScenarioReviewTransactions transactions;
    @Autowired private InitialScenarioPreparationService preparations;
    @Autowired private ScenarioCandidateQueryService queries;
    @Autowired private ScenarioCandidateRepository candidates;
    @Autowired private ScenarioCandidateContentRepository contents;
    @Autowired private ScenarioApprovalRegistry approvals;
    @Autowired private PlatformTransactionManager manager;
    @MockitoBean private InitialDifficultyQueryService difficulties;
    @MockitoBean private LlmProvider llm;
    @Autowired private ScenarioPreparationService preparation;
    @Autowired private ScenarioConfirmationService confirmation;

    @Autowired
    private com.neuringo.neuringobe.scenario.repository.ScenarioConfirmationRepository
            confirmationRepository;

    @Autowired private ScenarioConfirmationPreparationService confirmationPreparation;
    @Autowired private com.neuringo.neuringobe.roleplay.service.RoleplayStartService roleplay;

    @Autowired
    private com.neuringo.neuringobe.scenario.repository.ScenarioRepository scenarioRepository;

    @Autowired private JsonMapper json;

    @Autowired
    private com.neuringo.neuringobe.scenario.repository.ScenarioDraftRepository draftRepository;

    @Test
    void freezesRotatingOrderAndRecordsOnlyFirstPresentation() {
        var ctx = context(null);
        UUID old = candidate(ctx);
        UUID recent = candidate(ctx);
        UUID unseen = candidate(ctx);
        seedPresentation(ctx.instructor(), old, "2026-01-01T00:00:00Z");
        seedPresentation(ctx.instructor(), recent, "2026-01-02T00:00:00Z");

        var snapshot = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));

        assertThat(order(snapshot.reviewId())).containsExactly(unseen, old, recent);
        assertThat(snapshot.candidate().scenarioId()).isEqualTo(unseen);
        assertThat(snapshot.currentPosition()).isEqualTo(1);
        assertThat(snapshot.revision()).isEqualTo(1);
        assertThat(snapshot.candidateCount()).isEqualTo(3);
        assertThat(presentations(ctx.instructor(), unseen)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sum(presentation_count) FROM scenario_presentation_daily WHERE instructor_id = ?",
                                Long.class,
                                ctx.instructor()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT presentation_day = (last_presented_at AT TIME ZONE 'Asia/Seoul')::date FROM scenario_presentation_daily d JOIN scenario_presentation_stat s USING (instructor_id, scenario_id) WHERE instructor_id = ?",
                                Boolean.class,
                                ctx.instructor()))
                .isTrue();
        var nextActivity = context(ctx.instructor());
        assertThat(
                        starts.startOrResume(
                                        nextActivity.activity(),
                                        UUID.randomUUID(),
                                        auth(nextActivity))
                                .candidate()
                                .scenarioId())
                .isEqualTo(old);
    }

    @Test
    void duplicateStartAndNewSessionRestoreFrozenListWithoutExtraPresentation() {
        var ctx = context(null);
        UUID first = candidate(ctx);
        candidate(ctx);
        UUID key = UUID.randomUUID();
        var original = starts.startOrResume(ctx.activity(), key, auth(ctx));
        var savedStat =
                jdbc.queryForMap(
                        "SELECT * FROM scenario_presentation_stat WHERE instructor_id = ? AND scenario_id = ?",
                        ctx.instructor(),
                        original.candidate().scenarioId());
        UUID addedLater = candidate(ctx);

        assertThat(starts.startOrResume(ctx.activity(), key, auth(ctx))).isEqualTo(original);
        var recreated = new ScenarioReviewStartService(preparations, queries, transactions);
        assertThat(recreated.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx)))
                .isEqualTo(original);
        assertThat(order(original.reviewId()))
                .hasSize(2)
                .doesNotContain(addedLater)
                .contains(first);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM scenario_presentation_stat WHERE instructor_id = ? AND scenario_id = ?",
                                ctx.instructor(),
                                original.candidate().scenarioId()))
                .isEqualTo(savedStat);
    }

    @Test
    void restoresStoredPositionAndRevisionRatherThanStartingAtFirstCandidate() {
        var ctx = context(null);
        candidate(ctx);
        candidate(ctx);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var ids = order(original.reviewId());
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        ignored -> {
                            jdbc.update(
                                    "UPDATE scenario_review SET current_position = 2, revision = 2 WHERE review_id = ?",
                                    original.reviewId());
                            jdbc.update(
                                    "INSERT INTO scenario_presentation_stat (instructor_id, scenario_id, last_presented_at, presentation_count) VALUES (?, ?, now(), 1)",
                                    ctx.instructor(),
                                    ids.get(1));
                        });

        var restored = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));

        assertThat(restored.currentPosition()).isEqualTo(2);
        assertThat(restored.revision()).isEqualTo(2);
        assertThat(restored.candidate().scenarioId()).isEqualTo(ids.get(1));
        assertThat(presentations(ctx.instructor(), ids.get(1))).isEqualTo(1);
    }

    @Test
    void skipsRetiredCurrentCandidateWithinFrozenListOnResume() {
        var ctx = context(null);
        candidate(ctx);
        candidate(ctx);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var ids = order(original.reviewId());
        jdbc.update("UPDATE scenario SET status = 'RETIRED' WHERE scenario_id = ?", ids.getFirst());
        UUID later = candidate(ctx);

        var resumed = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));

        assertThat(resumed.currentPosition()).isEqualTo(2);
        assertThat(resumed.revision()).isEqualTo(2);
        assertThat(resumed.candidate().scenarioId()).isEqualTo(ids.get(1));
        assertThat(order(original.reviewId())).doesNotContain(later);
        assertThat(presentations(ctx.instructor(), ids.get(1))).isEqualTo(1);
        assertThat(starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx)))
                .isEqualTo(resumed);
        assertThat(presentations(ctx.instructor(), ids.get(1))).isEqualTo(1);
    }

    @Test
    void simultaneousStartsCommitOneReviewAndOnePresentation() throws Exception {
        var ctx = context(null);
        UUID scenario = candidate(ctx);
        var barrier = new CountDownLatch(2);
        var racing =
                facadeWithSelectionHook(
                        input -> {
                            barrier.countDown();
                            try {
                                assertThat(barrier.await(10, TimeUnit.SECONDS)).isTrue();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interrupted);
                            }
                        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var calls =
                    List.of(1, 2).stream()
                            .map(
                                    ignored ->
                                            pool.submit(
                                                    () ->
                                                            racing.startOrResume(
                                                                    ctx.activity(),
                                                                    UUID.randomUUID(),
                                                                    auth(ctx))))
                            .toList();
            var first = calls.get(0).get(15, TimeUnit.SECONDS);
            assertThat(calls.get(1).get(15, TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario_review WHERE activity_id = ?",
                                Integer.class,
                                ctx.activity()))
                .isEqualTo(1);
        assertThat(presentations(ctx.instructor(), scenario)).isEqualTo(1);
    }

    @Test
    void performsSelectionWithoutHoldingActivityWriteLockAndRechecksInputsBeforeSave() {
        var ctx = context(null);
        UUID scenario = candidate(ctx);
        var racing =
                facadeWithSelectionHook(
                        input -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            new TransactionTemplate(manager)
                                    .executeWithoutResult(
                                            ignored ->
                                                    jdbc.queryForObject(
                                                            "SELECT activity_id FROM activity WHERE activity_id = ? FOR UPDATE NOWAIT",
                                                            UUID.class,
                                                            ctx.activity()));
                            jdbc.update(
                                    "UPDATE learning_goal SET content_hash = ? WHERE goal_id = ?",
                                    "b".repeat(64),
                                    ctx.goal());
                        });

        assertThatThrownBy(() -> racing.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SCENARIO_REVIEW_INPUT_CHANGED"));
        assertNoReview(ctx);
        assertThat(presentations(ctx.instructor(), scenario)).isZero();
    }

    @Test
    void rechecksCandidateAvailabilityBetweenQueryAndSave() {
        var ctx = context(null);
        var ids =
                List.of(candidate(ctx), candidate(ctx)).stream()
                        .sorted(Comparator.comparing(UUID::toString))
                        .toList();
        var racing =
                facadeWithSelectionHook(
                        input ->
                                jdbc.update(
                                        "UPDATE scenario SET status = 'RETIRED' WHERE scenario_id = ?",
                                        ids.getFirst()));

        var snapshot = racing.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));

        assertThat(order(snapshot.reviewId())).containsExactlyElementsOf(ids);
        assertThat(snapshot.currentPosition()).isEqualTo(2);
        assertThat(snapshot.candidate().scenarioId()).isEqualTo(ids.get(1));
        assertThat(presentations(ctx.instructor(), ids.getFirst())).isZero();
        assertThat(presentations(ctx.instructor(), ids.get(1))).isEqualTo(1);
    }

    @Test
    void failedDailyCounterRollsBackSnapshotCursorReceiptAndCumulativeCounter() {
        var ctx = context(null);
        UUID scenario = candidate(ctx);
        String constraint =
                "test_presentation_failure_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                "ALTER TABLE scenario_presentation_daily ADD CONSTRAINT "
                        + constraint
                        + " CHECK (instructor_id <> '"
                        + ctx.instructor()
                        + "') NOT VALID");
        try {
            assertThatThrownBy(
                            () ->
                                    starts.startOrResume(
                                            ctx.activity(), UUID.randomUUID(), auth(ctx)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertNoReview(ctx);
            assertThat(presentations(ctx.instructor(), scenario)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE scenario_presentation_daily DROP CONSTRAINT " + constraint);
        }
        assertThat(
                        starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx))
                                .candidate()
                                .scenarioId())
                .isEqualTo(scenario);
        assertThat(presentations(ctx.instructor(), scenario)).isEqualTo(1);
    }

    @Test
    void emptyFrozenListDoesNotGrowOnResumeAndDoesNotCountPresentation() {
        var ctx = context(null);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        candidate(ctx);
        var restored = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        assertThat(restored).isEqualTo(original);
        assertThat(restored.candidate()).isNull();
        assertThat(restored.candidateCount()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario_presentation_stat WHERE instructor_id = ?",
                                Integer.class,
                                ctx.instructor()))
                .isZero();
    }

    @Test
    void terminalReceiptPreventsOldRequestFromRecreatingReviewButNewStartIsAllowed() {
        var ctx = context(null);
        candidate(ctx);
        UUID key = UUID.randomUUID();
        var original = starts.startOrResume(ctx.activity(), key, auth(ctx));
        transactions.cancel(
                ctx.activity(),
                UUID.randomUUID(),
                original.reviewId(),
                original.revision(),
                auth(ctx));
        assertThatThrownBy(() -> starts.startOrResume(ctx.activity(), key, auth(ctx)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SCENARIO_REVIEW_CLOSED"));
        assertThat(starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx)).reviewId())
                .isNotEqualTo(original.reviewId());
    }

    @Test
    void deletingActivityCascadesTemporaryRowsAndReceiptsButKeepsPresentationHistory() {
        var ctx = context(null);
        UUID scenario = candidate(ctx);
        var review = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        jdbc.update("DELETE FROM activity WHERE activity_id = ?", ctx.activity());
        assertNoReview(ctx);
        assertThat(order(review.reviewId())).isEmpty();
        assertThat(presentations(ctx.instructor(), scenario)).isEqualTo(1);
    }

    @Test
    void blocksCallerTransactionAndDoesNotOfferAlreadyAssignedActivity() {
        var ctx = context(null);
        UUID scenario = candidate(ctx);
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(manager)
                                        .execute(
                                                ignored ->
                                                        starts.startOrResume(
                                                                ctx.activity(),
                                                                UUID.randomUUID(),
                                                                auth(ctx))))
                .isInstanceOf(IllegalTransactionStateException.class);
        jdbc.update(
                "UPDATE activity SET scenario_id = ? WHERE activity_id = ?",
                scenario,
                ctx.activity());
        assertThatThrownBy(() -> starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SCENARIO_ALREADY_ASSIGNED"));
        assertNoReview(ctx);
    }

    @Test
    void duplicateRequestDoesNotAdvanceAgainWhenCurrentCandidateBecomesInvalid() {
        var ctx = context(null);
        candidate(ctx);
        candidate(ctx);
        UUID key = UUID.randomUUID();
        var original = starts.startOrResume(ctx.activity(), key, auth(ctx));
        var ids = order(original.reviewId());
        jdbc.update("UPDATE scenario SET status = 'RETIRED' WHERE scenario_id = ?", ids.getFirst());

        assertThatThrownBy(() -> starts.startOrResume(ctx.activity(), key, auth(ctx)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SCENARIO_REVIEW_CHANGED"));
        assertThat(presentations(ctx.instructor(), ids.get(1))).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT current_position FROM scenario_review WHERE review_id = ?",
                                Integer.class,
                                original.reviewId()))
                .isEqualTo(1);

        assertThat(
                        starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx))
                                .candidate()
                                .scenarioId())
                .isEqualTo(ids.get(1));
        assertThat(presentations(ctx.instructor(), ids.get(1))).isEqualTo(1);
    }

    @Test
    void nextRejectsOnlyCurrentCandidateAndPreservesApprovalAndFrozenList() {
        var ctx = context(null);
        candidate(ctx);
        candidate(ctx);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var ids = order(original.reviewId());
        UUID addedLater = candidate(ctx);

        var next =
                transactions.next(
                        ctx.activity(),
                        UUID.randomUUID(),
                        original.reviewId(),
                        original.revision(),
                        auth(ctx));

        assertThat(next.currentPosition()).isEqualTo(2);
        assertThat(next.revision()).isEqualTo(2);
        assertThat(next.candidate().scenarioId()).isEqualTo(ids.get(1));
        assertThat(order(original.reviewId()))
                .containsExactlyElementsOf(ids)
                .doesNotContain(addedLater);
        assertThat(rejectedCount(original.reviewId())).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM scenario WHERE scenario_id = ?",
                                String.class,
                                ids.getFirst()))
                .isEqualTo("APPROVED");
        assertThat(presentations(ctx.instructor(), ids.getFirst())).isEqualTo(1);
        assertThat(presentations(ctx.instructor(), ids.get(1))).isEqualTo(1);
        var otherActivity = context(ctx.instructor());
        assertThat(queries.select(otherActivity.activity(), auth(otherActivity)).candidates())
                .extracting(c -> c.scenarioId())
                .contains(ids.getFirst());
    }

    @Test
    void nextReplayReturnsOriginalResultBeforeCheckingCurrentRevision() {
        var ctx = context(null);
        candidate(ctx);
        candidate(ctx);
        candidate(ctx);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        UUID key = UUID.randomUUID();
        var second = transactions.next(ctx.activity(), key, original.reviewId(), 1, auth(ctx));
        var rejection =
                jdbc.queryForObject(
                        "SELECT rejected_at FROM scenario_review_candidate WHERE review_id = ? AND position = 1",
                        Timestamp.class,
                        original.reviewId());
        assertThat(transactions.next(ctx.activity(), key, original.reviewId(), 1, auth(ctx)))
                .isEqualTo(second);
        var third =
                transactions.next(
                        ctx.activity(), UUID.randomUUID(), original.reviewId(), 2, auth(ctx));
        assertThat(transactions.next(ctx.activity(), key, original.reviewId(), 1, auth(ctx)))
                .isEqualTo(second);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT rejected_at FROM scenario_review_candidate WHERE review_id = ? AND position = 1",
                                Timestamp.class,
                                original.reviewId()))
                .isEqualTo(rejection);
        assertThat(starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx)))
                .isEqualTo(third);
        assertThat(rejectedCount(original.reviewId())).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sum(presentation_count) FROM scenario_presentation_stat WHERE instructor_id = ?",
                                Long.class,
                                ctx.instructor()))
                .isEqualTo(3);
        assertThat(nextReceiptCount(ctx)).isEqualTo(2);
    }

    @Test
    void rejectsReusedKeyWithDifferentRevisionReviewOrAction() {
        var ctx = context(null);
        candidate(ctx);
        candidate(ctx);
        UUID startKey = UUID.randomUUID();
        var original = starts.startOrResume(ctx.activity(), startKey, auth(ctx));
        UUID key = UUID.randomUUID();
        transactions.next(ctx.activity(), key, original.reviewId(), 1, auth(ctx));
        assertConflict(
                "IDEMPOTENCY_KEY_REUSED",
                () -> transactions.next(ctx.activity(), key, original.reviewId(), 2, auth(ctx)));
        assertConflict(
                "IDEMPOTENCY_KEY_REUSED",
                () -> transactions.next(ctx.activity(), key, UUID.randomUUID(), 1, auth(ctx)));
        assertConflict(
                "IDEMPOTENCY_KEY_REUSED",
                () ->
                        transactions.next(
                                ctx.activity(), startKey, original.reviewId(), 1, auth(ctx)));
        assertConflict(
                "IDEMPOTENCY_KEY_REUSED",
                () -> starts.startOrResume(ctx.activity(), key, auth(ctx)));
        assertThat(rejectedCount(original.reviewId())).isEqualTo(1);
        assertThat(nextReceiptCount(ctx)).isEqualTo(1);
    }

    @Test
    void rejectsStaleDistinctRequestWithoutRejectingUnseenNextCandidate() {
        var ctx = context(null);
        candidate(ctx);
        candidate(ctx);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var second =
                transactions.next(
                        ctx.activity(), UUID.randomUUID(), original.reviewId(), 1, auth(ctx));
        assertConflict(
                "SCENARIO_REVIEW_CHANGED",
                () ->
                        transactions.next(
                                ctx.activity(),
                                UUID.randomUUID(),
                                original.reviewId(),
                                1,
                                auth(ctx)));
        assertConflict(
                "SCENARIO_REVIEW_CHANGED",
                () ->
                        transactions.next(
                                ctx.activity(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                2,
                                auth(ctx)));
        assertThat(starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx)))
                .isEqualTo(second);
        assertThat(rejectedCount(original.reviewId())).isEqualTo(1);
        assertThat(nextReceiptCount(ctx)).isEqualTo(1);
    }

    @Test
    void concurrentDistinctNextRequestsFromSameRevisionMoveOnlyOnce() throws Exception {
        var ctx = context(null);
        candidate(ctx);
        candidate(ctx);
        candidate(ctx);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var results = raceNext(ctx, original, false);
        assertThat(results.stream().filter(ScenarioReviewSnapshot.class::isInstance)).hasSize(1);
        assertThat(
                        results.stream()
                                .filter(ApiException.class::isInstance)
                                .map(ApiException.class::cast))
                .extracting(ApiException::getCode)
                .containsExactly("SCENARIO_REVIEW_CHANGED");
        assertThat(rejectedCount(original.reviewId())).isEqualTo(1);
        assertThat(nextReceiptCount(ctx)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateNextRequestsReplayOneCommittedResult() throws Exception {
        var ctx = context(null);
        candidate(ctx);
        candidate(ctx);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var results = raceNext(ctx, original, true);
        assertThat(results.getFirst())
                .isInstanceOf(ScenarioReviewSnapshot.class)
                .isEqualTo(results.get(1));
        assertThat(rejectedCount(original.reviewId())).isEqualTo(1);
        assertThat(nextReceiptCount(ctx)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sum(presentation_count) FROM scenario_presentation_stat WHERE instructor_id = ?",
                                Long.class,
                                ctx.instructor()))
                .isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RETIRED", "DELETED", "ASSIGNED"})
    void nextSkipsInvalidUnseenCandidateWithoutRecordingItAsRejected(String invalidation) {
        var ctx = context(null);
        candidate(ctx);
        candidate(ctx);
        candidate(ctx);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var ids = order(original.reviewId());
        if (invalidation.equals("RETIRED"))
            jdbc.update("UPDATE scenario SET status = 'RETIRED' WHERE scenario_id = ?", ids.get(1));
        else if (invalidation.equals("DELETED"))
            jdbc.update("DELETE FROM scenario WHERE scenario_id = ?", ids.get(1));
        else
            jdbc.update(
                    """
                INSERT INTO activity (activity_id, child_id, goal_id, activity_type, scenario_id, status, assigned_at)
                VALUES (?, ?, ?, 'ROLEPLAY', ?, 'COMPLETED', now())
                """,
                    UUID.randomUUID(),
                    ctx.child(),
                    ctx.goal(),
                    ids.get(1));
        var next =
                transactions.next(
                        ctx.activity(), UUID.randomUUID(), original.reviewId(), 1, auth(ctx));
        assertThat(next.currentPosition()).isEqualTo(3);
        assertThat(next.candidate().scenarioId()).isEqualTo(ids.get(2));
        assertThat(rejectedCount(original.reviewId())).isEqualTo(1);
        assertThat(presentations(ctx.instructor(), ids.get(1))).isZero();
        assertThat(presentations(ctx.instructor(), ids.get(2))).isEqualTo(1);
    }

    @Test
    void exhaustionDoesNotCycleBackToRejectedCandidateOrExpandFrozenList() {
        var ctx = context(null);
        UUID first = candidate(ctx);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        UUID key = UUID.randomUUID();
        var exhausted = transactions.next(ctx.activity(), key, original.reviewId(), 1, auth(ctx));
        assertThat(exhausted.currentPosition()).isNull();
        assertThat(exhausted.candidate()).isNull();
        assertThat(exhausted.revision()).isEqualTo(2);
        assertThat(transactions.next(ctx.activity(), key, original.reviewId(), 1, auth(ctx)))
                .isEqualTo(exhausted);
        candidate(ctx);
        assertThat(starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx)))
                .isEqualTo(exhausted);
        assertConflict(
                "SCENARIO_REVIEW_NO_CANDIDATE",
                () ->
                        transactions.next(
                                ctx.activity(),
                                UUID.randomUUID(),
                                original.reviewId(),
                                2,
                                auth(ctx)));
        assertThat(rejectedCount(original.reviewId())).isEqualTo(1);
        assertThat(presentations(ctx.instructor(), first)).isEqualTo(1);
    }

    @Test
    void nextCounterFailureRollsBackRejectionCursorStatisticsAndReplayReceipt() {
        var ctx = context(null);
        candidate(ctx);
        candidate(ctx);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        UUID key = UUID.randomUUID();
        UUID second = order(original.reviewId()).get(1);
        String constraint = "test_next_failure_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                "ALTER TABLE scenario_presentation_daily ADD CONSTRAINT "
                        + constraint
                        + " CHECK (instructor_id <> '"
                        + ctx.instructor()
                        + "') NOT VALID");
        try {
            assertThatThrownBy(
                            () ->
                                    transactions.next(
                                            ctx.activity(), key, original.reviewId(), 1, auth(ctx)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(rejectedCount(original.reviewId())).isZero();
            assertThat(presentations(ctx.instructor(), second)).isZero();
            assertThat(nextReceiptCount(ctx)).isZero();
            assertThat(starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx)))
                    .isEqualTo(original);
        } finally {
            jdbc.execute("ALTER TABLE scenario_presentation_daily DROP CONSTRAINT " + constraint);
        }
        assertThat(
                        transactions
                                .next(ctx.activity(), key, original.reviewId(), 1, auth(ctx))
                                .candidate()
                                .scenarioId())
                .isEqualTo(second);
    }

    @Test
    void terminalNextReceiptCanBeScrubbedAndCannotReplayDeletedReview() {
        var ctx = context(null);
        candidate(ctx);
        candidate(ctx);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        UUID key = UUID.randomUUID();
        transactions.next(ctx.activity(), key, original.reviewId(), 1, auth(ctx));
        transactions.cancel(ctx.activity(), UUID.randomUUID(), original.reviewId(), 2, auth(ctx));
        assertConflict(
                "SCENARIO_REVIEW_CLOSED",
                () -> transactions.next(ctx.activity(), key, original.reviewId(), 1, auth(ctx)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT result_snapshot IS NULL FROM scenario_review_request WHERE activity_id = ? AND request_id = ?",
                                Boolean.class,
                                ctx.activity(),
                                key))
                .isTrue();
        assertThat(order(original.reviewId())).isEmpty();
    }

    @Test
    void nextRechecksGoalInputAndInstructorPermission() {
        var ctx = context(null);
        candidate(ctx);
        candidate(ctx);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var stranger = context(null);
        assertThatThrownBy(
                        () ->
                                transactions.next(
                                        ctx.activity(),
                                        UUID.randomUUID(),
                                        original.reviewId(),
                                        1,
                                        auth(stranger)))
                .isInstanceOf(ResourceNotFoundException.class);
        jdbc.update(
                "UPDATE learning_goal SET content_hash = ? WHERE goal_id = ?",
                "b".repeat(64),
                ctx.goal());
        assertConflict(
                "SCENARIO_REVIEW_INPUT_CHANGED",
                () ->
                        transactions.next(
                                ctx.activity(),
                                UUID.randomUUID(),
                                original.reviewId(),
                                1,
                                auth(ctx)));
        assertThat(rejectedCount(original.reviewId())).isZero();
        assertThat(nextReceiptCount(ctx)).isZero();
    }

    @Test
    void cancelDeletesTemporaryDataScrubsReplayResultsAndKeepsPresentationStatistics() {
        var ctx = context(null);
        candidate(ctx);
        candidate(ctx);
        UUID startKey = UUID.randomUUID();
        var original = starts.startOrResume(ctx.activity(), startKey, auth(ctx));
        UUID nextKey = UUID.randomUUID();
        var second = transactions.next(ctx.activity(), nextKey, original.reviewId(), 1, auth(ctx));
        var stats =
                jdbc.queryForList(
                        "SELECT * FROM scenario_presentation_stat WHERE instructor_id = ? ORDER BY scenario_id",
                        ctx.instructor());
        var daily =
                jdbc.queryForList(
                        "SELECT * FROM scenario_presentation_daily WHERE instructor_id = ? ORDER BY scenario_id",
                        ctx.instructor());

        var closure =
                transactions.cancel(
                        ctx.activity(),
                        UUID.randomUUID(),
                        original.reviewId(),
                        second.revision(),
                        auth(ctx));

        assertThat(closure.outcome()).isEqualTo("CANCELLED");
        assertThat(closure.closedAt()).isNotNull();
        assertThat(closure.reviewId()).isEqualTo(original.reviewId());
        assertThat(order(original.reviewId())).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario_review WHERE activity_id = ?",
                                Long.class,
                                ctx.activity()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario_review_request WHERE activity_id = ? AND outcome = 'CANCELLED' AND closed_at IS NOT NULL AND expected_revision IS NULL AND result_snapshot IS NULL",
                                Long.class,
                                ctx.activity()))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM scenario_presentation_stat WHERE instructor_id = ? ORDER BY scenario_id",
                                ctx.instructor()))
                .isEqualTo(stats);
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM scenario_presentation_daily WHERE instructor_id = ? ORDER BY scenario_id",
                                ctx.instructor()))
                .isEqualTo(daily);
        assertThat(epoch(ctx)).isEqualTo(1);
        assertConflict(
                "SCENARIO_REVIEW_CLOSED",
                () -> starts.startOrResume(ctx.activity(), startKey, auth(ctx)));
        assertConflict(
                "SCENARIO_REVIEW_CLOSED",
                () ->
                        transactions.next(
                                ctx.activity(), nextKey, original.reviewId(), 1, auth(ctx)));
    }

    @Test
    void cancelReplayDoesNotDeleteNewReviewAndDetectsChangedRequestPayload() {
        var ctx = context(null);
        candidate(ctx);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        UUID key = UUID.randomUUID();
        var cancelled = transactions.cancel(ctx.activity(), key, original.reviewId(), 1, auth(ctx));
        var fresh = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        assertThat(transactions.cancel(ctx.activity(), key, original.reviewId(), 1, auth(ctx)))
                .isEqualTo(cancelled);
        assertConflict(
                "IDEMPOTENCY_KEY_REUSED",
                () -> transactions.cancel(ctx.activity(), key, original.reviewId(), 2, auth(ctx)));
        assertConflict(
                "IDEMPOTENCY_KEY_REUSED",
                () -> transactions.cancel(ctx.activity(), key, fresh.reviewId(), 1, auth(ctx)));
        assertConflict(
                "SCENARIO_REVIEW_CHANGED",
                () ->
                        transactions.cancel(
                                ctx.activity(),
                                UUID.randomUUID(),
                                original.reviewId(),
                                1,
                                auth(ctx)));
        assertThat(starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx)))
                .isEqualTo(fresh);
        assertThat(epoch(ctx)).isEqualTo(1);
    }

    @Test
    void staleCancellationDoesNotDeleteReviewAfterNextOrUndoAssignment() {
        var ctx = context(null);
        candidate(ctx);
        candidate(ctx);
        UUID startKey = UUID.randomUUID();
        var original = starts.startOrResume(ctx.activity(), startKey, auth(ctx));
        var second =
                transactions.next(
                        ctx.activity(), UUID.randomUUID(), original.reviewId(), 1, auth(ctx));
        assertConflict(
                "SCENARIO_REVIEW_CHANGED",
                () ->
                        transactions.cancel(
                                ctx.activity(),
                                UUID.randomUUID(),
                                original.reviewId(),
                                1,
                                auth(ctx)));
        assertConflict(
                "IDEMPOTENCY_KEY_REUSED",
                () ->
                        transactions.cancel(
                                ctx.activity(), startKey, original.reviewId(), 2, auth(ctx)));
        jdbc.update(
                "UPDATE activity SET scenario_id = ? WHERE activity_id = ?",
                second.candidate().scenarioId(),
                ctx.activity());
        assertConflict(
                "SCENARIO_ALREADY_ASSIGNED",
                () ->
                        transactions.cancel(
                                ctx.activity(),
                                UUID.randomUUID(),
                                original.reviewId(),
                                2,
                                auth(ctx)));
        assertThat(order(original.reviewId())).hasSize(2);
        assertThat(epoch(ctx)).isZero();
    }

    @Test
    void onlyInstructorCanCancelButCancellationDoesNotRequireUnchangedGoalOrDifficulty() {
        var ctx = context(null);
        candidate(ctx);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var other = context(null);
        assertThatThrownBy(
                        () ->
                                transactions.cancel(
                                        ctx.activity(),
                                        UUID.randomUUID(),
                                        original.reviewId(),
                                        1,
                                        auth(other)))
                .isInstanceOf(ResourceNotFoundException.class);
        jdbc.update(
                "UPDATE learning_goal SET content_hash = ? WHERE goal_id = ?",
                "b".repeat(64),
                ctx.goal());
        when(difficulties.requireForActivity(eq(ctx.activity()), any()))
                .thenThrow(
                        new ApiException(
                                org.springframework.http.HttpStatus.CONFLICT,
                                "INITIAL_DIFFICULTY_NOT_READY",
                                "난이도 근거가 없습니다."));
        assertThat(
                        transactions
                                .cancel(
                                        ctx.activity(),
                                        UUID.randomUUID(),
                                        original.reviewId(),
                                        1,
                                        auth(ctx))
                                .outcome())
                .isEqualTo("CANCELLED");
        assertThat(order(original.reviewId())).isEmpty();
        assertThat(epoch(ctx)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateCancellationReturnsOneClosureAndAdvancesEpochOnce() throws Exception {
        var ctx = context(null);
        candidate(ctx);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var results = raceCancellation(ctx, original, true, false);
        assertThat(results.getFirst())
                .isInstanceOf(ScenarioReviewClosure.class)
                .isEqualTo(results.get(1));
        assertThat(epoch(ctx)).isEqualTo(1);
        assertThat(cancelReceiptCount(ctx)).isEqualTo(1);
    }

    @Test
    void concurrentDistinctCancellationsCommitOnlyOneClosure() throws Exception {
        var ctx = context(null);
        candidate(ctx);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var results = raceCancellation(ctx, original, false, false);
        assertThat(results.stream().filter(ScenarioReviewClosure.class::isInstance)).hasSize(1);
        assertThat(
                        results.stream()
                                .filter(ApiException.class::isInstance)
                                .map(ApiException.class::cast))
                .extracting(ApiException::getCode)
                .containsExactly("SCENARIO_REVIEW_NOT_READY");
        assertThat(epoch(ctx)).isEqualTo(1);
        assertThat(cancelReceiptCount(ctx)).isEqualTo(1);
    }

    @Test
    void cancellationAndNextFromSameRevisionCannotBothSucceed() throws Exception {
        var ctx = context(null);
        candidate(ctx);
        candidate(ctx);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var results = raceCancellation(ctx, original, false, true);
        assertThat(results.stream().filter(r -> !(r instanceof ApiException))).hasSize(1);
        assertThat(results.stream().filter(ApiException.class::isInstance)).hasSize(1);
        if (results.stream().anyMatch(ScenarioReviewClosure.class::isInstance)) {
            assertThat(order(original.reviewId())).isEmpty();
            assertThat(epoch(ctx)).isEqualTo(1);
            assertThat(nextReceiptCount(ctx)).isZero();
        } else {
            assertThat(order(original.reviewId())).hasSize(2);
            assertThat(epoch(ctx)).isZero();
            assertThat(cancelReceiptCount(ctx)).isZero();
        }
    }

    @Test
    void cancellationFencesPreviouslyUnrecordedStartThatFinishesItsQueryLate() {
        var ctx = context(null);
        candidate(ctx);
        UUID slowKey = UUID.randomUUID();
        var slow =
                facadeWithSelectionHook(
                        input -> {
                            var competing =
                                    starts.startOrResume(
                                            ctx.activity(), UUID.randomUUID(), auth(ctx));
                            transactions.cancel(
                                    ctx.activity(),
                                    UUID.randomUUID(),
                                    competing.reviewId(),
                                    competing.revision(),
                                    auth(ctx));
                        });
        assertConflict(
                "SCENARIO_REVIEW_CHANGED",
                () -> slow.startOrResume(ctx.activity(), slowKey, auth(ctx)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario_review WHERE activity_id = ?",
                                Long.class,
                                ctx.activity()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario_review_request WHERE activity_id = ? AND request_id = ?",
                                Long.class,
                                ctx.activity(),
                                slowKey))
                .isZero();
        assertThat(epoch(ctx)).isEqualTo(1);
        assertThat(starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx)).candidate())
                .isNotNull();
    }

    @Test
    void cancellationFailureRollsBackClosureScrubbingDeletionAndEpoch() {
        var ctx = context(null);
        candidate(ctx);
        candidate(ctx);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        UUID nextKey = UUID.randomUUID();
        var second = transactions.next(ctx.activity(), nextKey, original.reviewId(), 1, auth(ctx));
        UUID key = UUID.randomUUID();
        String constraint = "test_cancel_failure_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                "ALTER TABLE activity ADD CONSTRAINT "
                        + constraint
                        + " CHECK (activity_id <> '"
                        + ctx.activity()
                        + "' OR scenario_review_epoch = 0) NOT VALID");
        try {
            assertThatThrownBy(
                            () ->
                                    transactions.cancel(
                                            ctx.activity(), key, original.reviewId(), 2, auth(ctx)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx)))
                    .isEqualTo(second);
            assertThat(
                            transactions.next(
                                    ctx.activity(), nextKey, original.reviewId(), 1, auth(ctx)))
                    .isEqualTo(second);
            assertThat(rejectedCount(original.reviewId())).isEqualTo(1);
            assertThat(cancelReceiptCount(ctx)).isZero();
            assertThat(epoch(ctx)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE activity DROP CONSTRAINT " + constraint);
        }
        assertThat(
                        transactions
                                .cancel(ctx.activity(), key, original.reviewId(), 2, auth(ctx))
                                .outcome())
                .isEqualTo("CANCELLED");
        assertThat(epoch(ctx)).isEqualTo(1);
    }

    @Test
    void activityDeletionRemovesTerminalCancellationReceipt() {
        var ctx = context(null);
        UUID scenario = candidate(ctx);
        var original = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        transactions.cancel(ctx.activity(), UUID.randomUUID(), original.reviewId(), 1, auth(ctx));
        jdbc.update("DELETE FROM activity WHERE activity_id = ?", ctx.activity());
        assertNoReview(ctx);
        assertThat(presentations(ctx.instructor(), scenario)).isEqualTo(1);
    }

    private long epoch(Context ctx) {
        return jdbc.queryForObject(
                "SELECT scenario_review_epoch FROM activity WHERE activity_id = ?",
                Long.class,
                ctx.activity());
    }

    private long cancelReceiptCount(Context ctx) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM scenario_review_request WHERE activity_id = ? AND request_kind = 'CANCEL'",
                Long.class,
                ctx.activity());
    }

    private List<Object> raceCancellation(
            Context ctx, ScenarioReviewSnapshot review, boolean sameKey, boolean withNext)
            throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        UUID key = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var calls =
                    List.of(1, 2).stream()
                            .map(
                                    index ->
                                            pool.submit(
                                                    () -> {
                                                        ready.countDown();
                                                        if (!go.await(10, TimeUnit.SECONDS))
                                                            throw new IllegalStateException(
                                                                    "동시 요청 시작 대기 시간 초과");
                                                        try {
                                                            if (withNext && index == 2)
                                                                return (Object)
                                                                        transactions.next(
                                                                                ctx.activity(),
                                                                                UUID.randomUUID(),
                                                                                review.reviewId(),
                                                                                review.revision(),
                                                                                auth(ctx));
                                                            return (Object)
                                                                    transactions.cancel(
                                                                            ctx.activity(),
                                                                            sameKey
                                                                                    ? key
                                                                                    : UUID
                                                                                            .randomUUID(),
                                                                            review.reviewId(),
                                                                            review.revision(),
                                                                            auth(ctx));
                                                        } catch (ApiException failure) {
                                                            return (Object) failure;
                                                        }
                                                    }))
                            .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            return List.of(
                    calls.get(0).get(15, TimeUnit.SECONDS), calls.get(1).get(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void preparationUsesReuseCandidatesAndAutomaticallyGeneratesOnlyAfterExhaustion() {
        var ctx = context(null);
        candidate(ctx);
        candidate(ctx);
        when(llm.complete(any()))
                .thenAnswer(inv -> completion(inv.getArgument(0), generatedJson()));
        var first = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var second =
                preparation.next(
                        ctx.activity(),
                        UUID.randomUUID(),
                        first.reviewId(),
                        first.revision(),
                        auth(ctx));
        verifyNoInteractions(llm);
        UUID nextKey = UUID.randomUUID();
        var generated =
                preparation.next(
                        ctx.activity(), nextKey, second.reviewId(), second.revision(), auth(ctx));
        assertThat(generated.candidate()).isNotNull();
        assertThat(generated.currentPosition()).isEqualTo(3);
        assertThat(generated.revision()).isEqualTo(3);
        assertThat(
                        preparation.next(
                                ctx.activity(),
                                nextKey,
                                second.reviewId(),
                                second.revision(),
                                auth(ctx)))
                .isEqualTo(generated);
        verify(llm, times(1)).complete(any());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM scenario WHERE scenario_id = ?",
                                String.class,
                                generated.candidate().scenarioId()))
                .isEqualTo("DRAFT");
    }

    @Test
    void generationPersistsValidatedDraftAndMicroGoalsOutsideLocksWithoutAssigningActivity() {
        var ctx = context(null);
        when(llm.complete(any()))
                .thenAnswer(
                        inv -> {
                            LlmRequest request = inv.getArgument(0);
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            new TransactionTemplate(manager)
                                    .executeWithoutResult(
                                            ignored -> {
                                                jdbc.queryForObject(
                                                        "SELECT activity_id FROM activity WHERE activity_id = ? FOR UPDATE NOWAIT",
                                                        UUID.class,
                                                        ctx.activity());
                                                jdbc.queryForObject(
                                                        "SELECT review_id FROM scenario_review WHERE activity_id = ? FOR UPDATE NOWAIT",
                                                        UUID.class,
                                                        ctx.activity());
                                            });
                            assertThat(request.userPrompt())
                                    .contains("차례를 기다려요", "L2")
                                    .doesNotContain(
                                            "검사 아동",
                                            ctx.child().toString(),
                                            ctx.instructor().toString());
                            return completion(request, generatedJson());
                        });
        UUID key = UUID.randomUUID();
        var generated = preparation.prepare(ctx.activity(), key, auth(ctx));
        UUID id = generated.candidate().scenarioId();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM scenario WHERE scenario_id = ?",
                                String.class,
                                id))
                .isEqualTo("DRAFT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario_micro_goal WHERE scenario_id = ?",
                                Long.class,
                                id))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT opening_prompts ->> 'S0' FROM scenario WHERE scenario_id = ?",
                                String.class,
                                id))
                .isEqualTo("친구에게 무슨 일이 있었어?");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT scenario_id FROM activity WHERE activity_id = ?",
                                UUID.class,
                                ctx.activity()))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario_approval WHERE scenario_id = ?",
                                Long.class,
                                id))
                .isZero();
        assertThat(queries.select(ctx.activity(), auth(ctx)).candidates()).isEmpty();
        assertThat(preparation.prepare(ctx.activity(), key, auth(ctx))).isEqualTo(generated);
        assertThat(preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx)))
                .isEqualTo(generated);
        verify(llm, times(1)).complete(any());
    }

    @Test
    void rejectionOfGeneratedDraftAutomaticallyGeneratesAgainWithPreviousContent() {
        var ctx = context(null);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(llm.complete(any()))
                .thenAnswer(
                        inv -> {
                            LlmRequest request = inv.getArgument(0);
                            if (calls.incrementAndGet() == 2)
                                assertThat(request.userPrompt())
                                        .contains("previous_scenario", "넘어져서 우는 친구", "직전 제안");
                            return completion(
                                    request, generatedJson().replace("넘어져서 우는 친구", "새롭게 제안한 상황"));
                        });
        var first = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        // 이전 초안의 제목이 다음 요청에 전달되는지 확인한다.
        jdbc.update(
                "UPDATE scenario SET title = '넘어져서 우는 친구' WHERE scenario_id = ?",
                first.candidate().scenarioId());
        var second =
                preparation.next(
                        ctx.activity(),
                        UUID.randomUUID(),
                        first.reviewId(),
                        first.revision(),
                        auth(ctx));
        assertThat(second.candidate().scenarioId()).isNotEqualTo(first.candidate().scenarioId());
        assertThat(second.revision()).isEqualTo(2);
        assertThat(rejectedCount(first.reviewId())).isEqualTo(1);
        assertThat(second.candidateCount()).isEqualTo(2);
        verify(llm, times(2)).complete(any());
    }

    @Test
    void malformedGenerationIsNotSavedAndExplicitRetryDoesNotAdvanceReviewAgain() {
        var ctx = context(null);
        long drafts = draftCount();
        when(llm.complete(any())).thenAnswer(inv -> completion(inv.getArgument(0), "{}"));
        UUID key = UUID.randomUUID();
        assertConflict(
                "SCENARIO_GENERATION_FAILED",
                () -> preparation.prepare(ctx.activity(), key, auth(ctx)));
        assertThat(draftCount()).isEqualTo(drafts);
        verify(llm, times(1)).complete(any());
        doAnswer(inv -> completion(inv.getArgument(0), generatedJson())).when(llm).complete(any());
        var retried = preparation.prepare(ctx.activity(), key, auth(ctx));
        assertThat(retried.revision()).isEqualTo(1);
        assertThat(retried.candidateCount()).isEqualTo(1);
        verify(llm, times(2)).complete(any());
    }

    @Test
    void providerTimeoutRemainsFailureWithoutDraftOrAutomaticRetryLoop() {
        var ctx = context(null);
        long drafts = draftCount();
        when(llm.complete(any()))
                .thenAnswer(
                        inv ->
                                new AiCallResult.Failure<>(
                                        new AiFailure(AiFailureType.TIMEOUT, null),
                                        metadata(inv.getArgument(0))));
        assertThatThrownBy(() -> preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.getCode()).isEqualTo("SCENARIO_GENERATION_FAILED");
                            assertThat(error.getStatus())
                                    .isEqualTo(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT);
                        });
        assertThat(draftCount()).isEqualTo(drafts);
        verify(llm, times(1)).complete(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCEL", "INPUT_CHANGED", "ACTIVITY_DELETED"})
    void lateGenerationDoesNotSaveAfterReviewBecomesInvalid(String change) {
        var ctx = context(null);
        long drafts = draftCount();
        when(llm.complete(any()))
                .thenAnswer(
                        inv -> {
                            if (change.equals("CANCEL")) {
                                UUID review =
                                        jdbc.queryForObject(
                                                "SELECT review_id FROM scenario_review WHERE activity_id = ?",
                                                UUID.class,
                                                ctx.activity());
                                transactions.cancel(
                                        ctx.activity(), UUID.randomUUID(), review, 1, auth(ctx));
                            } else if (change.equals("INPUT_CHANGED")) {
                                jdbc.update(
                                        "UPDATE learning_goal SET content_hash = ? WHERE goal_id = ?",
                                        "b".repeat(64),
                                        ctx.goal());
                            } else
                                jdbc.update(
                                        "DELETE FROM activity WHERE activity_id = ?",
                                        ctx.activity());
                            return completion(inv.getArgument(0), generatedJson());
                        });
        assertThatThrownBy(() -> preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx)))
                .isInstanceOf(ApiException.class);
        assertThat(draftCount()).isEqualTo(drafts);
    }

    @Test
    void overlappingGenerationRequestsShareOneProviderCallAndOneSavedDraft() throws Exception {
        var ctx = context(null);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(llm.complete(any()))
                .thenAnswer(
                        inv -> {
                            entered.countDown();
                            if (!release.await(10, TimeUnit.SECONDS))
                                throw new IllegalStateException("생성 결과 대기 시간 초과");
                            return completion(inv.getArgument(0), generatedJson());
                        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () ->
                                    preparation.prepare(
                                            ctx.activity(), UUID.randomUUID(), auth(ctx)));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var second =
                    pool.submit(
                            () ->
                                    preparation.prepare(
                                            ctx.activity(), UUID.randomUUID(), auth(ctx)));
            release.countDown();
            var result = first.get(15, TimeUnit.SECONDS);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(result);
            assertThat(order(result.reviewId())).hasSize(1);
        }
        verify(llm, times(1)).complete(any());
    }

    @Test
    void draftSavingFailureRollsBackDraftMicroGoalsAndCandidatePosition() {
        var ctx = context(null);
        long drafts = draftCount();
        long goals = jdbc.queryForObject("SELECT count(*) FROM scenario_micro_goal", Long.class);
        when(llm.complete(any()))
                .thenAnswer(inv -> completion(inv.getArgument(0), generatedJson()));
        UUID key = UUID.randomUUID();
        String constraint =
                "test_generated_failure_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                "ALTER TABLE scenario_presentation_daily ADD CONSTRAINT "
                        + constraint
                        + " CHECK (instructor_id <> '"
                        + ctx.instructor()
                        + "') NOT VALID");
        try {
            assertThatThrownBy(() -> preparation.prepare(ctx.activity(), key, auth(ctx)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(draftCount()).isEqualTo(drafts);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM scenario_micro_goal", Long.class))
                    .isEqualTo(goals);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT current_position IS NULL FROM scenario_review WHERE activity_id = ?",
                                    Boolean.class,
                                    ctx.activity()))
                    .isTrue();
        } finally {
            jdbc.execute("ALTER TABLE scenario_presentation_daily DROP CONSTRAINT " + constraint);
        }
        assertThat(preparation.prepare(ctx.activity(), key, auth(ctx)).candidate()).isNotNull();
        verify(llm, times(2)).complete(any());
    }

    @Test
    void cancellationDeletesUnapprovedGeneratedDraftsButPreservesApprovedReuseSource() {
        var ctx = context(null);
        UUID approved = candidate(ctx);
        when(llm.complete(any()))
                .thenAnswer(inv -> completion(inv.getArgument(0), generatedJson()));
        var reused = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var firstDraft =
                preparation.next(
                        ctx.activity(),
                        UUID.randomUUID(),
                        reused.reviewId(),
                        reused.revision(),
                        auth(ctx));
        var secondDraft =
                preparation.next(
                        ctx.activity(),
                        UUID.randomUUID(),
                        firstDraft.reviewId(),
                        firstDraft.revision(),
                        auth(ctx));
        transactions.cancel(
                ctx.activity(),
                UUID.randomUUID(),
                secondDraft.reviewId(),
                secondDraft.revision(),
                auth(ctx));
        for (UUID id :
                List.of(
                        firstDraft.candidate().scenarioId(),
                        secondDraft.candidate().scenarioId())) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM scenario WHERE scenario_id = ?",
                                    Long.class,
                                    id))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM scenario_micro_goal WHERE scenario_id = ?",
                                    Long.class,
                                    id))
                    .isZero();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM scenario WHERE scenario_id = ?",
                                String.class,
                                approved))
                .isEqualTo("APPROVED");
        assertThat(presentations(ctx.instructor(), approved)).isEqualTo(1);
    }

    @Test
    void activityDeletionAlsoCleansItsUnapprovedGeneratedDraft() {
        var ctx = context(null);
        when(llm.complete(any()))
                .thenAnswer(inv -> completion(inv.getArgument(0), generatedJson()));
        var generated = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        jdbc.update("DELETE FROM activity WHERE activity_id = ?", ctx.activity());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario WHERE scenario_id = ?",
                                Long.class,
                                generated.candidate().scenarioId()))
                .isZero();
        assertNoReview(ctx);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHARACTERS", "FORBIDDEN_UNICODE"})
    void rejectsGeneratedContentThatViolatesRequestedDeterministicConditions(String type) {
        var ctx = context(null);
        if (type.equals("CHARACTERS"))
            jdbc.update(
                    "UPDATE learning_goal SET characters_json = '[{\"role\":\"요청한 다른 역할\"}]' WHERE goal_id = ?",
                    ctx.goal());
        else
            jdbc.update(
                    "UPDATE learning_goal SET forbidden_expressions_json = '[\"badword\"]' WHERE goal_id = ?",
                    ctx.goal());
        String body =
                type.equals("FORBIDDEN_UNICODE")
                        ? generatedJson().replace("넘어져서 우는 친구", "B\u2003adword")
                        : generatedJson();
        when(llm.complete(any())).thenAnswer(inv -> completion(inv.getArgument(0), body));
        long drafts = draftCount();
        assertConflict(
                "SCENARIO_GENERATION_INVALID_OUTPUT",
                () -> preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx)));
        assertThat(draftCount()).isEqualTo(drafts);
    }

    @Test
    void missingProviderConfigurationFailsClosedOnlyWhenGenerationIsNeeded() {
        var ctx = context(null);
        var snapshot = starts.startOrResume(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var context = transactions.prepareGeneration(snapshot, auth(ctx));
        var noProvider =
                new ScenarioGenerationService(
                        new DefaultListableBeanFactory().getBeanProvider(LlmProvider.class), json);
        assertConflict(
                "SCENARIO_GENERATOR_UNAVAILABLE",
                () -> noProvider.generate(context, UUID.randomUUID()));
        verifyNoInteractions(llm);
    }

    @Test
    void confirmsGeneratedCandidateAtomicallyAndStartsRoleplayUsingExplicitOpeningFocus() {
        var ctx = context(null);
        when(llm.complete(any()))
                .thenAnswer(
                        inv ->
                                completion(
                                        inv.getArgument(0),
                                        generatedJson()
                                                .replace(
                                                        "\"S1\": \"MG-01\"", "\"S1\": \"MG-02\"")));
        var candidate = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        UUID id = candidate.candidate().scenarioId();
        var closed = confirm(ctx, candidate, UUID.randomUUID());
        assertThat(closed.outcome()).isEqualTo("CONFIRMED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT scenario_id FROM activity WHERE activity_id = ?",
                                UUID.class,
                                ctx.activity()))
                .isEqualTo(id);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM scenario WHERE scenario_id = ?",
                                String.class,
                                id))
                .isEqualTo("APPROVED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT class_id FROM scenario WHERE scenario_id = ?",
                                UUID.class,
                                id))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT goal_content_hash FROM scenario WHERE scenario_id = ?",
                                String.class,
                                id))
                .isNull();
        assertThat(approvals.findOwnedApproved(id, ctx.instructor())).isPresent();
        assertThat(order(candidate.reviewId())).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM activity_scenario_approval WHERE activity_id = ?",
                                Long.class,
                                ctx.activity()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reuse_count FROM scenario_presentation_stat WHERE instructor_id = ? AND scenario_id = ?",
                                Long.class,
                                ctx.instructor(),
                                id))
                .isZero();
        assertThat(scenarioRepository.findAssignedApprovedVersion(ctx.activity(), id, "L2"))
                .isPresent();
        Authentication child =
                new UsernamePasswordAuthenticationToken(
                        new com.neuringo.neuringobe.child.security.ChildPrincipal(ctx.child()),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CHILD")));
        roleplay.startForDelivery(ctx.activity(), UUID.randomUUID(), child);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT current_micro_goal_id FROM roleplay_session WHERE activity_id = ?",
                                UUID.class,
                                ctx.activity()))
                .isEqualTo(
                        jdbc.queryForObject(
                                "SELECT micro_goal_id FROM scenario_opening_focus WHERE scenario_id = ? AND support_level = 'S1'",
                                UUID.class,
                                id));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT m.order_no FROM scenario_opening_focus f JOIN scenario_micro_goal m USING (scenario_id, micro_goal_id) WHERE f.scenario_id = ? AND f.support_level = 'S1'",
                                Integer.class,
                                id))
                .isEqualTo(2);
    }

    @Test
    void confirmsReuseForDifferentGoalHashWithoutChangingOriginalApprovalAndCountsReuseOnce() {
        var original = context(null);
        var source = approvedSource(original);
        var approved =
                approvals
                        .findOwnedApproved(source.candidate().scenarioId(), original.instructor())
                        .orElseThrow();
        var ctx = context(original.instructor());
        var reused = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        assertThat(reused.candidate().scenarioId()).isEqualTo(source.candidate().scenarioId());
        UUID key = UUID.randomUUID();
        var result = confirm(ctx, reused, key);
        assertThat(confirm(ctx, reused, key)).isEqualTo(result);
        assertThat(approvals.findOwnedApproved(reused.candidate().scenarioId(), ctx.instructor()))
                .contains(approved);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reuse_count FROM scenario_presentation_stat WHERE instructor_id = ? AND scenario_id = ?",
                                Long.class,
                                ctx.instructor(),
                                reused.candidate().scenarioId()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sum(reuse_count) FROM scenario_presentation_daily WHERE instructor_id = ? AND scenario_id = ?",
                                Long.class,
                                ctx.instructor(),
                                reused.candidate().scenarioId()))
                .isEqualTo(1);
        assertThat(
                        scenarioRepository.findAssignedApprovedVersion(
                                ctx.activity(), reused.candidate().scenarioId(), "L2"))
                .isPresent();
        assertThat(
                        scenarioRepository.findAssignedApprovedVersion(
                                ctx.activity(), reused.candidate().scenarioId(), "L1"))
                .isEmpty();
        assertConflict(
                "IDEMPOTENCY_KEY_REUSED",
                () ->
                        confirmation.confirm(
                                ctx.activity(),
                                key,
                                reused.reviewId(),
                                reused.revision() + 1,
                                reused.candidate().scenarioId(),
                                1,
                                auth(ctx)));
        verify(llm, times(1)).complete(any());
    }

    @Test
    void concurrentActivitiesCannotAssignSameScenarioIdToSameStudent() throws Exception {
        var owner = context(null);
        approvedSource(owner);
        var firstCtx = context(owner.instructor());
        var secondCtx = anotherActivityForSameChild(firstCtx);
        var first = preparation.prepare(firstCtx.activity(), UUID.randomUUID(), auth(firstCtx));
        var second = preparation.prepare(secondCtx.activity(), UUID.randomUUID(), auth(secondCtx));
        var firstInput =
                confirmationPreparation.prepare(
                        firstCtx.activity(),
                        first.reviewId(),
                        first.revision(),
                        first.candidate().scenarioId(),
                        1,
                        auth(firstCtx));
        var secondInput =
                confirmationPreparation.prepare(
                        secondCtx.activity(),
                        second.reviewId(),
                        second.revision(),
                        second.candidate().scenarioId(),
                        1,
                        auth(secondCtx));
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var calls =
                    List.of(1, 2).stream()
                            .map(
                                    index ->
                                            pool.submit(
                                                    () -> {
                                                        var ctx = index == 1 ? firstCtx : secondCtx;
                                                        var snapshot = index == 1 ? first : second;
                                                        var input =
                                                                index == 1
                                                                        ? firstInput
                                                                        : secondInput;
                                                        ready.countDown();
                                                        if (!go.await(10, TimeUnit.SECONDS))
                                                            throw new IllegalStateException(
                                                                    "동시 배정 시작 대기 시간 초과");
                                                        try {
                                                            return (Object)
                                                                    transactions.confirm(
                                                                            input,
                                                                            UUID.randomUUID(),
                                                                            snapshot.reviewId(),
                                                                            snapshot.revision(),
                                                                            snapshot.candidate()
                                                                                    .scenarioId(),
                                                                            1,
                                                                            auth(ctx));
                                                        } catch (ApiException error) {
                                                            return (Object) error;
                                                        }
                                                    }))
                            .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var results =
                    List.of(
                            calls.get(0).get(15, TimeUnit.SECONDS),
                            calls.get(1).get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(ScenarioReviewClosure.class::isInstance)).hasSize(1);
            assertThat(
                            results.stream()
                                    .filter(ApiException.class::isInstance)
                                    .map(ApiException.class::cast))
                    .extracting(ApiException::getCode)
                    .containsExactly("SCENARIO_ALREADY_USED");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM activity WHERE child_id = ? AND scenario_id IS NOT NULL",
                                Long.class,
                                firstCtx.child()))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONTENT", "INPUT", "FOCUS"})
    void confirmationRechecksContentInputAndOpeningMappingAfterPreflight(String changed) {
        var ctx = context(null);
        when(llm.complete(any()))
                .thenAnswer(inv -> completion(inv.getArgument(0), generatedJson()));
        var candidate = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var input =
                confirmationPreparation.prepare(
                        ctx.activity(),
                        candidate.reviewId(),
                        1,
                        candidate.candidate().scenarioId(),
                        1,
                        auth(ctx));
        if (changed.equals("CONTENT"))
            jdbc.update(
                    "UPDATE scenario SET summary = '바뀐 내용' WHERE scenario_id = ?",
                    candidate.candidate().scenarioId());
        else if (changed.equals("INPUT"))
            jdbc.update(
                    "UPDATE learning_goal SET content_hash = ? WHERE goal_id = ?",
                    "b".repeat(64),
                    ctx.goal());
        else
            jdbc.update(
                    "DELETE FROM scenario_opening_focus WHERE scenario_id = ?",
                    candidate.candidate().scenarioId());
        assertThatThrownBy(
                        () ->
                                transactions.confirm(
                                        input,
                                        UUID.randomUUID(),
                                        candidate.reviewId(),
                                        1,
                                        candidate.candidate().scenarioId(),
                                        1,
                                        auth(ctx)))
                .isInstanceOf(ApiException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT scenario_id FROM activity WHERE activity_id = ?",
                                UUID.class,
                                ctx.activity()))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM scenario WHERE scenario_id = ?",
                                String.class,
                                candidate.candidate().scenarioId()))
                .isEqualTo("DRAFT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM activity_scenario_approval WHERE activity_id = ?",
                                Long.class,
                                ctx.activity()))
                .isZero();
    }

    @Test
    void failedConfirmationRollsBackApprovalOwnershipAssignmentProofAndReviewClosure() {
        var ctx = context(null);
        when(llm.complete(any()))
                .thenAnswer(inv -> completion(inv.getArgument(0), generatedJson()));
        var candidate = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        UUID key = UUID.randomUUID();
        String constraint = "test_confirm_failure_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                "ALTER TABLE activity_scenario_approval ADD CONSTRAINT "
                        + constraint
                        + " CHECK (activity_id <> '"
                        + ctx.activity()
                        + "') NOT VALID");
        try {
            assertThatThrownBy(() -> confirm(ctx, candidate, key))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM scenario WHERE scenario_id = ?",
                                    String.class,
                                    candidate.candidate().scenarioId()))
                    .isEqualTo("DRAFT");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT scenario_id FROM activity WHERE activity_id = ?",
                                    UUID.class,
                                    ctx.activity()))
                    .isNull();
            assertThat(
                            approvals.findOwnedApproved(
                                    candidate.candidate().scenarioId(), ctx.instructor()))
                    .isEmpty();
            assertThat(order(candidate.reviewId())).hasSize(1);
            assertThat(epoch(ctx)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE activity_scenario_approval DROP CONSTRAINT " + constraint);
        }
        assertThat(confirm(ctx, candidate, key).outcome()).isEqualTo("CONFIRMED");
        assertThat(epoch(ctx)).isEqualTo(1);
    }

    @Test
    void confirmationRejectsStaleCandidateCancelledReviewAndDifferentInstructor() {
        var ctx = context(null);
        when(llm.complete(any()))
                .thenAnswer(inv -> completion(inv.getArgument(0), generatedJson()));
        var candidate = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var stranger = context(null);
        assertThatThrownBy(
                        () ->
                                confirmation.confirm(
                                        ctx.activity(),
                                        UUID.randomUUID(),
                                        candidate.reviewId(),
                                        1,
                                        candidate.candidate().scenarioId(),
                                        1,
                                        auth(stranger)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertConflict(
                "SCENARIO_REVIEW_CHANGED",
                () ->
                        confirmation.confirm(
                                ctx.activity(),
                                UUID.randomUUID(),
                                candidate.reviewId(),
                                2,
                                candidate.candidate().scenarioId(),
                                1,
                                auth(ctx)));
        assertConflict(
                "SCENARIO_REVIEW_CHANGED",
                () ->
                        confirmation.confirm(
                                ctx.activity(),
                                UUID.randomUUID(),
                                candidate.reviewId(),
                                1,
                                UUID.randomUUID(),
                                1,
                                auth(ctx)));
        transactions.cancel(ctx.activity(), UUID.randomUUID(), candidate.reviewId(), 1, auth(ctx));
        assertConflict("SCENARIO_REVIEW_CHANGED", () -> confirm(ctx, candidate, UUID.randomUUID()));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT scenario_id FROM activity WHERE activity_id = ?",
                                UUID.class,
                                ctx.activity()))
                .isNull();
    }

    @Test
    void alteredGoalCannotUseApprovalProofAsAHashBypass() {
        var ctx = context(null);
        var candidate = approvedSource(ctx);
        jdbc.update(
                "UPDATE learning_goal SET content_hash = ? WHERE goal_id = ?",
                "b".repeat(64),
                ctx.goal());
        assertThat(
                        scenarioRepository.findAssignedApprovedVersion(
                                ctx.activity(), candidate.candidate().scenarioId(), "L2"))
                .isEmpty();
        assertThat(
                        scenarioRepository.findApprovedOpening(
                                ctx.activity(), candidate.candidate().scenarioId(), 1, "S1"))
                .isEmpty();
    }

    private ScenarioReviewSnapshot approvedSource(Context ctx) {
        when(llm.complete(any()))
                .thenAnswer(inv -> completion(inv.getArgument(0), generatedJson()));
        var candidate = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        confirm(ctx, candidate, UUID.randomUUID());
        return candidate;
    }

    private ScenarioReviewClosure confirm(Context ctx, ScenarioReviewSnapshot snapshot, UUID key) {
        return confirmation.confirm(
                ctx.activity(),
                key,
                snapshot.reviewId(),
                snapshot.revision(),
                snapshot.candidate().scenarioId(),
                snapshot.candidate().version(),
                auth(ctx));
    }

    private Context anotherActivityForSameChild(Context original) {
        UUID goal = UUID.randomUUID();
        UUID activity = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO learning_goal (goal_id, child_id, instructor_id, title, content_hash, created_at) VALUES (?, ?, ?, '차례를 기다려요', ?, now())",
                goal,
                original.child(),
                original.instructor(),
                goal.toString().replace("-", "").repeat(2));
        jdbc.update(
                "INSERT INTO activity (activity_id, child_id, goal_id, activity_type, assigned_at) VALUES (?, ?, ?, 'ROLEPLAY', now())",
                activity,
                original.child(),
                goal);
        when(difficulties.requireForActivity(eq(activity), any()))
                .thenReturn(
                        new InitialDifficultyEvidence(
                                activity,
                                original.child(),
                                goal,
                                10,
                                5,
                                "L2",
                                "S1",
                                false,
                                null,
                                "INITIAL_QUIZ_V2"));
        return new Context(original.instructor(), original.child(), goal, activity);
    }

    @Test
    void storedGeneratedScenarioRoundTripsThroughCurrentOutputSchema() {
        var ctx = context(null);
        when(llm.complete(any()))
                .thenAnswer(inv -> completion(inv.getArgument(0), generatedJson()));
        var candidate = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var stored = draftRepository.read(candidate.candidate().scenarioId());
        var factory =
                new com.neuringo.neuringobe.ai.application.prompt.ScenarioPromptFactory(json, null);
        var input =
                new com.neuringo.neuringobe.ai.application.prompt.ScenarioGenerationInput(
                        new com.neuringo.neuringobe.ai.application.prompt.ScenarioGenerationInput
                                .TeacherInput(
                                "차례를 기다려요", null, null, List.of(), List.of(), List.of()),
                        com.neuringo.neuringobe.ai.application.prompt.ScenarioGenerationInput
                                .Difficulty.of("L2"),
                        null);
        var trace =
                new com.neuringo.neuringobe.ai.application.model.AiTraceContext(
                        UUID.randomUUID(),
                        ctx.activity(),
                        UUID.randomUUID(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        factory.generation(
                        trace,
                        new com.neuringo.neuringobe.ai.application.model.AiAttemptContext(0, 1, 0),
                        1,
                        input)
                .parser()
                .parse(json.writeValueAsString(stored));
    }

    @Test
    void editedReuseCreatesApprovedNewVersionWithoutChangingOriginalOrItsAssignment() {
        var owner = context(null);
        var original = approvedSource(owner);
        UUID source = original.candidate().scenarioId();
        String before = confirmationRepository.contentBody(source);
        var sourceApproval = approvals.findOwnedApproved(source, owner.instructor()).orElseThrow();
        var ctx = context(owner.instructor());
        var offered = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        UUID goal =
                jdbc.queryForObject(
                        "SELECT micro_goal_id FROM scenario_micro_goal WHERE scenario_id = ? AND order_no = 2",
                        UUID.class,
                        source);
        var edits =
                new ScenarioEdits(
                        "강사가 다듬은 이야기",
                        "놀이터에서 넘어진 친구에게 무슨 일이 있었는지 알아본다.",
                        List.of(
                                new ScenarioEdits.MicroGoalEdit(
                                        goal, "친구의 표정을 설명한다.", List.of("친구의 표정을 말한다."))));

        assertThat(confirmEdited(ctx, offered, UUID.randomUUID(), edits).outcome())
                .isEqualTo("CONFIRMED");

        UUID assigned = assigned(ctx);
        assertThat(assigned).isNotEqualTo(source);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT source_scenario_id FROM scenario WHERE scenario_id = ?",
                                UUID.class,
                                assigned))
                .isEqualTo(source);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT scenario_version FROM scenario WHERE scenario_id = ?",
                                Integer.class,
                                assigned))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT origin FROM scenario WHERE scenario_id = ?",
                                String.class,
                                assigned))
                .isEqualTo("INSTRUCTOR_EDITED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT scenario_source FROM activity WHERE activity_id = ?",
                                String.class,
                                ctx.activity()))
                .isEqualTo("EDITED");
        assertThat(confirmationRepository.contentBody(source)).isEqualTo(before);
        assertThat(approvals.findOwnedApproved(source, owner.instructor()))
                .contains(sourceApproval);
        assertThat(assigned(owner)).isEqualTo(source);
        assertThat(approvals.findOwnedApproved(assigned, ctx.instructor())).isPresent();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reuse_count FROM scenario_presentation_stat WHERE instructor_id = ? AND scenario_id = ?",
                                Long.class,
                                ctx.instructor(),
                                source))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT description FROM scenario_micro_goal WHERE scenario_id = ? AND order_no = 2",
                                String.class,
                                assigned))
                .isEqualTo("친구의 표정을 설명한다.");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario_micro_goal a JOIN scenario_micro_goal b USING (micro_goal_id) WHERE a.scenario_id = ? AND b.scenario_id = ?",
                                Long.class,
                                source,
                                assigned))
                .isZero();
        assertThat(
                        scenarioRepository
                                .findAssignedApprovedVersion(ctx.activity(), assigned, "L2")
                                .orElseThrow()
                                .version())
                .isEqualTo(2);
        verify(llm, times(1)).complete(any());
    }

    @Test
    void editedGeneratedCandidateKeepsExplicitOpeningMappingAndUnapprovedSourceLineage() {
        var ctx = context(null);
        when(llm.complete(any()))
                .thenAnswer(
                        inv ->
                                completion(
                                        inv.getArgument(0),
                                        generatedJson()
                                                .replace(
                                                        "\"S1\": \"MG-01\"", "\"S1\": \"MG-02\"")));
        var offered = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        UUID source = offered.candidate().scenarioId();
        String before = confirmationRepository.contentBody(source);
        confirmEdited(
                ctx,
                offered,
                UUID.randomUUID(),
                new ScenarioEdits("수정해서 확정한 이야기", null, List.of()));
        UUID id = assigned(ctx);
        assertThat(confirmationRepository.contentBody(source)).isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM scenario WHERE scenario_id = ?",
                                String.class,
                                source))
                .isEqualTo("DRAFT");
        assertThat(approvals.findOwnedApproved(source, ctx.instructor())).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM scenario WHERE scenario_id = ?",
                                String.class,
                                id))
                .isEqualTo("APPROVED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT m.order_no FROM scenario_opening_focus f JOIN scenario_micro_goal m USING (scenario_id, micro_goal_id) WHERE f.scenario_id = ? AND f.support_level = 'S1'",
                                Integer.class,
                                id))
                .isEqualTo(2);
        Authentication child =
                new UsernamePasswordAuthenticationToken(
                        new com.neuringo.neuringobe.child.security.ChildPrincipal(ctx.child()),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CHILD")));
        roleplay.startForDelivery(ctx.activity(), UUID.randomUUID(), child);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT current_micro_goal_id FROM roleplay_session WHERE activity_id = ?",
                                UUID.class,
                                ctx.activity()))
                .isEqualTo(
                        jdbc.queryForObject(
                                "SELECT micro_goal_id FROM scenario_opening_focus WHERE scenario_id = ? AND support_level = 'S1'",
                                UUID.class,
                                id));
        verify(llm, times(1)).complete(any());
    }

    @Test
    void editedRequestReplayUsesCanonicalGoalOrderAndRejectsDifferentEdits() {
        var ctx = context(null);
        when(llm.complete(any()))
                .thenAnswer(inv -> completion(inv.getArgument(0), generatedJson()));
        var offered = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var goals =
                jdbc.query(
                        "SELECT micro_goal_id FROM scenario_micro_goal WHERE scenario_id = ? ORDER BY order_no",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        offered.candidate().scenarioId());
        var first = new ScenarioEdits.MicroGoalEdit(goals.get(0), "친구에게 일어난 일을 설명한다.", null);
        var second = new ScenarioEdits.MicroGoalEdit(goals.get(1), "친구의 표정을 설명한다.", null);
        UUID key = UUID.randomUUID();
        var result =
                confirmEdited(
                        ctx, offered, key, new ScenarioEdits(null, null, List.of(first, second)));
        UUID id = assigned(ctx);
        assertThat(
                        confirmEdited(
                                ctx,
                                offered,
                                key,
                                new ScenarioEdits(null, null, List.of(second, first))))
                .isEqualTo(result);
        assertConflict(
                "IDEMPOTENCY_KEY_REUSED",
                () ->
                        confirmEdited(
                                ctx, offered, key, new ScenarioEdits("다른 내용", null, List.of())));
        assertConflict("IDEMPOTENCY_KEY_REUSED", () -> confirm(ctx, offered, key));
        assertThat(assigned(ctx)).isEqualTo(id);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario WHERE source_scenario_id = ?",
                                Long.class,
                                offered.candidate().scenarioId()))
                .isEqualTo(1);
    }

    @Test
    void noOpEditConfirmsExistingVersionAndCountsActualReuse() {
        var owner = context(null);
        var source = approvedSource(owner);
        var ctx = context(owner.instructor());
        var offered = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var title =
                jdbc.queryForObject(
                        "SELECT title FROM scenario WHERE scenario_id = ?",
                        String.class,
                        source.candidate().scenarioId());
        confirmEdited(
                ctx,
                offered,
                UUID.randomUUID(),
                new ScenarioEdits("  " + title + "  ", null, List.of()));
        assertThat(assigned(ctx)).isEqualTo(source.candidate().scenarioId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario WHERE source_scenario_id = ?",
                                Long.class,
                                source.candidate().scenarioId()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reuse_count FROM scenario_presentation_stat WHERE instructor_id = ? AND scenario_id = ?",
                                Long.class,
                                ctx.instructor(),
                                assigned(ctx)))
                .isEqualTo(1);
    }

    @Test
    void copiedNewVersionCanBeAssignedEvenWhenOriginalWasAssignedAfterOffer() {
        var owner = context(null);
        var source = approvedSource(owner);
        var ctx = context(owner.instructor());
        var offered = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var other = anotherActivityForSameChild(ctx);
        var competing = preparation.prepare(other.activity(), UUID.randomUUID(), auth(other));
        confirm(other, competing, UUID.randomUUID());
        assertThat(assigned(other)).isEqualTo(source.candidate().scenarioId());
        confirmEdited(
                ctx,
                offered,
                UUID.randomUUID(),
                new ScenarioEdits("새 버전으로 확정한 이야기", null, List.of()));
        assertThat(assigned(ctx)).isNotEqualTo(source.candidate().scenarioId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT source_scenario_id FROM scenario WHERE scenario_id = ?",
                                UUID.class,
                                assigned(ctx)))
                .isEqualTo(source.candidate().scenarioId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reuse_count FROM scenario_presentation_stat WHERE instructor_id = ? AND scenario_id = ?",
                                Long.class,
                                ctx.instructor(),
                                source.candidate().scenarioId()))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UNKNOWN_GOAL",
                "DUPLICATE_GOAL",
                "EMPTY_EVIDENCE",
                "FACT_AS_EVIDENCE",
                "FORBIDDEN",
                "LONG_TITLE"
            })
    void rejectsInvalidEditsWithoutCreatingOrApprovingAnyVersion(String type) {
        var ctx = context(null);
        jdbc.update(
                "UPDATE learning_goal SET forbidden_expressions_json = '[\"금지어\"]' WHERE goal_id = ?",
                ctx.goal());
        when(llm.complete(any()))
                .thenAnswer(inv -> completion(inv.getArgument(0), generatedJson()));
        var offered = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        UUID source = offered.candidate().scenarioId();
        UUID goal =
                jdbc.queryForObject(
                        "SELECT micro_goal_id FROM scenario_micro_goal WHERE scenario_id = ? AND order_no = 1",
                        UUID.class,
                        source);
        var patch = new ScenarioEdits.MicroGoalEdit(goal, "친구에게 일어난 일을 설명한다.", null);
        ScenarioEdits edits =
                switch (type) {
                    case "UNKNOWN_GOAL" ->
                            new ScenarioEdits(
                                    null,
                                    null,
                                    List.of(
                                            new ScenarioEdits.MicroGoalEdit(
                                                    UUID.randomUUID(), "새 설명", null)));
                    case "DUPLICATE_GOAL" -> new ScenarioEdits(null, null, List.of(patch, patch));
                    case "EMPTY_EVIDENCE" ->
                            new ScenarioEdits(
                                    null,
                                    null,
                                    List.of(
                                            new ScenarioEdits.MicroGoalEdit(
                                                    goal, null, List.of())));
                    case "FACT_AS_EVIDENCE" ->
                            new ScenarioEdits(
                                    null,
                                    null,
                                    List.of(
                                            new ScenarioEdits.MicroGoalEdit(
                                                    goal, null, List.of("친구가 넘어졌다."))));
                    case "FORBIDDEN" -> new ScenarioEdits("금지어가 들어간 이야기", null, List.of());
                    default -> new ScenarioEdits("가".repeat(100), null, List.of());
                };
        assertConflict(
                "INVALID_SCENARIO_EDITS",
                () -> confirmEdited(ctx, offered, UUID.randomUUID(), edits));
        assertThat(assigned(ctx)).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario WHERE source_scenario_id = ?",
                                Long.class,
                                source))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM scenario WHERE scenario_id = ?",
                                String.class,
                                source))
                .isEqualTo("DRAFT");
        assertThat(order(offered.reviewId())).hasSize(1);
    }

    @Test
    void editedConfirmationFailureRollsBackNewVersionGoalsFocusAndApproval() {
        var ctx = context(null);
        when(llm.complete(any()))
                .thenAnswer(inv -> completion(inv.getArgument(0), generatedJson()));
        var offered = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        UUID source = offered.candidate().scenarioId();
        String body = confirmationRepository.contentBody(source);
        String constraint = "test_edited_failure_" + UUID.randomUUID().toString().replace("-", "");
        var edits = new ScenarioEdits("수정한 이야기", null, List.of());
        UUID key = UUID.randomUUID();
        jdbc.execute(
                "ALTER TABLE activity_scenario_approval ADD CONSTRAINT "
                        + constraint
                        + " CHECK (activity_id <> '"
                        + ctx.activity()
                        + "') NOT VALID");
        try {
            assertThatThrownBy(() -> confirmEdited(ctx, offered, key, edits))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(assigned(ctx)).isNull();
            assertThat(confirmationRepository.contentBody(source)).isEqualTo(body);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM scenario WHERE source_scenario_id = ?",
                                    Long.class,
                                    source))
                    .isZero();
            assertThat(order(offered.reviewId())).hasSize(1);
        } finally {
            jdbc.execute("ALTER TABLE activity_scenario_approval DROP CONSTRAINT " + constraint);
        }
        assertThat(confirmEdited(ctx, offered, key, edits).outcome()).isEqualTo("CONFIRMED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario WHERE source_scenario_id = ?",
                                Long.class,
                                source))
                .isEqualTo(1);
    }

    @Test
    void concurrentEditedReplayCreatesOnlyOneVersion() throws Exception {
        var ctx = context(null);
        when(llm.complete(any()))
                .thenAnswer(inv -> completion(inv.getArgument(0), generatedJson()));
        var offered = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        UUID key = UUID.randomUUID();
        var edits = new ScenarioEdits("수정한 이야기", null, List.of());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var calls =
                    List.of(1, 2).stream()
                            .map(
                                    index ->
                                            pool.submit(
                                                    () -> {
                                                        ready.countDown();
                                                        if (!go.await(10, TimeUnit.SECONDS))
                                                            throw new IllegalStateException(
                                                                    "동시 수정 확정 시작 대기 시간 초과");
                                                        return confirmEdited(
                                                                ctx, offered, key, edits);
                                                    }))
                            .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(calls.get(0).get(15, TimeUnit.SECONDS))
                    .isEqualTo(calls.get(1).get(15, TimeUnit.SECONDS));
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario WHERE source_scenario_id = ?",
                                Long.class,
                                offered.candidate().scenarioId()))
                .isEqualTo(1);
    }

    @Test
    void editedScenarioPreservesDevelopCharacterPersonaAndExplicitOpeningTargets() {
        var ctx = context(null);
        var body = json.readValue(generatedJson(), java.util.Map.class);
        body.put("ai_role", "FRIEND");
        var characters = (java.util.List<java.util.Map<String, Object>>) body.get("characters");
        characters.get(1).put("personality", "겁이 많지만 다정하다.");
        characters.get(1).put("speech_style", "반말과 짧은 문장으로 말한다.");
        body.put(
                "starting_questions",
                java.util.Map.of(
                        "S0", "흐윽, 무릎이 아파.",
                        "S1", "넘어졌어. 나 좀 도와줄래?",
                        "S2", "아파서 울고 있어. 같이 있어 줄래?"));
        doAnswer(call -> completion(call.getArgument(0), json.writeValueAsString(body)))
                .when(llm)
                .complete(any());
        var offered = preparation.prepare(ctx.activity(), UUID.randomUUID(), auth(ctx));
        var original = draftRepository.read(offered.candidate().scenarioId());

        confirmEdited(
                ctx,
                offered,
                UUID.randomUUID(),
                new ScenarioEdits("친구의 부탁을 들어요", null, java.util.List.of()));

        var edited = draftRepository.read(assigned(ctx));
        assertThat(edited.aiRole()).isEqualTo("FRIEND");
        assertThat(edited.characters()).isEqualTo(original.characters());
        assertThat(edited.characters().get(1).personality()).isEqualTo("겁이 많지만 다정하다.");
        assertThat(edited.characters().get(1).speechStyle()).isEqualTo("반말과 짧은 문장으로 말한다.");
        assertThat(edited.startingQuestions()).isEqualTo(original.startingQuestions());
        assertThat(edited.startingQuestionGoalIds()).isEqualTo(original.startingQuestionGoalIds());
    }

    private ScenarioReviewClosure confirmEdited(
            Context ctx, ScenarioReviewSnapshot snapshot, UUID key, ScenarioEdits edits) {
        return confirmation.confirm(
                ctx.activity(),
                key,
                snapshot.reviewId(),
                snapshot.revision(),
                snapshot.candidate().scenarioId(),
                snapshot.candidate().version(),
                edits,
                auth(ctx));
    }

    private UUID assigned(Context ctx) {
        return jdbc.queryForObject(
                "SELECT scenario_id FROM activity WHERE activity_id = ?",
                UUID.class,
                ctx.activity());
    }

    private long draftCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM scenario WHERE status = 'DRAFT'", Long.class);
    }

    private String generatedJson() {
        try {
            return new ClassPathResource("scenario/generation-valid-l2.json")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private AiCallResult<LlmCompletion> completion(LlmRequest request, String body) {
        return new AiCallResult.Success<>(
                new LlmCompletion(body, "fake", "fixture", "stop", 10, 20), metadata(request));
    }

    private AiCallMetadata metadata(LlmRequest request) {
        return new AiCallMetadata(
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
                "stop");
    }

    private void assertConflict(String code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class, error -> assertThat(error.getCode()).isEqualTo(code));
    }

    private long rejectedCount(UUID review) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM scenario_review_candidate WHERE review_id = ? AND rejected_at IS NOT NULL",
                Long.class,
                review);
    }

    private long nextReceiptCount(Context ctx) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM scenario_review_request WHERE activity_id = ? AND request_kind = 'NEXT'",
                Long.class,
                ctx.activity());
    }

    private List<Object> raceNext(Context ctx, ScenarioReviewSnapshot review, boolean sameKey)
            throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        UUID key = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var calls =
                    List.of(1, 2).stream()
                            .map(
                                    index ->
                                            pool.submit(
                                                    () -> {
                                                        ready.countDown();
                                                        if (!go.await(10, TimeUnit.SECONDS))
                                                            throw new IllegalStateException(
                                                                    "동시 요청 시작 대기 시간 초과");
                                                        try {
                                                            return (Object)
                                                                    transactions.next(
                                                                            ctx.activity(),
                                                                            sameKey
                                                                                    ? key
                                                                                    : UUID
                                                                                            .randomUUID(),
                                                                            review.reviewId(),
                                                                            1,
                                                                            auth(ctx));
                                                        } catch (ApiException failure) {
                                                            return (Object) failure;
                                                        }
                                                    }))
                            .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            return List.of(
                    calls.get(0).get(15, TimeUnit.SECONDS), calls.get(1).get(15, TimeUnit.SECONDS));
        }
    }

    private ScenarioReviewStartService facadeWithSelectionHook(
            Consumer<InitialScenarioPreparation> hook) {
        var query =
                new ScenarioCandidateQueryService(preparations, candidates, contents) {
                    @Override
                    ScenarioCandidateSelection selectPrepared(
                            InitialScenarioPreparation input, Authentication authentication) {
                        var selected = super.selectPrepared(input, authentication);
                        hook.accept(input);
                        return selected;
                    }
                };
        return new ScenarioReviewStartService(preparations, query, transactions);
    }

    private void assertNoReview(Context ctx) {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario_review WHERE activity_id = ?",
                                Integer.class,
                                ctx.activity()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario_review_request WHERE activity_id = ?",
                                Integer.class,
                                ctx.activity()))
                .isZero();
    }

    private List<UUID> order(UUID review) {
        return jdbc.query(
                "SELECT scenario_id FROM scenario_review_candidate WHERE review_id = ? ORDER BY position",
                (rs, row) -> rs.getObject(1, UUID.class),
                review);
    }

    private long presentations(UUID instructor, UUID scenario) {
        return jdbc.queryForObject(
                "SELECT coalesce(sum(presentation_count), 0) FROM scenario_presentation_stat WHERE instructor_id = ? AND scenario_id = ?",
                Long.class,
                instructor,
                scenario);
    }

    private void seedPresentation(UUID instructor, UUID scenario, String instant) {
        jdbc.update(
                "INSERT INTO scenario_presentation_stat (instructor_id, scenario_id, last_presented_at, presentation_count) VALUES (?, ?, ?, 1)",
                instructor,
                scenario,
                Timestamp.from(Instant.parse(instant)));
    }

    private Authentication auth(Context ctx) {
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(ctx.instructor(), UUID.randomUUID(), UserRole.INSTRUCTOR),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_INSTRUCTOR")));
    }

    private UUID candidate(Context ctx) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO scenario (scenario_id, status, scenario_level, title, scenario_facts, origin, created_at)
                VALUES (?, 'APPROVED', 'L2', '상황 제목', '[]', 'AI_GENERATED', now())
                """,
                id);
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        ignored ->
                                approvals.recordApproval(
                                        id,
                                        ctx.instructor(),
                                        new ScenarioApprovalConditions(
                                                "차례를 기다려요", "L2", null, null, null, null)));
        return id;
    }

    private Context context(UUID existingInstructor) {
        UUID instructor = existingInstructor == null ? UUID.randomUUID() : existingInstructor;
        if (existingInstructor == null)
            jdbc.update(
                    """
                INSERT INTO user_account (user_id, email, password_hash, name, role, status, created_at)
                VALUES (?, ?, 'test-hash', '검사 강사', 'INSTRUCTOR', 'ACTIVE', now())
                """,
                    instructor,
                    instructor + "@example.com");
        UUID classroom = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        UUID goal = UUID.randomUUID();
        UUID activity = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO classroom (class_id, instructor_id, name) VALUES (?, ?, '검사 학급')",
                classroom,
                instructor);
        jdbc.update(
                "INSERT INTO child (child_id, class_id, display_name) VALUES (?, ?, '검사 아동')",
                child,
                classroom);
        jdbc.update(
                """
                INSERT INTO learning_goal (goal_id, child_id, instructor_id, title, content_hash, created_at)
                VALUES (?, ?, ?, '차례를 기다려요', ?, now())
                """,
                goal,
                child,
                instructor,
                goal.toString().replace("-", "").repeat(2));
        jdbc.update(
                "INSERT INTO activity (activity_id, child_id, goal_id, activity_type, assigned_at) VALUES (?, ?, ?, 'ROLEPLAY', now())",
                activity,
                child,
                goal);
        when(difficulties.requireForActivity(eq(activity), any()))
                .thenReturn(
                        new InitialDifficultyEvidence(
                                activity,
                                child,
                                goal,
                                10,
                                5,
                                "L2",
                                "S1",
                                false,
                                null,
                                "INITIAL_QUIZ_V2"));
        return new Context(instructor, child, goal, activity);
    }

    private record Context(UUID instructor, UUID child, UUID goal, UUID activity) {}
}
