package com.neuringo.neuringobe.ai.eval.sim;

import com.neuringo.neuringobe.ai.eval.sim.RoleplaySimulation.ChildRun;
import com.neuringo.neuringobe.ai.eval.sim.RoleplaySimulation.Session;
import com.neuringo.neuringobe.report.domain.RecommendationType;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyDecision.Direction;
import com.neuringo.neuringobe.scenario.domain.ScenarioLevel;
import java.util.List;

/**
 * 한 아동 프로필의 여러 시드 실행을 숫자 몇 개로 줄인다. 적정 난이도 값은 실행에 적정 난이도를 잰 경우에만 의미가 있다(아니면 NaN).
 *
 * @param finalL3 마지막 결정이 L3 인 시드의 비율
 * @param lowers 시드당 하향 횟수
 * @param flips 시드당 뒤집힘(상향 바로 다음 결정이 하향, 또는 그 반대) 횟수
 * @param atAppropriate 적정 난이도에서 한 회차 비율
 * @param tooHard 적정보다 높은 난이도에서 한 회차 비율
 * @param tooEasy 적정보다 낮은 난이도에서 한 회차 비율
 * @param extends_ 시드당 확장을 고른 횟수
 */
record SimMetrics(
        double finalL3,
        double lowers,
        double flips,
        double atAppropriate,
        double tooHard,
        double tooEasy,
        double extends_) {

    static SimMetrics of(List<ChildRun> runs) {
        double l3 = 0, lowers = 0, flips = 0, extendsChosen = 0;
        int sessions = 0, measured = 0, at = 0, hard = 0, easy = 0;
        for (ChildRun run : runs) {
            Direction last = null;
            for (Session s : run.sessions()) {
                sessions++;
                Direction d = s.decision().direction();
                if (d == Direction.LOWER) lowers++;
                if ((d == Direction.RAISE && last == Direction.LOWER)
                        || (d == Direction.LOWER && last == Direction.RAISE)) {
                    flips++;
                }
                if (d != Direction.KEEP) last = d;
                if (s.chosen() != null
                        && s.chosen().candidate().type() == RecommendationType.EXTEND)
                    extendsChosen++;
                if (s.appropriate() != null) {
                    measured++;
                    int diff = s.level().ordinal() - s.appropriate().ordinal();
                    if (diff == 0) at++;
                    else if (diff > 0) hard++;
                    else easy++;
                }
            }
            if (run.sessions().getLast().decision().nextLevel() == ScenarioLevel.L3) l3++;
        }
        int n = runs.size();
        return new SimMetrics(
                l3 / n,
                lowers / n,
                flips / n,
                measured == 0 ? Double.NaN : (double) at / measured,
                measured == 0 ? Double.NaN : (double) hard / measured,
                measured == 0 ? Double.NaN : (double) easy / measured,
                extendsChosen / n);
    }
}
