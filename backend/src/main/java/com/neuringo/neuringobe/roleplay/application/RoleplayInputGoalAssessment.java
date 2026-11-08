package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.prompt.GoalObservationInput;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayInputEvidence;
import com.neuringo.neuringobe.ai.application.prompt.RoleplaySupportApplication;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputContext;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult.NextStrategy;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan.EvidenceReference;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 검수된 입력 목표 판정을 사전 도움·현재 관찰에 연결한다. 지원 감소 횟수나 활동 완료는 계산하지 않는다. */
public record RoleplayInputGoalAssessment(
        Outcome outcome,
        SupportDecision supportDecision,
        Set<EvidenceReference> basis,
        RoleplaySupportApplication.Decision supportApplication) {
    public RoleplayInputGoalAssessment(
            Outcome outcome, SupportDecision supportDecision, Set<EvidenceReference> basis) {
        this(outcome, supportDecision, basis, null);
    }

    public RoleplayInputGoalAssessment {
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(supportDecision);
        basis = Set.copyOf(basis);
        boolean achieved =
                outcome == Outcome.ACHIEVED_WITHOUT_HINT
                        || outcome == Outcome.ACHIEVED_WITH_SUPPORT;
        if (achieved == basis.isEmpty())
            throw new IllegalArgumentException("도달 판정에만 현재 목표의 관찰 근거가 필요합니다.");
    }

    public static RoleplayInputGoalAssessment assess(
            RoleplayInputContext input,
            GoalObservationInput observations,
            RoleplayTurnResult.Ready ready) {
        var analysis = ready.analysis();
        if (analysis.goalObservation() == null
                || ready.evaluation().goalObservationReview() == null)
            throw new IllegalArgumentException("입력 목표 판정에는 검수된 관찰 계약이 필요합니다.");
        if (analysis.learningState().equals("NOT_ASSESSABLE")
                && (!analysis.nextStrategy().targetMicroGoalId().equals(input.microGoalId())
                        || !analysis.nextStrategy()
                                .recommendedSupportLevel()
                                .equals(input.supportLevel())))
            throw new IllegalArgumentException("판정 불가 입력으로 질문 목표·지원 수준을 바꿀 수 없습니다.");
        var decision = supportDecision(input, analysis.nextStrategy());
        if (analysis.learningState().equals("NOT_ASSESSABLE"))
            return new RoleplayInputGoalAssessment(Outcome.NOT_ASSESSABLE, decision, Set.of());
        var evidence = RoleplayInputEvidence.assess(input, observations, analysis);
        return new RoleplayInputGoalAssessment(
                Outcome.valueOf(evidence.outcome().name()),
                decision,
                evidence.basis().stream()
                        .map(
                                r ->
                                        new EvidenceReference(
                                                r.turnId(),
                                                r.microGoalId(),
                                                r.criterionReference()))
                        .collect(Collectors.toSet()),
                analysis.supportAdjustment());
    }

    private static SupportDecision supportDecision(RoleplayInputContext input, NextStrategy next) {
        if (next.type().equals("CLOSE_ROLEPLAY")) return SupportDecision.CLOSING;
        if (!next.targetMicroGoalId().equals(input.microGoalId()))
            return SupportDecision.NEXT_GOAL_START;
        int difference = next.recommendedSupportLevel().compareTo(input.supportLevel());
        if (difference < 0) throw new IllegalArgumentException("같은 목표의 지원을 낮춰 반복 유도할 수 없습니다.");
        return difference == 0 ? SupportDecision.MAINTAINED : SupportDecision.INCREASED;
    }

    public enum Outcome {
        ACHIEVED_WITHOUT_HINT,
        ACHIEVED_WITH_SUPPORT,
        SUPPORTED_NOT_ACHIEVED,
        IN_PROGRESS,
        NOT_ASSESSABLE
    }

    public enum SupportDecision {
        MAINTAINED,
        INCREASED,
        NEXT_GOAL_START,
        CLOSING
    }
}
