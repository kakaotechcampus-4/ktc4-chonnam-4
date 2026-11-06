package com.neuringo.neuringobe.scenario.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.goal.dto.LearningGoalResponse;
import com.neuringo.neuringobe.quiz.dto.InitialDifficultyEvidence;
import com.neuringo.neuringobe.scenario.dto.ScenarioDifficultyBasis;
import com.neuringo.neuringobe.scenario.dto.ScenarioPreparationInput;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 공통 검토 입력 지문. 최초 결합은 예전 값 그대로, 역할극 단독은 활동에 고정한 결정을 구분한다. */
class ScenarioInputFingerprintTest {
    private static final UUID ACTIVITY = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID CHILD = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID GOAL = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final String HASH = "a".repeat(64);

    @Test
    void initialInputKeepsTheFingerprintStoredBeforeTheFollowUpAdapter() throws Exception {
        var evidence =
                new InitialDifficultyEvidence(
                        ACTIVITY, CHILD, GOAL, 10, 5, "L2", "S1", false, null, "INITIAL_QUIZ_V2");
        var input =
                new ScenarioPreparationInput(
                        ScenarioDifficultyBasis.initial(evidence), goal(), reuseKey("L2"));

        String before =
                String.join(
                        "|",
                        "v1",
                        ACTIVITY.toString(),
                        CHILD.toString(),
                        GOAL.toString(),
                        HASH,
                        "L2",
                        "INITIAL_QUIZ_V2");
        assertThat(ScenarioInputFingerprint.of(input)).isEqualTo(sha256(before));
    }

    @Test
    void followUpInputChangesOnlyWithThePinnedDecisionOrSupport() {
        UUID pinned = UUID.randomUUID();
        String base = ScenarioInputFingerprint.of(followUp(pinned, "S1"));

        assertThat(ScenarioInputFingerprint.of(followUp(pinned, "S1"))).isEqualTo(base);
        assertThat(ScenarioInputFingerprint.of(followUp(UUID.randomUUID(), "S1")))
                .isNotEqualTo(base);
        assertThat(ScenarioInputFingerprint.of(followUp(pinned, "S0"))).isNotEqualTo(base);
        // 같은 레벨·정책이어도 최초 근거와 고정 근거는 다른 입력이다.
        var initialLike =
                new ScenarioPreparationInput(
                        ScenarioDifficultyBasis.initial(
                                new InitialDifficultyEvidence(
                                        ACTIVITY,
                                        CHILD,
                                        GOAL,
                                        0,
                                        0,
                                        "L2",
                                        "S1",
                                        false,
                                        null,
                                        "FOLLOW_UP_ROLEPLAY_V1")),
                        goal(),
                        reuseKey("L2"));
        assertThat(ScenarioInputFingerprint.of(initialLike)).isNotEqualTo(base);
    }

    private static ScenarioPreparationInput followUp(UUID decision, String support) {
        return new ScenarioPreparationInput(
                ScenarioDifficultyBasis.followUp(
                        ACTIVITY, CHILD, GOAL, "L2", support, "FOLLOW_UP_ROLEPLAY_V1", decision),
                goal(),
                reuseKey("L2"));
    }

    private static LearningGoalResponse goal() {
        return new LearningGoalResponse(
                GOAL,
                CHILD,
                UUID.randomUUID(),
                null,
                "차례를 기다려요",
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                HASH,
                Instant.EPOCH);
    }

    private static ScenarioPreparationInput.ReuseKey reuseKey(String level) {
        return new ScenarioPreparationInput.ReuseKey(UUID.randomUUID(), HASH, level);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
