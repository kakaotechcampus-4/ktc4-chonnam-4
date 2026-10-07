package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointedTurnExecutor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcome;
import com.neuringo.neuringobe.roleplay.dto.RoleplayTurnResponse;
import com.neuringo.neuringobe.roleplay.dto.RoleplayTurnResponse.Message;
import com.neuringo.neuringobe.roleplay.dto.RoleplayTurnResponse.Speech;
import com.neuringo.neuringobe.roleplay.model.RoleplayDeliveryAction;
import com.neuringo.neuringobe.roleplay.model.RoleplayDeliveryStatus;
import java.util.List;
import java.util.Objects;

/** Maps post-checkpoint execution results only. No AI call, canonical-text lookup or DB write. */
public final class RoleplayTurnResponseMapper {
    private static final Speech NO_SPEECH = new Speech(false, null);
    private final RoleplaySpeechDelivery speech;

    public RoleplayTurnResponseMapper(RoleplaySpeechDelivery speech) {
        this.speech = Objects.requireNonNull(speech);
    }

    public RoleplayTurnResponse map(RoleplayCheckpointedTurnExecutor.Result result) {
        Objects.requireNonNull(result);
        if (result instanceof RoleplayCheckpointedTurnExecutor.Success success)
            return success(success);
        if (result instanceof RoleplayCheckpointedTurnExecutor.Recovery recovery)
            return recovery(recovery.outcome());
        if (result instanceof RoleplayCheckpointedTurnExecutor.Conflict)
            return empty(RoleplayDeliveryStatus.CONFLICT, RoleplayDeliveryAction.NONE);
        if (result instanceof RoleplayCheckpointedTurnExecutor.Processing)
            return empty(RoleplayDeliveryStatus.PROCESSING, RoleplayDeliveryAction.WAIT);
        if (result instanceof RoleplayCheckpointedTurnExecutor.CapacityUnavailable)
            return empty(RoleplayDeliveryStatus.BUSY, RoleplayDeliveryAction.RESUBMIT_SAME_REQUEST);
        throw new IllegalArgumentException("Unsupported turn result");
    }

    private RoleplayTurnResponse success(RoleplayCheckpointedTurnExecutor.Success success) {
        if (success.checkpointVersion() < 1)
            throw new IllegalArgumentException("Committed version required");
        RoleplayTurnOutcome.ApprovedResponse approved;
        String accepted = null;
        Speech deliveredSpeech = NO_SPEECH;
        if (success.outcome() instanceof RoleplayTurnOutcome.SpokenResponse spoken) {
            if (success.replayed())
                throw new IllegalArgumentException(
                        "Replay must not include canonical input or transient audio");
            approved = spoken.response();
            accepted = spoken.canonicalUtterance();
            if (spoken.speech() != null) {
                deliveredSpeech =
                        speech.publish(approved.candidateId(), spoken.speech())
                                .map(url -> new Speech(true, url))
                                .orElse(NO_SPEECH);
            }
        } else if (success.outcome() instanceof RoleplayTurnOutcome.ApprovedResponse response) {
            approved = response;
        } else throw new IllegalArgumentException("Success requires an approved response");
        return new RoleplayTurnResponse(
                RoleplayDeliveryStatus.DELIVERED,
                RoleplayDeliveryAction.NONE,
                List.of(new Message(approved.text(), deliveredSpeech)),
                accepted,
                success.checkpointVersion(),
                success.replayed());
    }

    private RoleplayTurnResponse recovery(RoleplayTurnOutcome outcome) {
        if (!(outcome instanceof RoleplayTurnOutcome.Recovery recovery))
            throw new IllegalArgumentException("Recovery must not contain an approved candidate");
        RoleplayDeliveryAction action =
                switch (recovery.action()) {
                    case REQUEST_NEW_INPUT -> RoleplayDeliveryAction.REQUEST_NEW_INPUT;
                    case RETRY_TURN -> RoleplayDeliveryAction.RETRY_TURN;
                    case STOP_DIALOGUE -> RoleplayDeliveryAction.STOP_DIALOGUE;
                };
        String text = null;
        if (recovery instanceof RoleplayTurnOutcome.InputRetry input) text = input.notice().text();
        else if (recovery instanceof RoleplayTurnOutcome.Guidance guidance)
            text = guidance.notice().text();
        else if (recovery instanceof RoleplayTurnOutcome.SafetyStop stop)
            text = stop.childNotice().text();
        if (text == null) return empty(RoleplayDeliveryStatus.NOTICE_UNAVAILABLE, action);
        RoleplayDeliveryStatus status =
                switch (action) {
                    case REQUEST_NEW_INPUT -> RoleplayDeliveryStatus.REINPUT_REQUIRED;
                    case RETRY_TURN -> RoleplayDeliveryStatus.RETRY_REQUIRED;
                    case STOP_DIALOGUE -> RoleplayDeliveryStatus.STOPPED;
                    default -> throw new IllegalArgumentException("Unexpected recovery action");
                };
        return new RoleplayTurnResponse(
                status, action, List.of(new Message(text, NO_SPEECH)), null, null, false);
    }

    private RoleplayTurnResponse empty(
            RoleplayDeliveryStatus status, RoleplayDeliveryAction action) {
        return new RoleplayTurnResponse(status, action, List.of(), null, null, false);
    }
}
