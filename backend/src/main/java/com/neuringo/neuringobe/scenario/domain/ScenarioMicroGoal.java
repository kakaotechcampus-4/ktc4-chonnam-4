package com.neuringo.neuringobe.scenario.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 시나리오의 작은 목표. 역할극 달성 판정의 기준이다. 시나리오가 초안일 때만 DB 트리거가 바꾸게 둔다. */
@Entity
@Table(name = "scenario_micro_goal")
public class ScenarioMicroGoal {
    @Id
    @Column(name = "micro_goal_id")
    private UUID microGoalId;

    @Column(name = "scenario_id", nullable = false)
    private UUID scenarioId;

    // 보여 주는 순서일 뿐 통과 순서가 아니다.
    @Column(name = "order_no", nullable = false)
    private int orderNo;

    @Column(name = "description", nullable = false)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_evidence", columnDefinition = "jsonb", nullable = false)
    private List<String> requiredEvidence;

    @Column(name = "achievement_example")
    private String achievementExample;

    protected ScenarioMicroGoal() {}

    /** 새 판으로 옮긴다. 작은 목표 ID 는 표 전체에서 하나라 새로 만든다. */
    public ScenarioMicroGoal copyTo(UUID newScenarioId, UUID newMicroGoalId) {
        ScenarioMicroGoal copy = new ScenarioMicroGoal();
        copy.microGoalId = newMicroGoalId;
        copy.scenarioId = newScenarioId;
        copy.orderNo = orderNo;
        copy.description = description;
        copy.requiredEvidence = requiredEvidence;
        copy.achievementExample = achievementExample;
        return copy;
    }

    /** 설명과 필요 증거를 고친다. 시나리오가 초안일 때만 DB 가 허용한다. */
    public void edit(String newDescription, List<String> newRequiredEvidence) {
        description = newDescription;
        requiredEvidence = List.copyOf(newRequiredEvidence);
    }

    public UUID getMicroGoalId() {
        return microGoalId;
    }

    public UUID getScenarioId() {
        return scenarioId;
    }

    public int getOrderNo() {
        return orderNo;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getRequiredEvidence() {
        return requiredEvidence;
    }
}
