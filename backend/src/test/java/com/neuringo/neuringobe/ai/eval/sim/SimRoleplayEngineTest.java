package com.neuringo.neuringobe.ai.eval.sim;

import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S0;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S1;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S2;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S3;
import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.eval.sim.SimSpec.ChildProfile;
import com.neuringo.neuringobe.report.domain.roleplay.LearningState;
import com.neuringo.neuringobe.report.domain.roleplay.RoleplayTurnRecord;
import com.neuringo.neuringobe.scenario.domain.ScenarioLevel;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SimRoleplayEngineTest {

    private final List<UUID> goals = List.of(UUID.randomUUID(), UUID.randomUUID());
    private final List<String> skills = List.of("EMOTION", "CAUSE");

    private static SimChild child(double ability) {
        return new SimChild(
                new ChildProfile("T", "test", "test", "L2", Map.of("default", ability), 0, 0, 0, 0),
                1);
    }

    @Test
    void childWhoAlwaysAnswersLowersSupportAfterEachEasyGoal() {
        SimRoleplayEngine engine = new SimRoleplayEngine(new SimSpec.Session(12, 3));

        SimRoleplayEngine.Result result =
                engine.run(goals, skills, S1, ScenarioLevel.L1, child(2.0));

        assertThat(result.turns())
                .extracting(RoleplayTurnRecord::inputSupport, RoleplayTurnRecord::learningState)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(S1, LearningState.GOAL_ACHIEVED),
                        org.assertj.core.groups.Tuple.tuple(S0, LearningState.GOAL_ACHIEVED));
        assertThat(result.endReason()).isEqualTo(SimRoleplayEngine.EndReason.ALL_GOALS_VISITED);
    }

    @Test
    void childWhoNeverAnswersClimbsToS3ThenMovesOnAndRestartsAtTheSessionStart() {
        SimRoleplayEngine engine = new SimRoleplayEngine(new SimSpec.Session(12, 3));

        SimRoleplayEngine.Result result =
                engine.run(goals, skills, S1, ScenarioLevel.L3, child(-5.0));

        List<RoleplayTurnRecord> first =
                result.turns().stream()
                        .filter(t -> t.microGoalId().equals(goals.getFirst()))
                        .toList();
        // 물은 횟수 상한(3)에 닿으면 올리는 규칙과 상관없이 S3 로 가고, S3 에서 답한 뒤에는 다음 목표로 간다.
        assertThat(first).last().extracting(RoleplayTurnRecord::inputSupport).isEqualTo(S3);
        assertThat(first.size()).isLessThanOrEqualTo(4);
        RoleplayTurnRecord secondStart =
                result.turns().stream()
                        .filter(t -> t.microGoalId().equals(goals.get(1)))
                        .findFirst()
                        .orElseThrow();
        assertThat(secondStart.inputSupport()).isEqualTo(S1);
    }

    @Test
    void turnLimitEndsTheRoleplayBeforeEveryGoalIsAsked() {
        SimRoleplayEngine engine = new SimRoleplayEngine(new SimSpec.Session(2, 3));

        SimRoleplayEngine.Result result =
                engine.run(goals, skills, S2, ScenarioLevel.L3, child(-5.0));

        assertThat(result.turns()).hasSize(2);
        assertThat(result.endReason()).isEqualTo(SimRoleplayEngine.EndReason.TURN_LIMIT);
    }
}
