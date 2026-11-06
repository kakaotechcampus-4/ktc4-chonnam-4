package com.neuringo.neuringobe.report.domain.roleplay;

import com.neuringo.neuringobe.report.domain.GoalEvidence;
import com.neuringo.neuringobe.scenario.domain.GoalOutcome;
import java.util.List;

/**
 * 역할극 한 번을 작은 목표별로 정리한 결과.
 *
 * @param goals 작은 목표 순서대로의 결과와 관찰. 리포트·추천 입력이다.
 * @param technicalExclusions 판단할 수 없던 기록이 있었음을 알리는 코드. 아동의 어려움으로 쓰지 않는다.
 */
public record RoleplaySummary(List<GoalEvidence> goals, List<String> technicalExclusions) {

    public RoleplaySummary {
        goals = List.copyOf(goals);
        technicalExclusions = List.copyOf(technicalExclusions);
    }

    /** 다음 난이도 정책의 입력. */
    public List<GoalOutcome> outcomes() {
        return goals.stream().map(GoalEvidence::outcome).toList();
    }
}
