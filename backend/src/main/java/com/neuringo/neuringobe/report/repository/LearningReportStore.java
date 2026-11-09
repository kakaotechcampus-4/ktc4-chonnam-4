package com.neuringo.neuringobe.report.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * learning_report 저장소. JSON 칸(snapshot·llm_input·items·rounds)은 서비스가 만든 문자열을 그대로 jsonb 로 넣는다.
 *
 * <p>상태는 PENDING 에서 COMPLETED 나 FAILED 로 한 번만 바뀐다. 바꾸는 UPDATE 가 모두 {@code status = 'PENDING'} 을
 * 조건으로 걸어서, 재처리 작업과 원래 작업이 겹쳐도 먼저 끝난 쪽만 남는다.
 */
@Repository
public class LearningReportStore {
    private static final String COLUMNS =
            """
            l.report_id, l.record_id, l.activity_id, l.child_id, l.status, l.snapshot::text as snapshot,
            l.summary, l.items::text as items, l.created_at, l.completed_at, r.created_at as record_created_at
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public LearningReportStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    public boolean existsByActivity(UUID activityId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists (select 1 from learning_report where activity_id = ?)",
                        Boolean.class,
                        activityId));
    }

    public void insertPending(
            UUID reportId,
            UUID recordId,
            UUID activityId,
            UUID childId,
            String snapshotJson,
            String llmInputJson,
            String promptVersion,
            Instant now) {
        jdbc.update(
                """
                insert into learning_report (report_id, record_id, activity_id, child_id, status, snapshot,
                    llm_input, prompt_version, created_at, updated_at)
                values (?, ?, ?, ?, 'PENDING', ?::jsonb, ?::jsonb, ?, ?, ?)
                """,
                reportId,
                recordId,
                activityId,
                childId,
                snapshotJson,
                llmInputJson,
                promptVersion,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    /** 생성 작업이 리포트를 맡는다. PENDING 이 아니면(이미 끝났으면) 비어 있다. */
    public Optional<Claimed> claim(UUID reportId, Instant now) {
        var rows =
                jdbc.query(
                        """
                        update learning_report set attempt_count = attempt_count + 1, updated_at = ?
                        where report_id = ? and status = 'PENDING'
                        returning report_id, activity_id, child_id, llm_input::text as llm_input, attempt_count
                        """,
                        (rs, n) ->
                                new Claimed(
                                        rs.getObject("report_id", UUID.class),
                                        rs.getObject("activity_id", UUID.class),
                                        rs.getObject("child_id", UUID.class),
                                        rs.getString("llm_input"),
                                        rs.getInt("attempt_count")),
                        Timestamp.from(now),
                        reportId);
        return rows.stream().findFirst();
    }

    public boolean complete(
            UUID reportId, String summary, String itemsJson, String roundsJson, Instant now) {
        return jdbc.update(
                        """
                        update learning_report set status = 'COMPLETED', summary = ?, items = ?::jsonb,
                            rounds = ?::jsonb, completed_at = ?, updated_at = ?
                        where report_id = ? and status = 'PENDING'
                        """,
                        summary,
                        itemsJson,
                        roundsJson,
                        Timestamp.from(now),
                        Timestamp.from(now),
                        reportId)
                == 1;
    }

    public boolean fail(UUID reportId, String reason, String roundsJson, Instant now) {
        return jdbc.update(
                        """
                        update learning_report set status = 'FAILED', failure_reason = ?, rounds = ?::jsonb,
                            updated_at = ?
                        where report_id = ? and status = 'PENDING'
                        """,
                        reason,
                        roundsJson,
                        Timestamp.from(now),
                        reportId)
                == 1;
    }

    /** 재처리 대상: updated_at 이 기준보다 오래된 PENDING. 시도 횟수는 서비스가 본다. */
    public List<Stale> findStalePending(Instant olderThan, int limit) {
        return jdbc.query(
                """
                select report_id, attempt_count from learning_report
                where status = 'PENDING' and updated_at < ?
                order by updated_at limit ?
                """,
                (rs, n) ->
                        new Stale(
                                rs.getObject("report_id", UUID.class), rs.getInt("attempt_count")),
                Timestamp.from(olderThan),
                limit);
    }

    public Optional<StoredReport> findCompletedByActivity(UUID activityId) {
        return jdbc
                .query(
                        "select "
                                + COLUMNS
                                + " from learning_report l join learning_record r on r.record_id = l.record_id"
                                + " where l.activity_id = ? and l.status = 'COMPLETED'",
                        LearningReportStore::stored,
                        activityId)
                .stream()
                .findFirst();
    }

    public List<StoredReport> findCompletedByChild(UUID childId, int page, int size) {
        return jdbc.query(
                "select "
                        + COLUMNS
                        + " from learning_report l join learning_record r on r.record_id = l.record_id"
                        + " where l.child_id = ? and l.status = 'COMPLETED'"
                        + " order by r.created_at desc, l.report_id desc limit ? offset ?",
                LearningReportStore::stored,
                childId,
                size,
                page * size);
    }

    /** 학급 아동 전체의 완료 리포트. 정렬과 페이지는 아동별 목록과 같다. */
    public List<StoredReport> findCompletedByClassroom(UUID classId, int page, int size) {
        return jdbc.query(
                "select "
                        + COLUMNS
                        + " from learning_report l join learning_record r on r.record_id = l.record_id"
                        + " join child c on c.child_id = l.child_id"
                        + " where c.class_id = ? and l.status = 'COMPLETED'"
                        + " order by r.created_at desc, l.report_id desc limit ? offset ?",
                LearningReportStore::stored,
                classId,
                size,
                page * size);
    }

    /** 리포트 목록 표에 쓰는 활동 유형·아동 이름·목표 제목을 활동마다 한 번에 읽는다. */
    public Map<UUID, ReportLabels> findLabels(Collection<UUID> activityIds) {
        if (activityIds.isEmpty()) return Map.of();
        var labels = new HashMap<UUID, ReportLabels>();
        named.query(
                "select a.activity_id, a.activity_type, c.display_name, g.title"
                        + " from activity a join child c on c.child_id = a.child_id"
                        + " left join learning_goal g on g.goal_id = a.goal_id"
                        + " where a.activity_id in (:ids)",
                new MapSqlParameterSource("ids", activityIds),
                (RowCallbackHandler)
                        rs ->
                                labels.put(
                                        rs.getObject("activity_id", UUID.class),
                                        new ReportLabels(
                                                rs.getString("activity_type"),
                                                rs.getString("display_name"),
                                                rs.getString("title"))));
        return labels;
    }

    /** 리포트 표의 사람이 읽는 칸. 독립 퀴즈처럼 목표가 없으면 goalTitle 이 null 이다. */
    public record ReportLabels(String activityType, String childName, String goalTitle) {}

    /** 추천 조회의 소유권 확인용. 리포트 상태와 상관없이 학습 기록에 연결된 리포트를 찾는다. */
    public Optional<StoredReport> findByRecord(UUID recordId) {
        return jdbc
                .query(
                        "select "
                                + COLUMNS
                                + " from learning_report l join learning_record r on r.record_id = l.record_id"
                                + " where l.record_id = ?",
                        LearningReportStore::stored,
                        recordId)
                .stream()
                .findFirst();
    }

    public Optional<UUID> findRecordIdByActivity(UUID activityId) {
        return jdbc
                .query(
                        "select record_id from learning_record where activity_id = ?",
                        (rs, n) -> rs.getObject("record_id", UUID.class),
                        activityId)
                .stream()
                .findFirst();
    }

    /** 학습 기록의 아동. 리포트가 없는 기록(퀴즈만 한 활동)도 소유권을 확인할 수 있게 활동에서 읽는다. */
    public Optional<UUID> findRecordChild(UUID recordId) {
        return jdbc
                .query(
                        "select a.child_id from learning_record r join activity a on a.activity_id = r.activity_id where r.record_id = ?",
                        (rs, n) -> rs.getObject("child_id", UUID.class),
                        recordId)
                .stream()
                .findFirst();
    }

    /** 아동 삭제. 추천 결정과 추천을 먼저 지운 뒤 리포트를 지운다. 학습 기록보다 먼저 부른다. */
    public void deleteByChildIds(Collection<UUID> childIds) {
        var params = new MapSqlParameterSource("childIds", childIds);
        named.update(
                "delete from recommendation_decision where recommendation_id in (select recommendation_id from recommendation where child_id in (:childIds))",
                params);
        named.update("delete from recommendation where child_id in (:childIds)", params);
        named.update("delete from learning_report where child_id in (:childIds)", params);
    }

    private static StoredReport stored(ResultSet rs, int n) throws SQLException {
        var completed = rs.getObject("completed_at", OffsetDateTime.class);
        return new StoredReport(
                rs.getObject("report_id", UUID.class),
                rs.getObject("record_id", UUID.class),
                rs.getObject("activity_id", UUID.class),
                rs.getObject("child_id", UUID.class),
                rs.getString("status"),
                rs.getString("snapshot"),
                rs.getString("summary"),
                rs.getString("items"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                completed == null ? null : completed.toInstant(),
                rs.getObject("record_created_at", OffsetDateTime.class).toInstant());
    }

    public record Claimed(
            UUID reportId, UUID activityId, UUID childId, String llmInputJson, int attemptCount) {}

    public record Stale(UUID reportId, int attemptCount) {}

    /**
     * @param recordCreatedAt 활동을 완료한 시각(학습 기록이 생긴 시각). 계약의 completedAt 이다
     */
    public record StoredReport(
            UUID reportId,
            UUID recordId,
            UUID activityId,
            UUID childId,
            String status,
            String snapshotJson,
            String summary,
            String itemsJson,
            Instant createdAt,
            Instant completedAt,
            Instant recordCreatedAt) {}
}
