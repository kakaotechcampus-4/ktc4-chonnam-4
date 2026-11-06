package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.scenario.dto.ScenarioApprovalItem;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인 대기함: 내 학급에서 퀴즈로 난이도가 정해졌고 시나리오가 아직 없는 최초 결합 활동을 기다린 순서대로 모은다. 재사용 후보는 이미 있는 공용 승인본이라 그 아동을 위한
 * 시나리오 행이 없으므로, 시나리오가 아니라 활동을 한 줄로 보여 준다.
 *
 * <p>검토를 시작해 서버에 남은 검토(scenario_review)가 있으면 IN_REVIEW, 없으면 WAITING 이다. 후보가 있는지는 줄마다 조회하지 않는다. 후보
 * 목록은 검토를 시작할 때 정해지고, 없으면 그 자리에서 새로 만들기 때문이다.
 */
@Service
public class ScenarioApprovalService {
    public static final String WAITING = "WAITING";
    public static final String IN_REVIEW = "IN_REVIEW";

    private final JdbcTemplate jdbc;

    public ScenarioApprovalService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<ScenarioApprovalItem> list(UUID instructorId) {
        return jdbc.query(
                """
                select a.activity_id, c.child_id, c.display_name, cl.class_id, cl.name as class_name,
                       g.goal_id, g.title as goal_title, r.scenario_level, r.snapshotted_at,
                       sr.review_id
                from activity a
                join child c on c.child_id = a.child_id and c.status = 'ACTIVE'
                join classroom cl on cl.class_id = c.class_id and cl.instructor_id = ?
                join learning_goal g on g.goal_id = a.goal_id and g.child_id = c.child_id
                join quiz_result r on r.activity_id = a.activity_id and r.initial_difficulty_used
                left join scenario_review sr on sr.activity_id = a.activity_id
                where a.activity_type = 'FIRST_COMBINED'
                  and a.scenario_id is null
                  and a.status <> 'COMPLETED'
                order by r.snapshotted_at, a.activity_id
                """,
                (rs, n) -> {
                    Timestamp since = rs.getTimestamp("snapshotted_at");
                    return new ScenarioApprovalItem(
                            rs.getObject("activity_id", UUID.class),
                            rs.getObject("child_id", UUID.class),
                            rs.getString("display_name"),
                            rs.getObject("class_id", UUID.class),
                            rs.getString("class_name"),
                            rs.getObject("goal_id", UUID.class),
                            rs.getString("goal_title"),
                            rs.getString("scenario_level"),
                            since.toInstant(),
                            rs.getObject("review_id", UUID.class) == null ? WAITING : IN_REVIEW);
                },
                instructorId);
    }
}
