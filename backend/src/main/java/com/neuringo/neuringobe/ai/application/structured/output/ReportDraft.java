package com.neuringo.neuringobe.ai.application.structured.output;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** 강사용 학습 리포트 초안(03 VS-014 "효과적 지원과 어려운 지점이 관찰 근거와 함께 표시된다"). 수치는 시스템이 따로 보여 준다. */
public record ReportDraft(
        String summary,
        List<ReportItem> observations,
        @JsonProperty("effective_supports") List<ReportItem> effectiveSupports,
        @JsonProperty("difficult_points") List<ReportItem> difficultPoints) {

    public ReportDraft {
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(observations, "observations must not be null");
        Objects.requireNonNull(effectiveSupports, "effectiveSupports must not be null");
        Objects.requireNonNull(difficultPoints, "difficultPoints must not be null");
        observations = List.copyOf(observations);
        effectiveSupports = List.copyOf(effectiveSupports);
        difficultPoints = List.copyOf(difficultPoints);
    }

    public Stream<ReportItem> items() {
        return Stream.of(observations, effectiveSupports, difficultPoints).flatMap(List::stream);
    }
}
