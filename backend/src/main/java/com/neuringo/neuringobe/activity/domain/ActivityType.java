package com.neuringo.neuringobe.activity.domain;

/** 활동 유형. 최초 결합은 아동당 한 번이고, 그것을 마친 뒤에 나머지 유형을 배정한다. 독립 퀴즈는 학습 목표 없이 문항만 푼다. */
public enum ActivityType {
    /** 표정 퀴즈 → 역할극. 퀴즈 결과가 첫 난이도가 된다. */
    FIRST_COMBINED,
    SELF_EMOTION_QUIZ,
    OTHER_EMOTION_QUIZ,
    /** 독립 역할극. 역할극 API 가 생기기 전에는 배정하지 않는다. */
    ROLEPLAY;

    /** 학습 목표가 있어야 하는 유형. 독립 퀴즈는 문항 선택에 목표를 쓰지 않는다. */
    public boolean requiresGoal() {
        return this == FIRST_COMBINED || this == ROLEPLAY;
    }
}
