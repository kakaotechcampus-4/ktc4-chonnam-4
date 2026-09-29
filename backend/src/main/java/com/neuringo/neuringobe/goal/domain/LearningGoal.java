package com.neuringo.neuringobe.goal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "learning_goal")
public class LearningGoal {
    @Id
    @Column(name = "goal_id")
    private UUID goalId;

    @Column(name = "child_id", nullable = false)
    private UUID childId;

    @Column(name = "instructor_id", nullable = false)
    private String instructorId;

    @Column(name = "parent_goal_id")
    private UUID parentGoalId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "situation_type")
    private String situationType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "characters_json", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> characters;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_elements_json", columnDefinition = "jsonb", nullable = false)
    private List<String> requiredElements;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "forbidden_expressions_json", columnDefinition = "jsonb", nullable = false)
    private List<String> forbiddenExpressions;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LearningGoal() {}

    public LearningGoal(
            UUID goalId,
            UUID childId,
            String instructorId,
            UUID parentGoalId,
            String title,
            String situationType,
            List<Map<String, Object>> characters,
            List<String> requiredElements,
            List<String> forbiddenExpressions,
            String contentHash,
            Instant createdAt) {
        this.goalId = goalId;
        this.childId = childId;
        this.instructorId = instructorId;
        this.parentGoalId = parentGoalId;
        this.title = title;
        this.situationType = situationType;
        this.characters = characters;
        this.requiredElements = requiredElements;
        this.forbiddenExpressions = forbiddenExpressions;
        this.contentHash = contentHash;
        this.createdAt = createdAt;
    }

    public UUID getGoalId() {
        return goalId;
    }

    public UUID getChildId() {
        return childId;
    }

    public String getInstructorId() {
        return instructorId;
    }

    public UUID getParentGoalId() {
        return parentGoalId;
    }

    public String getTitle() {
        return title;
    }

    public String getSituationType() {
        return situationType;
    }

    public List<Map<String, Object>> getCharacters() {
        return characters;
    }

    public List<String> getRequiredElements() {
        return requiredElements;
    }

    public List<String> getForbiddenExpressions() {
        return forbiddenExpressions;
    }

    public String getContentHash() {
        return contentHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
