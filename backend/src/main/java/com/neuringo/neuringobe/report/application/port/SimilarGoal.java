package com.neuringo.neuringobe.report.application.port;

import java.util.Objects;
import java.util.UUID;

/**
 * @param similarity 코사인 유사도 -1~1
 */
public record SimilarGoal(UUID goalId, String title, double similarity) {

    public SimilarGoal {
        Objects.requireNonNull(goalId, "goalId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        if (Double.isNaN(similarity) || similarity < -1.0 || similarity > 1.0) {
            throw new IllegalArgumentException("similarity must be within -1..1");
        }
    }
}
