package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayAudioResource;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointedTurnExecutor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestIdentity;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplaySpeechTurnPipeline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.roleplay.service.RoleplayRequestScopeService.Scope;
import java.util.Objects;
import java.util.UUID;

/**
 * Internal entry after authenticated scope lookup. Not an HTTP controller or an authentication
 * adapter.
 */
public final class RoleplayScopedVoiceTurnExecutor {
    private final RoleplayCheckpointedTurnExecutor checkpoints;
    private final RoleplayContextAssembler contexts;
    private final RoleplaySpeechTurnPipeline speech;

    public RoleplayScopedVoiceTurnExecutor(
            RoleplayCheckpointedTurnExecutor checkpoints,
            RoleplayContextAssembler contexts,
            RoleplaySpeechTurnPipeline speech) {
        this.checkpoints = Objects.requireNonNull(checkpoints);
        this.contexts = Objects.requireNonNull(contexts);
        this.speech = Objects.requireNonNull(speech);
    }

    /**
     * Scope must come from authenticated server lookup. This entry computes the fingerprint itself.
     * Owns the upload on every exit.
     */
    public RoleplayCheckpointedTurnExecutor.Result executeVoice(
            Scope scope,
            UUID requestId,
            UUID turnId,
            UUID idempotencyKey,
            RoleplayAudioResource audio,
            RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(audio);
        try (audio) {
            Objects.requireNonNull(scope);
            var trace = scope.trace(requestId, turnId);
            return checkpoints.executePrepared(
                    deadline,
                    shared -> {
                        var input = RoleplayVoiceInput.prepare(audio, trace, shared);
                        var identity =
                                new RoleplayRequestIdentity(
                                        trace,
                                        idempotencyKey,
                                        input.fingerprint(),
                                        scope.checkpointVersion(),
                                        Math.addExact(scope.lastTurnNumber(), 1));
                        return new RoleplayCheckpointedTurnExecutor.PreparedExecution(
                                identity,
                                active -> {
                                    var prepared =
                                            contexts.prepareVoice(scope, requestId, turnId, active);
                                    return speech.execute(
                                            input.request(), prepared.input(), active);
                                });
                    });
        }
    }
}
