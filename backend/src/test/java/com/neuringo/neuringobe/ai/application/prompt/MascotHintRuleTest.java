package com.neuringo.neuringobe.ai.application.prompt;

import static com.neuringo.neuringobe.ai.application.prompt.RoleplayFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.prompt.MascotHintRule.Reason;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MascotHintRuleTest {

    @Test
    void narratorRoleplayNeverShowsMascot() {
        assertThat(MascotHintRule.decide(input("몰라"), analysis("DONT_KNOW", "HELP_NEEDED", "S1")))
                .isEmpty();
    }

    @Test
    void showsMascotForEachReason() {
        assertThat(decide(state(1, 0, false), analysis("DONT_KNOW", "HELP_NEEDED", "S1")))
                .contains(Reason.HELP_REQUEST);
        assertThat(decide(state(1, 0, false), analysis("HELP_REQUEST", "HELP_NEEDED", "S1")))
                .contains(Reason.HELP_REQUEST);
        assertThat(
                        decide(
                                state(2, 0, false),
                                analysis("ANSWER_ATTEMPT", "PARTIAL_UNDERSTANDING", "S2")))
                .contains(Reason.STUCK);
        assertThat(decide(state(1, 0, false), analysis("OFF_TOPIC_REMARK", "OFF_TOPIC", "S1")))
                .contains(Reason.OFF_TOPIC);
        assertThat(decide(state(1, 0, false), analysis("NEGATIVE_EXPRESSION", "OFF_TOPIC", "S1")))
                .contains(Reason.NEGATIVE_EXPRESSION);
        assertThat(decide(state(1, 0, false), analysis("REFUSAL", "HELP_NEEDED", "S1")))
                .contains(Reason.NEGATIVE_EXPRESSION);
        assertThat(decide(state(1, 0, false), analysis("ANSWER_ATTEMPT", "MISCONCEPTION", "S1")))
                .contains(Reason.MISCONCEPTION);
    }

    @Test
    void staysQuietWhenTheChildIsDoingWell() {
        assertThat(decide(state(1, 0, false), analysis("ANSWER_ATTEMPT", "GOAL_ACHIEVED", "S1")))
                .isEmpty();
        assertThat(
                        decide(
                                state(1, 0, false),
                                analysis("ANSWER_ATTEMPT", "PARTIAL_UNDERSTANDING", "S1")))
                .isEmpty();
        assertThat(
                        decide(
                                state(1, 0, false),
                                analysis("DONT_KNOW", "HELP_NEEDED", "S1", "CLOSE_ROLEPLAY")))
                .isEmpty();
        // 첫 유도 턴에서 S2 로 시작해도 막힌 것은 아니다.
        assertThat(
                        decide(
                                state(1, 0, false),
                                analysis("ANSWER_ATTEMPT", "PARTIAL_UNDERSTANDING", "S2")))
                .isEmpty();
    }

    @Test
    void restsAfterAHintOrAtTheLimitOrWhenTheCharacterRevealsTheAnswer() {
        var dontKnow = analysis("DONT_KNOW", "HELP_NEEDED", "S2");
        assertThat(decide(state(2, 1, true), dontKnow)).isEmpty();
        assertThat(decide(state(3, MascotHintRule.MAX_HINTS_PER_MICRO_GOAL, false), dontKnow))
                .isEmpty();
        assertThat(decide(state(3, 1, false), analysis("DONT_KNOW", "HELP_NEEDED", "S3")))
                .isEmpty();
        assertThat(decide(state(3, 1, false), dontKnow)).contains(Reason.HELP_REQUEST);
    }

    @Test
    void offTopicAndNegativeExpressionIgnoreTheRestRules() {
        var limitedAfterHint = state(3, MascotHintRule.MAX_HINTS_PER_MICRO_GOAL, true);
        assertThat(decide(limitedAfterHint, analysis("OFF_TOPIC_REMARK", "OFF_TOPIC", "S3")))
                .contains(Reason.OFF_TOPIC);
        assertThat(decide(limitedAfterHint, analysis("NEGATIVE_EXPRESSION", "OFF_TOPIC", "S3")))
                .contains(Reason.NEGATIVE_EXPRESSION);
    }

    @Test
    void unassessableTurnsDoNotTriggerStuckHintWhenLearningTurnIsKnown() {
        var partial = analysis("ANSWER_ATTEMPT", "PARTIAL_UNDERSTANDING", "S2");
        var before =
                new RoleplayTurnInput.State(MG_EMOTION, "S1", 3, 0, false, 3, 8, 4, 1, 1, 0, false);
        var reached =
                new RoleplayTurnInput.State(MG_EMOTION, "S1", 3, 0, false, 3, 8, 4, 2, 2, 0, false);
        assertThat(decide(before, partial)).isEmpty();
        assertThat(decide(reached, partial)).contains(Reason.STUCK);
    }

    private static Optional<Reason> decide(RoleplayTurnInput.State state, AnalysisResult analysis) {
        RoleplayTurnInput character = characterInput("말");
        return MascotHintRule.decide(
                new RoleplayTurnInput(
                        character.scenario(),
                        state,
                        character.learnerTurn(),
                        character.recentDialogue()),
                analysis);
    }

    private static RoleplayTurnInput.State state(
            int supportTurn, int mascotHints, boolean previousTurnHadMascotHint) {
        return new RoleplayTurnInput.State(
                MG_EMOTION,
                "S1",
                supportTurn,
                0,
                false,
                2,
                8,
                4,
                mascotHints,
                previousTurnHadMascotHint);
    }

    private static AnalysisResult analysis(String act, String learningState, String supportLevel) {
        return analysis(act, learningState, supportLevel, "PROBE_EMOTION");
    }

    private static AnalysisResult analysis(
            String act, String learningState, String supportLevel, String strategy) {
        return new AnalysisResult(
                TURN_ID,
                act,
                "RELEVANT",
                learningState,
                new AnalysisResult.PrimaryGap("MISSING_EMOTION", "감정을 말하지 않음"),
                new AnalysisResult.NextStrategy(strategy, MG_EMOTION, "마음 드러내기", supportLevel),
                0.9);
    }
}
