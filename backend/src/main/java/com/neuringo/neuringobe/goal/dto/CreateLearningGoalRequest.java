package com.neuringo.neuringobe.goal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateLearningGoalRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 30) String situationType,
        List<Map<String, Object>> characters,
        List<String> requiredElements,
        List<String> forbiddenExpressions,
        UUID parentGoalId) {}
