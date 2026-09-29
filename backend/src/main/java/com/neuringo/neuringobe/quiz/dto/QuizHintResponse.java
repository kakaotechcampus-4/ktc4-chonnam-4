package com.neuringo.neuringobe.quiz.dto;

import com.neuringo.neuringobe.quiz.domain.QuizHint;
import java.util.UUID;

public record QuizHintResponse(UUID hintId, int hintOrder, String type, String text) {
    public static QuizHintResponse from(QuizHint hint) {
        return new QuizHintResponse(
                hint.getHintId(), hint.getHintOrder(), hint.getHintType(), hint.getHintText());
    }
}
