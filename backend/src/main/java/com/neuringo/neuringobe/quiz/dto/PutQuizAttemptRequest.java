package com.neuringo.neuringobe.quiz.dto;

import com.neuringo.neuringobe.quiz.domain.ExpressionMatchResult;
import com.neuringo.neuringobe.quiz.domain.TechnicalFailureReason;
import jakarta.validation.constraints.Size;

public record PutQuizAttemptRequest(
        @Size(max = 200) String firstResponse,
        ExpressionMatchResult expressionMatchResult,
        @Size(max = 100) String cameraModelVersion,
        @Size(max = 200) String finalResponse,
        TechnicalFailureReason technicalFailureReason) {}
