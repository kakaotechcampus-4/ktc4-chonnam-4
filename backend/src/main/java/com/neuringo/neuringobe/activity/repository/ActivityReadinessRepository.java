package com.neuringo.neuringobe.activity.repository;

import com.neuringo.neuringobe.activity.domain.ActivityReadinessPolicy.Evidence;
import com.neuringo.neuringobe.activity.domain.ActivityReadinessPolicy.QuizState;
import com.neuringo.neuringobe.activity.domain.ActivityReadinessPolicy.ScenarioState;
import com.neuringo.neuringobe.activity.domain.ActivityReadinessPolicy.SessionState;
import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.domain.ActivityType;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ActivityReadinessRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    public ActivityReadinessRepository(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 현재 페이지의 활동만 읽으며, 다대다 자료는 활동별 집계로 행 중복을 막는다. */
    public Map<UUID, Evidence> load(
            UUID childId, Collection<UUID> ids, Map<String, List<String>> composition) {
        if (ids.isEmpty()) return Map.of();
        var parameters =
                new MapSqlParameterSource()
                        .addValue("child", childId)
                        .addValue("ids", ids)
                        .addValue("composition", json.writeValueAsString(composition));
        return jdbc.query(
                """
            WITH page AS MATERIALIZED (
                SELECT a.* FROM activity a WHERE a.child_id=:child AND a.activity_id IN (:ids)
            ), quiz AS (
                SELECT a.activity_id, count(q.activity_quiz_id) AS assigned,
                    count(DISTINCT q.item_id) AS distinct_items,
                    min(q.question_order) AS first_order, max(q.question_order) AS last_order,
                    coalesce(bool_and(i.item_id IS NOT NULL AND i.item_version=q.item_version
                        AND i.status='APPROVED' AND length(btrim(i.question_text))>0
                        AND (i.quiz_type<>'OTHER_EMOTION_IMAGE' OR length(btrim(i.image_url))>0)
                        AND CASE WHEN jsonb_typeof(i.choices_json)='array' THEN
                            jsonb_array_length(i.choices_json)>0 AND jsonb_exists(i.choices_json, i.correct_answer)
                            AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(i.choices_json) e
                                WHERE jsonb_typeof(e)<>'string' OR length(btrim(e #>> '{}'))=0)
                            ELSE false END), false) AS items_valid,
                    coalesce(jsonb_agg(i.quiz_type ORDER BY i.quiz_type)
                        FILTER (WHERE q.activity_quiz_id IS NOT NULL), '[]') AS types,
                    count(*) FILTER (WHERE t.status='FINALIZED') AS finalized
                FROM page a LEFT JOIN activity_quiz q ON q.activity_id=a.activity_id
                LEFT JOIN quiz_item i ON i.item_id=q.item_id
                LEFT JOIN quiz_attempt t ON t.activity_quiz_id=q.activity_quiz_id
                GROUP BY a.activity_id
            ), sessions AS (
                SELECT a.activity_id, count(s.session_id) AS session_count,
                    coalesce(bool_and(s.child_id=a.child_id AND s.scenario_id=a.scenario_id
                        AND EXISTS (SELECT 1 FROM scenario sc WHERE sc.scenario_id=a.scenario_id
                            AND sc.scenario_version=s.scenario_version)), false) AS scenario_matches,
                    coalesce(bool_and(s.status IN ('IN_PROGRESS','PAUSED')
                        AND s.current_support_level IN ('S0','S1','S2','S3')
                        AND s.last_turn_number BETWEEN 0 AND 2147483646
                        AND EXISTS (SELECT 1 FROM scenario_micro_goal mg
                            WHERE mg.scenario_id=s.scenario_id AND mg.micro_goal_id=s.current_micro_goal_id)
                        AND CASE WHEN s.last_turn_number=0 THEN
                            s.last_turn_id IS NULL AND EXISTS (
                                SELECT 1 FROM scenario_opening_focus f
                                WHERE f.scenario_id=s.scenario_id AND f.support_level=s.current_support_level
                                    AND f.micro_goal_id=s.current_micro_goal_id)
                            AND (EXISTS (SELECT 1 FROM roleplay_start_message m
                                WHERE m.session_id=s.session_id AND m.scenario_id=s.scenario_id
                                    AND m.scenario_version=s.scenario_version AND length(btrim(m.opening_text))>0)
                                OR EXISTS (SELECT 1 FROM scenario sc WHERE sc.scenario_id=s.scenario_id
                                    AND jsonb_typeof(sc.opening_prompts -> s.current_support_level)='string'
                                    AND length(btrim(sc.opening_prompts ->> s.current_support_level))>0))
                        ELSE EXISTS (SELECT 1 FROM roleplay_turn t
                            WHERE t.session_id=s.session_id AND t.turn_id=s.last_turn_id
                                AND t.turn_number=s.last_turn_number AND t.checkpoint_version<=s.row_version
                                AND length(btrim(t.response_text))>0) END), false) AS resumable
                FROM page a LEFT JOIN roleplay_session s ON s.activity_id=a.activity_id
                GROUP BY a.activity_id
            )
            SELECT a.activity_id, a.activity_type, a.status,
                CASE WHEN a.activity_type='ROLEPLAY' THEN 'NOT_REQUIRED'
                    WHEN r.activity_id IS NOT NULL THEN 'COMPLETED'
                    WHEN q.assigned>0 AND q.first_order=1 AND q.last_order=q.assigned
                        AND q.distinct_items=q.assigned AND q.items_valid
                        AND q.types=(CAST(:composition AS jsonb) -> a.activity_type)
                        AND q.finalized<q.assigned THEN 'INCOMPLETE'
                    ELSE 'INVALID' END AS quiz_state,
                coalesce((a.activity_type='FIRST_COMBINED' AND g.child_id=a.child_id
                    AND r.initial_difficulty_used AND a.initial_support_level=r.initial_support_level
                    AND a.difficulty_fallback_applied=r.difficulty_fallback_applied
                    AND r.scenario_level IN ('L1','L2','L3') AND r.initial_support_level IN ('S0','S1','S2','S3')
                    AND r.valid_question_count>=0 AND r.correct_question_count>=0
                    AND r.correct_question_count<=r.valid_question_count
                    AND (r.valid_question_count>0 OR r.difficulty_fallback_applied)
                    AND length(btrim(r.policy_version))>0 AND length(btrim(g.content_hash))>0)
                    OR (a.activity_type='ROLEPLAY' AND g.child_id=a.child_id
                    AND a.initial_support_level=d.initial_support_level AND NOT a.difficulty_fallback_applied
                    AND length(btrim(g.content_hash))>0), false) AS difficulty_ready,
                CASE WHEN a.scenario_id IS NULL THEN 'UNASSIGNED'
                    WHEN EXISTS (SELECT 1 FROM activity_approved_scenario v
                        WHERE v.activity_id=a.activity_id AND v.scenario_id=a.scenario_id
                            AND (s.session_count>0 OR v.scenario_level=coalesce(r.scenario_level, d.scenario_level)))
                        AND (s.session_count=0 OR s.scenario_matches)
                        AND EXISTS (SELECT 1 FROM scenario_micro_goal mg WHERE mg.scenario_id=a.scenario_id)
                        AND NOT EXISTS (SELECT 1 FROM scenario_micro_goal mg WHERE mg.scenario_id=a.scenario_id
                            AND (length(btrim(mg.description))=0 OR CASE WHEN jsonb_typeof(mg.required_evidence)='array' THEN
                                jsonb_array_length(mg.required_evidence)=0 OR EXISTS (
                                    SELECT 1 FROM jsonb_array_elements(mg.required_evidence) e
                                    WHERE jsonb_typeof(e)<>'string' OR length(btrim(e #>> '{}'))=0)
                                ELSE true END))
                        AND (s.session_count>0 OR EXISTS (
                            SELECT 1 FROM scenario sc JOIN scenario_opening_focus f ON f.scenario_id=sc.scenario_id
                            WHERE sc.scenario_id=a.scenario_id
                                AND f.support_level=coalesce(r.initial_support_level, d.initial_support_level)
                                AND jsonb_typeof(sc.opening_prompts -> coalesce(r.initial_support_level, d.initial_support_level))='string'
                                AND length(btrim(sc.opening_prompts ->> coalesce(r.initial_support_level, d.initial_support_level)))>0))
                    THEN 'READY' ELSE 'INVALID' END AS scenario_state,
                CASE WHEN s.session_count=0 THEN 'ABSENT'
                    WHEN s.session_count>1 THEN 'AMBIGUOUS'
                    WHEN s.resumable THEN 'RESUMABLE' ELSE 'INVALID' END AS session_state
            FROM page a JOIN quiz q USING(activity_id) JOIN sessions s USING(activity_id)
            LEFT JOIN quiz_result r ON r.activity_id=a.activity_id
            -- 역할극 단독은 퀴즈 결과 대신 배정 때 고정한 다음 역할극 난이도가 레벨·시작 지원의 근거다.
            LEFT JOIN roleplay_activity_difficulty d ON d.activity_id=a.activity_id AND a.activity_type='ROLEPLAY'
            LEFT JOIN learning_goal g ON g.goal_id=a.goal_id
            """,
                parameters,
                rs -> {
                    var result = new LinkedHashMap<UUID, Evidence>();
                    while (rs.next()) {
                        result.put(
                                rs.getObject("activity_id", UUID.class),
                                new Evidence(
                                        ActivityType.valueOf(rs.getString("activity_type")),
                                        ActivityStatus.valueOf(rs.getString("status")),
                                        QuizState.valueOf(rs.getString("quiz_state")),
                                        rs.getBoolean("difficulty_ready"),
                                        ScenarioState.valueOf(rs.getString("scenario_state")),
                                        SessionState.valueOf(rs.getString("session_state"))));
                    }
                    return result;
                });
    }
}
