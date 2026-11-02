package com.neuringo.neuringobe.scenario.repository;

import com.neuringo.neuringobe.scenario.dto.RoleplayScenarioCandidate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 역할극 단독 배정에서 강사가 고를 승인 시나리오를 읽는다. 학급은 보지 않는다(팀 결정). 같은 목표 내용(content_hash)·같은 레벨의 승인본 가운데 이 아동이 아직
 * 쓰지 않은 것이 후보다.
 *
 * <p>순서는 마지막으로 쓰인 지 가장 오래된 것부터다. 한 번도 쓰이지 않은 것이 맨 앞이고, 같으면 최근 승인본이 앞이다. "쓰였다"는 그 시나리오가 붙은 활동이 배정된
 * 시각이다.
 */
@Repository
public class RoleplayScenarioCandidateStore {
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final int LIMIT = 50;

    private final JdbcTemplate jdbc;

    public RoleplayScenarioCandidateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<RoleplayScenarioCandidate> candidates(String goalHash, String level, UUID childId) {
        var heads =
                jdbc.query(
                        """
                        select s.scenario_id, s.scenario_version, s.scenario_level, s.title, s.summary,
                               s.approved_at, u.last_used_at, coalesce(u.other_children, 0) as other_children
                        from scenario s
                            left join (
                                select a.scenario_id, max(a.assigned_at) as last_used_at,
                                       count(distinct a.child_id) filter (where a.child_id <> ?) as other_children,
                                       bool_or(a.child_id = ?) as used_by_child
                                from activity a
                                where a.scenario_id is not null
                                group by a.scenario_id
                            ) u on u.scenario_id = s.scenario_id
                        where s.status = 'APPROVED'
                          and s.goal_content_hash = ?
                          and s.scenario_level = ?
                          and s.scenario_version > 0
                          and coalesce(u.used_by_child, false) = false
                        order by u.last_used_at asc nulls first, s.approved_at desc nulls last, s.scenario_id
                        limit ?
                        """,
                        (rs, n) -> {
                            int others = rs.getInt("other_children");
                            return new RoleplayScenarioCandidate(
                                    rs.getObject("scenario_id", UUID.class),
                                    rs.getInt("scenario_version"),
                                    rs.getString("scenario_level"),
                                    rs.getString("title"),
                                    rs.getString("summary"),
                                    List.of(),
                                    kst(rs.getObject("approved_at", OffsetDateTime.class)),
                                    kst(rs.getObject("last_used_at", OffsetDateTime.class)),
                                    others,
                                    others > 0);
                        },
                        childId,
                        childId,
                        goalHash,
                        level,
                        LIMIT);
        return heads.stream()
                .map(head -> head.withMicroGoals(microGoals(head.scenarioId())))
                .toList();
    }

    /** 배정이 끝날 때까지 승인 폐기와 겹치지 않도록 시나리오 행을 잠그고 읽는다. */
    public Optional<Locked> lock(UUID scenarioId) {
        return jdbc
                .query(
                        "select status, scenario_level, goal_content_hash, scenario_version from scenario where scenario_id = ? for share",
                        (rs, n) ->
                                new Locked(
                                        rs.getString("status"),
                                        rs.getString("scenario_level"),
                                        rs.getString("goal_content_hash"),
                                        rs.getInt("scenario_version")),
                        scenarioId)
                .stream()
                .findFirst();
    }

    public boolean usedByChild(UUID scenarioId, UUID childId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists(select 1 from activity where scenario_id = ? and child_id = ?)",
                        Boolean.class,
                        scenarioId,
                        childId));
    }

    public boolean usedByOtherChildren(UUID scenarioId, UUID childId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists(select 1 from activity where scenario_id = ? and child_id <> ?)",
                        Boolean.class,
                        scenarioId,
                        childId));
    }

    private List<String> microGoals(UUID scenarioId) {
        return jdbc.queryForList(
                "select description from scenario_micro_goal where scenario_id = ? order by order_no",
                String.class,
                scenarioId);
    }

    private static OffsetDateTime kst(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(KST);
    }

    /** 잠근 시나리오의 승인 상태와 재사용 조건. */
    public record Locked(String status, String level, String goalHash, int version) {}
}
