package com.neuringo.neuringobe.scenario.dto;

import java.util.UUID;

public record ScenarioReviewState(
        UUID reviewId,
        UUID activityId,
        UUID instructorId,
        String inputFingerprint,
        Integer currentPosition,
        long revision,
        int candidateCount) {}
