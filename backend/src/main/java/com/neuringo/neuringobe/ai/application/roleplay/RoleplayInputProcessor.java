package com.neuringo.neuringobe.ai.application.roleplay;

/** 음성 전사·텍스트 입력을 공통으로 정제하고 LLM 호출 전에 개인정보·의미 보존·안전을 확인한다. */
public interface RoleplayInputProcessor {
    Result process(String input);

    sealed interface Result {}

    /** 확인하지 못한 판정은 null로 전달하며 호출자가 임의로 성공 처리하지 않는다. */
    record Assessment(
            Double normalizationConfidence,
            Boolean meaningPreserved,
            Boolean safe,
            Boolean personalInformationMasked) {}

    record Accepted(String canonicalUtterance, Assessment assessment) implements Result {
        public Accepted(String canonicalUtterance) {
            this(canonicalUtterance, null);
        }

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

    /** 검사 장애는 입력 거절이나 위험 판정으로 취급하지 않는다. */
    record Unavailable() implements Result {}
}
