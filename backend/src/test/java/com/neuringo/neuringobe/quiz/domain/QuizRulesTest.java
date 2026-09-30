package com.neuringo.neuringobe.quiz.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.quiz.domain.QuizResultCalculator.FinalizedAttempt;
import com.neuringo.neuringobe.quiz.domain.QuizResultCalculator.Summary;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QuizRulesTest {

    @Test
    void selfEmotionNeedsBothCorrectLabelAndCameraMatch() {
        Set<String> choices = Set.of("기쁨", "슬픔");
        assertThat(
                        QuizJudgement.judge(
                                        QuizType.SELF_EMOTION_SITUATION,
                                        choices,
                                        Set.of("슬픔"),
                                        "슬픔",
                                        ExpressionMatchResult.MATCH,
                                        false)
                                .correct())
                .isTrue();
        assertThat(
                        QuizJudgement.judge(
                                        QuizType.SELF_EMOTION_SITUATION,
                                        choices,
                                        Set.of("슬픔"),
                                        "슬픔",
                                        ExpressionMatchResult.MISMATCH,
                                        false)
                                .correct())
                .isFalse();
        assertThat(
                        QuizJudgement.judge(
                                        QuizType.SELF_EMOTION_SITUATION,
                                        choices,
                                        Set.of("슬픔"),
                                        "슬픔",
                                        ExpressionMatchResult.NOT_ANALYZABLE,
                                        false)
                                .excludedFromScoring())
                .isTrue();
    }

    @Test
    void rejectsAnswerOutsideAssignedChoices() {
        assertThatThrownBy(
                        () ->
                                QuizJudgement.judge(
                                        QuizType.OTHER_EMOTION_IMAGE,
                                        Set.of("기쁨", "슬픔"),
                                        Set.of("슬픔"),
                                        "화남",
                                        null,
                                        false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void excludesTechnicalErrorAndDoesNotCountHintResolutionAsFirstCorrect() {
        Summary summary =
                QuizResultCalculator.calculate(
                        List.of(
                                new FinalizedAttempt(false, true, 0, false),
                                new FinalizedAttempt(false, false, 1, true),
                                new FinalizedAttempt(true, false, 0, false)));
        assertThat(summary.validQuestionCount()).isEqualTo(2);
        assertThat(summary.correctQuestionCount()).isEqualTo(1);
        assertThat(summary.totalHintCount()).isEqualTo(1);
        assertThat(summary.resolvedAfterHintCount()).isEqualTo(1);
        assertThat(summary.overallAccuracy()).isEqualTo(0.5);
    }

    @Test
    void mapsFiftyAndEightyPercentBoundariesExactly() {
        assertThat(InitialDifficultyPolicy.decide(() -> new Summary(3, 1, 0, 0)).scenarioLevel())
                .isEqualTo("L1");
        assertThat(InitialDifficultyPolicy.decide(() -> new Summary(2, 1, 0, 0)).scenarioLevel())
                .isEqualTo("L2");
        assertThat(InitialDifficultyPolicy.decide(() -> new Summary(5, 4, 0, 0)).scenarioLevel())
                .isEqualTo("L3");
        assertThat(
                        InitialDifficultyPolicy.decide(() -> new Summary(5, 4, 0, 0))
                                .initialSupportLevel())
                .isEqualTo("S0");
    }

    @Test
    void retriesTwiceThenFallsBackWhenNoValidQuestions() {
        AtomicInteger calls = new AtomicInteger();
        var decision =
                InitialDifficultyPolicy.decide(
                        () -> {
                            calls.incrementAndGet();
                            return new Summary(0, 0, 0, 0);
                        });
        assertThat(calls).hasValue(2);
        assertThat(decision.scenarioLevel()).isEqualTo("L1");
        assertThat(decision.initialSupportLevel()).isEqualTo("S1");
        assertThat(decision.fallbackApplied()).isTrue();
    }
}
