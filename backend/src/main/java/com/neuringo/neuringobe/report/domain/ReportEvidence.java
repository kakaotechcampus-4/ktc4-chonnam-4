package com.neuringo.neuringobe.report.domain;

import java.util.List;

/**
 * 완료 활동 하나의 리포트 입력. 퀴즈만 있는 활동이면 goals 가 비고, 역할극만 있는 활동이면 quiz 가 null 이다.
 *
 * @param technicalExclusions 기술 오류 코드(예: STT_LOW_CONFIDENCE). 아동의 어려움으로 쓰지 않고 따로 표시한다.
 * @param difficulty 이 활동에 적용한 난이도 근거. 아직 없으면 null.
 */
public record ReportEvidence(
        QuizEvidence quiz,
        List<GoalEvidence> goals,
        List<String> technicalExclusions,
        DifficultyBasis difficulty) {

    public ReportEvidence {
        goals = goals == null ? List.of() : List.copyOf(goals);
        technicalExclusions =
                technicalExclusions == null ? List.of() : List.copyOf(technicalExclusions);
        if (quiz == null && goals.isEmpty()) {
            throw new IllegalArgumentException("a report needs quiz or role-play evidence");
        }
    }
}
