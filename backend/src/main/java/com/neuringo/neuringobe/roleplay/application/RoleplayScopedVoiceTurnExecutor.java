package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayAudioResource;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointedTurnExecutor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputContext;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestIdentity;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplaySpeechTurnPipeline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
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

    /** 인증된 범위에서 음성 지문을 계산하고 분석 자료 지문을 결과에 연결한다. 모든 종료 경로에서 업로드를 해제한다. */
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
                                    var result =
                                            speech.execute(
                                                    input.request(),
                                                    prepared.input(),
                                                    prepared.observations(),
                                                    active);
                                    if (result instanceof RoleplayTurnResult.SpokenReady spoken) {
                                        return new RoleplayTurnResult.SpokenReady(
                                                spoken.ready(),
                                                spoken.canonicalUtterance(),
                                                spoken.speech(),
                                                prepared.contextFingerprint(),
                                                RoleplayInputContext.from(
                                                        prepared.input().state()));
                                    }
                                    return result;
                                });
                    });
        }
    }
}
