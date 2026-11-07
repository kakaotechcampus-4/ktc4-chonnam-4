package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.scenario.domain.ScenarioDifficultySource;
import com.neuringo.neuringobe.scenario.dto.ScenarioPreparationInput;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 검토를 시작한 입력과 확정 시점의 입력이 같은지 보는 지문.
 *
 * <p>최초 결합(INITIAL_QUIZ)은 예전과 같은 문자열이라 이미 저장된 검토가 그대로 이어진다. 역할극 단독(FOLLOW_UP_PINNED)은 활동에 고정한 결정 ID
 * 와 시작 지원을 덧붙인다. 그 활동의 고정값이 바뀌면 지문이 달라져 이전 검토 확정을 거부하고, 다른 활동이 끝나 아동의 최신 결정만 바뀐 경우는 이 활동의 고정값이 그대로라
 * 지문도 그대로다.
 */
final class ScenarioInputFingerprint {
    private ScenarioInputFingerprint() {}

    static String of(ScenarioPreparationInput input) {
        var difficulty = input.difficulty();
        List<String> parts =
                new ArrayList<>(
                        List.of(
                                "v1",
                                difficulty.activityId().toString(),
                                difficulty.childId().toString(),
                                input.learningGoal().goalId().toString(),
                                input.learningGoal().contentHash(),
                                difficulty.scenarioLevel(),
                                difficulty.policyVersion()));
        if (difficulty.source() == ScenarioDifficultySource.FOLLOW_UP_PINNED) {
            parts.add("follow-up");
            parts.add(difficulty.sourceDecisionId().toString());
            parts.add(String.valueOf(difficulty.initialSupportLevel()));
        }
        String value = String.join("|", parts);
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
