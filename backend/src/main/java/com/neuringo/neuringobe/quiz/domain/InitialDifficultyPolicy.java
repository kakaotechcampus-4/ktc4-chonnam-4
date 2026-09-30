package com.neuringo.neuringobe.quiz.domain;

import java.util.function.Supplier;

/** The first role-play difficulty uses only first, pre-hint answers from valid questions. */
public final class InitialDifficultyPolicy {

    public static final String VERSION = "INITIAL_QUIZ_V1";

    private InitialDifficultyPolicy() {}

    public static Decision decide(Supplier<QuizResultCalculator.Summary> recalculation) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                QuizResultCalculator.Summary summary = recalculation.get();
                if (summary == null
                        || summary.validQuestionCount() == 0
                        || summary.correctQuestionCount() < 0
                        || summary.correctQuestionCount() > summary.validQuestionCount()) {
                    continue;
                }
                long percentageNumerator = (long) summary.correctQuestionCount() * 100;
                long denominator = summary.validQuestionCount();
                if (percentageNumerator < 50 * denominator) {
                    return new Decision("L1", "S2", false, VERSION);
                }
                if (percentageNumerator < 80 * denominator) {
                    return new Decision("L2", "S1", false, VERSION);
                }
                return new Decision("L3", "S0", false, VERSION);
            } catch (IllegalArgumentException ex) {
                // A second calculation can recover from transiently inconsistent evidence.
            }
        }
        return new Decision("L1", "S1", true, VERSION);
    }

    public record Decision(
            String scenarioLevel,
            String initialSupportLevel,
            boolean fallbackApplied,
            String policyVersion) {}
}
