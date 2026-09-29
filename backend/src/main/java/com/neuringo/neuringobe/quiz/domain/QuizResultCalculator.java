package com.neuringo.neuringobe.quiz.domain;

import java.util.List;

/** Aggregates only finalized attempts. A technical exclusion never enters the denominator. */
public final class QuizResultCalculator {

    private QuizResultCalculator() {}

    public static Summary calculate(List<FinalizedAttempt> attempts) {
        if (attempts == null || attempts.isEmpty()) {
            throw new IllegalArgumentException("확정된 퀴즈 응답이 필요합니다.");
        }
        int valid = 0;
        int firstCorrect = 0;
        int hints = 0;
        int resolved = 0;
        for (FinalizedAttempt attempt : attempts) {
            if (attempt == null || attempt.hintCount() < 0 || attempt.hintCount() > 2) {
                throw new IllegalArgumentException("퀴즈 응답 근거가 올바르지 않습니다.");
            }
            if (attempt.excludedFromScoring()) {
                if (attempt.firstResponseCorrect() || attempt.resolvedAfterHint()) {
                    throw new IllegalArgumentException("기술 제외 문항은 정답으로 집계할 수 없습니다.");
                }
                continue;
            }
            valid++;
            if (attempt.firstResponseCorrect()) {
                firstCorrect++;
            }
            hints += attempt.hintCount();
            if (attempt.resolvedAfterHint()) {
                if (attempt.firstResponseCorrect() || attempt.hintCount() == 0) {
                    throw new IllegalArgumentException("힌트 후 해결 근거가 모순됩니다.");
                }
                resolved++;
            }
        }
        return new Summary(valid, firstCorrect, hints, resolved);
    }

    public record FinalizedAttempt(
            boolean firstResponseCorrect,
            boolean excludedFromScoring,
            int hintCount,
            boolean resolvedAfterHint) {}

    public record Summary(
            int validQuestionCount,
            int correctQuestionCount,
            int totalHintCount,
            int resolvedAfterHintCount) {
        public double overallAccuracy() {
            if (validQuestionCount == 0) {
                throw new IllegalStateException("유효 문항이 없어 정답률을 계산할 수 없습니다.");
            }
            return (double) correctQuestionCount / validQuestionCount;
        }
    }
}
