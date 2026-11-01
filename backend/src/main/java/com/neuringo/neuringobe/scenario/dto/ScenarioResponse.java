package com.neuringo.neuringobe.scenario.dto;

import com.neuringo.neuringobe.scenario.domain.Scenario;
import com.neuringo.neuringobe.scenario.domain.ScenarioMicroGoal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 강사가 검토하는 시나리오 한 판. 시나리오는 학급·목표에 묶지 않는 공용 자료라 명세 초안의 goalId·scenarioSource 는 두지 않는다. 다른 아동이 이
 * 시나리오를 언제·몇 번 썼는지도 담지 않는다.
 */
public record ScenarioResponse(
        UUID scenarioId,
        int version,
        String status,
        String scenarioLevel,
        String title,
        String summary,
        List<String> scenarioFacts,
        List<String> prohibitedInferences,
        List<Map<String, Object>> characters,
        List<MicroGoal> microGoals,
        Map<String, Object> openingPrompts) {

    public record MicroGoal(UUID microGoalId, String description, List<String> requiredEvidence) {}

    public static ScenarioResponse of(Scenario scenario, List<ScenarioMicroGoal> microGoals) {
        return new ScenarioResponse(
                scenario.getScenarioId(),
                scenario.getScenarioVersion(),
                scenario.getStatus().name(),
                scenario.getScenarioLevel(),
                scenario.getTitle(),
                scenario.getSummary(),
                scenario.getScenarioFacts(),
                scenario.getProhibitedInferences(),
                scenario.getCharacters(),
                microGoals.stream()
                        .map(
                                g ->
                                        new MicroGoal(
                                                g.getMicroGoalId(),
                                                g.getDescription(),
                                                g.getRequiredEvidence()))
                        .toList(),
                scenario.getOpeningPrompts());
    }
}
