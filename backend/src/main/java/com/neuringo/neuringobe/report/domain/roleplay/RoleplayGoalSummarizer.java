package com.neuringo.neuringobe.report.domain.roleplay;

import com.neuringo.neuringobe.report.domain.GoalEvidence;
import com.neuringo.neuringobe.scenario.domain.GoalOutcome;
import com.neuringo.neuringobe.scenario.domain.GoalOutcome.Result;
import com.neuringo.neuringobe.scenario.domain.SupportLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 역할극 한 번의 답 기록을 작은 목표별 결과(GoalEvidence)로 바꾼다. 리포트·다음 난이도·추천이 모두 이 결과를 입력으로 쓴다.
 *
 * <p>목표마다 그 목표에 답한 기록을 순서대로 보고 정한다.
 *
 * <ul>
 *   <li>시작 지원은 첫 기록의 지원 단계다.
 *   <li>GOAL_ACHIEVED 가 있으면 도달이고, 최종 지원은 처음 달성한 기록의 단계다. S3(답을 들은 뒤) 달성도 도달이지만 점수는 0이다.
 *   <li>달성 기록이 없으면 미도달이고, 최종 지원은 마지막으로 판단할 수 있었던 기록의 단계다.
 *   <li>기록이 없으면 활동이 끝나 시도하지 못한 것이라 중단으로 뺀다.
 *   <li>NOT_ASSESSABLE 만 있으면 기술 오류로 뺀다. 섞여 있으면 NOT_ASSESSABLE 기록만 무시한다.
 *   <li>판단할 수 있는 기록이 모두 거부면 거부로 뺀다.
 * </ul>
 *
 * <p>관찰 문장은 지원 단계와 학습 상태로 만든 짧은 문장이고 발화 원문을 담지 않는다.
 */
public final class RoleplayGoalSummarizer {

    /** 판단할 수 없던 기록이 있을 때 리포트에 따로 표시할 기술 오류 코드. */
    public static final String UNCLEAR_INPUT = "UNCLEAR_INPUT";

    private static final int MAX_OBSERVATIONS_PER_GOAL = 3;

    public RoleplaySummary summarize(
            List<RoleplayMicroGoal> microGoals,
            List<RoleplayTurnRecord> turns,
            SupportLevel sessionStartSupport) {
        Objects.requireNonNull(sessionStartSupport, "sessionStartSupport must not be null");
        if (microGoals == null || microGoals.isEmpty()) {
            throw new IllegalArgumentException("a role-play needs at least one micro goal");
        }
        Map<UUID, List<RoleplayTurnRecord>> byGoal = new LinkedHashMap<>();
        microGoals.forEach(goal -> byGoal.put(goal.id(), new ArrayList<>()));
        List<RoleplayTurnRecord> ordered =
                (turns == null ? List.<RoleplayTurnRecord>of() : turns)
                        .stream()
                                .sorted(Comparator.comparingInt(RoleplayTurnRecord::turnNumber))
                                .toList();
        for (RoleplayTurnRecord turn : ordered) {
            List<RoleplayTurnRecord> goalTurns = byGoal.get(turn.microGoalId());
            if (goalTurns == null) {
                throw new IllegalArgumentException("turn refers to an unknown micro goal");
            }
            goalTurns.add(turn);
        }

        List<GoalEvidence> goals = new ArrayList<>();
        for (RoleplayMicroGoal goal : microGoals) {
            goals.add(summarizeGoal(goal, byGoal.get(goal.id()), sessionStartSupport));
        }
        boolean unclearInput =
                ordered.stream().anyMatch(t -> t.learningState() == LearningState.NOT_ASSESSABLE);
        return new RoleplaySummary(goals, unclearInput ? List.of(UNCLEAR_INPUT) : List.of());
    }

    private GoalEvidence summarizeGoal(
            RoleplayMicroGoal goal, List<RoleplayTurnRecord> turns, SupportLevel sessionStart) {
        if (turns.isEmpty()) {
            return evidence(
                    goal, sessionStart, sessionStart, Result.EXCLUDED_INTERRUPTED, List.of());
        }
        SupportLevel start = turns.getFirst().inputSupport();
        List<RoleplayTurnRecord> assessable =
                turns.stream()
                        .filter(t -> t.learningState() != LearningState.NOT_ASSESSABLE)
                        .toList();
        if (assessable.isEmpty()) {
            return evidence(
                    goal,
                    start,
                    turns.getLast().inputSupport(),
                    Result.EXCLUDED_TECHNICAL_ERROR,
                    List.of());
        }
        if (assessable.stream().allMatch(RoleplayTurnRecord::refusal)) {
            return evidence(
                    goal,
                    start,
                    assessable.getLast().inputSupport(),
                    Result.EXCLUDED_REFUSED,
                    List.of());
        }

        int achievedAt = -1;
        for (int i = 0; i < assessable.size(); i++) {
            if (assessable.get(i).learningState() == LearningState.GOAL_ACHIEVED) {
                achievedAt = i;
                break;
            }
        }
        List<RoleplayTurnRecord> considered =
                achievedAt >= 0 ? assessable.subList(0, achievedAt + 1) : assessable;
        SupportLevel last = considered.getLast().inputSupport();
        Result result = achievedAt >= 0 ? Result.REACHED : Result.NOT_REACHED;
        return evidence(goal, start, last, result, considered);
    }

    private GoalEvidence evidence(
            RoleplayMicroGoal goal,
            SupportLevel start,
            SupportLevel last,
            Result result,
            List<RoleplayTurnRecord> considered) {
        GoalOutcome outcome = new GoalOutcome(goal.id(), start, last, result);
        List<String> supports =
                result == Result.REACHED && last.ordinal() > start.ordinal()
                        ? List.of(supportName(last))
                        : List.of();
        return new GoalEvidence(outcome, goal.title(), observations(considered), supports);
    }

    private static List<String> observations(List<RoleplayTurnRecord> turns) {
        List<String> sentences = new ArrayList<>();
        for (RoleplayTurnRecord turn : turns) {
            String sentence = observation(turn);
            if (sentences.isEmpty() || !sentences.getLast().equals(sentence)) {
                sentences.add(sentence);
            }
        }
        if (sentences.size() <= MAX_OBSERVATIONS_PER_GOAL) {
            return sentences;
        }
        // 처음 반응과 마지막 반응, 그 사이에서 둘과 다른 가장 늦은 반응을 남긴다. 강사에게는 어디서 시작해 어떻게 끝났는지가 중요하다.
        String first = sentences.getFirst();
        String last = sentences.getLast();
        for (int i = sentences.size() - 2; i > 0; i--) {
            String middle = sentences.get(i);
            if (!middle.equals(first) && !middle.equals(last)) {
                return List.of(first, middle, last);
            }
        }
        return first.equals(last) ? List.of(first) : List.of(first, last);
    }

    static String observation(RoleplayTurnRecord turn) {
        String when =
                switch (turn.inputSupport()) {
                    case S0 -> "열린 질문에";
                    case S1 -> "상황 단서를 들은 뒤";
                    case S2 -> "두 선택지를 들은 뒤";
                    case S3 -> "답을 들은 뒤";
                };
        // 기준별 관찰이 있으면 "무엇을" 말했는지가 드러나게 기준 설명으로 쓴다. 말하지 않은 기준은 내용이 없어 쓰지 않는다.
        List<String> criteria =
                turn.observations().stream()
                        .filter(o -> o.status() != CriterionObservation.Status.NOT_EXPRESSED)
                        .map(
                                o ->
                                        objectParticle(o.criterion())
                                                + " "
                                                + criterionPhrase(o.status(), turn.inputSupport()))
                        .toList();
        if (!criteria.isEmpty()) {
            return when + " " + String.join(", ", criteria);
        }
        String what =
                switch (turn.learningState()) {
                    case GOAL_ACHIEVED ->
                            turn.inputSupport() == SupportLevel.S3 ? "그 답을 따라 말함" : "목표에 맞게 답함";
                    case CORRECT_BUT_SHALLOW -> "맞게 답했지만 이유 설명이 짧았음";
                    case PARTIAL_UNDERSTANDING -> "일부만 답함";
                    case MISCONCEPTION -> "상황과 다른 이유를 말함";
                    case OFF_TOPIC -> turn.refusal() ? "하기 싫다고 함" : "질문과 다른 이야기를 함";
                    case HELP_NEEDED -> "모르겠다고 하거나 도움을 요청함";
                    case NOT_ASSESSABLE -> "말을 알아듣기 어려웠음";
                };
        return when + " " + what;
    }

    private static String criterionPhrase(
            CriterionObservation.Status status, SupportLevel support) {
        return switch (status) {
            case EXPLICIT -> support == SupportLevel.S3 ? "따라 말함" : "분명히 말함";
            case IMPLICIT -> "간접적으로 말함";
            case MISUNDERSTOOD -> "잘못 이해함";
            case CONTRADICTORY -> "상황과 다르게 말함";
            case NOT_EXPRESSED -> "말하지 않음";
        };
    }

    /** 받침이 있으면 "을", 없으면 "를". 한글로 끝나지 않으면 "을(를)". */
    static String objectParticle(String noun) {
        char last = noun.charAt(noun.length() - 1);
        if (last < '가' || last > '힣') {
            return noun + "을(를)";
        }
        return noun + ((last - '가') % 28 == 0 ? "를" : "을");
    }

    static String supportName(SupportLevel level) {
        return switch (level) {
            case S0 -> "열린 질문";
            case S1 -> "상황 단서";
            case S2 -> "두 선택지";
            case S3 -> "답 알려 주기";
        };
    }
}
