package com.neuringo.neuringobe.scenario.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 역할극 시나리오 한 판. 판정이 끝난 판(승인·고쳐 와·반려)은 DB 트리거가 바꾸지 못하게 막는다. */
@Entity
@Table(name = "scenario")
public class Scenario {
    @Id
    @Column(name = "scenario_id")
    private UUID scenarioId;

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

    @Column(name = "summary")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "characters", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> characters;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scenario_facts", columnDefinition = "jsonb", nullable = false)
    private List<String> scenarioFacts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prohibited_inferences", columnDefinition = "jsonb", nullable = false)
    private List<String> prohibitedInferences;

    // 지원 단계(S0·S1·S2)별 시작 질문.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "opening_prompts", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> openingPrompts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // 승인본만 값이 있다. 재사용 후보 순서에서 최근 승인본을 앞에 두는 데 쓴다.
    @Column(name = "approved_at")
    private Instant approvedAt;

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

    public int getScenarioVersion() {
        return scenarioVersion;
    }

    public String getSummary() {
        return summary;
    }

    public List<Map<String, Object>> getCharacters() {
        return characters;
    }

    public List<String> getScenarioFacts() {
        return scenarioFacts;
    }

    public List<String> getProhibitedInferences() {
        return prohibitedInferences;
    }

    public Map<String, Object> getOpeningPrompts() {
        return openingPrompts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }
}
