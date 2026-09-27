package com.neuringo.neuringobe.ai.application.model;

public record AiAttemptContext(int analysisAttempt, int generationAttempt, int evaluationAttempt) {

    public AiAttemptContext {
        if (analysisAttempt < 0 || generationAttempt < 0 || evaluationAttempt < 0) {
            throw new IllegalArgumentException("attempt counts must not be negative");
        }
    }
}
