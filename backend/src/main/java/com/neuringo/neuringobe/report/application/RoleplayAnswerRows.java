package com.neuringo.neuringobe.report.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 완료한 역할극 한 건의 답 기록을 DB 행 모양 그대로 옮긴 값.
 * roleplay_turn·roleplay_goal_assessment·roleplay_goal_observation 과 승인 시나리오의 작은 목표에서 읽는다. 발화
 * 원문(canonical_utterance·response_text)은 읽지 않는다.
 *
 * @param startSupportLevel 활동에 적용한 시작 지원(activity.initial_support_level). 없으면 null
 */
public record RoleplayAnswerRows(
        UUID recordId,
        Instant completedAt,
        UUID sessionId,
        UUID scenarioId,
        String scenarioLevel,
        String startSupportLevel,
        List<MicroGoalRow> goals,
        List<TurnRow> turns,
        List<ObservationRow> observations) {

    public RoleplayAnswerRows {
        Objects.requireNonNull(recordId, "recordId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(scenarioId, "scenarioId must not be null");
        goals = List.copyOf(goals);
        turns = List.copyOf(turns);
        observations = List.copyOf(observations);
    }

    /** scenario_micro_goal 한 행. description 이 강사 화면의 작은 목표 이름이고 requiredEvidence 가 관찰 기준 문장이다. */
    public record MicroGoalRow(
            UUID microGoalId, int orderNo, String description, List<String> requiredEvidence) {
        public MicroGoalRow {
            requiredEvidence = requiredEvidence == null ? List.of() : List.copyOf(requiredEvidence);
        }
    }

    /** 아동이 실제로 답한 질문의 문맥(input_*)과 그 답의 학습 상태. */
    public record TurnRow(
            UUID turnId,
            int turnNumber,
            UUID inputMicroGoalId,
            String inputSupportLevel,
            boolean answerRevealed,
            String learningState) {}

    /** 기준 하나의 관찰. criterionReference 는 기준 문장의 해시라서 문장으로 되돌리려면 같은 해시를 다시 계산한다. */
    public record ObservationRow(
            UUID turnId, UUID microGoalId, String criterionReference, String status) {}
}
