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

    /** 과거 판정이 모두 확인될 때만 현재 학습 순번을 반환한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public java.util.OptionalInt nextLearningTurn(
            UUID sessionId,
            int inputOrdinal,
            com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline deadline) {
        deadline.requireActive();
        long millis = Math.max(1, (deadline.remainingNanos() + 999999) / 1000000);
        jdbc.queryForObject(
                "select set_config('statement_timeout', ?, true)", String.class, millis + "ms");
        var totals =
                jdbc.queryForMap(
                        """
                select count(*) as inputs, count(a.input_learning_state) as known,
                    count(*) filter (where a.input_learning_state <> 'NOT_ASSESSABLE') as assessable
                from roleplay_turn t
                left join roleplay_goal_assessment a on a.session_id=t.session_id and a.turn_id=t.turn_id
                where t.session_id=? and t.turn_number<?
                """,
                        sessionId,
                        inputOrdinal);
        deadline.requireActive();
        int inputs = ((Number) totals.get("inputs")).intValue();
        if (inputs != inputOrdinal - 1 || ((Number) totals.get("known")).intValue() != inputs)
            return java.util.OptionalInt.empty();
        return java.util.OptionalInt.of(
                Math.addExact(((Number) totals.get("assessable")).intValue(), 1));
    }

    /** 턴을 저장한 트랜잭션에서만 호출한다. 관찰·판정 근거가 실패하면 턴도 함께 취소한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(RoleplayGoalObservationPlan plan) {
        var proposal = plan.proposal();
        var scope = proposal.scope();
        var inputAssessment =
                proposal.inputGoalProgress() == null
                        ? null
                        : proposal.inputGoalProgress().assessment();
        var application = inputAssessment == null ? null : inputAssessment.supportApplication();
        jdbc.update(
                """
                insert into roleplay_goal_assessment
                    (session_id, turn_id, checkpoint_version, scenario_id, scenario_version,
                     approval_reference, analysis_contract_version, evaluation_contract_version, decision,
                     input_micro_goal_id, input_learning_state, input_goal_outcome, support_decision, support_application_kind)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                        : proposal.inputGoalProgress().learningState(),
                inputAssessment == null ? null : inputAssessment.outcome().name(),
                inputAssessment == null ? null : inputAssessment.supportDecision().name(),
                application == null ? null : application.kind());
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
        if (inputAssessment != null) {
            for (var ref : inputAssessment.basis()) {
                jdbc.update(
                        """
                        insert into roleplay_input_goal_basis (session_id, turn_id, micro_goal_id, criterion_reference)
                        values (?, ?, ?, ?)
                        """,
                        scope.sessionId(),
                        ref.turnId(),
                        ref.microGoalId(),
                        ref.criterionReference());
            }
        }
        if (application != null) {
            jdbc.update(
                    """
                    insert into roleplay_support_application
                        (session_id,turn_id,target_micro_goal_id,policy_version,required_independent_goals,
                         in_progress_handling,base_support_level,applied_support_level,application_kind)
                    values(?,?,?,?,?,?,?,?,?)
                    """,
                    scope.sessionId(),
                    scope.turnId(),
                    application.targetMicroGoalId(),
                    application.policyVersion(),
                    application.requiredIndependentGoals(),
                    application.inProgressHandling(),
                    application.baseSupportLevel(),
                    application.appliedSupportLevel(),
                    application.kind());
            for (var input : application.basis()) {
                for (var ref : input.basis()) {
                    jdbc.update(
                            """
                            insert into roleplay_support_application_basis
                                (session_id,application_turn_id,evidence_turn_id,micro_goal_id,criterion_reference)
                            values(?,?,?,?,?)
                            """,
                            scope.sessionId(),
                            scope.turnId(),
                            ref.turnId(),
                            ref.microGoalId(),
                            ref.criterionReference());
                }
            }
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
                        "roleplay_support_application_basis",
                        "roleplay_support_application",
                        "roleplay_input_goal_basis",
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
