package com.neuringo.neuringobe.scenario.repository;

import com.neuringo.neuringobe.scenario.dto.ApprovedScenarioVersion;
import com.neuringo.neuringobe.scenario.dto.ScenarioReviewCandidate;
import com.neuringo.neuringobe.scenario.dto.ScenarioReviewClosure;
import com.neuringo.neuringobe.scenario.dto.ScenarioReviewSnapshot;
import com.neuringo.neuringobe.scenario.dto.ScenarioReviewState;
import com.neuringo.neuringobe.scenario.dto.ScenarioVersionRef;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ScenarioReviewRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public ScenarioReviewRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public boolean lockInstructor(UUID instructorId) {
        return !jdbc.query(
                        "SELECT user_id FROM user_account WHERE user_id = ? FOR KEY SHARE",
                        (rs, row) -> rs.getObject(1, UUID.class),
                        instructorId)
                .isEmpty();
    }

    public boolean lockActivity(UUID activityId) {
        return !jdbc.query(
                        "SELECT activity_id FROM activity WHERE activity_id = ? FOR UPDATE",
                        (rs, row) -> rs.getObject(1, UUID.class),
                        activityId)
                .isEmpty();
    }

    public long epoch(UUID activityId) {
        return jdbc.queryForObject(
                "SELECT scenario_review_epoch FROM activity WHERE activity_id = ?",
                Long.class,
                activityId);
    }

    public void advanceEpoch(UUID activityId) {
        jdbc.update(
                "UPDATE activity SET scenario_review_epoch = scenario_review_epoch + 1 WHERE activity_id = ?",
                activityId);
    }

    public UUID childId(UUID activityId) {
        return jdbc.queryForObject(
                "SELECT child_id FROM activity WHERE activity_id = ?", UUID.class, activityId);
    }

    public void closeRequests(UUID activityId, UUID reviewId, String outcome, Instant closedAt) {
        jdbc.update(
                """
                UPDATE scenario_review_request SET outcome = ?, closed_at = ?,
                    expected_revision = NULL, result_snapshot = NULL
                WHERE activity_id = ? AND review_id = ? AND outcome = 'ACTIVE'
                """,
                outcome,
                Timestamp.from(closedAt),
                activityId,
                reviewId);
    }

    public void recordCancellation(
            UUID activityId, UUID requestId, UUID reviewId, String fingerprint, Instant closedAt) {
        jdbc.update(
                """
                INSERT INTO scenario_review_request (
                    activity_id, request_id, review_id, request_kind, outcome, request_fingerprint, closed_at)
                VALUES (?, ?, ?, 'CANCEL', 'CANCELLED', ?, ?)
                """,
                activityId,
                requestId,
                reviewId,
                fingerprint,
                Timestamp.from(closedAt));
    }

    public void recordConfirmation(
            UUID activityId, UUID requestId, UUID reviewId, String fingerprint, Instant closedAt) {
        jdbc.update(
                """
                INSERT INTO scenario_review_request (activity_id, request_id, review_id, request_kind, outcome, request_fingerprint, closed_at)
                VALUES (?, ?, ?, 'CONFIRM', 'CONFIRMED', ?, ?)
                """,
                activityId,
                requestId,
                reviewId,
                fingerprint,
                Timestamp.from(closedAt));
    }

    public ScenarioReviewClosure closure(UUID activityId, UUID requestId) {
        return jdbc.queryForObject(
                """
                SELECT review_id, outcome, closed_at FROM scenario_review_request
                WHERE activity_id = ? AND request_id = ?
                """,
                (rs, row) ->
                        new ScenarioReviewClosure(
                                rs.getObject("review_id", UUID.class),
                                activityId,
                                rs.getString("outcome"),
                                rs.getTimestamp("closed_at").toInstant()),
                activityId,
                requestId);
    }

    public void delete(UUID activityId, UUID reviewId) {
        int deleted =
                jdbc.update(
                        "DELETE FROM scenario_review WHERE activity_id = ? AND review_id = ?",
                        activityId,
                        reviewId);
        if (deleted != 1) throw new IllegalStateException("종료할 검토가 없습니다.");
    }

    public boolean isAssigned(UUID activityId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT scenario_id IS NOT NULL FROM activity WHERE activity_id = ?",
                        Boolean.class,
                        activityId));
    }

    public Optional<Map<String, Object>> request(UUID activityId, UUID requestId) {
        return jdbc
                .queryForList(
                        "SELECT review_id, request_kind, outcome, expected_revision, result_snapshot, request_fingerprint FROM scenario_review_request WHERE activity_id = ? AND request_id = ?",
                        activityId,
                        requestId)
                .stream()
                .findFirst();
    }

    public Optional<ScenarioReviewState> find(UUID activityId) {
        return jdbc
                .query(
                        """
                SELECT r.*, (SELECT count(*) FROM scenario_review_candidate c WHERE c.review_id = r.review_id) AS candidate_count
                FROM scenario_review r WHERE activity_id = ? FOR UPDATE
                """,
                        (rs, row) ->
                                new ScenarioReviewState(
                                        rs.getObject("review_id", UUID.class),
                                        rs.getObject("activity_id", UUID.class),
                                        rs.getObject("instructor_id", UUID.class),
                                        rs.getString("input_fingerprint"),
                                        rs.getObject("current_position", Integer.class),
                                        rs.getLong("revision"),
                                        rs.getInt("candidate_count")),
                        activityId)
                .stream()
                .findFirst();
    }

    public void create(
            UUID reviewId,
            UUID activityId,
            UUID instructorId,
            String fingerprint,
            List<ApprovedScenarioVersion> candidates) {
        jdbc.update(
                "INSERT INTO scenario_review (review_id, activity_id, instructor_id, input_fingerprint) VALUES (?, ?, ?, ?)",
                reviewId,
                activityId,
                instructorId,
                fingerprint);
        var position = new AtomicInteger();
        jdbc.batchUpdate(
                "INSERT INTO scenario_review_candidate (review_id, position, scenario_id, scenario_version) VALUES (?, ?, ?, ?)",
                candidates,
                500,
                (ps, candidate) -> {
                    // 순번은 목록 전체에서 고정되므로 배치 경계와 무관하게 부여한다.
                    ps.setObject(1, reviewId);
                    ps.setInt(2, position.incrementAndGet());
                    ps.setObject(3, candidate.scenarioId());
                    ps.setInt(4, candidate.version());
                });
    }

    public Optional<ScenarioReviewCandidate> firstAvailable(
            ScenarioReviewState review, UUID childId) {
        return firstAvailableFrom(
                review, childId, review.currentPosition() == null ? 1 : review.currentPosition());
    }

    public Optional<ScenarioReviewCandidate> firstAvailableFrom(
            ScenarioReviewState review, UUID childId, int minimumPosition) {
        return jdbc
                .query(
                        """
                SELECT c.position, c.scenario_id, c.scenario_version
                FROM scenario_review_candidate c
                JOIN scenario s ON s.scenario_id = c.scenario_id AND s.scenario_version = c.scenario_version
                LEFT JOIN scenario_approval a ON a.scenario_id = c.scenario_id
                WHERE c.review_id = ? AND c.position >= ? AND c.rejected_at IS NULL
                    AND ((c.candidate_source = 'REUSE' AND s.status = 'APPROVED' AND a.scenario_id IS NOT NULL)
                        OR (c.candidate_source = 'GENERATED' AND s.status = 'DRAFT'))
                    AND NOT EXISTS (SELECT 1 FROM activity h WHERE h.child_id = ? AND h.scenario_id = c.scenario_id)
                ORDER BY c.position LIMIT 1 FOR SHARE OF s
                """,
                        (rs, row) ->
                                new ScenarioReviewCandidate(
                                        rs.getInt("position"),
                                        new ScenarioVersionRef(
                                                rs.getObject("scenario_id", UUID.class),
                                                rs.getInt("scenario_version"))),
                        review.reviewId(),
                        minimumPosition,
                        childId)
                .stream()
                .findFirst();
    }

    public int appendGenerated(UUID reviewId, UUID scenarioId) {
        int position =
                jdbc.queryForObject(
                        "SELECT coalesce(max(position), 0) + 1 FROM scenario_review_candidate WHERE review_id = ?",
                        Integer.class,
                        reviewId);
        jdbc.update(
                """
                INSERT INTO scenario_review_candidate (review_id, position, scenario_id, scenario_version, candidate_source)
                VALUES (?, ?, ?, 1, 'GENERATED')
                """,
                reviewId,
                position,
                scenarioId);
        return position;
    }

    public void completeGenerationRequests(ScenarioReviewSnapshot snapshot) {
        jdbc.update(
                """
                UPDATE scenario_review_request SET result_snapshot = CAST(? AS jsonb)
                WHERE activity_id = ? AND review_id = ? AND request_kind = 'NEXT' AND outcome = 'ACTIVE'
                    AND expected_revision = ? AND result_snapshot ->> 'candidate' IS NULL
                """,
                json.writeValueAsString(snapshot),
                snapshot.activityId(),
                snapshot.reviewId(),
                snapshot.revision() - 1);
    }

    public void setPosition(UUID reviewId, Integer position, boolean incrementRevision) {
        jdbc.update(
                "UPDATE scenario_review SET current_position = ?, revision = revision + ? WHERE review_id = ?",
                position,
                incrementRevision ? 1 : 0,
                reviewId);
    }

    public void recordRequest(UUID activityId, UUID requestId, UUID reviewId) {
        jdbc.update(
                """
                INSERT INTO scenario_review_request (activity_id, request_id, review_id, request_kind, outcome)
                VALUES (?, ?, ?, 'START_OR_RESUME', 'ACTIVE')
                """,
                activityId,
                requestId,
                reviewId);
    }

    public void reject(UUID reviewId, int position) {
        int changed =
                jdbc.update(
                        """
                UPDATE scenario_review_candidate SET rejected_at = statement_timestamp()
                WHERE review_id = ? AND position = ? AND rejected_at IS NULL
                """,
                        reviewId,
                        position);
        if (changed != 1) throw new IllegalStateException("거절할 현재 후보가 없습니다.");
    }

    public void recordNextRequest(
            UUID activityId, UUID requestId, long expectedRevision, ScenarioReviewSnapshot result) {
        jdbc.update(
                """
                INSERT INTO scenario_review_request (
                    activity_id, request_id, review_id, request_kind, outcome, expected_revision, result_snapshot)
                VALUES (?, ?, ?, 'NEXT', 'ACTIVE', ?, CAST(? AS jsonb))
                """,
                activityId,
                requestId,
                result.reviewId(),
                expectedRevision,
                json.writeValueAsString(result));
    }

    public ScenarioReviewSnapshot recordedResult(Map<String, Object> receipt) {
        return json.readValue(
                receipt.get("result_snapshot").toString(), ScenarioReviewSnapshot.class);
    }

    public void recordPresentation(UUID instructorId, UUID scenarioId) {
        var now = Timestamp.from(Instant.now());
        jdbc.update(
                """
                INSERT INTO scenario_presentation_stat (instructor_id, scenario_id, last_presented_at, presentation_count)
                VALUES (?, ?, ?, 1)
                ON CONFLICT (instructor_id, scenario_id) DO UPDATE
                SET last_presented_at = GREATEST(scenario_presentation_stat.last_presented_at, EXCLUDED.last_presented_at),
                    presentation_count = scenario_presentation_stat.presentation_count + 1
                """,
                instructorId,
                scenarioId,
                now);
        jdbc.update(
                """
                INSERT INTO scenario_presentation_daily (instructor_id, scenario_id, presentation_day, presentation_count)
                VALUES (?, ?, (CAST(? AS timestamptz) AT TIME ZONE 'Asia/Seoul')::date, 1)
                ON CONFLICT (instructor_id, scenario_id, presentation_day) DO UPDATE
                SET presentation_count = scenario_presentation_daily.presentation_count + 1
                """,
                instructorId,
                scenarioId,
                now);
    }
}
