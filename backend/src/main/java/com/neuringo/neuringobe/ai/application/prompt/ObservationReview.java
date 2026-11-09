package com.neuringo.neuringobe.ai.application.prompt;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** 검증 중에만 사용하는 모델 출력이다. 인용과 설명은 내부 판정·저장 결과로 전달하지 않는다. */
record ObservationReview(
        @JsonProperty("micro_goal_id") UUID microGoalId,
        @JsonProperty("criterion_reference") String criterionReference,
        @JsonProperty("evidence_quote") String evidenceQuote,
        Verdict verdict,
        @JsonProperty("reason_code") String reasonCode,
        String explanation) {
    enum Verdict {
        SUPPORTED,
        UNSUPPORTED,
        INSUFFICIENT_CONTEXT
    }
}
