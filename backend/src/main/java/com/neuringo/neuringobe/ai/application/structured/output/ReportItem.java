package com.neuringo.neuringobe.ai.application.structured.output;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/** 리포트 문장 하나와 그 문장이 기대는 근거 ID. 근거 없는 문장은 쓰지 않는다. */
public record ReportItem(String text, @JsonProperty("evidence_ids") List<String> evidenceIds) {

    public ReportItem {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(evidenceIds, "evidenceIds must not be null");
        evidenceIds = List.copyOf(evidenceIds);
    }
}
