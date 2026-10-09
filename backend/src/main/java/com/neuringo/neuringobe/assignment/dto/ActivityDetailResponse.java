package com.neuringo.neuringobe.assignment.dto;

import com.neuringo.neuringobe.activity.dto.ActivityResponse;
import com.neuringo.neuringobe.quiz.dto.AssignedQuizItemResponse;
import java.util.List;

/**
 * 정본 05 `GET /activities/{activityId}` 응답. 역할극 세션 요약과 학습 기록은 그 기능이 생기기 전이라 S1 에서는 항상 null 이다. 문항은
 * 아동에게도 보이는 모양(정답·힌트 내용 제외)이다.
 */
public record ActivityDetailResponse(
        ActivityResponse activity,
        List<AssignedQuizItemResponse> quizItems,
        Object sessionSummary,
        Object learningRecord) {

    public static ActivityDetailResponse of(
            ActivityResponse activity, List<AssignedQuizItemResponse> quizItems) {
        return new ActivityDetailResponse(activity, quizItems, null, null);
    }
}
