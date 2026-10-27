package com.neuringo.neuringobe.quiz.service;

import java.util.UUID;

/**
 * 최초 결합 활동의 퀴즈가 끝나 첫 난이도가 정해졌다는 알림. 퀴즈 저장이 확정된 뒤 시나리오 쪽이 받아 역할극 시나리오를 붙인다. 시나리오가 아직 붙지 않은 활동의 퀴즈
 * 결과를 다시 읽을 때도 한 번 더 보낸다(앞선 처리가 실패했을 때 다시 시도하는 길).
 */
public record FirstQuizCompleted(UUID activityId, UUID childId, String scenarioLevel) {}
