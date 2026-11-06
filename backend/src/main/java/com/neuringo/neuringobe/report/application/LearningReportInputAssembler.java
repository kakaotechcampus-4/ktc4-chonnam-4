package com.neuringo.neuringobe.report.application;

import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput.Evidence;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput.EvidenceKind;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput.GoalStatus;
import com.neuringo.neuringobe.report.domain.DifficultyBasis;
import com.neuringo.neuringobe.report.domain.GoalEvidence;
import com.neuringo.neuringobe.report.domain.LearningMetrics;
import com.neuringo.neuringobe.report.domain.QuizEvidence;
import com.neuringo.neuringobe.report.domain.Ratio;
import com.neuringo.neuringobe.report.domain.ReportEvidence;
import com.neuringo.neuringobe.scenario.domain.GoalOutcome;
import com.neuringo.neuringobe.scenario.domain.SupportLevel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 리포트 근거를 프롬프트 입력으로 옮긴다. 근거마다 E1, E2 … 순서로 ID 를 붙여서 초안의 문장이 어느 근거에 기대는지 검사할 수 있게 한다.
 *
 * <p>기술 오류로 제외된 목표와 기술 오류 코드는 TECHNICAL 근거가 되어, 규칙 검사가 어려운 지점에 쓰이지 않았는지 볼 수 있다.
 */
public final class LearningReportInputAssembler {

    public LearningReportInput assemble(ReportEvidence evidence, LearningMetrics metrics) {
        List<Evidence> items = new ArrayList<>();
        QuizEvidence quiz = evidence.quiz();
        if (quiz != null) {
            items.add(
                    item(
                            items,
                            EvidenceKind.QUIZ_RESULT,
                            null,
                            "유효 문항 %d개 중 첫 응답 정답 %d개, 힌트 뒤 해결 %d개"
                                    .formatted(
                                            quiz.validQuestionCount(),
                                            quiz.correctQuestionCount(),
                                            quiz.resolvedAfterHintCount())));
        }
        for (GoalEvidence goal : evidence.goals()) {
            GoalOutcome outcome = goal.outcome();
            GoalStatus status = status(outcome);
            EvidenceKind resultKind =
                    outcome.result() == GoalOutcome.Result.EXCLUDED_TECHNICAL_ERROR
                            ? EvidenceKind.TECHNICAL
                            : EvidenceKind.GOAL_RESULT;
            items.add(item(items, resultKind, goal.goalTitle(), resultText(outcome), status));
            for (String observation : goal.observations()) {
                items.add(
                        item(
                                items,
                                EvidenceKind.OBSERVATION,
                                goal.goalTitle(),
                                observation,
                                status));
            }
            for (String support : goal.effectiveSupports()) {
                items.add(
                        item(
                                items,
                                EvidenceKind.EFFECTIVE_SUPPORT,
                                goal.goalTitle(),
                                support,
                                status));
            }
        }
        for (String code : evidence.technicalExclusions()) {
            items.add(item(items, EvidenceKind.TECHNICAL, null, "기술 오류로 제외: " + code));
        }
        return new LearningReportInput(
                items, metricSection(metrics), difficulty(evidence.difficulty()));
    }

    private static Evidence item(
            List<Evidence> existing, EvidenceKind kind, String goalTitle, String text) {
        return item(existing, kind, goalTitle, text, null);
    }

    private static Evidence item(
            List<Evidence> existing,
            EvidenceKind kind,
            String goalTitle,
            String text,
            GoalStatus status) {
        return new Evidence("E" + (existing.size() + 1), kind, goalTitle, text, status);
    }

    /** 강사 화면도 같은 분류로 목표를 표시한다(ReportSnapshots). */
    public static GoalStatus status(GoalOutcome outcome) {
        if (!outcome.valid()) return GoalStatus.EXCLUDED;
        if (outcome.result() == GoalOutcome.Result.NOT_REACHED) return GoalStatus.NOT_REACHED;
        if (outcome.finalSupport() == SupportLevel.S3) return GoalStatus.ANSWER_GIVEN;
        if (!outcome.reachedWithoutExtraSupport()) return GoalStatus.EXTRA_SUPPORT;
        return outcome.startSupport() == SupportLevel.S0
                ? GoalStatus.NO_SUPPORT_NEEDED
                : GoalStatus.REACHED_AT_START;
    }

    private static String resultText(GoalOutcome outcome) {
        String start = "시작 지원 " + describe(outcome.startSupport());
        return switch (outcome.result()) {
            case REACHED -> start + ", " + describe(outcome.finalSupport()) + "에서 도달";
            case NOT_REACHED -> start + ", " + describe(outcome.finalSupport()) + "까지 받고 도달하지 못함";
            case EXCLUDED_TECHNICAL_ERROR -> "기술 오류로 결과에서 제외";
            case EXCLUDED_INTERRUPTED -> "중단되어 결과에서 제외";
            case EXCLUDED_REFUSED -> "응답하지 않아 결과에서 제외";
        };
    }

    private static String describe(SupportLevel level) {
        return switch (level) {
            case S0 -> "S0(열린 질문)";
            case S1 -> "S1(단서)";
            case S2 -> "S2(두 선택지)";
            case S3 -> "S3(답 알려 주기)";
        };
    }

    private static Map<String, Double> metricSection(LearningMetrics metrics) {
        Map<String, Double> section = new LinkedHashMap<>();
        put(section, "quiz_accuracy", metrics.quizAccuracy());
        put(section, "quiz_hint_recovery", metrics.quizHintRecovery());
        put(section, "roleplay_score", metrics.roleplayScore());
        put(section, "goal_achievement", metrics.goalAchievement());
        put(section, "independent_achievement", metrics.independentAchievement());
        return section;
    }

    private static void put(Map<String, Double> section, String name, Ratio ratio) {
        if (ratio != null) section.put(name, ratio.value());
    }

    private static LearningReportInput.Difficulty difficulty(DifficultyBasis basis) {
        if (basis == null) return null;
        return new LearningReportInput.Difficulty(
                basis.decisionType().name(),
                basis.scenarioLevel().name(),
                basis.reasonCode(),
                basis.policyVersion());
    }
}
