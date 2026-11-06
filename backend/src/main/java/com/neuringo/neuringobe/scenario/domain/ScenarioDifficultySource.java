package com.neuringo.neuringobe.scenario.domain;

/**
 * 시나리오 검토 입력의 난이도 근거가 어디서 왔는지. INITIAL_QUIZ 는 최초 결합 퀴즈 결과의 최초 난이도, FOLLOW_UP_PINNED 는 역할극 단독 활동을
 * 배정할 때 roleplay_activity_difficulty 에 고정한 다음 역할극 난이도다.
 */
public enum ScenarioDifficultySource {
    INITIAL_QUIZ,
    FOLLOW_UP_PINNED
}
