package com.neuringo.neuringobe.quiz.dto;

import java.util.UUID;

/** 시나리오 준비에 전달할 최초 난이도 근거. activityId가 결과 스냅샷의 식별자다. */
public record InitialDifficultyEvidence(
        UUID activityId,
        UUID childId,
        UUID goalId,
        int validQuestionCount,
        int correctQuestionCount,
        String scenarioLevel,
        String initialSupportLevel,
        boolean difficultyFallbackApplied,
        String difficultyFallbackReason,
        String policyVersion) {

    public InitialDifficultyEvidence {
        if (difficultyFallbackApplied && difficultyFallbackReason == null) {
            difficultyFallbackReason = "LEGACY_UNSPECIFIED";
        }
    }
}
