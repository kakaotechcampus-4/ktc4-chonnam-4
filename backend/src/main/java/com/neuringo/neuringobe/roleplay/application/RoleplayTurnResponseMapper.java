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
        return switch (result) {
            case RoleplayCheckpointedTurnExecutor.Success success -> mapSuccess(success);
            case RoleplayCheckpointedTurnExecutor.Recovery recovery ->
                    mapRecovery(recovery.outcome());
            case RoleplayCheckpointedTurnExecutor.Conflict ignored ->
                    empty(RoleplayDeliveryStatus.CONFLICT, RoleplayDeliveryAction.NONE);
            case RoleplayCheckpointedTurnExecutor.Processing ignored ->
                    empty(RoleplayDeliveryStatus.PROCESSING, RoleplayDeliveryAction.WAIT);
            case RoleplayCheckpointedTurnExecutor.CapacityUnavailable ignored ->
                    empty(
                            RoleplayDeliveryStatus.BUSY,
                            RoleplayDeliveryAction.RESUBMIT_SAME_REQUEST);
        };
    }

    private RoleplayTurnResponse mapSuccess(RoleplayCheckpointedTurnExecutor.Success success) {
        if (success.checkpointVersion() < 1)
            throw new IllegalArgumentException("Committed version required");
        return switch (success.outcome()) {
            case RoleplayTurnOutcome.ApprovedResponse approved ->
                    delivered(success, approved, null, NO_SPEECH);
            case RoleplayTurnOutcome.SpokenResponse spoken -> mapSpokenSuccess(success, spoken);
            case RoleplayTurnOutcome.Recovery ignored ->
                    throw new IllegalArgumentException("Success requires an approved response");
            case null ->
                    throw new IllegalArgumentException("Success requires an approved response");
        };
    }

    private RoleplayTurnResponse mapSpokenSuccess(
            RoleplayCheckpointedTurnExecutor.Success success,
            RoleplayTurnOutcome.SpokenResponse spoken) {
        if (success.replayed())
            throw new IllegalArgumentException(
                    "Replay must not include canonical input or transient audio");
        Speech deliveredSpeech =
                spoken.speech() == null
                        ? NO_SPEECH
                        : speech.publish(spoken.response().candidateId(), spoken.speech())
                                .map(url -> new Speech(true, url))
                                .orElse(NO_SPEECH);
        return delivered(success, spoken.response(), spoken.canonicalUtterance(), deliveredSpeech);
    }

    private RoleplayTurnResponse delivered(
            RoleplayCheckpointedTurnExecutor.Success success,
            RoleplayTurnOutcome.ApprovedResponse approved,
            String acceptedInput,
            Speech deliveredSpeech) {
        return new RoleplayTurnResponse(
                RoleplayDeliveryStatus.DELIVERED,
                RoleplayDeliveryAction.NONE,
                List.of(new Message(approved.text(), deliveredSpeech)),
                acceptedInput,
                success.checkpointVersion(),
                success.replayed());
    }

    private RoleplayTurnResponse mapRecovery(RoleplayTurnOutcome outcome) {
        if (!(outcome instanceof RoleplayTurnOutcome.Recovery recovery))
            throw new IllegalArgumentException("Recovery must not contain an approved candidate");
        return switch (recovery) {
            case RoleplayTurnOutcome.InputRetry input ->
                    recoveryResponse(input.notice().text(), input.action());
            case RoleplayTurnOutcome.Guidance guidance ->
                    recoveryResponse(guidance.notice().text(), guidance.action());
            case RoleplayTurnOutcome.SafetyStop stop ->
                    recoveryResponse(stop.childNotice().text(), stop.action());
            case RoleplayTurnOutcome.InputNoticeUnavailable unavailable ->
                    recoveryResponse(null, unavailable.action());
            case RoleplayTurnOutcome.NoticeUnavailable unavailable ->
                    recoveryResponse(null, unavailable.action());
        };
    }

    private RoleplayTurnResponse recoveryResponse(String text, RoleplayTurnOutcome.Action action) {
        return switch (action) {
            case REQUEST_NEW_INPUT ->
                    notice(
                            text,
                            RoleplayDeliveryStatus.REINPUT_REQUIRED,
                            RoleplayDeliveryAction.REQUEST_NEW_INPUT);
            case RETRY_TURN ->
                    notice(
                            text,
                            RoleplayDeliveryStatus.RETRY_REQUIRED,
                            RoleplayDeliveryAction.RETRY_TURN);
            case STOP_DIALOGUE ->
                    notice(
                            text,
                            RoleplayDeliveryStatus.STOPPED,
                            RoleplayDeliveryAction.STOP_DIALOGUE);
        };
    }

    private RoleplayTurnResponse notice(
            String text, RoleplayDeliveryStatus status, RoleplayDeliveryAction action) {
        if (text == null) return empty(RoleplayDeliveryStatus.NOTICE_UNAVAILABLE, action);
        return new RoleplayTurnResponse(
                status, action, List.of(new Message(text, NO_SPEECH)), null, null, false);
    }

    private RoleplayTurnResponse empty(
            RoleplayDeliveryStatus status, RoleplayDeliveryAction action) {
        return new RoleplayTurnResponse(status, action, List.of(), null, null, false);
    }
}
