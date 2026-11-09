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

    /** 캐릭터 대사(DIALOGUE)와 마스코트 힌트·안내(HINT)를 화면이 다른 자리에 보여 준다. */
    public static final String DIALOGUE = "DIALOGUE";

    public static final String HINT = "HINT";

    public record Message(String speakerRole, String text, Speech speech) {
        public Message(String text, Speech speech) {
            this(DIALOGUE, text, speech);
        }

        public Message {
            if (!DIALOGUE.equals(speakerRole) && !HINT.equals(speakerRole))
                throw new IllegalArgumentException("Unknown speaker role");
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
