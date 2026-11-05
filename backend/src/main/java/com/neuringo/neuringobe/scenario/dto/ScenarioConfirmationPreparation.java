package com.neuringo.neuringobe.scenario.dto;

import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import com.neuringo.neuringobe.scenario.domain.ScenarioApprovalConditions;

public record ScenarioConfirmationPreparation(
        InitialScenarioPreparation input,
        String source,
        String contentBody,
        ScenarioApprovalConditions conditions,
        ScenarioEdits edits,
        GeneratedScenario editedContent) {
    public ScenarioConfirmationPreparation(
            InitialScenarioPreparation input,
            String source,
            String contentBody,
            ScenarioApprovalConditions conditions) {
        this(input, source, contentBody, conditions, ScenarioEdits.none(), null);
    }
}
