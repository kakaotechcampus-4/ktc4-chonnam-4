package com.neuringo.neuringobe.scenario.dto;

import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;

public record ScenarioGenerationContext(
        ScenarioPreparationInput input,
        ScenarioReviewSnapshot snapshot,
        long epoch,
        GeneratedScenario previous) {}
