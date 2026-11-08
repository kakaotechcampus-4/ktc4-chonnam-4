package com.neuringo.neuringobe.scenario.domain;

import java.util.List;
import java.util.Objects;

/**
 * VS-016 점수 정규화. 후속 난이도·리포트 지표·추천이 이 값 하나를 같이 쓴다.
 *
 * <p>S0 시작 목표는 S0=3·S1=2·S2=1·S3=0점이다. S1·S2 시작 목표는 시작 단계에서 S3 까지 남은 단계 수로 나눠 0~1 로 맞춘다. 시작 단계에서
 * 도달하면 1, S3 도달(정답 제공)과 미도달은 0 이다. 제외 목표(기술 오류·중단·응답 거부)는 세지 않고, 남은 목표는 같은 무게로 평균한다.
 *
 * <p>분모는 1·2·3 중 하나라서 최소공배수 6 을 곱한 정수로 들고 있다. 50%·80% 경계를 소수 오차 없이 비교하기 위해서다.
 */
public record GoalScoreRate(int scaledPoints, int goalCount) {

    private static final int SCALE = 6;

    public GoalScoreRate {
        if (goalCount < 0 || scaledPoints < 0 || scaledPoints > SCALE * goalCount) {
            throw new IllegalArgumentException("score rate is out of range");
        }
    }

    public static GoalScoreRate of(List<GoalOutcome> outcomes) {
        Objects.requireNonNull(outcomes, "outcomes must not be null");
        int points = 0;
        int count = 0;
        for (GoalOutcome outcome : outcomes) {
            if (!outcome.valid()) continue;
            points += scaledPoints(outcome);
            count++;
        }
        return new GoalScoreRate(points, count);
    }

    /** 목표 하나의 점수(0~6). 6 이 만점(1.0)이다. */
    static int scaledPoints(GoalOutcome outcome) {
        if (!outcome.reachedWithoutAnswer()) return 0;
        int possible = outcome.startSupport().stepsToFullSupport();
        if (possible == 0) return 0;
        // 도중에 지원을 낮춰 시작 단계보다 낮은 단계에서 도달해도 만점을 넘지 않는다.
        int earned = Math.min(possible, outcome.finalSupport().stepsToFullSupport());
        return earned * (SCALE / possible);
    }

    /** scaledPoints 의 만점. ratio() 의 분모다. */
    public int maxScaledPoints() {
        return SCALE * goalCount;
    }

    public boolean atLeastPercent(int percent) {
        return goalCount > 0 && (long) scaledPoints * 100 >= (long) percent * SCALE * goalCount;
    }

    /** 표시용 0~1 값. 경계 비교에는 atLeastPercent 를 쓴다. */
    public double ratio() {
        return goalCount == 0 ? 0.0 : (double) scaledPoints / (SCALE * goalCount);
    }
}
