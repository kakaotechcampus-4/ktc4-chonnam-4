package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** 기존 합성 제공자를 새 wire에 연결한다. 실제 모델 원문에는 적용하지 않는다. */
public final class CharacterWireFixtures {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CharacterWireFixtures() {}

    public static String forRequest(String content, LlmRequest request) {
        if (!List.of(CharacterModelOutput.ANALYSIS_VERSION, CharacterModelOutput.CANDIDATE_VERSION)
                .contains(request.responseSchemaVersion())) return content;
        var source = JSON.readTree(content);
        var output = JSON.createObjectNode();
        if (request.responseSchemaVersion().equals(CharacterModelOutput.CANDIDATE_VERSION)) {
            output.set("text", source.path("text"));
            output.set(
                    "mascot_hint",
                    source.has("mascot_hint") ? source.path("mascot_hint") : JSON.nullNode());
            return output.toString();
        }
        if (!source.path("goal_observation").isObject()) return content;
        for (String field :
                List.of(
                        "response_act",
                        "relevance",
                        "learning_state",
                        "primary_gap",
                        "next_strategy",
                        "analysis_confidence")) output.set(field, source.path(field));
        output.set(
                "analysis_basis",
                source.has("analysis_basis")
                        ? source.path("analysis_basis")
                        : JSON.createArrayNode().add("합성 제공자 판단"));
        output.set("activity_goal", source.path("goal_observation").path("activity_goal"));
        var statuses = output.putObject("observations");
        var tasks = JSON.readTree(request.userPrompt()).path("analysis_observation_tasks");
        // 합성 제공자가 표현 증거를 주지 않은 승인 기준은 미표현으로 명시한다.
        tasks.propertyNames().forEach(key -> statuses.put(key, "NOT_EXPRESSED"));
        for (var row : source.path("goal_observation").path("observations")) {
            String found = null;
            for (String key : tasks.propertyNames()) {
                var task = tasks.path(key);
                if (task.path("micro_goal_id").equals(row.path("reference").path("micro_goal_id"))
                        && task.path("criterion_reference")
                                .equals(row.path("reference").path("criterion_reference")))
                    found = key;
            }
            if (found == null) return content;
            statuses.set(found, row.path("status"));
        }
        return output.toString();
    }
}
