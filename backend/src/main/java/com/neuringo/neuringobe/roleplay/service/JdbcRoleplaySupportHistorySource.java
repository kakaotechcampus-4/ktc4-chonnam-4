package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.ai.application.prompt.GoalObservationInput;
import com.neuringo.neuringobe.ai.application.prompt.RoleplaySupportAdjustmentContext;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.Reference;
import com.neuringo.neuringobe.roleplay.application.RoleplayContextSource.Key;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalResponseHistory;
import com.neuringo.neuringobe.roleplay.application.RoleplaySupportAdjustmentPolicy;
import com.neuringo.neuringobe.roleplay.application.RoleplaySupportHistory;
import com.neuringo.neuringobe.roleplay.config.RoleplaySupportAdjustmentProperties;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JdbcRoleplaySupportHistorySource {
    private final JdbcTemplate jdbc;
    private final RoleplaySupportAdjustmentProperties rules;

    public JdbcRoleplaySupportHistorySource(
            JdbcTemplate jdbc, RoleplaySupportAdjustmentProperties rules) {
        this.jdbc = jdbc;
        this.rules = rules;
    }

    /** 승인 문맥의 같은 읽기 트랜잭션·요청 기한을 사용한다. */
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public RoleplaySupportAdjustmentContext load(
            Key key,
            RoleplayGoalResponseHistory history,
            GoalObservationInput observations,
            RoleplayTurnDeadline deadline) {
        readBudget(deadline);
        var saved =
                jdbc.query(
                        """
                select turn_id,input_micro_goal_id,input_learning_state,input_goal_outcome
                from roleplay_goal_assessment where session_id=?
                """,
                        (rs, n) ->
                                new RoleplaySupportHistory.Saved(
                                        rs.getObject(1, UUID.class),
                                        rs.getObject(2, UUID.class),
                                        rs.getString(3),
                                        rs.getString(4)),
                        key.sessionId());
        deadline.requireActive();
        readBudget(deadline);
        var basis =
                jdbc.query(
                        """
                select turn_id,micro_goal_id,criterion_reference from roleplay_input_goal_basis where session_id=?
                """,
                        (rs, n) ->
                                new Reference(
                                        rs.getObject(1, UUID.class),
                                        rs.getObject(2, UUID.class),
                                        rs.getString(3)),
                        key.sessionId());
        deadline.requireActive();
        var events = RoleplaySupportHistory.restore(history, observations, saved, basis);
        var result = RoleplaySupportAdjustmentPolicy.evaluate(events, rules.rules());
        deadline.requireActive();
        return result;
    }

    private void readBudget(RoleplayTurnDeadline deadline) {
        deadline.requireActive();
        long millis = Math.max(1, (deadline.remainingNanos() + 999999) / 1000000);
        jdbc.queryForObject(
                "select set_config('statement_timeout', ?, true)", String.class, millis + "ms");
    }
}
