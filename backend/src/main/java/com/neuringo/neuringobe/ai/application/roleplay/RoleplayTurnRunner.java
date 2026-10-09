package com.neuringo.neuringobe.ai.application.roleplay;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/** Waiting boundary. The application owns the injected worker pool and its lifecycle. */
public final class RoleplayTurnRunner {
    private final ExecutorService workers;
    private final RetryingRoleplayTurnExecutor turns;

    public RoleplayTurnRunner(ExecutorService workers) {
        this.workers = Objects.requireNonNull(workers);
        this.turns = new RetryingRoleplayTurnExecutor();
    }

    /**
     * Text/LLM entry point. Voice orchestration must start the shared budget before STT instead.
     */
    public RoleplayTurnResult execute(UUID turnId, RetryableRoleplayTurnSteps steps) {
        Objects.requireNonNull(turnId);
        Objects.requireNonNull(steps);
        return execute(
                RoleplayTurnDeadline.start(), deadline -> turns.execute(turnId, steps, deadline));
    }

    /**
     * Runs one whole pipeline. STT/LLM/TTS must use this same deadline; persistence is outside it.
     */
    public RoleplayTurnResult execute(
            RoleplayTurnDeadline deadline,
            Function<RoleplayTurnDeadline, RoleplayTurnResult> pipeline) {
        return executeWithTimeout(deadline, pipeline, RoleplayTurnResult.TimedOut::new);
    }

    public <T> T executeWithTimeout(
            RoleplayTurnDeadline deadline,
            Function<RoleplayTurnDeadline, T> pipeline,
            Supplier<T> onTimeout) {
        Objects.requireNonNull(deadline);
        Objects.requireNonNull(pipeline);
        Objects.requireNonNull(onTimeout);
        Future<T> task = null;
        try {
            deadline.requireActive();
            task = workers.submit(() -> deadline.withinBudget(() -> pipeline.apply(deadline)));
            T result = task.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            deadline.requireActive();
            return result;
        } catch (TimeoutException | RoleplayTurnDeadline.Expired timeout) {
            timeout(deadline, task);
            return onTimeout.get();
        } catch (InterruptedException interrupted) {
            deadline.cancel();
            if (task != null) task.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException("Roleplay turn caller interrupted");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RoleplayTurnDeadline.Expired) {
                timeout(deadline, task);
                return onTimeout.get();
            }
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failed.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Roleplay pipeline failed", failed.getCause());
        }
    }

    private static void timeout(RoleplayTurnDeadline deadline, Future<?> task) {
        deadline.cancel();
        if (task != null) task.cancel(true);
    }
}
