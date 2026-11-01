package com.neuringo.neuringobe.report.domain.roleplay;

/**
 * 역할극 원인 판단이 아동 발화 한 번에 매기는 학습 상태. 팀 역할극 프롬프트(prompts/roleplay/v2/cause-analysis.md)의
 * learning_state 코드와 이름·뜻이 같다. 이 값으로 지원 단계를 올리거나 유지한다.
 */
public enum LearningState {
    /** 현재 작은 목표 달성. */
    GOAL_ACHIEVED,
    /** 맞지만 이유·설명이 부족. 지원 단계 유지. */
    CORRECT_BUT_SHALLOW,
    /** 일부만 이해. 처음이면 유지, 같은 목표에서 다시면 한 단계 올림. */
    PARTIAL_UNDERSTANDING,
    /** 원인·개념을 잘못 이해. 한 단계 올림. */
    MISCONCEPTION,
    /** 질문과 무관. 지원 단계 유지. */
    OFF_TOPIC,
    /** 모름·도움 요청. 한 단계 올림. */
    HELP_NEEDED,
    /** 음성 인식 오류 등으로 발화만으로 판단할 수 없음. 아동의 어려움이 아니다. */
    NOT_ASSESSABLE
}
