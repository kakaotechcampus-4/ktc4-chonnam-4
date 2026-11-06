package com.neuringo.neuringobe.report.application;

import com.neuringo.neuringobe.report.domain.GoalEvidence;
import com.neuringo.neuringobe.report.domain.LearningMetrics;
import com.neuringo.neuringobe.report.domain.QuizEvidence;
import com.neuringo.neuringobe.report.domain.Ratio;
import com.neuringo.neuringobe.report.domain.roleplay.RoleplaySummary;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficulty;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 서버 계산값을 강사 화면용 스냅샷으로 옮긴다. 숫자는 분자·분모를 함께 들고 있어 화면이 "3개 중 2개"처럼 쓸 수 있다. */
public final class ReportSnapshots {

    private ReportSnapshots() {}

    public static ReportSnapshot build(
            QuizEvidence quiz,
            RoleplayAnswerMapper.MappedRoleplay roleplay,
            RoleplaySummary summary,
            LearningMetrics metrics,
            FollowUpDifficulty decision) {
        List<ReportSnapshot.Goal> goals = new ArrayList<>();
        List<String> observations = new ArrayList<>();
        LinkedHashSet<String> supports = new LinkedHashSet<>();
        for (GoalEvidence goal : summary.goals()) {
            var outcome = goal.outcome();
            var criteria =
                    roleplay.criteria().getOrDefault(outcome.goalId(), List.of()).stream()
                            .map(
                                    c ->
                                            new ReportSnapshot.Criterion(
                                                    c.description(),
                                                    c.status() == null ? null : c.status().name()))
                            .toList();
            goals.add(
                    new ReportSnapshot.Goal(
                            outcome.goalId(),
                            goal.goalTitle(),
                            outcome.startSupport().name(),
                            outcome.finalSupport().name(),
                            outcome.result().name(),
                            LearningReportInputAssembler.status(outcome).name(),
                            criteria,
                            goal.observations()));
            observations.addAll(goal.observations());
            supports.addAll(goal.effectiveSupports());
        }
        return new ReportSnapshot(
                quiz == null
                        ? null
                        : new ReportSnapshot.Quiz(
                                quiz.validQuestionCount(),
                                quiz.correctQuestionCount(),
                                quiz.validQuestionCount() == 0
                                        ? null
                                        : (double) quiz.correctQuestionCount()
                                                / quiz.validQuestionCount(),
                                quiz.resolvedAfterHintCount()),
                new ReportSnapshot.Roleplay(
                        roleplay.level().name(),
                        roleplay.startSupport().name(),
                        goals,
                        observations,
                        List.copyOf(supports),
                        summary.technicalExclusions()),
                new ReportSnapshot.Metrics(
                        view(metrics.roleplayScore()),
                        view(metrics.goalAchievement()),
                        view(metrics.independentAchievement()),
                        view(metrics.quizAccuracy()),
                        view(metrics.quizHintRecovery()),
                        metrics.validGoalCount(),
                        metrics.excludedGoalCount()),
                new ReportSnapshot.Difficulty(
                        "FOLLOW_UP",
                        decision.getNextLevel().name(),
                        decision.getPreviousLevel().name(),
                        decision.getNextSupportLevel().name(),
                        decision.getDirection().name(),
                        decision.getReason().name(),
                        decision.getScoreDenominator() == 0
                                ? null
                                : new ReportSnapshot.RatioView(
                                        decision.getScoreNumerator(),
                                        decision.getScoreDenominator(),
                                        (double) decision.getScoreNumerator()
                                                / decision.getScoreDenominator()),
                        decision.getConsecutiveUpgradeCandidates(),
                        decision.getPolicyVersion()));
    }

    private static ReportSnapshot.RatioView view(Ratio ratio) {
        return ratio == null
                ? null
                : new ReportSnapshot.RatioView(
                        ratio.numerator(), ratio.denominator(), ratio.value());
    }
}
