package com.neuringo.neuringobe.ai.application.prompt;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.neuringo.neuringobe.ai.application.prompt.RoleplaySupportAdjustmentContext.Outcome;
import com.neuringo.neuringobe.ai.application.prompt.RoleplaySupportAdjustmentContext.Status;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputContext;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 생성 전에 새 목표의 시작 지원을 보정하고, 검수 후 같은 근거로 다시 검사한다. */
public final class RoleplaySupportApplication {
    private static final Set<String> GOAL_QUESTIONS =
            Set.of("ADVANCE_GOAL", "PROBE_EVENT", "PROBE_EMOTION", "PROBE_CAUSE", "PROBE_ACTION");

    private RoleplaySupportApplication() {}

    public static AnalysisResult apply(
            RoleplayTurnInput.State state,
            GoalObservationInput observations,
            AnalysisResult analysis) {
        if (analysis.supportAdjustment() != null)
            throw new IllegalArgumentException("정책 적용은 원래 분석에 한 번만 수행합니다.");
        var next = analysis.nextStrategy();
        if (!GOAL_QUESTIONS.contains(next.type())
                || next.targetMicroGoalId().equals(state.currentMicroGoalId())
                || !analysis.learningState().equals("GOAL_ACHIEVED")) return analysis;
        var policy = observations.supportAdjustment();
        if (policy.policyVersion() == null
                || policy.requiredIndependentGoals() == null
                || policy.inProgressHandling() == null) return analysis;
        var target =
                observations.currentGoalExposures().stream()
                        .filter(e -> e.microGoalId().equals(next.targetMicroGoalId()))
                        .findFirst()
                        .orElseThrow()
                        .exposureBeforeInput();
        // 이미 질문한 목표의 지원을 되돌려 같은 질문을 반복하지 않는다.
        if (target.lastResponse() != null
                || target.highestSupportLevel() != null
                || target.answerRevealed()) return analysis;
        var evidence =
                RoleplayInputEvidence.assess(
                        RoleplayInputContext.from(state), observations, analysis);
        var inputs =
                new ArrayList<>(
                        policy.confirmedInputs().stream()
                                .map(
                                        e ->
                                                new RoleplaySupportSequence.Input(
                                                        e.turnId(),
                                                        e.microGoalId(),
                                                        e.turnNumber(),
                                                        e.outcome(),
                                                        e.basis()))
                                .toList());
        inputs.add(
                new RoleplaySupportSequence.Input(
                        analysis.turnId(),
                        state.currentMicroGoalId(),
                        state.turnCount(),
                        evidence.outcome(),
                        evidence.basis()));
        var sequence =
                RoleplaySupportSequence.evaluate(
                        inputs, policy.requiredIndependentGoals(), policy.inProgressHandling());
        if (sequence.status() != Status.ELIGIBLE_NEXT_GOAL) return analysis;
        var base = next.recommendedSupportLevel();
        RoleplayCodes.requireOneOf(RoleplayCodes.SUPPORT_LEVELS, base, "baseSupportLevel");
        var applied = "S" + Math.max(0, base.charAt(1) - '0' - 1);
        var decision =
                new Decision(
                        policy.policyVersion(),
                        policy.requiredIndependentGoals(),
                        policy.inProgressHandling(),
                        next.targetMicroGoalId(),
                        base,
                        applied,
                        sequence.basis());
        return withSupport(analysis, applied, decision);
    }

    public static void requireValid(
            RoleplayTurnInput.State state,
            GoalObservationInput observations,
            AnalysisResult analysis) {
        var stored = analysis.supportAdjustment();
        var raw =
                stored == null ? analysis : withSupport(analysis, stored.baseSupportLevel(), null);
        if (!apply(state, observations, raw).equals(analysis))
            throw new IllegalArgumentException("지원 보정과 확정 이력·이번 발화의 도달 근거가 일치하지 않습니다.");
    }

    private static AnalysisResult withSupport(
            AnalysisResult analysis, String support, Decision decision) {
        var next = analysis.nextStrategy();
        return new AnalysisResult(
                analysis.turnId(),
                analysis.responseAct(),
                analysis.relevance(),
                analysis.learningState(),
                analysis.primaryGap(),
                new AnalysisResult.NextStrategy(
                        next.type(), next.targetMicroGoalId(), next.purpose(), support),
                analysis.analysisConfidence(),
                analysis.goalObservation(),
                decision);
    }

    public record Decision(
            @JsonProperty("policy_version") String policyVersion,
            @JsonProperty("required_independent_goals") int requiredIndependentGoals,
            @JsonProperty("in_progress_handling") String inProgressHandling,
            @JsonProperty("target_micro_goal_id") UUID targetMicroGoalId,
            @JsonProperty("base_support_level") String baseSupportLevel,
            @JsonProperty("applied_support_level") String appliedSupportLevel,
            List<RoleplaySupportSequence.Input> basis) {
        public Decision {
            Objects.requireNonNull(targetMicroGoalId);
            RoleplayCodes.requireOneOf(
                    RoleplayCodes.SUPPORT_LEVELS, baseSupportLevel, "baseSupportLevel");
            RoleplayCodes.requireOneOf(
                    RoleplayCodes.SUPPORT_LEVELS, appliedSupportLevel, "appliedSupportLevel");
            basis = List.copyOf(basis);
            if (policyVersion == null
                    || policyVersion.isBlank()
                    || requiredIndependentGoals < 1
                    || !("RESET_SEQUENCE".equals(inProgressHandling)
                            || "KEEP_SEQUENCE".equals(inProgressHandling))
                    || basis.size() < requiredIndependentGoals
                    || basis.stream()
                                    .map(RoleplaySupportSequence.Input::microGoalId)
                                    .distinct()
                                    .count()
                            != basis.size()
                    || basis.stream()
                            .anyMatch(
                                    e ->
                                            e.outcome() != Outcome.ACHIEVED_WITHOUT_HINT
                                                    || e.basis().isEmpty())
                    || !appliedSupportLevel.equals(
                            "S" + Math.max(0, baseSupportLevel.charAt(1) - '0' - 1)))
                throw new IllegalArgumentException("지원 보정에는 한 단계 감소와 서로 다른 목표의 도달 근거가 필요합니다.");
        }

        @JsonProperty("application_kind")
        public String kind() {
            return baseSupportLevel.equals(appliedSupportLevel) ? "AT_MINIMUM" : "DECREASED";
        }
    }
}
