package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.prompt.GoalObservationInput;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Executes the bounded pipeline, then selects approved guidance without calling AI or storage. */
public final class RoleplayTurnResponder {
    private final RoleplayTurnRunner runner;
    private final RoleplayTurnOutcomeResolver outcomes;

    public RoleplayTurnResponder(RoleplayTurnRunner runner, RoleplayTurnOutcomeResolver outcomes) {
        this.runner = Objects.requireNonNull(runner);
        this.outcomes = Objects.requireNonNull(outcomes);
    }

    public RoleplayTurnOutcome execute(UUID turnId, RetryableRoleplayTurnSteps steps) {
        return outcomes.resolve(runner.execute(turnId, steps));
    }

    public RoleplayTurnOutcome execute(
            RoleplayTurnDeadline deadline,
            Function<RoleplayTurnDeadline, RoleplayTurnResult> pipeline) {
        return outcomes.resolve(runner.execute(deadline, pipeline));
    }

    /** 취소된 제공자가 계속 실행 중이어도 모든 종료 경로에서 업로드 자원을 해제한다. */
    public RoleplayTurnOutcome executeVoice(
            RoleplayAudioResource audio,
            RoleplayTurnInput context,
            GoalObservationInput observations,
            RoleplaySpeechTurnPipeline pipeline,
            RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(audio);
        try (audio) {
            Objects.requireNonNull(context);
            Objects.requireNonNull(pipeline);
            Objects.requireNonNull(deadline);
            return execute(
                    deadline,
                    shared -> {
                        var request = shared.withinBudget(audio::request);
                        return pipeline.execute(request, context, observations, shared);
                    });
        }
    }
}
