package com.neuringo.neuringobe.scenario.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** 승인 후보 조회에 필요한 시나리오 버전과 재사용 조건이다. */
@Entity
@Table(name = "scenario")
public class Scenario {
    @Id
    @Column(name = "scenario_id")
    private UUID scenarioId;

    @Column(name = "class_id")
    private UUID classId;

    @Column(name = "scenario_version", nullable = false)
    private int scenarioVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ScenarioStatus status;

    @Column(name = "scenario_level", nullable = false)
    private String scenarioLevel;

    // 과거 공용 자료는 비어 있어 자동 재사용 후보에서 제외한다.
    @Column(name = "goal_content_hash")
    private String goalContentHash;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Scenario() {}

    public UUID getScenarioId() {
        return scenarioId;
    }

    public ScenarioStatus getStatus() {
        return status;
    }

    public String getScenarioLevel() {
        return scenarioLevel;
    }

    public String getGoalContentHash() {
        return goalContentHash;
    }

    public String getTitle() {
        return title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
