package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.prompt.RoleplayCodes;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 확정 응답이 향한 목표별 이력이다. 아동의 독립 도달이나 목표 달성을 추정하지 않는다. */
public record RoleplayGoalResponseHistory(
        Map<UUID, GoalResponses> byGoal, RoleplayGoalInputHistory inputHistory) {
    public RoleplayGoalResponseHistory {
        byGoal = Map.copyOf(byGoal);
        Objects.requireNonNull(inputHistory);
    }

    public static RoleplayGoalResponseHistory restore(
            Set<UUID> approvedGoals, Checkpoint checkpoint, List<Response> responses) {
        if (approvedGoals.isEmpty() || checkpoint.lastTurnNumber() != responses.size()) {
            throw new IllegalArgumentException("확정 응답 이력과 체크포인트가 일치하지 않습니다.");
        }
        var grouped = new HashMap<UUID, List<Response>>();
        approvedGoals.forEach(goal -> grouped.put(goal, new ArrayList<>()));
        var questionCounts = new HashMap<UUID, Integer>();
        var revealedGoals = new HashSet<UUID>();
        long previousVersion = 0;
        for (int i = 0; i < responses.size(); i++) {
            var response = responses.get(i);
            if (!approvedGoals.contains(response.microGoalId())
                    || response.turnNumber() != i + 1
                    || response.checkpointVersion() <= previousVersion
                    || response.checkpointVersion() > checkpoint.version()) {
                throw new IllegalArgumentException("승인 범위 또는 확정 응답의 순서가 올바르지 않습니다.");
            }
            previousVersion = response.checkpointVersion();
            var input = response.inputContext();
            if (input != null) {
                if (!approvedGoals.contains(input.microGoalId())) {
                    throw new IllegalArgumentException("입력 당시 목표가 승인 범위에 없습니다.");
                }
                if (input.promptCount() != questionCounts.getOrDefault(input.microGoalId(), 0)
                        || input.answerRevealed() != revealedGoals.contains(input.microGoalId())) {
                    throw new IllegalArgumentException("입력 당시 질문·정답 제공 상태가 이전 확정 이력과 다릅니다.");
                }
                if (i > 0) {
                    var previous = responses.get(i - 1);
                    if (!input.microGoalId().equals(previous.microGoalId())
                            || !input.supportLevel().equals(previous.supportLevel())) {
                        throw new IllegalArgumentException("입력 당시 목표·지원이 직전 확정 질문과 다릅니다.");
                    }
                }
            }
            grouped.get(response.microGoalId()).add(response);
            if (RoleplayCodes.QUESTION_RESPONSE_TYPES.contains(response.responseType()))
                questionCounts.merge(response.microGoalId(), 1, Integer::sum);
            if (response.responseType().equals("ANSWER_REVEAL"))
                revealedGoals.add(response.microGoalId());
        }
        if (responses.isEmpty()) {
            if (checkpoint.lastTurnId() != null) {
                throw new IllegalArgumentException("응답 이력이 없는 세션에 마지막 턴이 지정됐습니다.");
            }
        } else {
            var last = responses.getLast();
            if (!last.turnId().equals(checkpoint.lastTurnId())
                    || !last.microGoalId().equals(checkpoint.currentMicroGoalId())
                    || !last.supportLevel().equals(checkpoint.currentSupportLevel())) {
                throw new IllegalArgumentException("마지막 응답과 현재 질문의 목표·지원이 일치하지 않습니다.");
            }
        }
        var result = new HashMap<UUID, GoalResponses>();
        grouped.forEach((goal, history) -> result.put(goal, new GoalResponses(history)));
        return new RoleplayGoalResponseHistory(
                result, RoleplayGoalInputHistory.restore(approvedGoals, responses));
    }

    public record Checkpoint(
            int lastTurnNumber,
            long version,
            UUID lastTurnId,
            UUID currentMicroGoalId,
            String currentSupportLevel) {
        public Checkpoint {
            if (lastTurnNumber < 0 || version < 0) {
                throw new IllegalArgumentException("확정 응답의 저장 버전이 올바르지 않습니다.");
            }
        }
    }

    public record Response(
            UUID turnId,
            UUID candidateId,
            int turnNumber,
            long checkpointVersion,
            UUID microGoalId,
            String supportLevel,
            String responseType,
            String strategyUsed,
            RoleplayInputContext inputContext) {
        public Response {
            Objects.requireNonNull(turnId);
            Objects.requireNonNull(candidateId);
            Objects.requireNonNull(microGoalId);
            if (turnNumber < 1
                    || checkpointVersion < 1
                    || supportLevel == null
                    || !RoleplayCodes.SUPPORT_LEVELS.contains(supportLevel)
                    || responseType == null
                    || !RoleplayCodes.RESPONSE_TYPES.contains(responseType)
                    || strategyUsed == null
                    || !RoleplayCodes.STRATEGY_TYPES.contains(strategyUsed)) {
                throw new IllegalArgumentException("확정 응답의 종류·전략·지원 이력이 부족하거나 올바르지 않습니다.");
            }
        }
    }

    public record GoalResponses(List<Response> responses) {
        public GoalResponses {
            responses = List.copyOf(responses);
        }

        public int questionCount() {
            return (int)
                    responses.stream()
                            .filter(
                                    r ->
                                            RoleplayCodes.QUESTION_RESPONSE_TYPES.contains(
                                                    r.responseType()))
                            .count();
        }

        public boolean answerRevealed() {
            return responses.stream().anyMatch(r -> r.responseType().equals("ANSWER_REVEAL"));
        }
    }
}
