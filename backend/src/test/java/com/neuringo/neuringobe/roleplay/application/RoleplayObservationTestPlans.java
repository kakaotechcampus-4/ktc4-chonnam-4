package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.prompt.GoalObservationInput;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointCommand;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 실제 모델 판정을 대신하는 테스트 전용 관찰 공급이다. */
public final class RoleplayObservationTestPlans {
    private RoleplayObservationTestPlans() {}

    public static RoleplayGoalResponseHistory emptyResponseHistory(
            RoleplayTurnInput.Scenario scenario) {
        var goals = new java.util.HashMap<UUID, RoleplayGoalResponseHistory.GoalResponses>();
        scenario.microGoals()
                .forEach(
                        g ->
                                goals.put(
                                        g.id(),
                                        new RoleplayGoalResponseHistory.GoalResponses(List.of())));
        return new RoleplayGoalResponseHistory(
                goals, RoleplayGoalInputHistory.restore(goals.keySet(), List.of()));
    }

    /** DB 이력을 쓰지 않는 테스트에서 관찰의 입력 연결을 명시한다. */
    public static GoalObservationInput withSyntheticObservationContexts(
            List<GoalObservationInput.Criterion> criteria,
            List<GoalObservationOutput.Observation> observations) {
        var sources = new java.util.LinkedHashMap<UUID, Integer>();
        var contexts = new ArrayList<GoalObservationInput.ObservationContext>();
        for (var observation : observations) {
            var ref = observation.reference();
            int number = sources.computeIfAbsent(ref.turnId(), ignored -> sources.size() + 1);
            contexts.add(
                    new GoalObservationInput.ObservationContext(
                            ref,
                            UUID.nameUUIDFromBytes(
                                    ("synthetic-candidate:" + ref.turnId())
                                            .getBytes(StandardCharsets.UTF_8)),
                            number,
                            number,
                            new com.neuringo.neuringobe.ai.application.roleplay
                                    .RoleplayInputContext(
                                    criteria.getFirst().microGoalId(), "S1", 1, 0, false),
                            new GoalObservationInput.GoalExposure(0, false, null)));
        }
        return new GoalObservationInput(
                criteria, observations, contexts, withoutPriorSupport(criteria));
    }

    public static List<GoalObservationInput.CurrentGoalExposure> withoutPriorSupport(
            List<GoalObservationInput.Criterion> criteria) {
        return criteria.stream()
                .map(GoalObservationInput.Criterion::microGoalId)
                .distinct()
                .sorted()
                .map(
                        goal ->
                                new GoalObservationInput.CurrentGoalExposure(
                                        goal,
                                        new GoalObservationInput.GoalExposure(0, false, null)))
                .toList();
    }

    public static GoalObservationInput inputForScenario(RoleplayTurnInput.Scenario scenario) {
        var criteria = new ArrayList<GoalObservationInput.Criterion>();
        for (var goal : scenario.microGoals()) {
            for (int i = 0; i < goal.requiredEvidence().size(); i++) {
                criteria.add(
                        new GoalObservationInput.Criterion(
                                goal.id(),
                                "test-evidence:" + goal.id() + ":" + i,
                                goal.requiredEvidence().get(i)));
            }
        }
        return new GoalObservationInput(
                criteria, List.of(), List.of(), withoutPriorSupport(criteria));
    }

    public static RoleplayGoalObservationPlan forCommand(
            RoleplayCheckpointCommand command, boolean achieved) {
        return forScope(
                new Scope(
                        command.sessionId(),
                        command.scenarioId(),
                        command.scenarioVersion(),
                        "test-approval",
                        command.turnId(),
                        command.candidateId(),
                        command.expectedVersion()),
                command.microGoalId(),
                achieved);
    }

    public static RoleplayGoalObservationPlan forScope(
            Scope scope, UUID nextFocus, boolean achieved) {
        var observedGoal =
                UUID.nameUUIDFromBytes(
                        (scope.sessionId() + ":observed-goal").getBytes(StandardCharsets.UTF_8));
        var ref = new EvidenceReference(scope.turnId(), observedGoal, "test-evidence");
        var context =
                new Context(
                        scope,
                        Map.of(
                                nextFocus,
                                Set.of("next-focus-evidence"),
                                observedGoal,
                                Set.of("test-evidence")),
                        List.of(),
                        GoalObservationOutput.ANALYSIS_VERSION,
                        GoalObservationOutput.EVALUATION_VERSION);
        return RoleplayGoalObservationPlan.validateReferences(
                context,
                new Proposal(
                        scope,
                        context.analysisContractVersion(),
                        context.evaluationContractVersion(),
                        List.of(new Observation(ref, ObservationStatus.EXPLICIT)),
                        nextFocus,
                        achieved
                                ? RoleplayActivityGoalEvaluator.Decision.ACHIEVED
                                : RoleplayActivityGoalEvaluator.Decision.IN_PROGRESS,
                        Set.of(ref)));
    }
}
