package com.neuringo.neuringobe.scenario.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 역할극 시나리오. 지금은 첫 활동에 붙일 시나리오를 고르는 데 필요한 칸만 옮겼다. 내용 칸(사실·금지 추론·시작 질문)과 작은 목표는 역할극이 시나리오를 읽어 가는 쪽에서
 * 필요한 모양으로 더한다.
 */
@Entity
@Table(name = "scenario")
public class Scenario {
    @Id
    @Column(name = "scenario_id")
    private UUID scenarioId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ScenarioStatus status;

    @Column(name = "scenario_level", nullable = false)
    private String scenarioLevel;

    // 미리 넣는 시나리오는 비어 있다. 목표 맞춤 시나리오(AI 생성)만 채운다.
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
