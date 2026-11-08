package com.neuringo.neuringobe.scenario.repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ScenarioCandidateContentRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    public ScenarioCandidateContentRepository(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Map<UUID, List<String>> readDisplayFields(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc.query(
                """
                SELECT s.scenario_id, s.title, s.summary, s.characters::text,
                    s.scenario_facts::text, s.opening_prompts::text,
                    coalesce((SELECT jsonb_agg(jsonb_build_object(
                        'description', m.description, 'required_evidence', m.required_evidence,
                        'achievement_example', m.achievement_example))
                        FROM scenario_micro_goal m WHERE m.scenario_id = s.scenario_id), '[]')::text AS micro_goals
                FROM scenario s WHERE s.scenario_id = ANY(:ids) AND s.status = 'APPROVED'
                """,
                Map.of("ids", new SqlArrayValue("uuid", ids.toArray())),
                rs -> {
                    Map<UUID, List<String>> fields = new HashMap<>();
                    while (rs.next()) {
                        var texts = new ArrayList<String>();
                        texts.add(rs.getString("title"));
                        if (rs.getString("summary") != null) texts.add(rs.getString("summary"));
                        try {
                            JsonNode characters = json.readTree(rs.getString("characters"));
                            requireArray(characters);
                            for (JsonNode character : characters)
                                addText(texts, character.get("role"));
                            addArray(texts, json.readTree(rs.getString("scenario_facts")));
                            JsonNode openings = json.readTree(rs.getString("opening_prompts"));
                            if (!openings.isObject()) throw new IllegalArgumentException();
                            for (JsonNode opening : openings) addText(texts, opening);
                            JsonNode goals = json.readTree(rs.getString("micro_goals"));
                            requireArray(goals);
                            for (JsonNode goal : goals) {
                                addText(texts, goal.get("description"));
                                addArray(texts, goal.get("required_evidence"));
                                JsonNode example = goal.get("achievement_example");
                                if (example != null && !example.isNull()) addText(texts, example);
                            }
                            fields.put(rs.getObject("scenario_id", UUID.class), List.copyOf(texts));
                        } catch (IllegalArgumentException invalidContent) {
                            // 표시 필드의 구조를 확인할 수 없으면 금지 표현 검사를 통과시키지 않는다.
                        }
                    }
                    return fields;
                });
    }

    private void addArray(List<String> texts, JsonNode node) {
        requireArray(node);
        for (JsonNode item : node) addText(texts, item);
    }

    private void requireArray(JsonNode node) {
        if (node == null || !node.isArray()) throw new IllegalArgumentException();
    }

    private void addText(List<String> texts, JsonNode node) {
        if (node == null || !node.isString()) throw new IllegalArgumentException();
        texts.add(node.asString());
    }
}
