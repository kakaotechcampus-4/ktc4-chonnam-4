package com.neuringo.neuringobe.report.domain;

import com.neuringo.neuringobe.report.domain.RecommendationCandidate.Reason;
import com.neuringo.neuringobe.scenario.domain.GoalOutcome;
import com.neuringo.neuringobe.scenario.domain.GoalScoreRate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 완료한 역할극의 목표별 결과로 다음 목표 추천 후보를 고른다(03 VS-015). 결과는 강사가 채택·수정·보류·거절할 후보일 뿐이고, 자동 배정하지 않는다.
 *
 * <ul>
 *   <li>보완: 도달하지 못한 목표가 먼저, 없으면 S3(답 알려 주기)에서 도달한 목표
 *   <li>반복: 시작 단계보다 지원을 가장 많이 더 받고 도달한 목표
 *   <li>적용: 시작 단계보다 지원을 더 받지 않고 도달한 첫 목표
 *   <li>확장: 점수율이 기준 이상이고 S3·미도달 목표가 없을 때 활동 전체로
 * </ul>
 *
 * <p>기술 오류·중단·응답 거부로 제외된 목표는 보지 않는다. 그래서 기술 오류만으로 보완 추천이 생기지 않는다. 유효 목표가 하나도 없으면 근거 부족으로 반복 하나만 낸다.
 */
public final class RecommendationPolicy {

    private final RecommendationRules rules;

    public RecommendationPolicy(RecommendationRules rules) {
        this.rules = Objects.requireNonNull(rules, "rules must not be null");
    }

    /** 추천마다 남기는 정책 버전. 같은 결과와 같은 버전이면 같은 추천이 나온다. */
    public String version() {
        return rules.version();
    }

    public List<RecommendationCandidate> recommend(List<GoalEvidence> goals) {
        Objects.requireNonNull(goals, "goals must not be null");
        List<GoalEvidence> valid = goals.stream().filter(goal -> goal.outcome().valid()).toList();
        if (valid.isEmpty()) {
            return List.of(
                    new RecommendationCandidate(
                            RecommendationType.REPEAT,
                            Reason.NOT_ENOUGH_EVIDENCE,
                            null,
                            List.of(),
                            EvidenceSufficiency.INSUFFICIENT));
        }

        Map<RecommendationType, RecommendationCandidate> byType =
                new EnumMap<>(RecommendationType.class);
        reinforce(valid).ifPresent(candidate -> byType.put(candidate.type(), candidate));
        repeat(valid).ifPresent(candidate -> byType.put(candidate.type(), candidate));
        apply(valid).ifPresent(candidate -> byType.put(candidate.type(), candidate));
        extend(goals, valid).ifPresent(candidate -> byType.put(candidate.type(), candidate));
        return rules.order().stream().map(byType::get).filter(Objects::nonNull).toList();
    }

    private Optional<RecommendationCandidate> reinforce(List<GoalEvidence> valid) {
        return valid.stream()
                .filter(goal -> goal.outcome().neededAnswerOrMissed())
                .min(Comparator.comparingInt(goal -> notReached(goal) ? 0 : 1))
                .map(
                        goal ->
                                forGoal(
                                        RecommendationType.REINFORCE,
                                        notReached(goal)
                                                ? Reason.GOAL_NOT_REACHED
                                                : Reason.GOAL_NEEDED_ANSWER,
                                        goal,
                                        valid));
    }

    private Optional<RecommendationCandidate> repeat(List<GoalEvidence> valid) {
        return valid.stream()
                .filter(
                        goal ->
                                goal.outcome().reachedWithoutAnswer()
                                        && !goal.outcome().reachedWithoutExtraSupport())
                .max(Comparator.comparingInt(RecommendationPolicy::extraSteps))
                .map(
                        goal ->
                                forGoal(
                                        RecommendationType.REPEAT,
                                        Reason.REACHED_WITH_EXTRA_SUPPORT,
                                        goal,
                                        valid));
    }

    private Optional<RecommendationCandidate> apply(List<GoalEvidence> valid) {
        return valid.stream()
                .filter(goal -> goal.outcome().reachedWithoutExtraSupport())
                .findFirst()
                .map(
                        goal ->
                                forGoal(
                                        RecommendationType.APPLY,
                                        Reason.REACHED_WITHOUT_EXTRA_SUPPORT,
                                        goal,
                                        valid));
    }

    private Optional<RecommendationCandidate> extend(
            List<GoalEvidence> all, List<GoalEvidence> valid) {
        boolean noAnswerOrMissed =
                valid.stream().noneMatch(g -> g.outcome().neededAnswerOrMissed());
        GoalScoreRate rate = GoalScoreRate.of(all.stream().map(GoalEvidence::outcome).toList());
        if (!noAnswerOrMissed
                || valid.size() < rules.sufficientValidGoals()
                || !rate.atLeastPercent(rules.extendAtLeastPercent())) {
            return Optional.empty();
        }
        List<String> evidence =
                valid.stream().flatMap(goal -> goal.observations().stream()).toList();
        return Optional.of(
                new RecommendationCandidate(
                        RecommendationType.EXTEND,
                        Reason.ALL_GOALS_WITHOUT_EXTRA_SUPPORT,
                        null,
                        evidence,
                        evidence.isEmpty()
                                ? EvidenceSufficiency.LIMITED
                                : EvidenceSufficiency.SUFFICIENT));
    }

    private RecommendationCandidate forGoal(
            RecommendationType type, Reason reason, GoalEvidence goal, List<GoalEvidence> valid) {
        List<String> evidence =
                Stream.concat(goal.observations().stream(), goal.effectiveSupports().stream())
                        .toList();
        EvidenceSufficiency sufficiency =
                valid.size() >= rules.sufficientValidGoals() && !goal.observations().isEmpty()
                        ? EvidenceSufficiency.SUFFICIENT
                        : EvidenceSufficiency.LIMITED;
        return new RecommendationCandidate(type, reason, goal, evidence, sufficiency);
    }

    private static boolean notReached(GoalEvidence goal) {
        return goal.outcome().result() == GoalOutcome.Result.NOT_REACHED;
    }

    private static int extraSteps(GoalEvidence goal) {
        return goal.outcome().finalSupport().ordinal() - goal.outcome().startSupport().ordinal();
    }
}
