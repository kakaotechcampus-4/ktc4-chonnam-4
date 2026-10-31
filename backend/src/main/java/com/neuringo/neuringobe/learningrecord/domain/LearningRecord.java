package com.neuringo.neuringobe.learningrecord.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** 완료한 활동 하나에 하나씩 생기는 학습 기록. 퀴즈 수치는 quiz_result 를 활동 ID 로 이어 읽는다. */
@Entity
@Table(name = "learning_record")
public class LearningRecord {
    @Id
    @Column(name = "record_id")
    private UUID recordId;

    @Column(name = "activity_id", nullable = false, updatable = false)
    private UUID activityId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LearningRecord() {}

    public LearningRecord(UUID recordId, UUID activityId, Instant createdAt) {
        this.recordId = recordId;
        this.activityId = activityId;
        this.createdAt = createdAt;
    }

    public UUID getRecordId() {
        return recordId;
    }

    public UUID getActivityId() {
        return activityId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
