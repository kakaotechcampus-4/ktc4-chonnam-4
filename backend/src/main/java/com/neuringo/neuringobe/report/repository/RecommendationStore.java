package com.neuringo.neuringobe.report.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** recommendation·recommendation_decision 저장소. 추천은 리포트 준비 때 한 번에 만들고, 그 뒤에는 강사 결정만 바뀐다. */
@Repository
public class RecommendationStore {
    private static final String COLUMNS =
            """
            recommendation_id, report_id, record_id, activity_id, child_id, type, display_order,
            reason_code, reason, basis_goal_title, evidence::text as evidence, evidence_sufficiency,
            suggested_goal::text as suggested_goal, policy_version, status, created_at, decided_at
            """;

    private final JdbcTemplate jdbc;

    public RecommendationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(NewRecommendation row) {
        jdbc.update(
                """
                insert into recommendation (recommendation_id, report_id, record_id, activity_id, child_id,
                    type, display_order, reason_code, reason, basis_goal_title, evidence, evidence_sufficiency,
                    suggested_goal, policy_version, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, 'PROPOSED', ?)
                """,
                row.recommendationId(),
                row.reportId(),
                row.recordId(),
                row.activityId(),
                row.childId(),
                row.type(),
                row.displayOrder(),
                row.reasonCode(),
                row.reason(),
                row.basisGoalTitle(),
                row.evidenceJson(),
                row.evidenceSufficiency(),
                row.suggestedGoalJson(),
                row.policyVersion(),
                Timestamp.from(row.createdAt()));
    }

    public List<StoredRecommendation> findByRecord(UUID recordId) {
        return jdbc.query(
                "select "
                        + COLUMNS
                        + " from recommendation where record_id = ? order by display_order",
                RecommendationStore::stored,
                recordId);
    }

    public Optional<StoredRecommendation> findById(UUID recommendationId) {
        return jdbc
                .query(
                        "select " + COLUMNS + " from recommendation where recommendation_id = ?",
                        RecommendationStore::stored,
                        recommendationId)
                .stream()
                .findFirst();
    }

    /** 결정은 추천 행을 잠그고 한 번에 하나씩 처리한다. */
    public Optional<StoredRecommendation> findByIdForUpdate(UUID recommendationId) {
        return jdbc
                .query(
                        "select "
                                + COLUMNS
                                + " from recommendation where recommendation_id = ? for update",
                        RecommendationStore::stored,
                        recommendationId)
                .stream()
                .findFirst();
    }

    public Optional<StoredDecision> findDecisionByKey(UUID idempotencyKey) {
        return jdbc
                .query(
                        """
                        select decision_id, recommendation_id, idempotency_key, decision, goal::text as goal,
                            rejection_reason, decided_by, decided_at
                        from recommendation_decision where idempotency_key = ?
                        """,
                        RecommendationStore::decision,
                        idempotencyKey)
                .stream()
                .findFirst();
    }

    /** 가장 최근 결정. 화면에 보이는 수정 목표(MODIFIED_ACCEPT)를 돌려줄 때 쓴다. */
    public Optional<StoredDecision> findLatestDecision(UUID recommendationId) {
        return jdbc
                .query(
                        """
                        select decision_id, recommendation_id, idempotency_key, decision, goal::text as goal,
                            rejection_reason, decided_by, decided_at
                        from recommendation_decision where recommendation_id = ?
                        order by decided_at desc, decision_id desc limit 1
                        """,
                        RecommendationStore::decision,
                        recommendationId)
                .stream()
                .findFirst();
    }

    public void insertDecision(
            UUID decisionId,
            UUID recommendationId,
            UUID idempotencyKey,
            String decision,
            String goalJson,
            String rejectionReason,
            UUID decidedBy,
            Instant decidedAt) {
        jdbc.update(
                """
                insert into recommendation_decision (decision_id, recommendation_id, idempotency_key, decision,
                    goal, rejection_reason, decided_by, decided_at)
                values (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """,
                decisionId,
                recommendationId,
                idempotencyKey,
                decision,
                goalJson,
                rejectionReason,
                decidedBy,
                Timestamp.from(decidedAt));
    }

    public void updateStatus(UUID recommendationId, String status, Instant decidedAt) {
        jdbc.update(
                "update recommendation set status = ?, decided_at = ? where recommendation_id = ?",
                status,
                Timestamp.from(decidedAt),
                recommendationId);
    }

    private static StoredRecommendation stored(ResultSet rs, int n) throws SQLException {
        var decided = rs.getObject("decided_at", OffsetDateTime.class);
        return new StoredRecommendation(
                rs.getObject("recommendation_id", UUID.class),
                rs.getObject("report_id", UUID.class),
                rs.getObject("record_id", UUID.class),
                rs.getObject("activity_id", UUID.class),
                rs.getObject("child_id", UUID.class),
                rs.getString("type"),
                rs.getInt("display_order"),
                rs.getString("reason_code"),
                rs.getString("reason"),
                rs.getString("basis_goal_title"),
                rs.getString("evidence"),
                rs.getString("evidence_sufficiency"),
                rs.getString("suggested_goal"),
                rs.getString("policy_version"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                decided == null ? null : decided.toInstant());
    }

    private static StoredDecision decision(ResultSet rs, int n) throws SQLException {
        return new StoredDecision(
                rs.getObject("decision_id", UUID.class),
                rs.getObject("recommendation_id", UUID.class),
                rs.getObject("idempotency_key", UUID.class),
                rs.getString("decision"),
                rs.getString("goal"),
                rs.getString("rejection_reason"),
                rs.getObject("decided_by", UUID.class),
                rs.getObject("decided_at", OffsetDateTime.class).toInstant());
    }

    public record NewRecommendation(
            UUID recommendationId,
            UUID reportId,
            UUID recordId,
            UUID activityId,
            UUID childId,
            String type,
            int displayOrder,
            String reasonCode,
            String reason,
            String basisGoalTitle,
            String evidenceJson,
            String evidenceSufficiency,
            String suggestedGoalJson,
            String policyVersion,
            Instant createdAt) {}

    public record StoredRecommendation(
            UUID recommendationId,
            UUID reportId,
            UUID recordId,
            UUID activityId,
            UUID childId,
            String type,
            int displayOrder,
            String reasonCode,
            String reason,
            String basisGoalTitle,
            String evidenceJson,
            String evidenceSufficiency,
            String suggestedGoalJson,
            String policyVersion,
            String status,
            Instant createdAt,
            Instant decidedAt) {}

    public record StoredDecision(
            UUID decisionId,
            UUID recommendationId,
            UUID idempotencyKey,
            String decision,
            String goalJson,
            String rejectionReason,
            UUID decidedBy,
            Instant decidedAt) {}
}
