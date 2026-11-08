package com.neuringo.neuringobe.scenario.repository;

import com.neuringo.neuringobe.ai.application.prompt.ScenarioPromptFactory;
import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ScenarioDraftRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public ScenarioDraftRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID insert(GeneratedScenario scenario) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO scenario (scenario_id, status, scenario_level, title, summary, ai_role,
                    characters, scenario_facts, prohibited_inferences, opening_prompts, origin,
                    prompt_version, created_at)
                VALUES (?, 'DRAFT', ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
                    CAST(? AS jsonb), CAST(? AS jsonb), 'AI_GENERATED', ?, now())
                """,
                id,
                scenario.scenarioLevel(),
                scenario.title(),
                scenario.summary(),
                scenario.aiRole(),
                json.writeValueAsString(scenario.characters()),
                json.writeValueAsString(scenario.scenarioFacts()),
                json.writeValueAsString(scenario.prohibitedInferences()),
                json.writeValueAsString(scenario.startingQuestions()),
                ScenarioPromptFactory.SCENARIO_GENERATION_PROMPT_VERSION);
        var ids = new HashMap<String, UUID>();
        int position = 0;
        for (var goal : scenario.microGoals()) {
            UUID goalId = UUID.randomUUID();
            ids.put(goal.id(), goalId);
            jdbc.update(
                    """
                    INSERT INTO scenario_micro_goal (micro_goal_id, scenario_id, order_no, description, required_evidence, achievement_example)
                    VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                    """,
                    goalId,
                    id,
                    ++position,
                    goal.description(),
                    json.writeValueAsString(goal.requiredEvidence()),
                    goal.achievementExample());
        }
        scenario.startingQuestionGoalIds()
                .forEach(
                        (support, goal) ->
                                jdbc.update(
                                        "INSERT INTO scenario_opening_focus (scenario_id, support_level, micro_goal_id) VALUES (?, ?, ?)",
                                        id,
                                        support,
                                        ids.get(goal)));
        return id;
    }

    public Map<UUID, String> microGoalKeys(UUID scenarioId) {
        return jdbc.query(
                "SELECT micro_goal_id, order_no FROM scenario_micro_goal WHERE scenario_id = ?",
                rs -> {
                    Map<UUID, String> result = new HashMap<>();
                    while (rs.next())
                        result.put(
                                rs.getObject(1, UUID.class),
                                String.format(java.util.Locale.ROOT, "MG-%02d", rs.getInt(2)));
                    return result;
                },
                scenarioId);
    }

    public UUID insertEdited(UUID sourceId, int sourceVersion, GeneratedScenario content) {
        UUID id = insert(content);
        jdbc.update(
                """
                UPDATE scenario SET source_scenario_id = ?, scenario_version = ?, origin = 'INSTRUCTOR_EDITED',
                    prompt_version = (SELECT prompt_version FROM scenario WHERE scenario_id = ?),
                    policy_version = (SELECT policy_version FROM scenario WHERE scenario_id = ?)
                WHERE scenario_id = ?
                """,
                sourceId,
                Math.addExact(sourceVersion, 1),
                sourceId,
                sourceId,
                id);
        return id;
    }

    public GeneratedScenario lastRejectedGenerated(UUID reviewId) {
        var ids =
                jdbc.query(
                        """
                SELECT c.scenario_id FROM scenario_review_candidate c JOIN scenario s USING (scenario_id)
                WHERE review_id = ? AND candidate_source = 'GENERATED' AND rejected_at IS NOT NULL
                ORDER BY position DESC LIMIT 1
                """,
                        (rs, row) -> rs.getObject(1, UUID.class),
                        reviewId);
        if (ids.isEmpty()) return null;
        return read(ids.getFirst());
    }

    public GeneratedScenario read(UUID scenarioId) {
        return jdbc.queryForObject(
                """
                SELECT jsonb_build_object(
                    'title', s.title, 'summary', s.summary, 'scenario_level', s.scenario_level,
                    'ai_role', s.ai_role, 'characters', s.characters, 'scenario_facts', s.scenario_facts,
                    'prohibited_inferences', s.prohibited_inferences, 'starting_questions', s.opening_prompts,
                    'starting_question_goal_ids', coalesce((SELECT jsonb_object_agg(f.support_level, 'MG-' || lpad(m.order_no::text, 2, '0'))
                        FROM scenario_opening_focus f JOIN scenario_micro_goal m ON m.scenario_id = f.scenario_id AND m.micro_goal_id = f.micro_goal_id
                        WHERE f.scenario_id = s.scenario_id), '{}'),
                    'micro_goals', (SELECT jsonb_agg(jsonb_build_object(
                        'id', 'MG-' || lpad(m.order_no::text, 2, '0'), 'description', m.description,
                        'required_evidence', m.required_evidence, 'achievement_example', m.achievement_example)
                        ORDER BY m.order_no) FROM scenario_micro_goal m WHERE m.scenario_id = s.scenario_id))::text
                FROM scenario s WHERE s.scenario_id = ?
                """,
                (rs, row) -> json.readValue(rs.getString(1), GeneratedScenario.class),
                scenarioId);
    }
}
