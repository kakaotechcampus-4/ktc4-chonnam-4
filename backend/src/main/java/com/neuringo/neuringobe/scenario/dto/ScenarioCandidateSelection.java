package com.neuringo.neuringobe.scenario.dto;

import java.util.List;
import java.util.Map;

/** 조건 조회 결과다. 순환 순서 결정과 검토 목록 저장은 별도 단계에서 수행한다. */
public record ScenarioCandidateSelection(
        List<ApprovedScenarioVersion> candidates,
        long poolScenarioCount,
        Map<String, Long> exclusionCounts) {
    public ScenarioCandidateSelection {
        candidates = List.copyOf(candidates);
        exclusionCounts = Map.copyOf(exclusionCounts);
    }
}
