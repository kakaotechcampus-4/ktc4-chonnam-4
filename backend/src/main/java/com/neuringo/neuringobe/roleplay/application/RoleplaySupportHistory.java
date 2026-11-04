package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.prompt.GoalObservationInput;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayIndependenceEvidence;
import com.neuringo.neuringobe.ai.application.prompt.RoleplaySupportAdjustmentContext.Event;
import com.neuringo.neuringobe.ai.application.prompt.RoleplaySupportAdjustmentContext.Outcome;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.Reference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 확정 입력마다 도달 결과 하나를 복원하며 과거 미확인 결과를 독립 도달로 추정하지 않는다. */
public final class RoleplaySupportHistory {
    private RoleplaySupportHistory() {}

    public static List<Event> restore(
            RoleplayGoalResponseHistory history,
            GoalObservationInput observations,
            List<Saved> saved,
            List<Reference> basis) {
        var byTurn = new HashMap<UUID, Saved>();
        for (var row : saved) {
            if (byTurn.putIfAbsent(row.turnId(), row) != null)
                throw new IllegalArgumentException("같은 입력의 도달 판정이 중복됐습니다.");
        }
        var byReference =
                RoleplayIndependenceEvidence.confirmed(observations).stream()
                        .collect(
                                Collectors.toMap(
                                        RoleplayIndependenceEvidence.Review::reference,
                                        Function.identity()));
        var basisByTurn = basis.stream().collect(Collectors.groupingBy(Reference::turnId));
        var responses =
                history.byGoal().values().stream()
                        .flatMap(g -> g.responses().stream())
                        .sorted(
                                Comparator.comparingInt(
                                        RoleplayGoalResponseHistory.Response::turnNumber))
                        .toList();
        var result = new ArrayList<Event>();
        var knownTurns = new HashSet<UUID>();
        for (var response : responses) {
            knownTurns.add(response.turnId());
            var input = response.inputContext();
            if (input == null) throw new IllegalArgumentException("지원 이력의 입력 상태를 확인할 수 없습니다.");
            var row = byTurn.get(response.turnId());
            var refs =
                    basisByTurn.getOrDefault(response.turnId(), List.of()).stream()
                            .sorted(Comparator.comparing(Reference::criterionReference))
                            .toList();
            Outcome outcome =
                    row == null || row.outcome() == null
                            ? Outcome.UNKNOWN
                            : Outcome.valueOf(row.outcome());
            if (outcome != Outcome.UNKNOWN
                    && (row.inputGoalId() == null || row.learningState() == null))
                throw new IllegalArgumentException("확정 도달 방식의 입력 목표·학습 상태가 누락됐습니다.");
            if (row != null
                    && row.inputGoalId() != null
                    && !row.inputGoalId().equals(input.microGoalId()))
                throw new IllegalArgumentException("지원 이력의 입력 목표가 확정 턴과 다릅니다.");
            boolean achieved =
                    outcome == Outcome.ACHIEVED_WITHOUT_HINT
                            || outcome == Outcome.ACHIEVED_WITH_SUPPORT;
            if (!achieved && !refs.isEmpty())
                throw new IllegalArgumentException("미도달 입력에 도달 근거가 있습니다.");
            if (achieved) {
                if (!"GOAL_ACHIEVED".equals(row.learningState()) || input.answerRevealed())
                    throw new IllegalArgumentException("도달 방식과 입력 판정·정답 제공 상태가 다릅니다.");
                var required =
                        observations.approvedCriteria().stream()
                                .filter(c -> c.microGoalId().equals(input.microGoalId()))
                                .map(GoalObservationInput.Criterion::reference)
                                .collect(Collectors.toSet());
                var supplied = new HashSet<String>();
                boolean helped = false;
                for (var ref : refs) {
                    var review = byReference.get(ref);
                    if (!ref.microGoalId().equals(input.microGoalId())
                            || !supplied.add(ref.criterionReference())
                            || review == null
                            || (review.eligibility()
                                            != RoleplayIndependenceEvidence.Eligibility
                                                    .POSITIVE_WITH_HELP
                                    && review.eligibility()
                                            != RoleplayIndependenceEvidence.Eligibility
                                                    .POSITIVE_WITHOUT_RECORDED_HELP))
                        throw new IllegalArgumentException("지원 이력의 도달 근거가 현재 입력 목표의 긍정 관찰과 다릅니다.");
                    helped |=
                            review.eligibility()
                                    == RoleplayIndependenceEvidence.Eligibility.POSITIVE_WITH_HELP;
                }
                if (required.isEmpty()
                        || !supplied.equals(required)
                        || helped != (outcome == Outcome.ACHIEVED_WITH_SUPPORT))
                    throw new IllegalArgumentException("지원 이력의 필수 관찰·도움과 도달 방식이 다릅니다.");
            }
            if (outcome == Outcome.SUPPORTED_NOT_ACHIEVED
                    && (!input.answerRevealed() || "NOT_ASSESSABLE".equals(row.learningState())))
                throw new IllegalArgumentException("정답 제공 후 결과에 사전 정답 제공 기록이 없습니다.");
            if (outcome == Outcome.NOT_ASSESSABLE && !"NOT_ASSESSABLE".equals(row.learningState()))
                throw new IllegalArgumentException("판정 불가 이력의 학습 상태가 다릅니다.");
            if (outcome == Outcome.IN_PROGRESS
                    && (input.answerRevealed()
                            || row.learningState() == null
                            || row.learningState().equals("GOAL_ACHIEVED")
                            || row.learningState().equals("NOT_ASSESSABLE")))
                throw new IllegalArgumentException("진행 중 이력의 학습 상태가 다릅니다.");
            result.add(
                    new Event(
                            response.turnId(),
                            input.microGoalId(),
                            response.turnNumber(),
                            response.checkpointVersion(),
                            outcome,
                            row == null ? null : row.learningState(),
                            refs));
        }
        if (!knownTurns.containsAll(byTurn.keySet())
                || !knownTurns.containsAll(basisByTurn.keySet()))
            throw new IllegalArgumentException("지원 이력이 다른 확정 입력을 참조합니다.");
        return List.copyOf(result);
    }

    public record Saved(UUID turnId, UUID inputGoalId, String learningState, String outcome) {}
}
