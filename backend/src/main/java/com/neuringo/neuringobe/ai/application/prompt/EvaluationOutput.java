package com.neuringo.neuringobe.ai.application.prompt;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisReviewIssue;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import com.neuringo.neuringobe.ai.application.structured.output.RetryTarget;
import com.neuringo.neuringobe.ai.application.structured.output.RevisionInstruction;
import java.util.List;
import java.util.UUID;

/** 모델은 검수만 수행하고 처리 경로·전달 여부·치명 실패 개수는 서버가 결정한다. */
record EvaluationOutput(
        @JsonProperty("candidate_id") UUID candidateId,
        EvaluationDecision decision,
        Checks checks,
        @JsonProperty("analysis_id") UUID analysisId,
        @JsonProperty("current_goal_review")
                ScopedAnalysisContext.CurrentGoalReview currentGoalReview,
        @JsonProperty("activity_review") AnalysisReview.Activity activityReview,
        @JsonProperty("interpretation_strategy_review")
                AnalysisReview.Logic interpretationStrategyReview,
        @JsonProperty("observation_reviews")
                java.util.Map<String, tools.jackson.databind.JsonNode> observationReviews,
        @JsonProperty("failure_codes") List<String> failureCodes,
        @JsonProperty("revision_instruction") RevisionInstruction revisionInstruction,
        @JsonProperty("answer_directness_violation")
                AnswerDirectnessViolation answerDirectnessViolation,
        @JsonProperty("goal_progress_violation") GoalProgressViolation goalProgressViolation) {

    EvaluationResult toResult(ScopedAnalysisContext.Approvals approvals) {
        boolean priority =
                decision == EvaluationDecision.SAFETY_ESCALATION
                        || decision == EvaluationDecision.CONFIRM_INPUT;
        boolean analysisSupported = approvals.allSupported();
        var codes = new java.util.LinkedHashSet<>(failureCodes);
        if (!priority && !analysisSupported) {
            codes.add("UNSUPPORTED_CAUSE_ANALYSIS");
            if (interpretationStrategyReview.reasonCode().equals("STATE_STRATEGY_MISMATCH"))
                codes.add("STATE_STRATEGY_MISMATCH");
        }
        var review =
                new GoalObservationOutput.Review(
                        GoalObservationOutput.EVALUATION_VERSION,
                        analysisId,
                        approvals.observationsSupported(),
                        approvals.activitySupported());
        if (decision == EvaluationDecision.PASS && (!checks.allPassed() || !analysisSupported)) {
            throw new InvalidLlmOutputException("PASS requires all checks to pass", null);
        }
        requireConsistentEvaluation(analysisSupported);
        RetryTarget target =
                switch (decision) {
                    case PASS -> null;
                    case REANALYZE -> RetryTarget.CAUSE_ANALYSIS;
                    case REGENERATE, SAFETY_REGENERATE -> RetryTarget.RESPONSE_GENERATION;
                    case CONFIRM_INPUT -> RetryTarget.INPUT_CONFIRMATION;
                    case SAFETY_ESCALATION -> RetryTarget.SAFETY_ESCALATION;
                    default ->
                            throw new InvalidLlmOutputException(
                                    "decision is reserved for the orchestrator", null);
                };
        int critical =
                (int) codes.stream().filter(RoleplayCodes.CRITICAL_FAILURE_CODES::contains).count();
        return new EvaluationResult(
                candidateId,
                decision == EvaluationDecision.PASS,
                decision,
                target,
                critical,
                List.copyOf(codes),
                revisionInstruction,
                review,
                priority ? List.of() : analysisReviewIssues());
    }

    private List<AnalysisReviewIssue> analysisReviewIssues() {
        var issues =
                new java.util.ArrayList<
                        com.neuringo.neuringobe.ai.application.structured.output
                                .AnalysisReviewIssue>();
        if (currentGoalReview.verdict() == ObservationReview.Verdict.UNSUPPORTED)
            issues.add(
                    issue(
                            "CURRENT_GOAL",
                            "learning_state",
                            currentGoalReview.reasonCode(),
                            currentGoalReview.explanation()));
        if (activityReview.verdict() == ObservationReview.Verdict.UNSUPPORTED)
            issues.add(
                    issue(
                            "ACTIVITY",
                            "goal_observation.activity_goal",
                            activityReview.reasonCode(),
                            activityReview.explanation()));
        if (interpretationStrategyReview.verdict() == ObservationReview.Verdict.UNSUPPORTED)
            issues.add(
                    issue(
                            "INTERPRETATION_STRATEGY",
                            reviewedLogicField(interpretationStrategyReview.reasonCode()),
                            interpretationStrategyReview.reasonCode(),
                            interpretationStrategyReview.explanation()));
        observationReviews.forEach(
                (key, value) -> {
                    if (value.path("verdict").asString().equals("UNSUPPORTED"))
                        issues.add(
                                issue(
                                        "OBSERVATION",
                                        "goal_observation.observations[" + key.substring(1) + "]",
                                        value.path("reason_code").asString(),
                                        value.path("explanation").asString()));
                });
        return List.copyOf(issues);
    }

    private static String reviewedLogicField(String reason) {
        return switch (reason) {
            case "RESPONSE_ACT_MISMATCH" -> "response_act";
            case "RELEVANCE_MISMATCH" -> "relevance";
            case "LEARNING_STATE_MISMATCH" -> "learning_state";
            case "PRIMARY_GAP_MISMATCH" -> "primary_gap";
            case "SUPPORT_LEVEL_MISMATCH" -> "next_strategy.recommended_support_level";
            default -> "next_strategy";
        };
    }

    private static AnalysisReviewIssue issue(
            String scope, String field, String reason, String explanation) {
        return new AnalysisReviewIssue(
                AnalysisReviewIssue.Scope.valueOf(scope), field, reason, explanation);
    }

    private void requireConsistentEvaluation(boolean analysisSupported) {
        if (decision == EvaluationDecision.SAFETY_ESCALATION
                || decision == EvaluationDecision.CONFIRM_INPUT) return;
        if ((decision == EvaluationDecision.REANALYZE) == analysisSupported) {
            throw new InvalidLlmOutputException("analysis review contradicts decision", null);
        }
        if ((failureCodes.contains("GOAL_SKIPPED") && checks.goalAligned())
                || (failureCodes.contains("ANALYSIS_RESPONSE_MISMATCH")
                        && checks.analysisResponseConsistent())) {
            throw new InvalidLlmOutputException("failure code contradicts evaluation check", null);
        }
    }

    record Checks(
            @JsonProperty("format_valid") Boolean formatValid,
            @JsonProperty("safety_valid") Boolean safetyValid,
            @JsonProperty("privacy_valid") Boolean privacyValid,
            @JsonProperty("scenario_consistent") Boolean scenarioConsistent,
            @JsonProperty("analysis_response_consistent") Boolean analysisResponseConsistent,
            @JsonProperty("goal_aligned") Boolean goalAligned,
            @JsonProperty("educationally_correct") Boolean educationallyCorrect,
            @JsonProperty("difficulty_appropriate") Boolean difficultyAppropriate,
            @JsonProperty("conversation_coherent") Boolean conversationCoherent,
            Boolean answerable,
            @JsonProperty("non_repetitive") Boolean nonRepetitive) {
        boolean allPassed() {
            return List.of(
                            formatValid,
                            safetyValid,
                            privacyValid,
                            scenarioConsistent,
                            analysisResponseConsistent,
                            goalAligned,
                            educationallyCorrect,
                            difficultyAppropriate,
                            conversationCoherent,
                            answerable,
                            nonRepetitive)
                    .stream()
                    .allMatch(Boolean.TRUE::equals);
        }
    }
}
