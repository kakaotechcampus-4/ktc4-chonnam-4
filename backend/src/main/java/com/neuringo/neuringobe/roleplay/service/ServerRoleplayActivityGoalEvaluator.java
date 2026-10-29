package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.ai.application.prompt.RoleplaySupportDecisionRules;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointCommand;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputContext;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.roleplay.application.RoleplayActivityGoalEvaluator;
import com.neuringo.neuringobe.roleplay.application.RoleplayContextFingerprint;
import com.neuringo.neuringobe.roleplay.application.RoleplayContextSource;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationMapper;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 외부 호출 없이 검수 결과와 서버 자료를 대조해 저장할 변경안을 준비한다. */
@Service
public class ServerRoleplayActivityGoalEvaluator implements RoleplayActivityGoalEvaluator {
    private final ActivityRepository activities;
    private final RoleplayContextSource contexts;

    public ServerRoleplayActivityGoalEvaluator(
            ActivityRepository activities, RoleplayContextSource contexts) {
        this.activities = activities;
        this.contexts = contexts;
    }

    @Override
    public RoleplayGoalObservationPlan evaluate(
            RoleplayCheckpointCommand command,
            RoleplayTurnResult.Ready approvedTurn,
            RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(approvedTurn);
        Objects.requireNonNull(deadline).requireActive();
        if (command.approvedContextFingerprint() == null || command.inputContext() == null)
            throw new Unavailable();
        var activity =
                deadline.withinBudget(() -> activities.findById(command.activityId()))
                        .orElseThrow(Unavailable::new);
        if (!command.childId().equals(activity.getChildId())
                || !command.scenarioId().equals(activity.getScenarioId())
                || activity.getGoalId() == null) {
            throw new Unavailable();
        }
        var key =
                new RoleplayContextSource.Key(
                        command.childId(),
                        command.activityId(),
                        activity.getGoalId(),
                        command.sessionId(),
                        command.scenarioId(),
                        command.scenarioVersion(),
                        command.expectedVersion());
        var snapshot =
                deadline.withinBudget(() -> contexts.load(key, deadline))
                        .orElseThrow(Unavailable::new);
        if (!key.equals(snapshot.key())
                || snapshot.state().turnCount() != command.turnNumber()
                || !RoleplayInputContext.from(snapshot.state()).equals(command.inputContext())
                || !command.approvedContextFingerprint()
                        .equals(RoleplayContextFingerprint.of(snapshot))) {
            throw new Unavailable();
        }
        try {
            snapshot.observations()
                    .requireMatches(
                            new RoleplayTurnInput(
                                    snapshot.scenario(),
                                    snapshot.state(),
                                    new RoleplayTurnInput.LearnerTurn(command.turnId(), ""),
                                    snapshot.recentDialogue()));
            RoleplaySupportDecisionRules.requireValidAnalysis(
                    snapshot.state(), snapshot.observations(), approvedTurn.analysis());
            var plan =
                    RoleplayGoalObservationMapper.toPlan(
                            RoleplayGoalObservationMapper.contextFor(snapshot, command),
                            command,
                            approvedTurn);
            deadline.requireActive();
            return plan;
        } catch (IllegalArgumentException invalidAssessment) {
            throw new Unavailable();
        }
    }
}
