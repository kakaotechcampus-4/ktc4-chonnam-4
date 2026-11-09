package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.prompt.RoleplaySupportAdjustmentContext.Outcome;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputContext;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.Reference;
import java.util.HashMap;
import java.util.List;

/** 분석의 입력 목표 판정을 현재 관찰·사전 도움과 대조한다. 의미 검수와 저장은 호출자가 수행한다. */
public final class RoleplayInputEvidence {
    private RoleplayInputEvidence() {}

    public static Evidence assess(
            RoleplayInputContext input,
            GoalObservationInput observations,
            AnalysisResult analysis) {
        if (analysis.goalObservation() == null)
            throw new IllegalArgumentException("목표 판정에 관찰 계약이 필요합니다.");
        if (analysis.learningState().equals("NOT_ASSESSABLE"))
            return new Evidence(Outcome.NOT_ASSESSABLE, List.of());
        var exposure =
                observations.currentGoalExposures().stream()
                        .filter(e -> e.microGoalId().equals(input.microGoalId()))
                        .findFirst()
                        .orElseThrow()
                        .exposureBeforeInput();
        if (input.answerRevealed() || exposure.answerRevealed())
            return new Evidence(Outcome.SUPPORTED_NOT_ACHIEVED, List.of());
        if (!analysis.learningState().equals("GOAL_ACHIEVED"))
            return new Evidence(Outcome.IN_PROGRESS, List.of());
        var required =
                observations.approvedCriteria().stream()
                        .filter(c -> c.microGoalId().equals(input.microGoalId()))
                        .map(GoalObservationInput.Criterion::reference)
                        .sorted()
                        .toList();
        if (required.isEmpty()) throw new IllegalArgumentException("입력 목표의 승인 기준이 없습니다.");
        var reviews = new HashMap<String, RoleplayIndependenceEvidence.Review>();
        for (var review :
                RoleplayIndependenceEvidence.current(
                        observations, input, analysis.goalObservation().observations())) {
            var ref = review.reference();
            if (!ref.turnId().equals(analysis.turnId()))
                throw new IllegalArgumentException("이번 발화의 도달 근거가 아닙니다.");
            if (ref.microGoalId().equals(input.microGoalId())
                    && reviews.putIfAbsent(ref.criterionReference(), review) != null)
                throw new IllegalArgumentException("도달 판정의 현재 관찰이 중복됐습니다.");
        }
        boolean helped = false;
        for (var criterion : required) {
            var review = reviews.get(criterion);
            if (review == null
                    || (review.eligibility()
                                    != RoleplayIndependenceEvidence.Eligibility
                                            .POSITIVE_WITHOUT_RECORDED_HELP
                            && review.eligibility()
                                    != RoleplayIndependenceEvidence.Eligibility.POSITIVE_WITH_HELP))
                throw new IllegalArgumentException("목표 도달 판정의 필수 긍정 관찰이 부족합니다.");
            helped |=
                    review.eligibility()
                            == RoleplayIndependenceEvidence.Eligibility.POSITIVE_WITH_HELP;
        }
        return new Evidence(
                helped ? Outcome.ACHIEVED_WITH_SUPPORT : Outcome.ACHIEVED_WITHOUT_HINT,
                required.stream().map(c -> reviews.get(c).reference()).toList());
    }

    public record Evidence(Outcome outcome, List<Reference> basis) {
        public Evidence {
            basis = List.copyOf(basis);
        }
    }
}
