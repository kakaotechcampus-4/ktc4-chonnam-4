package com.neuringo.neuringobe.report.application;

import java.util.List;
import java.util.UUID;

/**
 * 강사 화면에 보일 서버 계산값. 역할극이 끝난 직후 learning_report.snapshot 에 JSON 으로 두고, 리포트 조회 응답의 quiz·roleplay·
 * difficultyDecision 칸이 그대로 이 값이다. LLM 결과와 상관없이 만들어지고 바뀌지 않는다.
 *
 * @param quiz 최초 결합 활동의 퀴즈 결과. 역할극 단독 활동이면 null
 */
public record ReportSnapshot(
        Quiz quiz, Roleplay roleplay, Metrics metrics, Difficulty difficultyDecision) {

    public record Quiz(
            int validQuestionCount,
            int correctQuestionCount,
            Double overallAccuracy,
            int resolvedAfterHintCount) {}

    /**
     * @param observations 목표마다 남긴 관찰 문장을 순서대로 모은 것(계약의 roleplay.observations)
     * @param effectiveSupport 지원을 더 받고 도달한 목표에서 효과가 있었던 지원 이름
     * @param technicalExclusions 기술 오류 코드. 아동의 어려움으로 쓰지 않는다
     */
    public record Roleplay(
            String scenarioLevel,
            String startSupportLevel,
            List<Goal> goals,
            List<String> observations,
            List<String> effectiveSupport,
            List<String> technicalExclusions) {}

    /**
     * @param result REACHED·NOT_REACHED·EXCLUDED_* (GoalOutcome.Result)
     * @param status 강사 화면 표기용 분류(LearningReportInput.GoalStatus): 혼자·처음 지원으로·지원을 더 받고·답을 듣고·못 함·제외
     */
    public record Goal(
            UUID microGoalId,
            String title,
            String startSupport,
            String finalSupport,
            String result,
            String status,
            List<Criterion> criteria,
            List<String> observations) {}

    /** 관찰 기준 문장과 마지막 관찰 상태(EXPLICIT 등). 관찰하지 않았으면 status 가 null. */
    public record Criterion(String description, String status) {}

    public record Metrics(
            RatioView roleplayScore,
            RatioView goalAchievement,
            RatioView independentAchievement,
            RatioView quizAccuracy,
            RatioView quizHintRecovery,
            int validGoalCount,
            int excludedGoalCount) {}

    public record RatioView(int numerator, int denominator, double value) {}

    /**
     * 이 역할극을 마친 뒤 정한 다음 역할극 난이도(VS-016).
     *
     * @param scenarioLevel 다음 역할극 레벨
     * @param previousLevel 이번에 한 역할극 레벨
     * @param nextSupportLevel 다음 역할극의 시작 지원
     * @param scoreRate 점수율. 분모는 유효 목표 수의 6배다
     */
    public record Difficulty(
            String decisionType,
            String scenarioLevel,
            String previousLevel,
            String nextSupportLevel,
            String direction,
            String reason,
            RatioView scoreRate,
            int consecutiveUpgradeCandidates,
            String policyVersion) {}
}
