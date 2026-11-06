package com.neuringo.neuringobe.ai.eval.sim;

import com.neuringo.neuringobe.ai.eval.sim.SimSpec.Session;
import com.neuringo.neuringobe.report.domain.roleplay.CriterionObservation;
import com.neuringo.neuringobe.report.domain.roleplay.LearningState;
import com.neuringo.neuringobe.report.domain.roleplay.RoleplayTurnRecord;
import com.neuringo.neuringobe.scenario.domain.ScenarioLevel;
import com.neuringo.neuringobe.scenario.domain.SupportLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 역할극 한 번을 대화 없이 진행한다. 지원 단계를 바꾸는 규칙은 팀 역할극 원인 판단 프롬프트(prompts/roleplay/v2/cause-analysis.md "다음
 * 전략과 지원 수준")를 그대로 따른다.
 *
 * <ul>
 *   <li>달성하면 다음 작은 목표로. S0·S1 에서 달성했으면 한 단계 낮추고, 아니면 유지한다.
 *   <li>S3(답 알려 주기)에서 답한 뒤에는 같은 목표를 더 묻지 않고 다음 목표로 간다.
 *   <li>한 목표에서 물은 횟수가 상한에 닿으면 S3 로 간다.
 *   <li>도움 요청·오개념·같은 목표에서 두 번째 일부 이해면 한 단계 올린다. 첫 일부 이해·얕은 답·딴 이야기·판단 불가는 유지한다.
 *   <li>모든 목표를 거치거나 턴 상한에 닿으면 끝난다.
 * </ul>
 *
 * <p>답을 알려 준 뒤 다음 목표의 지원은 프롬프트에 정해져 있지 않아 활동 시작 단계로 돌린다(시뮬레이션의 가정).
 */
final class SimRoleplayEngine {

    enum EndReason {
        ALL_GOALS_VISITED,
        TURN_LIMIT
    }

    record Result(List<RoleplayTurnRecord> turns, EndReason endReason) {}

    /**
     * 프롬프트가 정하지 않았거나 실험으로 바꿔 볼 두 규칙. 기본값(TEAM)은 프롬프트를 글자 그대로 읽은 것이다.
     *
     * @param unclearCountsTowardCap 판단 불가(음성 인식 오류 등) 답도 "이 목표에서 물은 횟수"에 넣는가
     * @param keepSupportAfterS3Achievement S3 에서 달성한 뒤 다음 목표도 S3 로 시작하는가(프롬프트 규칙 2 "아니면 현재 수준을 유지")
     */
    record Rules(
            String name, boolean unclearCountsTowardCap, boolean keepSupportAfterS3Achievement) {
        static final Rules TEAM = new Rules("team", true, true);
    }

    private final Session limits;
    private final Rules rules;

    SimRoleplayEngine(Session limits) {
        this(limits, Rules.TEAM);
    }

    SimRoleplayEngine(Session limits, Rules rules) {
        this.limits = limits;
        this.rules = rules;
    }

    Result run(
            List<UUID> microGoalIds,
            List<String> skills,
            SupportLevel start,
            ScenarioLevel level,
            SimChild child) {
        return run(microGoalIds, skills, null, start, level, child);
    }

    /**
     * @param criteria 작은 목표 순서대로의 기준 설명. null 이면 기준 관찰을 붙이지 않는다.
     */
    Result run(
            List<UUID> microGoalIds,
            List<String> skills,
            List<String> criteria,
            SupportLevel start,
            ScenarioLevel level,
            SimChild child) {
        List<RoleplayTurnRecord> turns = new ArrayList<>();
        int goal = 0;
        SupportLevel support = start;
        int askedOnGoal = 0;
        boolean partialSeen = false;
        while (goal < microGoalIds.size() && turns.size() < limits.maxTurns()) {
            LearningState state = child.respond(skills.get(goal), support, level);
            List<CriterionObservation> observed = List.of();
            if (criteria != null) {
                CriterionObservation.Status status = child.observe(state);
                if (status != null)
                    observed = List.of(new CriterionObservation(criteria.get(goal), status));
            }
            turns.add(
                    new RoleplayTurnRecord(
                            microGoalIds.get(goal),
                            turns.size() + 1,
                            support,
                            state,
                            child.lastRefusal(),
                            observed));

            if (state == LearningState.GOAL_ACHIEVED || support == SupportLevel.S3) {
                SupportLevel next;
                if (state != LearningState.GOAL_ACHIEVED) {
                    next = start;
                } else if (support.ordinal() <= SupportLevel.S1.ordinal()) {
                    next = lower(support);
                } else if (support == SupportLevel.S3 && !rules.keepSupportAfterS3Achievement()) {
                    next = start;
                } else {
                    next = support;
                }
                goal++;
                support = next;
                askedOnGoal = 0;
                partialSeen = false;
                continue;
            }
            if (state == LearningState.NOT_ASSESSABLE && !rules.unclearCountsTowardCap()) {
                continue;
            }
            askedOnGoal++;
            if (askedOnGoal >= limits.maxSupportTurnsPerGoal()) {
                support = SupportLevel.S3;
                continue;
            }
            switch (state) {
                case HELP_NEEDED, MISCONCEPTION -> support = higher(support);
                case PARTIAL_UNDERSTANDING -> {
                    if (partialSeen) {
                        support = higher(support);
                    }
                    partialSeen = true;
                }
                default -> {}
            }
        }
        return new Result(
                turns,
                goal >= microGoalIds.size() ? EndReason.ALL_GOALS_VISITED : EndReason.TURN_LIMIT);
    }

    private static SupportLevel lower(SupportLevel level) {
        return level == SupportLevel.S0
                ? SupportLevel.S0
                : SupportLevel.values()[level.ordinal() - 1];
    }

    private static SupportLevel higher(SupportLevel level) {
        return level == SupportLevel.S3
                ? SupportLevel.S3
                : SupportLevel.values()[level.ordinal() + 1];
    }
}
