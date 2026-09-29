package com.neuringo.neuringobe.quiz.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AssignQuizItemRequest(
        @NotNull UUID itemId, @Min(1) int itemVersion, @Min(1) @Max(3) int questionOrder) {}
