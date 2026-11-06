package com.neuringo.neuringobe.report.domain;

import com.neuringo.neuringobe.scenario.domain.GoalOutcome;
import java.util.List;
import java.util.Objects;

/**
 * 역할극 목표 하나의 결과와 관찰 증거(03 VS-014 "삭제 전 추출된 관찰 증거"). 표준 발화 원문은 담지 않는다. observations 와
 * effectiveSupports 는 원인 판단 결과에서 뽑은 짧은 관찰 문장이다.
 */
public record GoalEvidence(
        GoalOutcome outcome,
        String goalTitle,
        List<String> observations,
        List<String> effectiveSupports) {

    public GoalEvidence {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (goalTitle == null || goalTitle.isBlank()) {
            throw new IllegalArgumentException("goalTitle must not be blank");
        }
        observations = observations == null ? List.of() : List.copyOf(observations);
        effectiveSupports = effectiveSupports == null ? List.of() : List.copyOf(effectiveSupports);
    }
}
