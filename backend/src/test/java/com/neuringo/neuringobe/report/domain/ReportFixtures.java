package com.neuringo.neuringobe.report.domain;

import com.neuringo.neuringobe.scenario.domain.GoalOutcome;
import com.neuringo.neuringobe.scenario.domain.SupportLevel;
import java.util.List;
import java.util.UUID;

/** 리포트·추천 테스트의 목표 근거. 관찰 문장은 원인 판단 결과에서 뽑은 짧은 문장을 흉내 낸다. */
public final class ReportFixtures {

    private ReportFixtures() {}

    public static GoalEvidence goal(
            String title, SupportLevel start, SupportLevel end, GoalOutcome.Result result) {
        return new GoalEvidence(
                new GoalOutcome(UUID.randomUUID(), start, end, result),
                title,
                List.of(title + " 관찰"),
                List.of(title + " 지원"));
    }

    public static GoalEvidence goalWithoutObservation(
            String title, SupportLevel start, SupportLevel end, GoalOutcome.Result result) {
        return new GoalEvidence(
                new GoalOutcome(UUID.randomUUID(), start, end, result),
                title,
                List.of(),
                List.of());
    }
}
