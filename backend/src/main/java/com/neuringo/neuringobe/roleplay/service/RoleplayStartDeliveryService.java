package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.roleplay.dto.RoleplaySessionState;
import com.neuringo.neuringobe.roleplay.dto.RoleplayStartResponse;
import com.neuringo.neuringobe.roleplay.dto.RoleplayTurnResponse;
import com.neuringo.neuringobe.roleplay.repository.RoleplayStartMessageRepository;
import com.neuringo.neuringobe.roleplay.repository.RoleplayTurnRepository;
import com.neuringo.neuringobe.scenario.repository.ScenarioRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleplayStartDeliveryService {
    private final RoleplayStartMessageRepository openings;
    private final RoleplayTurnRepository turns;
    private final ScenarioRepository scenarios;

    public RoleplayStartDeliveryService(
            RoleplayStartMessageRepository openings,
            RoleplayTurnRepository turns,
            ScenarioRepository scenarios) {
        this.openings = openings;
        this.turns = turns;
        this.scenarios = scenarios;
    }

    /** 시작·복원과 문구 준비가 함께 확정돼야 하므로 독립 호출을 허용하지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public RoleplayStartResponse require(RoleplaySessionState state) {
        List<RoleplayStartResponse.Message> messages = List.of();
        if ("IN_PROGRESS".equals(state.status())
                && state.activityStatus() == ActivityStatus.IN_PROGRESS) {
            if (state.lastTurnNumber() == 0) {
                var opening = openings.findById(state.sessionId()).orElse(null);
                UUID messageId;
                String text;
                if (opening == null) {
                    if (state.currentSupportLevel() == null) throw notReady();
                    text =
                            scenarios
                                    .findApprovedOpening(
                                            state.activityId(),
                                            state.scenarioId(),
                                            state.scenarioVersion(),
                                            state.currentSupportLevel())
                                    .filter(value -> !value.isBlank())
                                    .orElseThrow(this::notReady);
                    messageId = UUID.randomUUID();
                    openings.insert(
                            state.sessionId(),
                            state.scenarioId(),
                            state.scenarioVersion(),
                            messageId,
                            text,
                            Instant.now());
                } else {
                    messageId = opening.getMessageId();
                    text = opening.getOpeningText();
                }
                messages = List.of(message(messageId, "QUESTION", text));
            } else {
                // 상태 변경으로 세션 버전이 증가해도 마지막 확정 턴은 그대로 복원한다.
                var saved =
                        turns.findLastCommittedMessage(
                                        state.sessionId(),
                                        state.lastTurnId(),
                                        state.checkpointVersion(),
                                        state.lastTurnNumber())
                                .orElseThrow(this::notReady);
                messages = List.of(message(saved.messageId(), "RESUME", saved.text()));
            }
        }
        return new RoleplayStartResponse(
                state.sessionId(),
                state.activityId(),
                state.scenarioId(),
                state.scenarioVersion(),
                state.status(),
                state.activityStatus(),
                state.lastTurnId(),
                state.checkpointVersion(),
                new RoleplayStartResponse.Delivery(messages));
    }

    private RoleplayStartResponse.Message message(UUID id, String purpose, String text) {
        return new RoleplayStartResponse.Message(
                id, "DIALOGUE", purpose, text, new RoleplayTurnResponse.Speech(false, null));
    }

    private ApiException notReady() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "ROLEPLAY_START_DELIVERY_NOT_READY",
                "역할극에서 표시할 확정 문구가 준비되지 않았습니다.");
    }
}
