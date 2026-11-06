package com.neuringo.neuringobe.ai.application.report;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput.Evidence;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput.EvidenceKind;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import com.neuringo.neuringobe.ai.application.structured.output.ReportItem;
import com.neuringo.neuringobe.ai.application.structured.output.ReportReview;
import com.neuringo.neuringobe.ai.application.structured.output.ReportReviewDecision;
import com.neuringo.neuringobe.ai.application.structured.output.ReportReviewScores;
import com.neuringo.neuringobe.ai.application.structured.output.RevisionInstruction;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 리포트 루프 테스트 입력. E4 는 기술 오류 근거다. */
public final class ReportTestFixtures {

    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    private ReportTestFixtures() {}

    /** 목표 상태가 붙은 근거. E1·E2 는 어려움 없이 도달, E3 는 지원을 더 받고 도달, E4 는 목표와 상관없는 퀴즈 결과다. */
    public static LearningReportInput placementInput() {
        return new LearningReportInput(
                List.of(
                        new Evidence(
                                "E1",
                                EvidenceKind.GOAL_RESULT,
                                "감정 이름",
                                "시작 지원 S0(열린 질문), S0(열린 질문)에서 도달",
                                LearningReportInput.GoalStatus.NO_SUPPORT_NEEDED),
                        new Evidence(
                                "E2",
                                EvidenceKind.OBSERVATION,
                                "감정 이유",
                                "단서를 듣고 바로 이유를 말함",
                                LearningReportInput.GoalStatus.REACHED_AT_START),
                        new Evidence(
                                "E3",
                                EvidenceKind.GOAL_RESULT,
                                "상대 마음",
                                "시작 지원 S0(열린 질문), S2(두 선택지)에서 도달",
                                LearningReportInput.GoalStatus.EXTRA_SUPPORT),
                        new Evidence(
                                "E4", EvidenceKind.QUIZ_RESULT, null, "유효 문항 3개 중 첫 응답 정답 1개")),
                Map.of(),
                null);
    }

    public static LearningReportInput input() {
        return new LearningReportInput(
                List.of(
                        new Evidence(
                                "E1", EvidenceKind.OBSERVATION, "감정 이유", "상황 단서를 받은 뒤 감정과 사건을 연결함"),
                        new Evidence("E2", EvidenceKind.EFFECTIVE_SUPPORT, "감정 이유", "상황 단서"),
                        new Evidence(
                                "E3",
                                EvidenceKind.GOAL_RESULT,
                                "상대 마음",
                                "시작 지원 S0(열린 질문), S2(두 선택지)에서 도달"),
                        new Evidence(
                                "E4",
                                EvidenceKind.TECHNICAL,
                                null,
                                "기술 오류로 제외: STT_LOW_CONFIDENCE")),
                Map.of("roleplay_score", 0.67),
                new LearningReportInput.Difficulty(
                        "FOLLOW_UP", "L2", "SCORE_IN_KEEP_RANGE", "FOLLOW_UP_ROLEPLAY_V1"));
    }

    public static ReportDraft goodDraft() {
        return draft(
                "상황 단서가 있을 때 감정과 사건을 연결해 말했어요.",
                List.of(item("감정과 사건을 연결해 말했어요", "E1")),
                List.of(item("상황 단서가 도움이 됐어요", "E2")),
                List.of(item("상대 마음은 두 선택지를 받고 도달했어요", "E3")));
    }

    public static ReportDraft draft(
            String summary,
            List<ReportItem> observations,
            List<ReportItem> supports,
            List<ReportItem> difficulties) {
        return new ReportDraft(summary, observations, supports, difficulties);
    }

    public static ReportItem item(String text, String... evidenceIds) {
        return new ReportItem(text, List.of(evidenceIds));
    }

    public static ReportReviewScores scores(int each) {
        return new ReportReviewScores(each, each, each, each, each);
    }

    public static ReportReview pass() {
        return new ReportReview(ReportReviewDecision.PASS, scores(4), List.of(), null);
    }

    public static ReportReview revise(String... codes) {
        return new ReportReview(
                ReportReviewDecision.REVISE,
                new ReportReviewScores(2, 4, 4, 3, 4),
                List.of(codes),
                new RevisionInstruction(
                        List.of("상황 단서 문장"), List.of("근거에 있는 행동만 쓴다"), List.of(), List.of()));
    }

    public static <T> AiCallResult<T> success(T data, AiOperation operation) {
        return new AiCallResult.Success<>(data, metadata(operation));
    }

    public static <T> AiCallResult<T> failure(AiFailureType type, AiOperation operation) {
        return new AiCallResult.Failure<>(new AiFailure(type, null), metadata(operation));
    }

    public static AiCallMetadata metadata(AiOperation operation) {
        return new AiCallMetadata(
                REQUEST_ID,
                operation,
                "fake",
                "fake-model",
                "v1",
                "v1",
                null,
                1,
                1,
                10,
                10,
                "stop");
    }
}
