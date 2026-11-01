package com.neuringo.neuringobe.scenario.dto;

import com.neuringo.neuringobe.common.validation.NoControlCharacters;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** 초안의 작은 목표 하나를 고친다(명세 DraftMicroGoal). 목표를 더하거나 빼지 않고, 있는 목표의 설명·필요 증거만 바꾼다. */
public record DraftMicroGoalPatch(
        @NotNull UUID microGoalId,
        @NotBlank @Size(max = 500) @NoControlCharacters String description,
        @NotEmpty @Size(max = 5) List<@NotBlank @Size(max = 200) String> requiredEvidence) {}
