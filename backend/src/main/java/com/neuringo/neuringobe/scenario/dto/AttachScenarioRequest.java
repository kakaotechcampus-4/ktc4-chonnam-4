package com.neuringo.neuringobe.scenario.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** 담당 강사가 승인한 재사용 후보. */
public record AttachScenarioRequest(@NotNull UUID scenarioId) {}
