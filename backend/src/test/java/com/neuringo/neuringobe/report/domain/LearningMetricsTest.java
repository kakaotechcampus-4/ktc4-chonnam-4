package com.neuringo.neuringobe.report.domain;

import static com.neuringo.neuringobe.report.domain.ReportFixtures.goal;
import static com.neuringo.neuringobe.scenario.domain.GoalOutcome.Result.EXCLUDED_TECHNICAL_ERROR;
import static com.neuringo.neuringobe.scenario.domain.GoalOutcome.Result.NOT_REACHED;
import static com.neuringo.neuringobe.scenario.domain.GoalOutcome.Result.REACHED;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S0;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S2;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S3;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 03 VS-014 "퀴즈 결과와 역할극 목표별 증거가 구분되어 보인다" 의 정량 지표 부분. */
class LearningMetricsTest {

    @Test
    void normalizesQuizAccuracyAndHintRecoveryOverValidQuestions() {
        LearningMetrics metrics =
                LearningMetrics.from(
                        new ReportEvidence(new QuizEvidence(3, 2, 1), List.of(), List.of(), null));

        assertThat(metrics.quizAccuracy()).isEqualTo(new Ratio(2, 3));
        assertThat(metrics.quizHintRecovery()).isEqualTo(new Ratio(1, 1));
        assertThat(metrics.roleplayScore()).isNull();
    }

    @Test
    void leavesHintRecoveryEmptyWhenEveryFirstAnswerWasCorrect() {
        LearningMetrics metrics =
                LearningMetrics.from(
                        new ReportEvidence(new QuizEvidence(3, 3, 0), List.of(), List.of(), null));

        assertThat(metrics.quizAccuracy().value()).isEqualTo(1.0);
        assertThat(metrics.quizHintRecovery()).isNull();
    }

    @Test
    void usesFollowUpDifficultyScoreAndSeparatesExcludedGoals() {
        LearningMetrics metrics =
                LearningMetrics.from(
                        new ReportEvidence(
                                null,
                                List.of(
                                        goal("감정 이름", S0, S0, REACHED),
                                        goal("감정 이유", S0, S2, REACHED),
                                        goal("상대 마음", S0, S3, NOT_REACHED),
                                        goal("다시 말하기", S0, S0, EXCLUDED_TECHNICAL_ERROR)),
                                List.of("STT_LOW_CONFIDENCE"),
                                null));

        // (6 + 2 + 0) / 18
        assertThat(metrics.roleplayScore()).isEqualTo(new Ratio(8, 18));
        assertThat(metrics.goalAchievement()).isEqualTo(new Ratio(2, 3));
        assertThat(metrics.independentAchievement()).isEqualTo(new Ratio(1, 3));
        assertThat(metrics.validGoalCount()).isEqualTo(3);
        assertThat(metrics.excludedGoalCount()).isEqualTo(1);
        assertThat(metrics.technicalExclusionCount()).isEqualTo(1);
        assertThat(metrics.quizAccuracy()).isNull();
    }

    @Test
    void answeredGoalIsNotCountedAsAchievement() {
        LearningMetrics metrics =
                LearningMetrics.from(
                        new ReportEvidence(
                                null,
                                List.of(
                                        goal("감정 이름", S0, S3, REACHED),
                                        goal("감정 이유", S0, S0, REACHED)),
                                List.of(),
                                null));

        assertThat(metrics.goalAchievement()).isEqualTo(new Ratio(1, 2));
    }

    @Test
    void rejectsInconsistentQuizCountsAndEmptyEvidence() {
        assertThatThrownBy(() -> new QuizEvidence(2, 3, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuizEvidence(3, 2, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportEvidence(null, List.of(), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
