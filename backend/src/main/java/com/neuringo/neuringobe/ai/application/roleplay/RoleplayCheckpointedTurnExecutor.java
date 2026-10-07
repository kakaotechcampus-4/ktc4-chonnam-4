package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Use authenticated trace and prior processing ownership. No success response before DB commit. */
public final class RoleplayCheckpointedTurnExecutor {
    private final RoleplayTurnRunner runner;
    private final RoleplayCheckpointStore checkpoints;
    private final RoleplayTurnOutcomeResolver outcomes;
    private final RoleplayRequestGuard requests;

    public RoleplayCheckpointedTurnExecutor(
            RoleplayTurnRunner runner,
            RoleplayCheckpointStore checkpoints,
            RoleplayTurnOutcomeResolver outcomes,
            RoleplayRequestGuard requests) {
        this.runner = Objects.requireNonNull(runner);
        this.checkpoints = Objects.requireNonNull(checkpoints);
        this.outcomes = Objects.requireNonNull(outcomes);
        this.requests = Objects.requireNonNull(requests);
    }

    public sealed interface Result {}

    public record Success(RoleplayTurnOutcome outcome, long checkpointVersion, boolean replayed)
            implements Result {}

    public record Recovery(RoleplayTurnOutcome outcome) implements Result {}

    public record Conflict() implements Result {}

    public record Processing() implements Result {}

    public record CapacityUnavailable() implements Result {}

    public Result executeVoice(
            RoleplayAudioResource audio,
            RoleplayTurnInput context,
            RoleplaySpeechTurnPipeline pipeline,
            AiTraceContext trace,
            long expectedVersion,
            int turnNumber,
            UUID idempotencyKey,
            String fingerprint,
            RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(audio);
        try (audio) {
            return execute(
                    trace,
                    expectedVersion,
                    turnNumber,
                    idempotencyKey,
                    fingerprint,
                    deadline,
                    shared -> {
                        var request = shared.withinBudget(audio::request);
                        if (!request.traceContext().equals(trace))
                            throw new IllegalArgumentException(
                                    "Upload trace does not match authenticated context");
                        return pipeline.execute(request, context, shared);
                    });
        }
    }

    public Result execute(
            AiTraceContext trace,
            long expectedVersion,
            int turnNumber,
            UUID idempotencyKey,
            String fingerprint,
            RoleplayTurnDeadline deadline,
            Function<RoleplayTurnDeadline, RoleplayTurnResult> pipeline) {
        var identity =
                new RoleplayRequestIdentity(
                        trace, idempotencyKey, fingerprint, expectedVersion, turnNumber);
        return runner.executeWithTimeout(
                deadline, shared -> executeOwned(identity, shared, pipeline), this::timeout);
    }

    /**
     * Input preparation and ownership share one bounded worker; never nest runners on the same
     * pool.
     */
    public Result executePrepared(
            RoleplayTurnDeadline deadline,
            Function<RoleplayTurnDeadline, PreparedExecution> preparation) {
        Objects.requireNonNull(preparation);
        try {
            return runner.executeWithTimeout(
                    deadline,
                    shared -> {
                        var prepared = shared.withinBudget(() -> preparation.apply(shared));
                        return executeOwned(prepared.identity(), shared, prepared.pipeline());
                    },
                    this::timeout);
        } catch (java.util.concurrent.RejectedExecutionException saturated) {
            return new CapacityUnavailable();
        }
    }

    public record PreparedExecution(
            RoleplayRequestIdentity identity,
            Function<RoleplayTurnDeadline, RoleplayTurnResult> pipeline) {
        public PreparedExecution {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(pipeline);
        }
    }

    private Result executeOwned(
            RoleplayRequestIdentity identity,
            RoleplayTurnDeadline shared,
            Function<RoleplayTurnDeadline, RoleplayTurnResult> pipeline) {
        java.util.Optional<RoleplayRequestGuard.Lease> ownership;
        try {
            ownership = requests.tryAcquire(identity, shared);
        } catch (RoleplayRequestGuard.CapacityUnavailable unavailable) {
            return new CapacityUnavailable();
        }
        if (ownership.isEmpty()) return new Processing();
        try (var lease = ownership.get()) {
            shared.requireActive();
            var previous = checkpoints.findConfirmed(identity, shared);
            if (previous instanceof RoleplayCheckpointStore.Found found)
                return replay(found.result());
            if (previous instanceof RoleplayCheckpointStore.Conflict) return new Conflict();
            if (previous instanceof RoleplayCheckpointStore.Expired) return timeout();
            var result = shared.withinBudget(() -> pipeline.apply(shared));
            return commit(identity, shared, result);
        }
    }

    private Result commit(
            RoleplayRequestIdentity identity,
            RoleplayTurnDeadline deadline,
            RoleplayTurnResult result) {
        if (!(result instanceof RoleplayTurnResult.SpokenReady spoken))
            return new Recovery(outcomes.resolve(result));
        var command =
                RoleplayCheckpointCommand.fromApproved(
                        identity.trace(),
                        identity.expectedVersion(),
                        identity.turnNumber(),
                        identity.idempotencyKey(),
                        identity.fingerprint(),
                        spoken);
        var committed = checkpoints.commit(command, deadline);
        if (committed instanceof RoleplayCheckpointStore.Conflict) return new Conflict();
        if (committed instanceof RoleplayCheckpointStore.Expired) return timeout();
        var saved = (RoleplayCheckpointStore.Committed) committed;
        // A duplicate can have a newly generated candidate; discard it and its audio.
        if (saved.replayed()) {
            return replay(saved);
        }
        if (!saved.turnId().equals(command.turnId())
                || !saved.candidateId().equals(command.candidateId())
                || !saved.responseText().equals(command.responseText()))
            throw new IllegalStateException("Committed candidate mismatch");
        return new Success(outcomes.resolve(spoken), saved.version(), false);
    }

    private Result timeout() {
        return new Recovery(outcomes.resolve(new RoleplayTurnResult.TimedOut()));
    }

    private static Result replay(RoleplayCheckpointStore.Committed saved) {
        return new Success(
                new RoleplayTurnOutcome.ApprovedResponse(saved.candidateId(), saved.responseText()),
                saved.version(),
                true);
    }
}
