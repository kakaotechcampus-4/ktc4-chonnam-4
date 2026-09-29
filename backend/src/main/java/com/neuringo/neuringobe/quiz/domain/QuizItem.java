package com.neuringo.neuringobe.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "quiz_item")
public class QuizItem {
    @Id
    @Column(name = "item_id")
    private UUID itemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "quiz_type", nullable = false)
    private QuizType quizType;

    @Enumerated(EnumType.STRING)
    @Column(name = "emotion", nullable = false)
    private Emotion emotion;

    @Column(name = "question_text", nullable = false)
    private String questionText;

    @Column(name = "image_url")
    private String imageUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "choices_json", columnDefinition = "jsonb", nullable = false)
    private List<String> choices;

    @Column(name = "correct_answer", nullable = false)
    private String correctAnswer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "acceptable_answers_json", columnDefinition = "jsonb", nullable = false)
    private List<String> acceptableAnswers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hints_json", columnDefinition = "jsonb", nullable = false)
    private List<HintSpec> hints;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "item_version", nullable = false)
    private int itemVersion;

    protected QuizItem() {}

    public QuizItem(
            UUID itemId,
            QuizType quizType,
            Emotion emotion,
            String questionText,
            String imageUrl,
            List<String> choices,
            String correctAnswer,
            List<String> acceptableAnswers,
            List<HintSpec> hints,
            int itemVersion) {
        this.itemId = itemId;
        this.quizType = quizType;
        this.emotion = emotion;
        this.questionText = questionText;
        this.imageUrl = imageUrl;
        this.choices = choices == null ? null : new ArrayList<>(choices);
        this.correctAnswer = correctAnswer;
        this.acceptableAnswers =
                acceptableAnswers == null ? null : new ArrayList<>(acceptableAnswers);
        this.hints = hints == null ? null : new ArrayList<>(hints);
        this.status = "APPROVED";
        this.itemVersion = itemVersion;
        validateForAssignment();
        this.choices = List.copyOf(this.choices);
        this.acceptableAnswers = List.copyOf(this.acceptableAnswers);
        this.hints = List.copyOf(this.hints);
    }

    public UUID getItemId() {
        return itemId;
    }

    public QuizType getQuizType() {
        return quizType;
    }

    public Emotion getEmotion() {
        return emotion;
    }

    public String getQuestionText() {
        return questionText;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public List<String> getChoices() {
        return choices;
    }

    public String getCorrectAnswer() {
        return correctAnswer;
    }

    public List<String> getAcceptableAnswers() {
        return acceptableAnswers;
    }

    public List<HintSpec> getHints() {
        return hints;
    }

    public String getStatus() {
        return status;
    }

    public int getItemVersion() {
        return itemVersion;
    }

    public void validateForAssignment() {
        if (quizType == null
                || emotion == null
                || questionText == null
                || questionText.isBlank()
                || (quizType == QuizType.OTHER_EMOTION_IMAGE
                        && (imageUrl == null || imageUrl.isBlank()))
                || choices == null
                || choices.isEmpty()
                || correctAnswer == null
                || correctAnswer.length() > 200
                || !choices.contains(correctAnswer)
                || acceptableAnswers == null
                || !choices.containsAll(acceptableAnswers)
                || hints == null
                || hints.size() > 2
                || itemVersion < 1) {
            throw new IllegalArgumentException("퀴즈 문항 구성 값이 올바르지 않습니다.");
        }
        if (choices.stream()
                        .anyMatch(value -> value == null || value.isBlank() || value.length() > 200)
                || acceptableAnswers.stream()
                        .anyMatch(
                                value ->
                                        value == null || value.isBlank() || value.length() > 200)) {
            throw new IllegalArgumentException("퀴즈 선택지가 올바르지 않습니다.");
        }
        Set<String> allowedHints =
                Set.of("OBSERVABLE_FACE_CUE", "OBSERVABLE_BODY_CUE", "CHOICE_REDUCTION");
        if (hints.stream()
                .anyMatch(
                        hint ->
                                hint == null
                                        || hint.text() == null
                                        || hint.text().isBlank()
                                        || !allowedHints.contains(hint.type()))) {
            throw new IllegalArgumentException("퀴즈 힌트 구성이 올바르지 않습니다.");
        }
    }

    public record HintSpec(String type, String text) {}
}
