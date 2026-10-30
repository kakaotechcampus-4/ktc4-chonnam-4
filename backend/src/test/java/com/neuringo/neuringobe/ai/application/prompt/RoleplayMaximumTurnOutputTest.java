package com.neuringo.neuringobe.ai.application.prompt;

import static com.neuringo.neuringobe.ai.application.prompt.RoleplayFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class RoleplayMaximumTurnOutputTest {
    private final RoleplayPromptFactory factory =
            new RoleplayPromptFactory(JsonMapper.builder().build(), null);

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void maximumOrLaterTurnRejectsAnotherQuestionStrategy(int maximum) {
        var input = inputWithMaximum(maximum);
        var parser =
                factory.causeAnalysis(
                                trace(ANALYSIS_ID, null),
                                attempts(),
                                1,
                                input,
                                null,
                                RoleplayObservationTestPlans.inputForScenario(input.scenario()))
                        .parser();
        assertThatThrownBy(() -> parser.parse(analysisJson("PROBE_EMOTION", MG_EMOTION, "S1")))
                .isInstanceOf(InvalidLlmOutputException.class);
        assertThat(
                        parser.parse(analysisJson("CLOSE_ROLEPLAY", MG_EMOTION, "S1"))
                                .nextStrategy()
                                .type())
                .isEqualTo("CLOSE_ROLEPLAY");
    }

    @Test
    void aQuestionStrategyRemainsAvailableBeforeTheMaximum() {
        var input = inputWithMaximum(3);
        var parser =
                factory.causeAnalysis(
                                trace(ANALYSIS_ID, null),
                                attempts(),
                                1,
                                input,
                                null,
                                RoleplayObservationTestPlans.inputForScenario(input.scenario()))
                        .parser();
        assertThat(
                        parser.parse(analysisJson("PROBE_EMOTION", MG_EMOTION, "S1"))
                                .nextStrategy()
                                .type())
                .isEqualTo("PROBE_EMOTION");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void maximumOrLaterTurnRequiresAClosingCandidate(int maximum) {
        var input = inputWithMaximum(maximum);
        var analysis =
                factory.causeAnalysis(
                                trace(ANALYSIS_ID, null),
                                attempts(),
                                1,
                                input,
                                null,
                                RoleplayObservationTestPlans.inputForScenario(input.scenario()))
                        .parser()
                        .parse(analysisJson("CLOSE_ROLEPLAY", MG_EMOTION, "S1"));
        var parser =
                factory.responseGeneration(
                                trace(ANALYSIS_ID, null),
                                attempts(),
                                1,
                                input,
                                analysis,
                                CANDIDATE_ID,
                                null)
                        .parser();
        var closing =
                candidateJson("함께해 줘서 고마워.", "CLOSING").replace("PROBE_EMOTION", "CLOSE_ROLEPLAY");
        var question =
                candidateJson("다음에는 무엇을 해 볼까?", "GUIDING_QUESTION")
                        .replace("PROBE_EMOTION", "CLOSE_ROLEPLAY");
        assertThat(parser.parse(closing).responseType()).isEqualTo("CLOSING");
        assertThatThrownBy(() -> parser.parse(question))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    private RoleplayTurnInput inputWithMaximum(int maximum) {
        var original = input("친구가 울어.");
        var state = original.state();
        return new RoleplayTurnInput(
                original.scenario(),
                new RoleplayTurnInput.State(
                        state.currentMicroGoalId(),
                        state.currentSupportLevel(),
                        state.currentMicroGoalSupportTurn(),
                        state.currentMicroGoalPromptCount(),
                        state.answerRevealed(),
                        state.turnCount(),
                        maximum,
                        state.maximumSupportTurnsPerMicroGoal()),
                original.learnerTurn(),
                original.recentDialogue());
    }
}
