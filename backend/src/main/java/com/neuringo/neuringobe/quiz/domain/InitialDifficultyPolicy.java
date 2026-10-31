package com.neuringo.neuringobe.quiz.domain;

import java.util.function.Supplier;

/** 최초 난이도는 기술 제외 후 힌트 전 첫 응답만 사용한다. */
public final class InitialDifficultyPolicy {

    public static final String VERSION = "INITIAL_QUIZ_V2";
    private static final int MAX_CALCULATION_ATTEMPTS = 2;

    private InitialDifficultyPolicy() {}

    public static Decision decide(Supplier<QuizResultCalculator.Summary> recalculation) {
        return assess(recalculation).decision();
    }

    public static Assessment assess(Supplier<QuizResultCalculator.Summary> recalculation) {
        for (int attempt = 1; attempt <= MAX_CALCULATION_ATTEMPTS; attempt++) {
            try {
                QuizResultCalculator.Summary summary = recalculation.get();
                if (summary == null
                        || summary.validQuestionCount() < 0
                        || summary.correctQuestionCount() < 0
                        || summary.correctQuestionCount() > summary.validQuestionCount()) {
                    continue;
                }
                if (summary.validQuestionCount() == 0) {
                    if (attempt == MAX_CALCULATION_ATTEMPTS) {
                        return new Assessment(summary, new Decision("L1", "S1", true, VERSION));
                    }
                    continue;
                }
                long percentageNumerator = (long) summary.correctQuestionCount() * 100;
                long denominator = summary.validQuestionCount();
                if (percentageNumerator < 50 * denominator) {
                    return new Assessment(summary, new Decision("L1", "S2", false, VERSION));
                }
                if (percentageNumerator < 80 * denominator) {
                    return new Assessment(summary, new Decision("L2", "S1", false, VERSION));
                }
                return new Assessment(summary, new Decision("L3", "S0", false, VERSION));
            } catch (IllegalArgumentException ex) {
                // 계산 근거 오류만 재시도하고 저장소 장애 등은 호출자에게 전달한다.
            }
        }
        throw new CalculationFailedException(MAX_CALCULATION_ATTEMPTS);
    }

    public record Assessment(QuizResultCalculator.Summary summary, Decision decision) {}

    public record Decision(
            String scenarioLevel,
            String initialSupportLevel,
            boolean fallbackApplied,
            String policyVersion) {}

    public static final class CalculationFailedException extends IllegalStateException {
        private final int attempts;

        private CalculationFailedException(int attempts) {
            super("최초 난이도 계산에 필요한 집계를 만들지 못했습니다.");
            this.attempts = attempts;
        }

        public int attempts() {
            return attempts;
        }
    }
}
