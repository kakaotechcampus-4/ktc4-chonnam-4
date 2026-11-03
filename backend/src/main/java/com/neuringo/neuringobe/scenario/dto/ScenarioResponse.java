package com.neuringo.neuringobe.scenario.dto;

import com.neuringo.neuringobe.scenario.domain.Scenario;
import com.neuringo.neuringobe.scenario.domain.ScenarioMicroGoal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 강사가 검토하는 시나리오 한 판(명세 DraftScenarioData). goalId 는 재사용 후보면 그 활동의 목표, 초안이면 초안의 목표다. 활동과 상관없이 공용
 * 승인본만 조회하면 목표가 없어 null 이다. 다른 아동이 이 시나리오를 언제·몇 번 썼는지는 담지 않는다. version 은 판 번호, editRevision 은 초안을
 * 저장할 때마다 오르는 수정 번호다. 초안을 고치거나 판정할 때 editRevision 을 그대로 돌려보낸다.
 */
public record ScenarioResponse(
        UUID scenarioId,
        int version,
        long editRevision,
        UUID goalId,
        String scenarioSource,
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

    /** 재사용 후보로 보여 준다. */
    public static final String REUSED = "REUSED";

    /** 이 목표를 위해 새로 만든 판(초안·판정 기록). */
    public static final String NEWLY_GENERATED = "NEWLY_GENERATED";

    public static ScenarioResponse of(
            Scenario scenario, List<ScenarioMicroGoal> microGoals, UUID goalId, String source) {
        return new ScenarioResponse(
                scenario.getScenarioId(),
                scenario.getScenarioVersion(),
                scenario.getRowVersion(),
                goalId,
                source,
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
