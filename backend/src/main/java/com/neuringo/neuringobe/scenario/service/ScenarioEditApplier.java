package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.scenario.dto.ScenarioEdits;
import com.neuringo.neuringobe.scenario.repository.ScenarioDraftRepository;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ScenarioEditApplier {
    private final ScenarioDraftRepository drafts;

    public ScenarioEditApplier(ScenarioDraftRepository drafts) {
        this.drafts = drafts;
    }

    public GeneratedScenario apply(UUID sourceId, GeneratedScenario original, ScenarioEdits edits) {
        var keys = drafts.microGoalKeys(sourceId);
        var patches = new HashMap<String, ScenarioEdits.MicroGoalEdit>();
        for (var patch : edits.microGoals()) {
            var key = keys.get(patch.microGoalId());
            if (key == null)
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "INVALID_SCENARIO_EDITS",
                        "현재 시나리오의 기존 작은 목표만 수정할 수 있습니다.");
            patches.put(key, patch);
        }
        var goals =
                original.microGoals().stream()
                        .map(
                                goal -> {
                                    var patch = patches.get(goal.id());
                                    if (patch == null) return goal;
                                    return new GeneratedScenario.MicroGoal(
                                            goal.id(),
                                            patch.description() == null
                                                    ? goal.description()
                                                    : patch.description(),
                                            patch.requiredEvidence() == null
                                                    ? goal.requiredEvidence()
                                                    : patch.requiredEvidence(),
                                            goal.achievementExample());
                                })
                        .toList();
        return new GeneratedScenario(
                edits.title() == null ? original.title() : edits.title(),
                edits.summary() == null ? original.summary() : edits.summary(),
                original.scenarioLevel(),
                original.aiRole(),
                original.characters(),
                original.scenarioFacts(),
                original.prohibitedInferences(),
                goals,
                original.startingQuestions(),
                original.startingQuestionGoalIds());
    }
}
