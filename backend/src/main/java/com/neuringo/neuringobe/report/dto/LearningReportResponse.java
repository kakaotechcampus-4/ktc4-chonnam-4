package com.neuringo.neuringobe.report.dto;

import com.neuringo.neuringobe.report.application.ReportNarrative;
import com.neuringo.neuringobe.report.application.ReportSnapshot;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API-REC-01·02 의 완료 리포트(contracts/api/v1/reports-recommendations/get-learning-report.md).
 *
 * @param completedAt 활동을 완료한 시각(학습 기록이 생긴 시각, +09:00)
 * @param summary AI 요약 한 줄. 생성·평가 루프를 통과한 것만 내보낸다
 * @param quiz 최초 결합 활동의 퀴즈 결과. 역할극 단독 활동은 null
 * @param metrics 서버가 계산한 지표(분자·분모 포함)
 * @param narrative AI 초안의 관찰·효과적인 지원·어려운 지점 항목과 근거 ID
 * @param activityType 활동 유형(최초 결합·역할극). 리포트 목록 표의 유형 칸
 * @param childName 아동 표시 이름. 학급 리포트 목록에서 누구의 리포트인지 보인다
 * @param goalTitle 활동의 학습 목표 제목. 목표가 없는 활동은 null
 */
public record LearningReportResponse(
        UUID recordId,
        UUID activityId,
        UUID childId,
        OffsetDateTime completedAt,
        String summary,
        ReportSnapshot.Quiz quiz,
        ReportSnapshot.Roleplay roleplay,
        ReportSnapshot.Difficulty difficultyDecision,
        ReportSnapshot.Metrics metrics,
        ReportNarrative narrative,
        String activityType,
        String childName,
        String goalTitle) {}
