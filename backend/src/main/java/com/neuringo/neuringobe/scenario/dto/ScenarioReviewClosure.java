package com.neuringo.neuringobe.scenario.dto;

import java.time.Instant;
import java.util.UUID;

public record ScenarioReviewClosure(
        UUID reviewId, UUID activityId, String outcome, Instant closedAt) {}
