package com.neuringo.neuringobe.scenario.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.neuringo.neuringobe.common.ApiException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public record ScenarioEdits(String title, String summary, List<MicroGoalEdit> microGoals) {
    public ScenarioEdits {
        microGoals = microGoals == null ? List.of() : List.copyOf(microGoals);
    }

    public record MicroGoalEdit(
            UUID microGoalId, String description, List<String> requiredEvidence) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException("허용하지 않은 수정 항목입니다.");
        }

        public MicroGoalEdit {
            requiredEvidence = requiredEvidence == null ? null : List.copyOf(requiredEvidence);
        }
    }

    public static ScenarioEdits none() {
        return new ScenarioEdits(null, null, List.of());
    }

    @JsonIgnore
    public boolean isEmpty() {
        return title == null && summary == null && microGoals.isEmpty();
    }

    @JsonAnySetter
    public void unknown(String name, Object value) {
        throw new IllegalArgumentException("허용하지 않은 수정 항목입니다.");
    }

    public ScenarioEdits normalized() {
        var ids = new HashSet<UUID>();
        var goals = new ArrayList<MicroGoalEdit>();
        for (var goal : microGoals) {
            if (goal.microGoalId() == null || !ids.add(goal.microGoalId())) throw invalid();
            var evidence = goal.requiredEvidence();
            if (evidence != null) {
                if (evidence.isEmpty() || evidence.size() > 3) throw invalid();
                evidence = evidence.stream().map(ScenarioEdits::text).toList();
            }
            String description = text(goal.description());
            if (description != null || evidence != null)
                goals.add(new MicroGoalEdit(goal.microGoalId(), description, evidence));
        }
        goals.sort(Comparator.comparing(g -> g.microGoalId().toString()));
        return new ScenarioEdits(text(title), text(summary), goals);
    }

    private static String text(String value) {
        if (value == null) return null;
        if (value.isBlank()) throw invalid();
        return value.strip();
    }

    private static ApiException invalid() {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_SCENARIO_EDITS", "수정할 항목과 내용을 확인해 주세요.");
    }
}
