package com.neuringo.neuringobe.quiz.dto;

import com.neuringo.neuringobe.quiz.domain.Emotion;
import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.domain.QuizType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CreateQuizItemRequest(
        @NotNull QuizType quizType,
        @NotNull Emotion emotion,
        @NotBlank String questionText,
        String imageUrl,
        @NotEmpty List<String> choices,
        @NotBlank String correctAnswer,
        List<String> acceptableAnswers,
        List<QuizItem.HintSpec> hints) {}
