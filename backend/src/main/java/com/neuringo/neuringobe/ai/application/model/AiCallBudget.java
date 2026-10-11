package com.neuringo.neuringobe.ai.application.model;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** Local call constraint; never serialize it to a provider or a child response. */
public final class AiCallBudget {
    private final Supplier<Duration> remainingTime;

    public AiCallBudget(Supplier<Duration> remainingTime) {
        this.remainingTime = Objects.requireNonNull(remainingTime);
    }

    /** The owner checks expiry/cancellation when the transport asks for its limit. */
    public Duration limit(Duration configuredMaximum) {
        Objects.requireNonNull(configuredMaximum);
        if (configuredMaximum.isZero() || configuredMaximum.isNegative())
            throw new IllegalArgumentException("Call timeout must be positive");
        Duration remaining = Objects.requireNonNull(remainingTime.get());
        if (remaining.isZero() || remaining.isNegative())
            throw new IllegalStateException("Budget owner must reject expired calls");
        return remaining.compareTo(configuredMaximum) < 0 ? remaining : configuredMaximum;
    }

    @Override
    public String toString() {
        return "AiCallBudget[local]";
    }
}
