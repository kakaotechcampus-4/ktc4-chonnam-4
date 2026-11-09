package com.neuringo.neuringobe.scenario.dto;

import com.neuringo.neuringobe.goal.dto.LearningGoalResponse;
import java.util.UUID;

/**
 * 공통 시나리오 검토(준비·다음 후보·생성·확정)의 서버 내부 입력이며 시나리오 생성·승인 결과를 뜻하지 않는다.
 *
 * <p>최초 결합은 퀴즈 결과의 최초 난이도로(InitialScenarioPreparationService), 역할극 단독은 배정 때 활동에 고정한 다음 역할극
 * 난이도로(FollowUpScenarioPreparationService) 같은 모양을 만든다. 검토 쪽 코드는 어느 쪽에서 왔는지 보지 않고, 입력 지문만 출처별 근거를 더
 * 담는다.
 */
public record ScenarioPreparationInput(
        ScenarioDifficultyBasis difficulty, LearningGoalResponse learningGoal, ReuseKey reuseKey) {

    public record ReuseKey(UUID classId, String goalContentHash, String scenarioLevel) {}
}
