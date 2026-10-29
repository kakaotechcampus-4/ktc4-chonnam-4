package com.neuringo.neuringobe.quiz.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.quiz.domain.InitialDifficultyPolicy.CalculationFailedException;
import com.neuringo.neuringobe.quiz.domain.QuizResultCalculator.FinalizedAttempt;
import com.neuringo.neuringobe.quiz.domain.QuizResultCalculator.Summary;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class InitialDifficultyPolicyTest {

    @ParameterizedTest
    @CsvSource({
        "3, 0, L1, S2",
        "3, 1, L1, S2",
        "10000, 4999, L1, S2",
        "2, 1, L2, S1",
        "3, 2, L2, S1",
        "10000, 7999, L2, S1",
        "5, 4, L3, S0",
        "3, 3, L3, S0"
    })
    void returnsOriginalSummaryWithExactBoundaryDecision(
            int valid, int correct, String level, String support) {
        Summary summary = new Summary(valid, correct, 0, 0);
        AtomicInteger calls = new AtomicInteger();

        var assessment =
                InitialDifficultyPolicy.assess(
                        () -> {
                            calls.incrementAndGet();
                            return summary;
                        });

        assertThat(calls).hasValue(1);
        assertThat(assessment.summary()).isSameAs(summary);
        assertThat(assessment.decision())
                .isEqualTo(
                        new InitialDifficultyPolicy.Decision(
                                level, support, false, "INITIAL_QUIZ_V2"));
    }

    @Test
    void allTechnicalExclusionsReturnZeroValidCountAndFallback() {
        var evidence = List.of(new FinalizedAttempt(false, true, 0, false));
        AtomicInteger calculations = new AtomicInteger();

        var assessment =
                InitialDifficultyPolicy.assess(
                        () -> {
                            calculations.incrementAndGet();
                            return QuizResultCalculator.calculate(evidence);
                        });

        assertThat(calculations).hasValue(2);
        assertThat(assessment.summary().validQuestionCount()).isZero();
        assertThat(assessment.summary().correctQuestionCount()).isZero();
        assertThat(assessment.decision())
                .isEqualTo(
                        new InitialDifficultyPolicy.Decision("L1", "S1", true, "INITIAL_QUIZ_V2"));
    }

    @ParameterizedTest
    @MethodSource("invalidSummaries")
    void missingOrInconsistentSummaryFailsInsteadOfReturningFallback(Summary summary) {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(
                        () ->
                                InitialDifficultyPolicy.assess(
                                        () -> {
                                            calls.incrementAndGet();
                                            return summary;
                                        }))
                .isInstanceOfSatisfying(
                        CalculationFailedException.class,
                        failure -> assertThat(failure.attempts()).isEqualTo(2));
        assertThat(calls).hasValue(2);
    }

    static Stream<Summary> invalidSummaries() {
        return Stream.of(
                null, new Summary(-1, 0, 0, 0), new Summary(3, -1, 0, 0), new Summary(3, 4, 0, 0));
    }

    @Test
    void returnsRecoveredSummaryWithItsDecision() {
        Summary recovered = new Summary(2, 1, 0, 0);
        AtomicInteger calls = new AtomicInteger();

        var assessment =
                InitialDifficultyPolicy.assess(
                        () -> {
                            if (calls.incrementAndGet() == 1) {
                                throw new IllegalArgumentException("집계 근거가 일치하지 않습니다.");
                            }
                            return recovered;
                        });

        assertThat(calls).hasValue(2);
        assertThat(assessment.summary()).isSameAs(recovered);
        assertThat(assessment.decision())
                .isEqualTo(
                        new InitialDifficultyPolicy.Decision("L2", "S1", false, "INITIAL_QUIZ_V2"));
    }

    @Test
    void repeatedInconsistentEvidenceFailsWithoutKeepingOriginalMessage() {
        var evidence = List.of(new FinalizedAttempt(true, true, 0, false));
        AtomicInteger calculations = new AtomicInteger();

        assertThatThrownBy(
                        () ->
                                InitialDifficultyPolicy.assess(
                                        () -> {
                                            calculations.incrementAndGet();
                                            return QuizResultCalculator.calculate(evidence);
                                        }))
                .isInstanceOf(CalculationFailedException.class)
                .hasMessage("최초 난이도 계산에 필요한 집계를 만들지 못했습니다.")
                .hasNoCause();
        assertThat(calculations).hasValue(2);
    }

    @Test
    void zeroValidCountDoesNotHideFailureOfFinalCalculation() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(
                        () ->
                                InitialDifficultyPolicy.assess(
                                        () -> {
                                            if (calls.incrementAndGet() == 1) {
                                                return new Summary(0, 0, 0, 0);
                                            }
                                            throw new IllegalArgumentException("집계 근거가 일치하지 않습니다.");
                                        }))
                .isInstanceOf(CalculationFailedException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void unexpectedFailurePropagatesWithoutBecomingFallback() {
        IllegalStateException failure = new IllegalStateException("저장소 조회에 실패했습니다.");
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(
                        () ->
                                InitialDifficultyPolicy.assess(
                                        () -> {
                                            calls.incrementAndGet();
                                            throw failure;
                                        }))
                .isSameAs(failure);
        assertThat(calls).hasValue(1);
    }

    @Test
    void solvingAfterHintKeepsFirstResponseDifficulty() {
        Summary beforeHint =
                QuizResultCalculator.calculate(
                        List.of(
                                new FinalizedAttempt(false, false, 0, false),
                                new FinalizedAttempt(true, false, 0, false)));
        Summary afterHint =
                QuizResultCalculator.calculate(
                        List.of(
                                new FinalizedAttempt(false, false, 1, true),
                                new FinalizedAttempt(true, false, 0, false)));

        var before = InitialDifficultyPolicy.assess(() -> beforeHint);
        var after = InitialDifficultyPolicy.assess(() -> afterHint);

        assertThat(after.summary().correctQuestionCount()).isEqualTo(1);
        assertThat(after.summary().resolvedAfterHintCount()).isEqualTo(1);
        assertThat(before.summary().resolvedAfterHintCount()).isZero();
        assertThat(after.decision())
                .isEqualTo(before.decision())
                .isEqualTo(
                        new InitialDifficultyPolicy.Decision("L2", "S1", false, "INITIAL_QUIZ_V2"));
    }
}
