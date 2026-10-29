package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleplayGoalObservationStore {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public RoleplayGoalObservationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    /** 턴을 저장한 트랜잭션에서만 호출한다. 관찰·판정 근거가 실패하면 턴도 함께 취소한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(RoleplayGoalObservationPlan plan) {
        var proposal = plan.proposal();
        var scope = proposal.scope();
        jdbc.update(
                """
                insert into roleplay_goal_assessment
                    (session_id, turn_id, checkpoint_version, scenario_id, scenario_version,
                     approval_reference, analysis_contract_version, evaluation_contract_version, decision,
                     input_micro_goal_id, input_learning_state)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                scope.sessionId(),
                scope.turnId(),
                Math.addExact(scope.checkpointVersion(), 1),
                scope.scenarioId(),
                scope.scenarioVersion(),
                scope.approvalReference(),
                proposal.analysisContractVersion(),
                proposal.evaluationContractVersion(),
                proposal.decision().name(),
                proposal.inputGoalProgress() == null
                        ? null
                        : proposal.inputGoalProgress().microGoalId(),
                proposal.inputGoalProgress() == null
                        ? null
                        : proposal.inputGoalProgress().learningState());
        for (var observation : proposal.observations()) {
            var ref = observation.reference();
            jdbc.update(
                    """
                    insert into roleplay_goal_observation
                        (session_id, turn_id, micro_goal_id, criterion_reference, observation_status)
                    values (?, ?, ?, ?, ?)
                    """,
                    scope.sessionId(),
                    ref.turnId(),
                    ref.microGoalId(),
                    ref.criterionReference(),
                    observation.status().name());
        }
        for (var ref : proposal.decisionBasis()) {
            jdbc.update(
                    """
                    insert into roleplay_goal_decision_basis
                        (session_id, decision_turn_id, observation_turn_id, micro_goal_id, criterion_reference)
                    values (?, ?, ?, ?, ?)
                    """,
                    scope.sessionId(),
                    scope.turnId(),
                    ref.turnId(),
                    ref.microGoalId(),
                    ref.criterionReference());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteByChildIds(List<UUID> childIds) {
        if (childIds.isEmpty()) return;
        var parameters = Map.of("children", childIds);
        for (var table :
                List.of(
                        "roleplay_goal_decision_basis",
                        "roleplay_goal_observation",
                        "roleplay_goal_assessment")) {
            named.update(
                    "delete from "
                            + table
                            + " where session_id in (select session_id from roleplay_session where child_id in (:children))",
                    parameters);
        }
    }
}
