package com.neuringo.neuringobe.quiz.dto;

import com.neuringo.neuringobe.quiz.domain.ExpressionMatchResult;
import com.neuringo.neuringobe.quiz.domain.QuizAttempt;
import com.neuringo.neuringobe.quiz.domain.QuizHint;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

public record QuizAttemptResponse(
        UUID attemptId,
        UUID activityQuizId,
        String firstResponse,
        boolean firstResponseCorrect,
        ExpressionMatchResult expressionMatchResult,
        String cameraModelVersion,
        List<String> usedHintTypes,
        String finalResponse,
        boolean resolvedAfterHint,
        boolean itemCorrect,
        boolean excludedFromScoring,
        String status,
        OffsetDateTime respondedAt) {
    public static QuizAttemptResponse from(QuizAttempt attempt, List<QuizHint> hints) {
        return new QuizAttemptResponse(
                attempt.getAttemptId(),
                attempt.getActivityQuizId(),
                attempt.getFirstResponse(),
                attempt.isFirstResponseCorrect(),
                attempt.getExpressionMatchResult(),
                attempt.getCameraModelVersion(),
                hints.stream().map(QuizHint::getHintType).toList(),
                attempt.getFinalResponse(),
                attempt.isResolvedAfterHint(),
                attempt.isItemCorrect(),
                attempt.isExcludedFromScoring(),
                attempt.getStatus(),
                attempt.getRespondedAt().atOffset(ZoneOffset.ofHours(9)));
    }
}
