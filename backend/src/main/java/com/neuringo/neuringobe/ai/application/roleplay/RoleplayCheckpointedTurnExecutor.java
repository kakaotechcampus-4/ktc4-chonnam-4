package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.prompt.GoalObservationInput;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** 인증된 요청만 처리하며, 턴 저장이 확정된 뒤 성공을 반환한다. */
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
            GoalObservationInput observations,
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
                        return pipeline.execute(request, context, observations, shared);
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

    /** 입력 준비와 중복 처리 확인은 같은 작업자에서 실행해, 작업자끼리 서로 기다리는 상황을 막는다. */
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
        } catch (RoleplayRequestGuard.InputConflict conflict) {
            return new Conflict();
        } catch (RoleplayRequestGuard.CapacityUnavailable unavailable) {
            return new CapacityUnavailable();
        }
        if (ownership.isEmpty()) return new Processing();
        try (var lease = ownership.get()) {
            shared.requireActive();
            return switch (checkpoints.findConfirmed(identity, shared)) {
                case RoleplayCheckpointStore.Found found -> replay(found.result());
                case RoleplayCheckpointStore.Conflict ignored -> new Conflict();
                case RoleplayCheckpointStore.Expired ignored -> timeout();
                case RoleplayCheckpointStore.Absent ignored -> {
                    var result = shared.withinBudget(() -> pipeline.apply(shared));
                    yield commit(identity, shared, result);
                }
            };
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
        return switch (checkpoints.commitApproved(command, spoken.ready(), deadline)) {
            case RoleplayCheckpointStore.Conflict ignored -> new Conflict();
            case RoleplayCheckpointStore.Expired ignored -> timeout();
            case RoleplayCheckpointStore.Committed saved -> confirmed(command, saved, spoken);
        };
    }

    private Result confirmed(
            RoleplayCheckpointCommand command,
            RoleplayCheckpointStore.Committed saved,
            RoleplayTurnResult.SpokenReady spoken) {
        // 이미 저장된 요청이면 새 후보와 음성을 버리고 원래 결과를 반환한다.
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
