package com.neuringo.neuringobe.roleplay.dto;

import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 아동용 시작·복원 응답에는 상세 지원 수준과 평가 근거를 노출하지 않는다. */
public record RoleplayStartResponse(
        UUID sessionId,
        UUID activityId,
        UUID scenarioId,
        int scenarioVersion,
        String status,
        ActivityStatus activityStatus,
        UUID lastDeliveredTurnId,
        long checkpointVersion,
        Delivery delivery) {

    public record Delivery(List<Message> messages) {
        public Delivery {
            messages = List.copyOf(messages);
        }
    }

    public record Message(
            UUID messageId,
            String speakerRole,
            String purpose,
            String text,
            RoleplayTurnResponse.Speech speech) {
        public Message {
            Objects.requireNonNull(messageId);
            if (text == null || text.isBlank())
                throw new IllegalArgumentException("표시할 문구가 필요합니다.");
            Objects.requireNonNull(speech);
        }

        @Override
        public String toString() {
            return "Message[id=" + messageId + ", content=redacted]";
        }
    }
}
