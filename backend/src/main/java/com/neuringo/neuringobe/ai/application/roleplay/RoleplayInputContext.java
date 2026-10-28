package com.neuringo.neuringobe.ai.application.roleplay;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayCodes;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import java.util.Objects;
import java.util.UUID;

/** 아동이 답할 당시의 서버 상태다. 다음 질문의 목표·지원과 구분해 보존한다. */
public record RoleplayInputContext(
        @JsonProperty("micro_goal_id") UUID microGoalId,
        @JsonProperty("support_level") String supportLevel,
        @JsonProperty("support_turn") int supportTurn,
        @JsonProperty("prompt_count") int promptCount,
        @JsonProperty("answer_revealed") boolean answerRevealed) {
    public RoleplayInputContext {
        Objects.requireNonNull(microGoalId);
        if (supportLevel == null
                || !RoleplayCodes.SUPPORT_LEVELS.contains(supportLevel)
                || supportTurn < 1
                || promptCount < 0) {
            throw new IllegalArgumentException("입력 당시 목표·지원 상태가 올바르지 않습니다.");
        }
    }

    public static RoleplayInputContext from(RoleplayTurnInput.State state) {
        return new RoleplayInputContext(
                state.currentMicroGoalId(),
                state.currentSupportLevel(),
                state.currentMicroGoalSupportTurn(),
                state.currentMicroGoalPromptCount(),
                state.answerRevealed());
    }
}
