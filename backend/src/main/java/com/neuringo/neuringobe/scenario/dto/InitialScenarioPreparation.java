package com.neuringo.neuringobe.scenario.dto;

import com.neuringo.neuringobe.goal.dto.LearningGoalResponse;
import com.neuringo.neuringobe.quiz.dto.InitialDifficultyEvidence;

/** 서버 내부 준비 입력이며 시나리오 생성·승인 결과를 뜻하지 않는다. */
public record InitialScenarioPreparation(
        InitialDifficultyEvidence initialDifficulty,
        LearningGoalResponse learningGoal,
        ReuseKey reuseKey) {

    /** 시나리오는 학급에 묶지 않는 공용 자료라 목표 내용과 난이도로만 찾는다. */
    public record ReuseKey(String goalContentHash, String scenarioLevel) {}
}
