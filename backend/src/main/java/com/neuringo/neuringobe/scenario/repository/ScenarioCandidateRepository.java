package com.neuringo.neuringobe.scenario.repository;

import com.neuringo.neuringobe.scenario.dto.ApprovedScenarioVersion;
import com.neuringo.neuringobe.scenario.dto.ScenarioCandidateCriteria;
import com.neuringo.neuringobe.scenario.dto.ScenarioCandidateSelection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ScenarioCandidateRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    public ScenarioCandidateRepository(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public ScenarioCandidateSelection select(ScenarioCandidateCriteria criteria) {
        return select(criteria, null);
    }

    public ScenarioCandidateSelection select(
            ScenarioCandidateCriteria criteria, java.util.UUID candidateId) {
        var c = criteria.requestedConditions();
        var parameters =
                new MapSqlParameterSource()
                        .addValue("candidate", candidateId)
                        .addValue("instructor", criteria.instructorId())
                        .addValue("child", criteria.childId())
                        .addValue("goal", c.goalTitle())
                        .addValue("level", c.scenarioLevel())
                        .addValue("category", c.category())
                        .addValue("situation", c.situationType())
                        .addValue("roles", restriction(c.characterRoles()))
                        .addValue("actions", restriction(c.requiredActions()));
        return jdbc.query(
                """
                WITH classified AS (
                    SELECT a.scenario_id, s.scenario_version,
                        CASE
                            WHEN s.status <> 'APPROVED' THEN 'NOT_APPROVED'
                            WHEN a.verification_rule_version <> 1 THEN 'UNSUPPORTED_VERIFICATION_RULE'
                            WHEN a.scenario_level <> :level THEN 'LEVEL_MISMATCH'
                            WHEN a.goal_title <> :goal THEN 'GOAL_TEXT_MISMATCH'
                            WHEN CAST(:category AS text) IS NOT NULL AND a.category IS NULL THEN 'CATEGORY_UNVERIFIED'
                            WHEN CAST(:category AS text) IS NOT NULL AND a.category <> :category THEN 'CATEGORY_MISMATCH'
                            WHEN CAST(:situation AS text) IS NOT NULL AND a.situation_type IS NULL THEN 'SITUATION_UNVERIFIED'
                            WHEN CAST(:situation AS text) IS NOT NULL AND a.situation_type <> :situation THEN 'SITUATION_MISMATCH'
                            WHEN CAST(:roles AS jsonb) IS NOT NULL AND a.character_roles IS NULL THEN 'CHARACTERS_UNVERIFIED'
                            WHEN CAST(:roles AS jsonb) IS NOT NULL AND a.character_roles <> CAST(:roles AS jsonb) THEN 'CHARACTERS_MISMATCH'
                            WHEN CAST(:actions AS jsonb) IS NOT NULL AND a.required_actions IS NULL THEN 'REQUIRED_ACTIONS_UNVERIFIED'
                            WHEN CAST(:actions AS jsonb) IS NOT NULL AND NOT (a.required_actions @> CAST(:actions AS jsonb)) THEN 'REQUIRED_ACTIONS_MISMATCH'
                            WHEN EXISTS (SELECT 1 FROM activity h
                                WHERE h.child_id = :child AND h.scenario_id = a.scenario_id) THEN 'PREVIOUSLY_ASSIGNED'
                            ELSE 'ELIGIBLE'
                        END AS reason
                    FROM scenario_approval a JOIN scenario s ON s.scenario_id = a.scenario_id
                    WHERE (CAST(:candidate AS uuid) IS NULL OR a.scenario_id = :candidate)
                ), counts AS (
                    SELECT reason, count(*) AS count FROM classified GROUP BY reason
                ), diagnostics AS (
                    SELECT coalesce(sum(count), 0) AS pool_count,
                        coalesce(jsonb_object_agg(reason, count), '{}')::text AS counts
                    FROM counts
                )
                SELECT d.*, c.scenario_id, c.scenario_version FROM diagnostics d
                LEFT JOIN classified c ON c.reason = 'ELIGIBLE'
                LEFT JOIN scenario_presentation_stat p ON p.instructor_id = :instructor AND p.scenario_id = c.scenario_id
                ORDER BY p.last_presented_at ASC NULLS FIRST, c.scenario_id
                """,
                parameters,
                rs -> {
                    var candidates = new ArrayList<ApprovedScenarioVersion>();
                    Map<String, Long> counts = new HashMap<>();
                    long poolCount = 0;
                    boolean first = true;
                    while (rs.next()) {
                        if (first) {
                            poolCount = rs.getLong("pool_count");
                            var type =
                                    json.getTypeFactory()
                                            .constructMapType(
                                                    HashMap.class, String.class, Long.class);
                            counts = json.readValue(rs.getString("counts"), type);
                            counts.remove("ELIGIBLE");
                            first = false;
                        }
                        var id = rs.getObject("scenario_id", java.util.UUID.class);
                        if (id != null) {
                            candidates.add(
                                    new ApprovedScenarioVersion(id, rs.getInt("scenario_version")));
                        }
                    }
                    return new ScenarioCandidateSelection(candidates, poolCount, counts);
                });
    }

    private String restriction(List<String> values) {
        return values == null || values.isEmpty() ? null : json.writeValueAsString(values);
    }
}
