package com.neuringo.neuringobe.scenario.dto;

import com.neuringo.neuringobe.goal.dto.LearningGoalResponse;
import com.neuringo.neuringobe.quiz.dto.InitialDifficultyEvidence;
import java.util.UUID;

/** 서버 내부 준비 입력이며 시나리오 생성·승인 결과를 뜻하지 않는다. */
public record InitialScenarioPreparation(
        InitialDifficultyEvidence initialDifficulty,
        LearningGoalResponse learningGoal,
        ReuseKey reuseKey) {

    public record ReuseKey(UUID classId, String goalContentHash, String scenarioLevel) {}
}
