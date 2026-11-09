package com.neuringo.neuringobe.scenario.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ScenarioReviewResponse(
        UUID reviewId, UUID activityId, long revision, Conditions conditions, Candidate scenario) {
    public record Conditions(
            String title,
            String category,
            String situationType,
            List<Map<String, Object>> characters,
            List<String> requiredElements,
            List<String> forbiddenExpressions,
            String scenarioLevel) {}

    public record Candidate(
            UUID scenarioId,
            int scenarioVersion,
            String scenarioLevel,
            String title,
            String summary,
            List<Character> characters,
            List<String> scenarioFacts,
            List<MicroGoal> microGoals,
            Map<String, String> startingQuestions,
            Map<String, UUID> startingQuestionGoalIds) {}

    public record Character(String id, String role) {}

    public record MicroGoal(
            UUID microGoalId,
            String description,
            List<String> requiredEvidence,
            String achievementExample) {}
}
