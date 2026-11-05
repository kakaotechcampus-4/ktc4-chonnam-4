package com.neuringo.neuringobe.scenario.repository;

import com.neuringo.neuringobe.scenario.dto.ScenarioReviewContent;
import com.neuringo.neuringobe.scenario.dto.ScenarioReviewResponse;
import com.neuringo.neuringobe.scenario.dto.ScenarioReviewSnapshot;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ScenarioReviewViewRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public ScenarioReviewViewRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<ScenarioReviewContent> find(
            ScenarioReviewSnapshot snapshot, UUID instructorId) {
        return jdbc
                .query(
                        """
            SELECT r.input_fingerprint, jsonb_build_object(
                'scenarioId', s.scenario_id, 'scenarioVersion', s.scenario_version,
                'scenarioLevel', s.scenario_level, 'title', s.title, 'summary', s.summary,
                'characters', s.characters, 'scenarioFacts', s.scenario_facts,
                'startingQuestions', s.opening_prompts,
                'microGoals', (SELECT jsonb_agg(jsonb_build_object(
                    'microGoalId', m.micro_goal_id, 'description', m.description,
                    'requiredEvidence', m.required_evidence, 'achievementExample', m.achievement_example)
                    ORDER BY m.order_no) FROM scenario_micro_goal m WHERE m.scenario_id=s.scenario_id),
                'startingQuestionGoalIds', coalesce((SELECT jsonb_object_agg(f.support_level, f.micro_goal_id)
                    FROM scenario_opening_focus f WHERE f.scenario_id=s.scenario_id), '{}'))::text AS content
            FROM scenario_review r JOIN scenario_review_candidate c USING (review_id)
            JOIN scenario s ON s.scenario_id=c.scenario_id AND s.scenario_version=c.scenario_version
            WHERE r.activity_id=? AND r.review_id=? AND r.instructor_id=?
                AND c.scenario_id=? AND c.scenario_version=? AND c.position=?
                AND s.status IN ('DRAFT', 'APPROVED')
            """,
                        (rs, row) ->
                                new ScenarioReviewContent(
                                        rs.getString("input_fingerprint"),
                                        json.readValue(
                                                rs.getString("content"),
                                                ScenarioReviewResponse.Candidate.class)),
                        snapshot.activityId(),
                        snapshot.reviewId(),
                        instructorId,
                        snapshot.candidate().scenarioId(),
                        snapshot.candidate().version(),
                        snapshot.currentPosition())
                .stream()
                .findFirst();
    }
}
