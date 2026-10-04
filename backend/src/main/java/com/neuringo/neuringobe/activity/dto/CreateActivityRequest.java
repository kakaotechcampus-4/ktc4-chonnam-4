package com.neuringo.neuringobe.activity.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateActivityRequest(@NotNull UUID childId, @NotNull UUID goalId) {}
