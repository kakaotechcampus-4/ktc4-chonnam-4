package com.neuringo.neuringobe.scenario.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ScenarioConfirmationRepository {
    private final JdbcTemplate jdbc;

    public ScenarioConfirmationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> currentSource(
            UUID activity,
            UUID review,
            long revision,
            UUID scenario,
            int version,
            UUID instructor) {
        return jdbc
                .query(
                        """
                SELECT c.candidate_source FROM scenario_review r
                JOIN scenario_review_candidate c ON c.review_id = r.review_id AND c.position = r.current_position
                JOIN scenario s ON s.scenario_id = c.scenario_id AND s.scenario_version = c.scenario_version
                WHERE r.activity_id = ? AND r.review_id = ? AND r.revision = ? AND r.instructor_id = ?
                    AND c.scenario_id = ? AND c.scenario_version = ? AND c.rejected_at IS NULL
                """,
                        (rs, row) -> rs.getString(1),
                        activity,
                        review,
                        revision,
                        instructor,
                        scenario,
                        version)
                .stream()
                .findFirst();
    }

    public String contentBody(UUID scenario) {
        return jdbc.queryForObject(
                """
                SELECT (to_jsonb(s) - 'status' - 'class_id' || jsonb_build_object(
                    'micro_goals', coalesce((SELECT jsonb_agg(to_jsonb(m) ORDER BY m.micro_goal_id)
                        FROM scenario_micro_goal m WHERE m.scenario_id = s.scenario_id), '[]'),
                    'opening_focus', coalesce((SELECT jsonb_agg(to_jsonb(f) ORDER BY f.support_level)
                        FROM scenario_opening_focus f WHERE f.scenario_id = s.scenario_id), '[]')))::text
                FROM scenario s WHERE s.scenario_id = ?
                """,
                String.class,
                scenario);
    }

    public boolean openingReady(UUID scenario) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                SELECT count(*) = 3 FROM scenario_opening_focus f
                JOIN scenario_micro_goal m ON m.scenario_id = f.scenario_id AND m.micro_goal_id = f.micro_goal_id
                JOIN scenario s ON s.scenario_id = f.scenario_id
                WHERE f.scenario_id = ? AND f.support_level IN ('S0', 'S1', 'S2')
                    AND jsonb_typeof(s.opening_prompts -> f.support_level) = 'string'
                    AND btrim(s.opening_prompts ->> f.support_level) <> ''
                """,
                        Boolean.class,
                        scenario));
    }

    public boolean lockChild(UUID child) {
        return !jdbc.query(
                        "SELECT child_id FROM child WHERE child_id = ? AND status = 'ACTIVE' FOR UPDATE",
                        (rs, row) -> rs.getObject(1, UUID.class),
                        child)
                .isEmpty();
    }

    public boolean lockScenario(UUID scenario, int version, String status) {
        return !jdbc.query(
                        "SELECT scenario_id FROM scenario WHERE scenario_id = ? AND scenario_version = ? AND status = ? FOR UPDATE",
                        (rs, row) -> rs.getObject(1, UUID.class),
                        scenario,
                        version,
                        status)
                .isEmpty();
    }

    public boolean used(UUID child, UUID scenario, UUID activity) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM activity WHERE child_id = ? AND scenario_id = ? AND activity_id <> ?)",
                        Boolean.class,
                        child,
                        scenario,
                        activity));
    }

    public void approveDraft(UUID scenario) {
        if (jdbc.update(
                        "UPDATE scenario SET status = 'APPROVED' WHERE scenario_id = ? AND status = 'DRAFT'",
                        scenario)
                != 1) throw new IllegalStateException("승인할 생성 초안이 없습니다.");
    }

    public void assign(UUID activity, UUID scenario, String source) {
        if (jdbc.update(
                        "UPDATE activity SET scenario_id = ?, scenario_source = ? WHERE activity_id = ? AND scenario_id IS NULL",
                        scenario,
                        source,
                        activity)
                != 1) throw new IllegalStateException("활동에 시나리오를 배정할 수 없습니다.");
    }

    public void recordAssignment(
            UUID activity,
            UUID review,
            UUID instructor,
            UUID scenario,
            int version,
            String level,
            String fingerprint,
            Instant approvedAt) {
        jdbc.update(
                """
                INSERT INTO activity_scenario_approval (activity_id, review_id, child_id, goal_id, goal_content_hash,
                    instructor_id, scenario_id, scenario_version, scenario_level, content_fingerprint, approved_at)
                SELECT a.activity_id, ?, a.child_id, a.goal_id, g.content_hash, ?, ?, ?, ?, ?, ?
                FROM activity a JOIN learning_goal g ON g.goal_id = a.goal_id AND g.child_id = a.child_id
                WHERE a.activity_id = ?
                """,
                review,
                instructor,
                scenario,
                version,
                level,
                fingerprint,
                Timestamp.from(approvedAt),
                activity);
    }

    public void recordReuse(UUID instructor, UUID scenario, Instant at) {
        if (jdbc.update(
                        "UPDATE scenario_presentation_stat SET reuse_count = reuse_count + 1, last_reused_at = GREATEST(last_reused_at, ?) WHERE instructor_id = ? AND scenario_id = ?",
                        Timestamp.from(at),
                        instructor,
                        scenario)
                != 1) throw new IllegalStateException("재사용 후보의 제시 기록이 없습니다.");
        jdbc.update(
                """
                INSERT INTO scenario_presentation_daily (instructor_id, scenario_id, presentation_day, presentation_count, reuse_count)
                VALUES (?, ?, (CAST(? AS timestamptz) AT TIME ZONE 'Asia/Seoul')::date, 0, 1)
                ON CONFLICT (instructor_id, scenario_id, presentation_day) DO UPDATE
                SET reuse_count = scenario_presentation_daily.reuse_count + 1
                """,
                instructor,
                scenario,
                Timestamp.from(at));
    }
}
