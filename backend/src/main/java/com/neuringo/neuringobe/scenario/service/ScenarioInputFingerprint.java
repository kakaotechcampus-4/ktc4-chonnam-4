package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.scenario.dto.InitialScenarioPreparation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class ScenarioInputFingerprint {
    private ScenarioInputFingerprint() {}

    static String of(InitialScenarioPreparation input) {
        String value =
                String.join(
                        "|",
                        "v1",
                        input.initialDifficulty().activityId().toString(),
                        input.initialDifficulty().childId().toString(),
                        input.learningGoal().goalId().toString(),
                        input.learningGoal().contentHash(),
                        input.initialDifficulty().scenarioLevel(),
                        input.initialDifficulty().policyVersion());
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
