package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import com.neuringo.neuringobe.ai.application.roleplay.*;
import com.neuringo.neuringobe.roleplay.service.RoleplayRequestScopeService.Scope;
import java.util.Objects;
import java.util.UUID;

/** 음성과 같은 세션·요청 키·체크포인트를 사용하며 STT와 음성 실패 집계는 건너뛴다. */
public final class RoleplayScopedTextTurnExecutor {
    private final RoleplayCheckpointedTurnExecutor checkpoints;
    private final RoleplayContextAssembler contexts;
    private final RoleplaySpeechTurnPipeline pipeline;
    private final RoleplayVoiceRecoverySource recoveries;

    public RoleplayScopedTextTurnExecutor(
            RoleplayCheckpointedTurnExecutor checkpoints,
            RoleplayContextAssembler contexts,
            RoleplaySpeechTurnPipeline pipeline,
            RoleplayVoiceRecoverySource recoveries) {
        this.checkpoints = checkpoints;
        this.contexts = contexts;
        this.pipeline = pipeline;
        this.recoveries = Objects.requireNonNull(recoveries);
    }

    public RoleplayCheckpointedTurnExecutor.Result execute(
            Scope scope,
            UUID requestId,
            UUID turnId,
            UUID idempotencyKey,
            String text,
            RoleplayTurnDeadline deadline) {
        var trace = scope.trace(requestId, turnId);
        return checkpoints.executePrepared(
                deadline,
                shared -> {
                    var identity =
                            new RoleplayRequestIdentity(
                                    trace,
                                    idempotencyKey,
                                    RoleplayTextInput.fingerprint(text, shared),
                                    scope.checkpointVersion(),
                                    Math.addExact(scope.lastTurnNumber(), 1));
                    return new RoleplayCheckpointedTurnExecutor.PreparedExecution(
                            identity,
                            active -> {
                                var prepared = contexts.prepare(scope, requestId, turnId, active);
                                var progress =
                                        Objects.requireNonNull(
                                                recoveries.progress(
                                                        new RoleplayVoiceRecoverySource.Key(
                                                                scope.childId(),
                                                                scope.activityId(),
                                                                scope.sessionId(),
                                                                scope.scenarioId(),
                                                                scope.scenarioVersion()),
                                                        active));
                                var snapshot =
                                        new RoleplayTurnInput.InputRecoverySnapshot(
                                                progress.failureCount(),
                                                progress.textRecommended());
                                var result =
                                        pipeline.executeText(
                                                trace,
                                                text,
                                                prepared.input(),
                                                prepared.observations(),
                                                active,
                                                snapshot);
                                if (result instanceof RoleplayTurnResult.SpokenReady ready) {
                                    return new RoleplayTurnResult.SpokenReady(
                                            ready.ready(),
                                            ready.canonicalUtterance(),
                                            ready.speech(),
                                            prepared.contextFingerprint(),
                                            RoleplayInputContext.from(prepared.input().state()),
                                            ready.inputQuality());
                                }
                                return result;
                            },
                            false);
                });
    }
}
