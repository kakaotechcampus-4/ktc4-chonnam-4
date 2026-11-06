package com.neuringo.neuringobe.report.domain;

import com.neuringo.neuringobe.scenario.domain.GoalOutcome;
import com.neuringo.neuringobe.scenario.domain.GoalScoreRate;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 리포트의 정량 지표. 모두 0~1 로 정규화하고, 잴 수 없으면(분모 0) null 이다. AI 요약 문장과 따로 계산해서, 요약이 틀려도 숫자는 기록에서 바로 나온다.
 *
 * <ul>
 *   <li>quizAccuracy: 유효 문항 중 첫 응답 정답
 *   <li>quizHintRecovery: 첫 응답이 틀린 유효 문항 중 힌트 뒤 해결
 *   <li>roleplayScore: VS-016 정규화 점수율(GoalScoreRate). 후속 난이도와 같은 값이다
 *   <li>goalAchievement: 유효 목표 중 S0~S2 에서 도달(S3 정답 제공 제외)
 *   <li>independentAchievement: 유효 목표 중 시작 단계보다 지원을 더 받지 않고 도달
 * </ul>
 */
public record LearningMetrics(
        Ratio quizAccuracy,
        Ratio quizHintRecovery,
        Ratio roleplayScore,
        Ratio goalAchievement,
        Ratio independentAchievement,
        int validGoalCount,
        int excludedGoalCount,
        int technicalExclusionCount) {

    public static LearningMetrics from(ReportEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        QuizEvidence quiz = evidence.quiz();
        List<GoalOutcome> outcomes = evidence.goals().stream().map(GoalEvidence::outcome).toList();
        List<GoalOutcome> valid = outcomes.stream().filter(GoalOutcome::valid).toList();
        GoalScoreRate score = GoalScoreRate.of(outcomes);

        return new LearningMetrics(
                quiz == null
                        ? null
                        : Ratio.ofOrNull(quiz.correctQuestionCount(), quiz.validQuestionCount()),
                quiz == null
                        ? null
                        : Ratio.ofOrNull(
                                quiz.resolvedAfterHintCount(),
                                quiz.validQuestionCount() - quiz.correctQuestionCount()),
                Ratio.ofOrNull(score.scaledPoints(), score.maxScaledPoints()),
                Ratio.ofOrNull(count(valid, GoalOutcome::reachedWithoutAnswer), valid.size()),
                Ratio.ofOrNull(count(valid, GoalOutcome::reachedWithoutExtraSupport), valid.size()),
                valid.size(),
                outcomes.size() - valid.size(),
                evidence.technicalExclusions().size());
    }

    private static int count(List<GoalOutcome> outcomes, Predicate<GoalOutcome> matches) {
        return (int) outcomes.stream().filter(matches).count();
    }
}
