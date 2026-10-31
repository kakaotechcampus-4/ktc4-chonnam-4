package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.ai.application.prompt.RoleplayCodes;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.roleplay.application.RoleplayContextSource.Key;
import com.neuringo.neuringobe.roleplay.application.RoleplayProgressPolicy;
import com.neuringo.neuringobe.roleplay.application.RoleplayProgressSource;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JdbcRoleplayProgressSource implements RoleplayProgressSource {
    private final JdbcTemplate jdbc;
    private final Optional<RoleplayProgressPolicy> policy;

    public JdbcRoleplayProgressSource(JdbcTemplate jdbc, Optional<RoleplayProgressPolicy> policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    /** 승인 문맥을 읽는 트랜잭션에 참여해 다른 저장 시점의 상태를 섞지 않는다. */
    @Override
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public Optional<Progress> load(Key key, RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(deadline).requireActive();
        if (policy.isEmpty()) return Optional.empty();
        long millis = Math.max(1, (deadline.remainingNanos() + 999999) / 1000000);
        jdbc.queryForObject(
                "select set_config('statement_timeout', ?, true)", String.class, millis + "ms");
        var rows =
                jdbc.query(
                        """
                select s.current_micro_goal_id, s.current_support_level, s.last_turn_number
                from roleplay_session s
                join activity a on a.activity_id=s.activity_id and a.child_id=s.child_id
                join child c on c.child_id=s.child_id
                join learning_goal g on g.goal_id=a.goal_id and g.child_id=c.child_id
                join scenario sc on sc.scenario_id=s.scenario_id and sc.scenario_version=s.scenario_version
                where s.session_id=? and s.child_id=? and s.activity_id=? and a.goal_id=?
                  and s.scenario_id=? and a.scenario_id=s.scenario_id and s.scenario_version=?
                  and s.row_version=? and s.status='IN_PROGRESS' and a.status='IN_PROGRESS'
                  and c.status='ACTIVE' and sc.status='APPROVED'
                  and sc.class_id=c.class_id and sc.goal_content_hash=g.content_hash
                """,
                        (rs, n) ->
                                new Saved(
                                        rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)),
                        key.sessionId(),
                        key.childId(),
                        key.activityId(),
                        key.goalId(),
                        key.scenarioId(),
                        key.scenarioVersion(),
                        key.checkpointVersion());
        deadline.requireActive();
        if (rows.size() != 1) return Optional.empty();
        var saved = rows.getFirst();
        if (saved.support() == null
                || !RoleplayCodes.SUPPORT_LEVELS.contains(saved.support())
                || saved.lastTurn() < 0
                || saved.lastTurn() == Integer.MAX_VALUE
                || saved.goal() == null) return Optional.empty();
        var goals =
                new HashSet<>(
                        jdbc.query(
                                "select micro_goal_id from scenario_micro_goal where scenario_id=?",
                                (rs, n) -> rs.getObject(1, UUID.class),
                                key.scenarioId()));
        deadline.requireActive();
        if (goals.isEmpty() || (saved.goal() != null && !goals.contains(saved.goal())))
            return Optional.empty();
        var stored =
                new RoleplayProgressPolicy.Stored(
                        key, saved.goal(), saved.support(), saved.lastTurn() + 1, goals);
        var selected = deadline.withinBudget(() -> policy.orElseThrow().select(stored, deadline));
        if (selected == null || selected.isEmpty()) return Optional.empty();
        var selection = selected.get();
        if (!goals.contains(selection.currentMicroGoalId())
                || !goals.equals(selection.microGoalStatuses().keySet())
                || (saved.goal() != null && !saved.goal().equals(selection.currentMicroGoalId())))
            return Optional.empty();
        return Optional.of(
                new Progress(
                        selection.currentMicroGoalId(),
                        saved.support(),
                        stored.turnCount(),
                        selection.maximumTurnCount(),
                        selection.maximumSupportTurnsPerMicroGoal(),
                        selection.microGoalStatuses()));
    }

    private record Saved(UUID goal, String support, int lastTurn) {}
}
