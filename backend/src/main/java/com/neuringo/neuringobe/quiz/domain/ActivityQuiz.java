package com.neuringo.neuringobe.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "activity_quiz")
public class ActivityQuiz {
    @Id
    @Column(name = "activity_quiz_id")
    private UUID activityQuizId;

    @Column(name = "activity_id", nullable = false)
    private UUID activityId;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(name = "item_version", nullable = false)
    private int itemVersion;

    @Column(name = "question_order", nullable = false)
    private int questionOrder;

    protected ActivityQuiz() {}

    public ActivityQuiz(
            UUID activityQuizId, UUID activityId, UUID itemId, int itemVersion, int questionOrder) {
        this.activityQuizId = activityQuizId;
        this.activityId = activityId;
        this.itemId = itemId;
        this.itemVersion = itemVersion;
        this.questionOrder = questionOrder;
    }

    public UUID getActivityQuizId() {
        return activityQuizId;
    }

    public UUID getActivityId() {
        return activityId;
    }

    public UUID getItemId() {
        return itemId;
    }

    public int getItemVersion() {
        return itemVersion;
    }

    public int getQuestionOrder() {
        return questionOrder;
    }
}
