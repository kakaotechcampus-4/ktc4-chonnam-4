package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiCallBudget;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * One monotonic budget shared by STT, all LLM attempts and TTS. Caller chooses the start boundary.
 */
public final class RoleplayTurnDeadline {
    public static final Duration MAX_DURATION = Duration.ofSeconds(60);
    private final LongSupplier nanoTime;
    private final long startedAt;
    private final long budgetNanos;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public static RoleplayTurnDeadline start() {
        return start(MAX_DURATION);
    }

    public static RoleplayTurnDeadline start(Duration budget) {
        return new RoleplayTurnDeadline(budget, System::nanoTime);
    }

    RoleplayTurnDeadline(Duration budget, LongSupplier nanoTime) {
        Objects.requireNonNull(budget);
        if (budget.isNegative() || budget.isZero() || budget.compareTo(MAX_DURATION) > 0) {
            throw new IllegalArgumentException("Budget must be positive and at most 60 seconds");
        }
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.budgetNanos = budget.toNanos();
        this.startedAt = nanoTime.getAsLong();
    }

    public long remainingNanos() {
        if (cancelled.get()) return 0;
        return Math.max(0, budgetNanos - (nanoTime.getAsLong() - startedAt));
    }

    public void requireActive() {
        if (remainingNanos() == 0) throw new Expired();
    }

    public AiCallBudget callBudget() {
        return new AiCallBudget(
                () -> {
                    long remaining = remainingNanos();
                    if (remaining == 0) throw new Expired();
                    return Duration.ofNanos(remaining);
                });
    }

    /** A late result cannot advance the pipeline, even if the provider ignores interruption. */
    public <T> T withinBudget(Supplier<T> work) {
        requireActive();
        T result = Objects.requireNonNull(work).get();
        requireActive();
        return result;
    }

    public void cancel() {
        cancelled.set(true);
    }

    public static final class Expired extends RuntimeException {
        private Expired() {
            super("Roleplay turn deadline exceeded", null, true, false);
        }
    }
}
