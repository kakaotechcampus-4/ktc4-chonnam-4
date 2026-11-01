package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.scenario.dto.ScenarioApprovalItem;
import com.neuringo.neuringobe.scenario.repository.ScenarioRepository;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인 대기함: 내 학급에서 퀴즈로 난이도가 정해졌고 시나리오가 아직 없는 최초 결합 활동을 기다린 순서대로 모은다. 재사용 후보는 이미 있는 공용 승인본이라 그 아동을 위한
 * 시나리오 행이 없으므로, 시나리오가 아니라 활동을 한 줄로 보여 준다.
 */
@Service
public class ScenarioApprovalService {
    public static final String DRAFT_IN_PROGRESS = "DRAFT_IN_PROGRESS";
    public static final String CANDIDATE_AVAILABLE = "CANDIDATE_AVAILABLE";
    public static final String NO_CANDIDATE = "NO_CANDIDATE";

    private final JdbcTemplate jdbc;
    private final ScenarioRepository scenarios;

    public ScenarioApprovalService(JdbcTemplate jdbc, ScenarioRepository scenarios) {
        this.jdbc = jdbc;
        this.scenarios = scenarios;
    }

    @Transactional(readOnly = true)
    public List<ScenarioApprovalItem> list(UUID instructorId) {
        return jdbc.query(
                """
                select a.activity_id, c.child_id, c.display_name, cl.class_id, cl.name as class_name,
                       g.goal_id, g.title as goal_title, g.content_hash, r.scenario_level, r.snapshotted_at,
                       d.scenario_id as draft_id, d.scenario_version as draft_version
                from activity a
                join child c on c.child_id = a.child_id and c.status = 'ACTIVE'
                join classroom cl on cl.class_id = c.class_id and cl.instructor_id = ?
                join learning_goal g on g.goal_id = a.goal_id and g.child_id = c.child_id
                join quiz_result r on r.activity_id = a.activity_id and r.initial_difficulty_used
                left join scenario d on d.goal_id = g.goal_id and d.status = 'DRAFT'
                where a.activity_type = 'FIRST_COMBINED'
                  and a.scenario_id is null
                  and a.status <> 'COMPLETED'
                order by r.snapshotted_at, a.activity_id
                """,
                (rs, n) -> {
                    UUID childId = rs.getObject("child_id", UUID.class);
                    UUID draftId = rs.getObject("draft_id", UUID.class);
                    String level = rs.getString("scenario_level");
                    String state;
                    if (draftId != null) {
                        state = DRAFT_IN_PROGRESS;
                    } else if (!scenarios
                            .findReuseCandidates(rs.getString("content_hash"), level, childId)
                            .isEmpty()) {
                        state = CANDIDATE_AVAILABLE;
                    } else {
                        state = NO_CANDIDATE;
                    }
                    Timestamp since = rs.getTimestamp("snapshotted_at");
                    return new ScenarioApprovalItem(
                            rs.getObject("activity_id", UUID.class),
                            childId,
                            rs.getString("display_name"),
                            rs.getObject("class_id", UUID.class),
                            rs.getString("class_name"),
                            rs.getObject("goal_id", UUID.class),
                            rs.getString("goal_title"),
                            level,
                            since.toInstant(),
                            state,
                            draftId,
                            draftId == null ? null : rs.getInt("draft_version"));
                },
                instructorId);
    }
}
