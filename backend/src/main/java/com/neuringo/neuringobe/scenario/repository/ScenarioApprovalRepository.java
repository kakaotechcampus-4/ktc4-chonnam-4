package com.neuringo.neuringobe.scenario.repository;

import com.neuringo.neuringobe.scenario.domain.ScenarioApprovalConditions;
import com.neuringo.neuringobe.scenario.dto.ScenarioApprovalRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ScenarioApprovalRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public ScenarioApprovalRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public boolean lockApprovedScenario(UUID scenarioId, String level) {
        return !jdbc.query(
                        """
                        SELECT scenario_id FROM scenario
                        WHERE scenario_id = ? AND status = 'APPROVED' AND scenario_level = ?
                        FOR UPDATE
                        """,
                        (rs, row) -> rs.getObject(1, UUID.class),
                        scenarioId,
                        level)
                .isEmpty();
    }

    public Optional<ScenarioApprovalRecord> find(UUID scenarioId) {
        return jdbc
                .query(
                        "SELECT * FROM scenario_approval WHERE scenario_id = ?",
                        this::map,
                        scenarioId)
                .stream()
                .findFirst();
    }

    public Optional<ScenarioApprovalRecord> findApproved(UUID scenarioId) {
        return jdbc
                .query(
                        """
            SELECT a.* FROM scenario_approval a JOIN scenario s ON s.scenario_id=a.scenario_id
            WHERE a.scenario_id=? AND s.status='APPROVED'
            """,
                        this::map,
                        scenarioId)
                .stream()
                .findFirst();
    }

    public Optional<ScenarioApprovalRecord> findOwnedApproved(UUID scenarioId, UUID instructorId) {
        return jdbc
                .query(
                        """
                        SELECT a.* FROM scenario_approval a
                        JOIN scenario s ON s.scenario_id = a.scenario_id
                        WHERE a.scenario_id = ? AND a.instructor_id = ? AND s.status = 'APPROVED'
                        """,
                        this::map,
                        scenarioId,
                        instructorId)
                .stream()
                .findFirst();
    }

    public ScenarioApprovalRecord insert(
            UUID scenarioId, UUID instructorId, ScenarioApprovalConditions conditions) {
        return jdbc.queryForObject(
                """
                INSERT INTO scenario_approval (
                    scenario_id, instructor_id, goal_title, scenario_level, category, situation_type,
                    character_roles, required_actions, verification_rule_version, approved_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), 1, ?)
                RETURNING *
                """,
                this::map,
                scenarioId,
                instructorId,
                conditions.goalTitle(),
                conditions.scenarioLevel(),
                conditions.category(),
                conditions.situationType(),
                arrayJson(conditions.characterRoles()),
                arrayJson(conditions.requiredActions()),
                Timestamp.from(Instant.now()));
    }

    private String arrayJson(List<String> values) {
        return values == null ? null : json.writeValueAsString(values);
    }

    private List<String> items(String value) {
        return value == null ? null : List.of(json.readValue(value, String[].class));
    }

    private ScenarioApprovalRecord map(ResultSet rs, int row) throws SQLException {
        return new ScenarioApprovalRecord(
                rs.getObject("scenario_id", UUID.class),
                rs.getObject("instructor_id", UUID.class),
                new ScenarioApprovalConditions(
                        rs.getString("goal_title"),
                        rs.getString("scenario_level"),
                        rs.getString("category"),
                        rs.getString("situation_type"),
                        items(rs.getString("character_roles")),
                        items(rs.getString("required_actions"))),
                rs.getInt("verification_rule_version"),
                rs.getTimestamp("approved_at").toInstant());
    }
}
