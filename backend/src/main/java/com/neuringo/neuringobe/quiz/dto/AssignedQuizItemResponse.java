package com.neuringo.neuringobe.quiz.dto;

import com.neuringo.neuringobe.quiz.domain.ActivityQuiz;
import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.domain.QuizType;
import java.util.List;
import java.util.UUID;

/** Deliberately excludes correctAnswer, acceptableAnswers, emotion and hint contents. */
public record AssignedQuizItemResponse(
        UUID activityQuizId,
        UUID activityId,
        UUID itemId,
        int itemVersion,
        int questionOrder,
        QuizType quizType,
        String questionText,
        String imageUrl,
        List<String> choices) {
    public static AssignedQuizItemResponse from(ActivityQuiz assigned, QuizItem item) {
        return new AssignedQuizItemResponse(
                assigned.getActivityQuizId(),
                assigned.getActivityId(),
                assigned.getItemId(),
                assigned.getItemVersion(),
                assigned.getQuestionOrder(),
                item.getQuizType(),
                item.getQuestionText(),
                item.getImageUrl(),
                item.getChoices());
    }
}
