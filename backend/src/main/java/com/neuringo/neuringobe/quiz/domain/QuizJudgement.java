package com.neuringo.neuringobe.quiz.domain;

import java.util.Set;

/**
 * Domain rule for the pre-hint answer. Persist its output, never client-supplied correctness flags.
 */
public final class QuizJudgement {

    private QuizJudgement() {}

    public static FirstResponseResult judge(
            QuizType type,
            Set<String> choices,
            Set<String> acceptedAnswers,
            String selectedAnswer,
            ExpressionMatchResult expressionMatch,
            boolean technicalFailure) {
        if (type == null
                || choices == null
                || choices.isEmpty()
                || acceptedAnswers == null
                || acceptedAnswers.isEmpty()) {
            throw new IllegalArgumentException("문항이 필요합니다.");
        }
        if (technicalFailure || expressionMatch == ExpressionMatchResult.NOT_ANALYZABLE) {
            return new FirstResponseResult(false, true);
        }
        if (selectedAnswer == null || selectedAnswer.isBlank()) {
            throw new IllegalArgumentException("첫 응답이 필요합니다.");
        }
        if (!choices.contains(selectedAnswer) || !choices.containsAll(acceptedAnswers)) {
            throw new IllegalArgumentException("배정 문항의 선택지가 아닙니다.");
        }
        if (type == QuizType.SELF_EMOTION_SITUATION
                && expressionMatch == null
                && !technicalFailure) {
            throw new IllegalArgumentException("자기 감정 문항에는 카메라 판정이 필요합니다.");
        }
        if (type != QuizType.SELF_EMOTION_SITUATION && expressionMatch != null) {
            throw new IllegalArgumentException("이 문항 유형에는 카메라 판정을 보낼 수 없습니다.");
        }
        boolean correct =
                acceptedAnswers.contains(selectedAnswer)
                        && (type != QuizType.SELF_EMOTION_SITUATION
                                || expressionMatch == ExpressionMatchResult.MATCH);
        return new FirstResponseResult(correct, false);
    }

    public record FirstResponseResult(boolean correct, boolean excludedFromScoring) {}
}
