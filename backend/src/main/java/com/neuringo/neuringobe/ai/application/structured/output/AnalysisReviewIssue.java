package com.neuringo.neuringobe.ai.application.structured.output;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/** 재분석에 필요한 거절 영역과 사유만 보존한다. 설명은 실행할 지시가 아닌 검수 자료다. */
public record AnalysisReviewIssue(
        Scope scope,
        @JsonProperty("reviewed_field") String reviewedField,
        @JsonProperty("reason_code") String reasonCode,
        String explanation) {
    public enum Scope {
        OBSERVATION,
        CURRENT_GOAL,
        ACTIVITY,
        INTERPRETATION_STRATEGY
    }

    public AnalysisReviewIssue {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(reviewedField);
        Objects.requireNonNull(reasonCode);
        Objects.requireNonNull(explanation);
        if (explanation.length() > 320) explanation = explanation.substring(0, 320);
    }
}
