package com.neuringo.neuringobe.quiz.dto;

import com.neuringo.neuringobe.quiz.domain.QuizResult;
import java.math.BigDecimal;
import java.util.UUID;

public record QuizResultResponse(
        UUID activityId,
        int validQuestionCount,
        int correctQuestionCount,
        BigDecimal overallAccuracy,
        int totalHintCount,
        int resolvedAfterHintCount,
        String scenarioLevel,
        String initialSupportLevel,
        boolean difficultyFallbackApplied,
        boolean initialDifficultyUsed,
        String policyVersion) {
    public static QuizResultResponse from(QuizResult result) {
        return new QuizResultResponse(
                result.getActivityId(),
                result.getValidQuestionCount(),
                result.getCorrectQuestionCount(),
                result.getOverallAccuracy(),
                result.getTotalHintCount(),
                result.getResolvedAfterHintCount(),
                result.getScenarioLevel(),
                result.getInitialSupportLevel(),
                result.isDifficultyFallbackApplied(),
                result.isInitialDifficultyUsed(),
                result.getPolicyVersion());
    }
}
