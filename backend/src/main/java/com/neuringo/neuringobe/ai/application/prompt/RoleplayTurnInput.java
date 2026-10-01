package com.neuringo.neuringobe.ai.application.prompt;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 역할극 한 턴의 프롬프트 입력(워크플로우 v3 의 roleplay_context + learner_turn + recent_dialogue).
 *
 * <p>아동·강사 실명, 학교, 연락처, 접근 코드는 이 객체에 넣지 않는다. 아동 발화는 입력 처리·안전 처리를 통과한 표준 발화 하나만 쓴다.
 */
public record RoleplayTurnInput(
        Scenario scenario,
        State state,
        LearnerTurn learnerTurn,
        List<DialogueLine> recentDialogue) {

    /** 프롬프트에 넣는 최근 대화 줄 수 상한. 넘으면 오래된 것부터 버린다. */
    public static final int MAX_DIALOGUE_LINES = 8;

    public RoleplayTurnInput {
        Objects.requireNonNull(scenario, "scenario must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(learnerTurn, "learnerTurn must not be null");
        recentDialogue = recentDialogue == null ? List.of() : List.copyOf(recentDialogue);
        if (recentDialogue.size() > MAX_DIALOGUE_LINES) {
            recentDialogue =
                    recentDialogue.subList(
                            recentDialogue.size() - MAX_DIALOGUE_LINES, recentDialogue.size());
        }
        UUID currentGoal = state.currentMicroGoalId();
        if (scenario.microGoals().stream().noneMatch(goal -> goal.id().equals(currentGoal))) {
            throw new IllegalArgumentException("currentMicroGoalId must be one of the micro goals");
        }
    }

    /** 승인된 시나리오. 역할극 중에는 바뀌지 않는다. */
    public record Scenario(
            String learningGoal,
            String scenarioLevel,
            List<String> scenarioFacts,
            List<String> prohibitedInferences,
            List<MicroGoal> microGoals) {

        public Scenario {
            requireText(learningGoal, "learningGoal");
            RoleplayCodes.requireOneOf(
                    RoleplayCodes.SCENARIO_LEVELS, scenarioLevel, "scenarioLevel");
            scenarioFacts = List.copyOf(Objects.requireNonNull(scenarioFacts, "scenarioFacts"));
            prohibitedInferences =
                    prohibitedInferences == null ? List.of() : List.copyOf(prohibitedInferences);
            microGoals = List.copyOf(Objects.requireNonNull(microGoals, "microGoals"));
            if (scenarioFacts.isEmpty()) {
                throw new IllegalArgumentException("scenarioFacts must not be empty");
            }
            if (microGoals.isEmpty()) {
                throw new IllegalArgumentException("microGoals must not be empty");
            }
        }
    }

    public record MicroGoal(
            UUID id, String description, List<String> requiredEvidence, String status) {

        public MicroGoal {
            Objects.requireNonNull(id, "micro goal id must not be null");
            requireText(description, "microGoal.description");
            requiredEvidence = List.copyOf(Objects.requireNonNull(requiredEvidence));
            RoleplayCodes.requireOneOf(
                    RoleplayCodes.MICRO_GOAL_STATUSES, status, "microGoal.status");
        }
    }

    /** 현재 진행 상태. 턴 제한 값은 정책(DEC-017)에서 정해 넘긴다. */
    public record State(
            UUID currentMicroGoalId,
            String currentSupportLevel,
            int currentMicroGoalSupportTurn,
            int currentMicroGoalPromptCount,
            boolean answerRevealed,
            int turnCount,
            int maximumTurnCount,
            int maximumSupportTurnsPerMicroGoal) {

        public State {
            Objects.requireNonNull(currentMicroGoalId, "currentMicroGoalId must not be null");
            RoleplayCodes.requireOneOf(
                    RoleplayCodes.SUPPORT_LEVELS, currentSupportLevel, "currentSupportLevel");
            if (currentMicroGoalSupportTurn < 1
                    || currentMicroGoalPromptCount < 0
                    || turnCount < 1
                    || maximumTurnCount < 1
                    || maximumSupportTurnsPerMicroGoal < 1) {
                throw new IllegalArgumentException("invalid roleplay state counters");
            }
        }
    }

    public record LearnerTurn(UUID turnId, String canonicalUtterance) {

        public LearnerTurn {
            Objects.requireNonNull(turnId, "turnId must not be null");
            Objects.requireNonNull(canonicalUtterance, "canonicalUtterance must not be null");
        }

        @Override
        public String toString() {
            return "LearnerTurn[turnId=" + turnId + "]";
        }
    }

    public enum Speaker {
        AI,
        LEARNER
    }

    public record DialogueLine(Speaker speaker, String text) {

        public DialogueLine {
            Objects.requireNonNull(speaker, "speaker must not be null");
            Objects.requireNonNull(text, "text must not be null");
        }

        @Override
        public String toString() {
            return "DialogueLine[speaker=" + speaker + "]";
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
