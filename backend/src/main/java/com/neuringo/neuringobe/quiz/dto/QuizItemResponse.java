package com.neuringo.neuringobe.quiz.dto;

import com.neuringo.neuringobe.quiz.domain.Emotion;
import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.domain.QuizType;
import java.util.List;
import java.util.UUID;

public record QuizItemResponse(
        UUID itemId,
        QuizType quizType,
        Emotion emotion,
        String questionText,
        String imageUrl,
        List<String> choices,
        String correctAnswer,
        List<String> acceptableAnswers,
        List<QuizItem.HintSpec> hints,
        String status,
        int version) {
    public static QuizItemResponse from(QuizItem item) {
        return new QuizItemResponse(
                item.getItemId(),
                item.getQuizType(),
                item.getEmotion(),
                item.getQuestionText(),
                item.getImageUrl(),
                item.getChoices(),
                item.getCorrectAnswer(),
                item.getAcceptableAnswers(),
                item.getHints(),
                item.getStatus(),
                item.getItemVersion());
    }
}
