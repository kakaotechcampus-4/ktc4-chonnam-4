package com.neuringo.neuringobe.roleplay.dto;

import com.neuringo.neuringobe.roleplay.model.RoleplayDeliveryAction;
import com.neuringo.neuringobe.roleplay.model.RoleplayDeliveryStatus;
import java.util.List;
import java.util.Objects;

/** Proposed child projection; endpoint/status-code and FE contract are not yet wired. */
public record RoleplayTurnResponse(
        RoleplayDeliveryStatus status,
        RoleplayDeliveryAction action,
        List<Message> messages,
        String acceptedInput,
        Long checkpointVersion,
        boolean replayed) {
    public RoleplayTurnResponse {
        Objects.requireNonNull(status);
        Objects.requireNonNull(action);
        messages = List.copyOf(Objects.requireNonNull(messages));
    }

    public record Message(String text, Speech speech) {
        public Message {
            if (text == null || text.isBlank())
                throw new IllegalArgumentException("Delivery text required");
            Objects.requireNonNull(speech);
        }

        @Override
        public String toString() {
            return "Message[content=redacted]";
        }
    }

    public record Speech(boolean available, String url) {
        public Speech {
            if (available != (url != null && !url.isBlank()) || (!available && url != null))
                throw new IllegalArgumentException("Speech availability requires a resource URL");
        }

        @Override
        public String toString() {
            return "Speech[available=" + available + "]";
        }
    }

    @Override
    public String toString() {
        return "RoleplayTurnResponse[status="
                + status
                + ", action="
                + action
                + ", replayed="
                + replayed
                + "]";
    }
}
