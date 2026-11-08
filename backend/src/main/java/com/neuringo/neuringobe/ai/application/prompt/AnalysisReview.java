package com.neuringo.neuringobe.ai.application.prompt;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.neuringo.neuringobe.ai.application.structured.IndeterminateLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 관찰과 구분해 활동 판정의 근거와 나머지 분석 논리를 검수한다. */
final class AnalysisReview {
    static final Set<String> SERVER_CODES =
            Set.of("UNSUPPORTED_CAUSE_ANALYSIS", "STATE_STRATEGY_MISMATCH");
    static final List<String> LOGIC_REASONS =
            List.of(
                    "LOGIC_GROUNDED",
                    "RESPONSE_ACT_MISMATCH",
                    "RELEVANCE_MISMATCH",
                    "LEARNING_STATE_MISMATCH",
                    "PRIMARY_GAP_MISMATCH",
                    "STATE_STRATEGY_MISMATCH",
                    "SUPPORT_LEVEL_MISMATCH",
                    "INSUFFICIENT_CONTEXT");
    static final List<String> ACTIVITY_REASONS =
            List.of(
                    "ASSESSMENT_GROUNDED",
                    "ACHIEVEMENT_NOT_GROUNDED",
                    "CONTINUATION_NOT_GROUNDED",
                    "UNAVAILABLE_NOT_GROUNDED",
                    "INSUFFICIENT_CONTEXT");

    record Activity(
            @JsonProperty("reviewed_decision")
                    GoalObservationOutput.ActivityDecision reviewedDecision,
            ObservationReview.Verdict verdict,
            @JsonProperty("reason_code") String reasonCode,
            @JsonProperty("evidence_references")
                    List<GoalObservationOutput.Reference> evidenceReferences,
            String explanation) {}

    record Logic(
            ObservationReview.Verdict verdict,
            @JsonProperty("reason_code") String reasonCode,
            String explanation) {}

    private AnalysisReview() {}

    static ObservationReview.Verdict activity(
            Activity review, GoalObservationOutput.Analysis analysis, GoalObservationInput input) {
        require(review != null, "activity review is required");
        require(
                review.reviewedDecision() == analysis.activityGoal().decision(),
                "activity review decision does not match");
        explanation(review.explanation(), "activity review explanation is invalid");
        String rejectedReason =
                switch (review.reviewedDecision()) {
                    case ACHIEVED -> "ACHIEVEMENT_NOT_GROUNDED";
                    case IN_PROGRESS -> "CONTINUATION_NOT_GROUNDED";
                    case UNAVAILABLE -> "UNAVAILABLE_NOT_GROUNDED";
                };
        verdict(
                review.verdict(),
                review.reasonCode(),
                "ASSESSMENT_GROUNDED",
                Set.of(rejectedReason),
                "activity review verdict and reason conflict");
        var available = new HashSet<GoalObservationOutput.Reference>();
        var positive = new HashSet<GoalObservationOutput.Reference>();
        var observations = new java.util.ArrayList<>(input.confirmedObservations());
        observations.addAll(analysis.observations());
        for (var observation : observations) {
            var reference = observation.reference();
            if (input.contains(reference.microGoalId(), reference.criterionReference())) {
                available.add(reference);
                if (observation.status() == GoalObservationOutput.Status.EXPLICIT
                        || observation.status() == GoalObservationOutput.Status.IMPLICIT)
                    positive.add(reference);
            }
        }
        var refs = review.evidenceReferences();
        require(
                refs != null
                        && new HashSet<>(refs).size() == refs.size()
                        && available.containsAll(refs),
                "activity review references do not match");
        if (review.verdict() == ObservationReview.Verdict.SUPPORTED
                && review.reviewedDecision() == GoalObservationOutput.ActivityDecision.ACHIEVED) {
            require(
                    refs.stream()
                            .anyMatch(
                                    ref ->
                                            positive.contains(ref)
                                                    && analysis.activityGoal()
                                                            .basis()
                                                            .contains(ref)),
                    "activity review lacks positive assessment basis");
        }
        return review.verdict();
    }

    static ObservationReview.Verdict logic(Logic review) {
        require(review != null, "analysis logic review is required");
        explanation(review.explanation(), "analysis logic review explanation is invalid");
        var rejected = new HashSet<>(LOGIC_REASONS);
        rejected.removeAll(Set.of("LOGIC_GROUNDED", "INSUFFICIENT_CONTEXT"));
        verdict(
                review.verdict(),
                review.reasonCode(),
                "LOGIC_GROUNDED",
                rejected,
                "analysis logic review verdict and reason conflict");
        return review.verdict();
    }

    static boolean supported(ObservationReview.Verdict verdict) {
        if (verdict == ObservationReview.Verdict.INSUFFICIENT_CONTEXT)
            throw new IndeterminateLlmOutputException();
        return verdict == ObservationReview.Verdict.SUPPORTED;
    }

    private static void verdict(
            ObservationReview.Verdict verdict,
            String reason,
            String supported,
            Set<String> rejected,
            String message) {
        require(
                verdict != null
                        && switch (verdict) {
                            case SUPPORTED -> supported.equals(reason);
                            case UNSUPPORTED -> rejected.contains(reason);
                            case INSUFFICIENT_CONTEXT -> "INSUFFICIENT_CONTEXT".equals(reason);
                        },
                message);
    }

    private static void explanation(String text, String reason) {
        require(
                text != null && !text.isBlank() && text.codePointCount(0, text.length()) <= 200,
                reason);
    }

    private static void require(boolean valid, String reason) {
        if (!valid) throw new InvalidLlmOutputException(reason, null);
    }
}
