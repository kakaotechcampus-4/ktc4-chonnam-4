package com.neuringo.neuringobe.ai.eval.sim;

import com.neuringo.neuringobe.ai.eval.sim.SimSpec.ChildProfile;
import com.neuringo.neuringobe.report.domain.roleplay.CriterionObservation;
import com.neuringo.neuringobe.report.domain.roleplay.LearningState;
import com.neuringo.neuringobe.scenario.domain.GoalOutcome;
import com.neuringo.neuringobe.scenario.domain.ScenarioLevel;
import com.neuringo.neuringobe.scenario.domain.SupportLevel;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 합성 아동 한 명. 질문 한 번에 학습 상태 하나로 답하고, 역할극을 마칠 때마다 연습한 작은 목표의 실력이 조금 오른다. 시드가 같으면 답이 같다.
 *
 * <p>달성 확률 = 실력 + 지원 단계 보정 − 시나리오 난이도 보정. 달성하지 못하면 도움 요청·일부 이해·얕은 답·오개념 중 하나를 고른다.
 */
final class SimChild {

    /** 지원 단계가 높을수록 쉬워진다. S3 는 답을 들은 뒤라 따라 말하기 쉽다. */
    private static final Map<SupportLevel, Double> SUPPORT_BOOST =
            Map.of(
                    SupportLevel.S0,
                    0.0,
                    SupportLevel.S1,
                    0.2,
                    SupportLevel.S2,
                    0.35,
                    SupportLevel.S3,
                    0.6);

    /** 시나리오가 복잡할수록 어렵다. */
    private static final Map<ScenarioLevel, Double> LEVEL_PENALTY =
            Map.of(ScenarioLevel.L1, -0.1, ScenarioLevel.L2, 0.0, ScenarioLevel.L3, 0.15);

    private static final double MAX_ABILITY = 0.95;

    /** 민감도 실험용 배율. 지원 단계 보정과 난이도 보정을 몇 배로 쓸지다. 기본은 1. */
    record Knobs(double supportBoostScale, double levelPenaltyScale) {
        static final Knobs DEFAULT = new Knobs(1.0, 1.0);
    }

    private final ChildProfile profile;
    private final Knobs knobs;
    private final Random random;

    /** 기준 관찰 상태용. 답(학습 상태)의 흐름과 따로 굴려서 기준을 붙여도 회차 결과가 같다. */
    private final Random observationRandom;

    private final Map<String, Double> ability = new HashMap<>();
    private boolean lastRefusal;

    SimChild(ChildProfile profile, long seed) {
        this(profile, seed, Knobs.DEFAULT);
    }

    SimChild(ChildProfile profile, long seed, Knobs knobs) {
        this.profile = profile;
        this.knobs = knobs;
        this.random = new Random(seed);
        this.observationRandom = new Random(seed ^ 0x5DEECE66DL);
    }

    /** 지금 실력 그대로, 다른 시드로 답하는 복제. 적정 난이도를 미리 재 볼 때 쓴다. */
    SimChild copy(long seed) {
        SimChild copy = new SimChild(profile, seed, knobs);
        copy.ability.putAll(ability);
        return copy;
    }

    double ability(String skill) {
        return ability.computeIfAbsent(skill, profile::abilityFor);
    }

    LearningState respond(String skill, SupportLevel support, ScenarioLevel level) {
        lastRefusal = false;
        double roll = random.nextDouble();
        if (roll < profile.techFailRate()) {
            return LearningState.NOT_ASSESSABLE;
        }
        roll -= profile.techFailRate();
        if (roll < profile.refusalRate()) {
            lastRefusal = true;
            return LearningState.OFF_TOPIC;
        }
        roll -= profile.refusalRate();
        if (roll < profile.offTopicRate()) {
            return LearningState.OFF_TOPIC;
        }
        double achieve =
                Math.clamp(
                        ability(skill)
                                + SUPPORT_BOOST.get(support) * knobs.supportBoostScale()
                                - LEVEL_PENALTY.get(level) * knobs.levelPenaltyScale(),
                        0.02,
                        0.97);
        if (random.nextDouble() < achieve) {
            return LearningState.GOAL_ACHIEVED;
        }
        double miss = random.nextDouble();
        if (miss < 0.4) return LearningState.HELP_NEEDED;
        if (miss < 0.7) return LearningState.PARTIAL_UNDERSTANDING;
        if (miss < 0.85) return LearningState.CORRECT_BUT_SHALLOW;
        return LearningState.MISCONCEPTION;
    }

    /** 학습 상태에 맞는 기준 관찰 상태. 내용이 없는 답(도움 요청·딴 이야기·판단 불가)은 null. */
    CriterionObservation.Status observe(LearningState state) {
        return switch (state) {
            case GOAL_ACHIEVED ->
                    observationRandom.nextDouble() < 0.8
                            ? CriterionObservation.Status.EXPLICIT
                            : CriterionObservation.Status.IMPLICIT;
            case CORRECT_BUT_SHALLOW -> CriterionObservation.Status.IMPLICIT;
            case PARTIAL_UNDERSTANDING ->
                    observationRandom.nextBoolean()
                            ? CriterionObservation.Status.IMPLICIT
                            : CriterionObservation.Status.NOT_EXPRESSED;
            case MISCONCEPTION -> CriterionObservation.Status.MISUNDERSTOOD;
            case OFF_TOPIC, HELP_NEEDED, NOT_ASSESSABLE -> null;
        };
    }

    boolean lastRefusal() {
        return lastRefusal;
    }

    /** 한 역할극의 작은 목표 결과로 실력을 올린다. 도움 없이 해낸 목표가 가장 많이 오른다. */
    void learn(String skill, GoalOutcome outcome) {
        if (!outcome.valid()) {
            return;
        }
        double gain =
                outcome.reachedWithoutAnswer()
                                && outcome.finalSupport().ordinal() <= SupportLevel.S1.ordinal()
                        ? 1.0
                        : outcome.reachedWithoutAnswer() ? 0.7 : 0.4;
        ability.put(skill, Math.min(MAX_ABILITY, ability(skill) + profile.learnRate() * gain));
    }
}
