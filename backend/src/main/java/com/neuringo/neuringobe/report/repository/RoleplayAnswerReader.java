package com.neuringo.neuringobe.report.repository;

import com.neuringo.neuringobe.report.application.RoleplayAnswerRows;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 완료한 역할극 활동의 답 기록을 읽는다. 역할극 답 기록 테이블은 팀 코드에서도 JDBC 로만 다룬다(RoleplayGoalObservationStore).
 *
 * <p>발화 원문 칸(roleplay_turn.canonical_utterance·response_text)은 고르지 않는다. 완료 트랜잭션이 원문을 이미 지웠더라도 이 조회는
 * 영향을 받지 않는다.
 */
@Repository
public class RoleplayAnswerReader {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public RoleplayAnswerReader(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 활동에 완료한 역할극 세션이 정확히 하나 있고 학습 기록이 있을 때만 값을 돌려준다. */
    public Optional<RoleplayAnswerRows> readCompleted(UUID activityId) {
        var heads =
                jdbc.query(
                        """
                        select r.record_id, r.created_at, s.session_id, s.scenario_id,
                               sc.scenario_level, a.initial_support_level
                        from activity a
                            join learning_record r on r.activity_id = a.activity_id
                            join roleplay_session s on s.activity_id = a.activity_id and s.child_id = a.child_id
                            join scenario sc on sc.scenario_id = s.scenario_id
                        where a.activity_id = ? and a.status = 'COMPLETED' and s.status = 'COMPLETED'
                        """,
                        (rs, n) ->
                                new Head(
                                        rs.getObject("record_id", UUID.class),
                                        rs.getObject("created_at", OffsetDateTime.class)
                                                .toInstant(),
                                        rs.getObject("session_id", UUID.class),
                                        rs.getObject("scenario_id", UUID.class),
                                        rs.getString("scenario_level"),
                                        rs.getString("initial_support_level")),
                        activityId);
        if (heads.size() != 1) {
            return Optional.empty();
        }
        Head head = heads.getFirst();
        var goals =
                jdbc.query(
                        "select micro_goal_id, order_no, description, required_evidence::text as evidence from scenario_micro_goal where scenario_id = ? order by order_no",
                        (rs, n) ->
                                new RoleplayAnswerRows.MicroGoalRow(
                                        rs.getObject("micro_goal_id", UUID.class),
                                        rs.getInt("order_no"),
                                        rs.getString("description"),
                                        strings(rs.getString("evidence"))),
                        head.scenarioId());
        var turns =
                jdbc.query(
                        """
                        select t.turn_id, t.turn_number, t.input_micro_goal_id, t.input_support_level,
                               t.input_answer_revealed, g.input_learning_state
                        from roleplay_turn t
                            join roleplay_goal_assessment g on g.session_id = t.session_id and g.turn_id = t.turn_id
                        where t.session_id = ? and t.input_micro_goal_id is not null
                            and g.input_learning_state is not null
                        order by t.turn_number
                        """,
                        (rs, n) ->
                                new RoleplayAnswerRows.TurnRow(
                                        rs.getObject("turn_id", UUID.class),
                                        rs.getInt("turn_number"),
                                        rs.getObject("input_micro_goal_id", UUID.class),
                                        rs.getString("input_support_level"),
                                        rs.getBoolean("input_answer_revealed"),
                                        rs.getString("input_learning_state")),
                        head.sessionId());
        var observations =
                jdbc.query(
                        "select turn_id, micro_goal_id, criterion_reference, observation_status from roleplay_goal_observation where session_id = ?",
                        (rs, n) ->
                                new RoleplayAnswerRows.ObservationRow(
                                        rs.getObject("turn_id", UUID.class),
                                        rs.getObject("micro_goal_id", UUID.class),
                                        rs.getString("criterion_reference"),
                                        rs.getString("observation_status")),
                        head.sessionId());
        return Optional.of(
                new RoleplayAnswerRows(
                        head.recordId(),
                        head.completedAt(),
                        head.sessionId(),
                        head.scenarioId(),
                        head.scenarioLevel(),
                        head.startSupport(),
                        goals,
                        turns,
                        observations));
    }

    private List<String> strings(String json) {
        return json == null ? List.of() : mapper.readValue(json, STRINGS);
    }

    private record Head(
            UUID recordId,
            Instant completedAt,
            UUID sessionId,
            UUID scenarioId,
            String scenarioLevel,
            String startSupport) {}
}
