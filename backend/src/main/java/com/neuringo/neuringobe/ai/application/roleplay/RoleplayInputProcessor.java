package com.neuringo.neuringobe.ai.application.roleplay;

/** Must implement canonicalization, privacy filtering and input safety before any LLM call. */
public interface RoleplayInputProcessor {
    Result process(String transcription);

    sealed interface Result {}

    record Accepted(String canonicalUtterance) implements Result {
        public Accepted {
            if (canonicalUtterance == null || canonicalUtterance.isBlank()) {
                throw new IllegalArgumentException("Canonical utterance must not be blank");
            }
        }

        @Override
        public String toString() {
            return "Accepted[canonicalUtterance=redacted]";
        }
    }

    record Invalid() implements Result {}

    record Unsafe() implements Result {}
}
