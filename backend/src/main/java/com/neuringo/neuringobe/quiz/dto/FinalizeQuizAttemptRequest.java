package com.neuringo.neuringobe.quiz.dto;

import jakarta.validation.constraints.NotBlank;

public record FinalizeQuizAttemptRequest(@NotBlank String finalResponse) {}
