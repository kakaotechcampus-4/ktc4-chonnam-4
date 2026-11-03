package com.neuringo.neuringobe.scenario.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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

    // 초안과 판정 기록만 그 아동의 학습 목표에 매인다. 승인하면 비운다.
    @Column(name = "goal_id")
    private UUID goalId;

    @Column(name = "source_scenario_id")
    private UUID sourceScenarioId;

    @Column(name = "ai_role")
    private String aiRole;

    @Column(name = "origin", nullable = false)
    private String origin;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "policy_version")
    private String policyVersion;

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

    // 수정 번호(응답 editRevision). 행이 바뀔 때마다 JPA 가 올린다. 판 번호와 달리 같은 초안을 저장해도 오른다.
    // 새 판은 비어 있어 새 행으로 저장되고 0 이 된다.
    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    protected Scenario() {}

    /**
     * 승인본을 이 목표에 맞게 고칠 새 판(초안)을 만든다. 내용은 원본을 그대로 옮기고, 번호는 원본 +1 이다. 작은 목표와 첫 질문 목표 연결은 호출하는 쪽이 옮긴다.
     */
    public static Scenario editedFrom(Scenario source, UUID scenarioId, UUID goalId, Instant now) {
        Scenario draft = new Scenario();
        draft.scenarioId = scenarioId;
        draft.scenarioVersion = source.scenarioVersion + 1;
        draft.status = ScenarioStatus.DRAFT;
        draft.scenarioLevel = source.scenarioLevel;
        draft.goalContentHash = source.goalContentHash;
        draft.goalId = goalId;
        draft.sourceScenarioId = source.scenarioId;
        draft.title = source.title;
        draft.summary = source.summary;
        draft.aiRole = source.aiRole;
        draft.characters = source.characters;
        draft.scenarioFacts = source.scenarioFacts;
        draft.prohibitedInferences = source.prohibitedInferences;
        draft.openingPrompts = source.openingPrompts;
        draft.origin = "INSTRUCTOR_EDITED";
        draft.promptVersion = source.promptVersion;
        draft.policyVersion = source.policyVersion;
        draft.createdAt = now;
        return draft;
    }

    public boolean isDraft() {
        return status == ScenarioStatus.DRAFT;
    }

    /** 초안의 제목·요약을 고친다. 빈 값은 그대로 둔다. */
    public void edit(String newTitle, String newSummary) {
        requireDraft();
        if (newTitle != null) title = newTitle;
        if (newSummary != null) summary = newSummary;
    }

    /** 승인하면 공용 재사용 후보가 되고 아동의 목표에서 떨어진다. */
    public void approve(Instant now) {
        requireDraft();
        status = ScenarioStatus.APPROVED;
        approvedAt = now;
        goalId = null;
    }

    /** 쓰지 않기로 판정한다. 그 목표의 기록으로 남는다. */
    public void reject() {
        requireDraft();
        status = ScenarioStatus.REJECTED;
    }

    private void requireDraft() {
        if (status != ScenarioStatus.DRAFT) {
            throw new IllegalStateException("판정이 끝난 시나리오는 바꿀 수 없습니다.");
        }
    }

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

    public UUID getGoalId() {
        return goalId;
    }

    public UUID getSourceScenarioId() {
        return sourceScenarioId;
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

    /** 저장 전 새 판은 null 이다. Spring Data 가 이 값으로 새 행인지 판단한다. */
    public Long getRowVersion() {
        return rowVersion;
    }
}
