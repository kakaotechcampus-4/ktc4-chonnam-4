package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.prompt.GoalObservationInput;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayCodes;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputContext;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 관찰을 같은 턴의 입력 상태와 관찰 목표의 사전 지원 이력에 연결한다. */
public final class RoleplayGoalObservationHistory {
    private RoleplayGoalObservationHistory() {}

    public static List<GoalObservationInput.ObservationContext> link(
            List<GoalObservationOutput.Observation> observations,
            RoleplayGoalResponseHistory history) {
        var byTurn = new HashMap<UUID, List<GoalObservationOutput.Observation>>();
        observations.forEach(
                o ->
                        byTurn.computeIfAbsent(o.reference().turnId(), ignored -> new ArrayList<>())
                                .add(o));
        var ordered =
                history.byGoal().values().stream()
                        .flatMap(g -> g.responses().stream())
                        .sorted(
                                Comparator.comparingInt(
                                        RoleplayGoalResponseHistory.Response::turnNumber))
                        .toList();
        var exposures = new HashMap<UUID, GoalObservationInput.GoalExposure>();
        var linked =
                new HashMap<
                        GoalObservationOutput.Reference, GoalObservationInput.ObservationContext>();
        for (var response : ordered) {
            includeInputSupport(exposures, response.inputContext());
            // 같은 턴의 새 응답을 누적하기 전에 발화 시점의 이력을 연결한다.
            for (var observation : byTurn.getOrDefault(response.turnId(), List.of())) {
                var reference = observation.reference();
                if (!history.byGoal().containsKey(reference.microGoalId())
                        || response.inputContext() == null)
                    throw new IllegalArgumentException("관찰의 입력 상태 또는 승인 목표를 확인할 수 없습니다.");
                var context =
                        new GoalObservationInput.ObservationContext(
                                reference,
                                response.candidateId(),
                                response.turnNumber(),
                                response.checkpointVersion(),
                                response.inputContext(),
                                exposure(exposures, reference.microGoalId()));
                if (linked.putIfAbsent(reference, context) != null)
                    throw new IllegalArgumentException("같은 관찰 근거가 중복됐습니다.");
            }
            includeResponse(exposures, response);
        }
        if (linked.size() != observations.size())
            throw new IllegalArgumentException("관찰이 같은 세션의 확정 입력 턴을 참조하지 않습니다.");
        return observations.stream().map(o -> linked.get(o.reference())).toList();
    }

    public static List<GoalObservationInput.CurrentGoalExposure> beforeNextInput(
            RoleplayGoalResponseHistory history) {
        var exposures = new HashMap<UUID, GoalObservationInput.GoalExposure>();
        history.byGoal().values().stream()
                .flatMap(g -> g.responses().stream())
                .sorted(Comparator.comparingInt(RoleplayGoalResponseHistory.Response::turnNumber))
                .forEach(
                        response -> {
                            includeInputSupport(exposures, response.inputContext());
                            includeResponse(exposures, response);
                        });
        return history.byGoal().keySet().stream()
                .sorted()
                .map(
                        goal ->
                                new GoalObservationInput.CurrentGoalExposure(
                                        goal, exposure(exposures, goal)))
                .toList();
    }

    private static void includeInputSupport(
            Map<UUID, GoalObservationInput.GoalExposure> exposures, RoleplayInputContext input) {
        if (input == null) return;
        var prior = exposure(exposures, input.microGoalId());
        // 첫 질문의 지원은 응답 턴이 없어도 확정 입력 상태에서 확인한다.
        exposures.put(
                input.microGoalId(),
                new GoalObservationInput.GoalExposure(
                        prior.questionCount(),
                        prior.answerRevealed(),
                        prior.lastResponse(),
                        highest(prior.highestSupportLevel(), input.supportLevel()),
                        prior.mascotHintSeen()));
    }

    private static void includeResponse(
            Map<UUID, GoalObservationInput.GoalExposure> exposures,
            RoleplayGoalResponseHistory.Response response) {
        var prior = exposure(exposures, response.microGoalId());
        exposures.put(
                response.microGoalId(),
                new GoalObservationInput.GoalExposure(
                        prior.questionCount()
                                + (RoleplayCodes.QUESTION_RESPONSE_TYPES.contains(
                                                response.responseType())
                                        ? 1
                                        : 0),
                        prior.answerRevealed() || response.responseType().equals("ANSWER_REVEAL"),
                        new GoalObservationInput.PriorResponse(
                                response.turnId(),
                                response.candidateId(),
                                response.microGoalId(),
                                response.turnNumber(),
                                response.checkpointVersion(),
                                response.supportLevel(),
                                response.responseType()),
                        highest(prior.highestSupportLevel(), response.supportLevel()),
                        prior.mascotHintSeen() || response.mascotHintDelivered()));
    }

    private static String highest(String prior, String support) {
        return prior == null || prior.compareTo(support) < 0 ? support : prior;
    }

    private static GoalObservationInput.GoalExposure exposure(
            Map<UUID, GoalObservationInput.GoalExposure> exposures, UUID goal) {
        return exposures.getOrDefault(
                goal, new GoalObservationInput.GoalExposure(0, false, null, null));
    }
}
