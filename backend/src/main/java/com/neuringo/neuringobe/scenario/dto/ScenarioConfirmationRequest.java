package com.neuringo.neuringobe.scenario.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record ScenarioConfirmationRequest(
        @NotNull UUID reviewId,
        @Positive long revision,
        @NotNull UUID scenarioId,
        @Positive int scenarioVersion,
        @Valid ScenarioEdits edits) {
    @JsonAnySetter
    public void unknown(String name, Object value) {
        throw new IllegalArgumentException("허용하지 않은 요청 항목입니다.");
    }
}
