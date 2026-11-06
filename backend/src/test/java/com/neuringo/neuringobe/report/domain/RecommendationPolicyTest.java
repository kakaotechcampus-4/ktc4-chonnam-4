package com.neuringo.neuringobe.report.domain;

import static com.neuringo.neuringobe.report.domain.ReportFixtures.goal;
import static com.neuringo.neuringobe.report.domain.ReportFixtures.goalWithoutObservation;
import static com.neuringo.neuringobe.scenario.domain.GoalOutcome.Result.EXCLUDED_INTERRUPTED;
import static com.neuringo.neuringobe.scenario.domain.GoalOutcome.Result.EXCLUDED_TECHNICAL_ERROR;
import static com.neuringo.neuringobe.scenario.domain.GoalOutcome.Result.NOT_REACHED;
import static com.neuringo.neuringobe.scenario.domain.GoalOutcome.Result.REACHED;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S0;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S1;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S2;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S3;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.report.domain.RecommendationCandidate.Reason;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 03 VS-015 인수 조건 "추천 유형, 이유, 관찰 근거와 근거 충분성이 표시된다", "기술 오류만으로 하향·보완 추천을 만들지 않는다". */
class RecommendationPolicyTest {

    private final RecommendationPolicy policy = new RecommendationPolicy(RecommendationRules.V1);

    @Test
    void picksAtMostOneCandidatePerTypeInConfiguredOrder() {
        List<RecommendationCandidate> candidates =
                policy.recommend(
                        List.of(
                                goal("감정 이름", S0, S0, REACHED),
                                goal("감정 이유", S0, S1, REACHED),
                                goal("상대 마음", S0, S2, REACHED),
                                goal("말로 표현", S0, S3, REACHED),
                                goal("다시 묻기", S0, S2, NOT_REACHED)));

        assertThat(candidates)
                .extracting(RecommendationCandidate::type)
                .containsExactly(
                        RecommendationType.REINFORCE,
                        RecommendationType.REPEAT,
                        RecommendationType.APPLY);
    }

    @Test
    void reinforcesUnreachedGoalBeforeAnsweredGoal() {
        RecommendationCandidate reinforce =
                policy.recommend(
                                List.of(
                                        goal("말로 표현", S0, S3, REACHED),
                                        goal("다시 묻기", S0, S2, NOT_REACHED),
                                        goal("감정 이름", S0, S0, REACHED)))
                        .getFirst();

        assertThat(reinforce.type()).isEqualTo(RecommendationType.REINFORCE);
        assertThat(reinforce.reason()).isEqualTo(Reason.GOAL_NOT_REACHED);
        assertThat(reinforce.basis().goalTitle()).isEqualTo("다시 묻기");
        assertThat(reinforce.evidence()).containsExactly("다시 묻기 관찰", "다시 묻기 지원");
        assertThat(reinforce.sufficiency()).isEqualTo(EvidenceSufficiency.SUFFICIENT);
    }

    @Test
    void repeatsTheGoalThatNeededTheMostExtraSupport() {
        RecommendationCandidate repeat =
                byType(
                        policy.recommend(
                                List.of(
                                        goal("감정 이유", S0, S1, REACHED),
                                        goal("상대 마음", S0, S2, REACHED))),
                        RecommendationType.REPEAT);

        assertThat(repeat.basis().goalTitle()).isEqualTo("상대 마음");
        assertThat(repeat.reason()).isEqualTo(Reason.REACHED_WITH_EXTRA_SUPPORT);
    }

    @Test
    void extendsWhenEveryValidGoalWasReachedWithoutExtraSupport() {
        List<RecommendationCandidate> candidates =
                policy.recommend(
                        List.of(
                                goal("감정 이름", S0, S0, REACHED),
                                goal("감정 이유", S1, S1, REACHED),
                                goal("다시 말하기", S0, S3, EXCLUDED_TECHNICAL_ERROR)));

        assertThat(candidates)
                .extracting(RecommendationCandidate::type)
                .containsExactly(RecommendationType.APPLY, RecommendationType.EXTEND);
        RecommendationCandidate extend = byType(candidates, RecommendationType.EXTEND);
        assertThat(extend.basis()).isNull();
        assertThat(extend.reason()).isEqualTo(Reason.ALL_GOALS_WITHOUT_EXTRA_SUPPORT);
        assertThat(extend.evidence()).containsExactly("감정 이름 관찰", "감정 이유 관찰");
    }

    @Test
    void doesNotExtendWhenAnyGoalNeededAnAnswer() {
        List<RecommendationCandidate> candidates =
                policy.recommend(
                        List.of(
                                goal("감정 이름", S0, S0, REACHED),
                                goal("감정 이유", S0, S0, REACHED),
                                goal("상대 마음", S0, S0, REACHED),
                                goal("말로 표현", S0, S0, REACHED),
                                goal("다시 묻기", S0, S3, REACHED)));

        assertThat(candidates)
                .extracting(RecommendationCandidate::type)
                .doesNotContain(RecommendationType.EXTEND);
    }

    @Test
    void technicalErrorsAloneDoNotProduceReinforcement() {
        List<RecommendationCandidate> candidates =
                policy.recommend(
                        List.of(
                                goal("감정 이름", S0, S3, EXCLUDED_TECHNICAL_ERROR),
                                goal("감정 이유", S0, S3, EXCLUDED_INTERRUPTED)));

        assertThat(candidates).hasSize(1);
        RecommendationCandidate only = candidates.getFirst();
        assertThat(only.type()).isEqualTo(RecommendationType.REPEAT);
        assertThat(only.reason()).isEqualTo(Reason.NOT_ENOUGH_EVIDENCE);
        assertThat(only.sufficiency()).isEqualTo(EvidenceSufficiency.INSUFFICIENT);
    }

    @Test
    void marksLimitedEvidenceWhenFewValidGoalsOrNoObservation() {
        RecommendationCandidate single =
                policy.recommend(List.of(goal("다시 묻기", S0, S2, NOT_REACHED))).getFirst();
        assertThat(single.sufficiency()).isEqualTo(EvidenceSufficiency.LIMITED);

        RecommendationCandidate noObservation =
                policy.recommend(
                                List.of(
                                        goalWithoutObservation("다시 묻기", S0, S2, NOT_REACHED),
                                        goal("감정 이름", S0, S0, REACHED)))
                        .getFirst();
        assertThat(noObservation.sufficiency()).isEqualTo(EvidenceSufficiency.LIMITED);
    }

    @Test
    void followsCustomOrder() {
        RecommendationPolicy applyFirst =
                new RecommendationPolicy(
                        new RecommendationRules(
                                "CUSTOM",
                                List.of(
                                        RecommendationType.APPLY,
                                        RecommendationType.EXTEND,
                                        RecommendationType.REPEAT,
                                        RecommendationType.REINFORCE),
                                2,
                                80));

        assertThat(
                        applyFirst.recommend(
                                List.of(
                                        goal("감정 이름", S0, S0, REACHED),
                                        goal("다시 묻기", S0, S2, NOT_REACHED))))
                .extracting(RecommendationCandidate::type)
                .containsExactly(RecommendationType.APPLY, RecommendationType.REINFORCE);
    }

    @Test
    void rulesMustListEveryTypeOnce() {
        assertThatThrownBy(
                        () ->
                                new RecommendationRules(
                                        "BROKEN",
                                        List.of(
                                                RecommendationType.APPLY,
                                                RecommendationType.APPLY,
                                                RecommendationType.REPEAT,
                                                RecommendationType.REINFORCE),
                                        2,
                                        80))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RecommendationCandidate byType(
            List<RecommendationCandidate> candidates, RecommendationType type) {
        return candidates.stream().filter(c -> c.type() == type).findFirst().orElseThrow();
    }
}
