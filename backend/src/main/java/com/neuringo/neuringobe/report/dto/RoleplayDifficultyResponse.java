package com.neuringo.neuringobe.report.dto;

import com.neuringo.neuringobe.scenario.domain.FollowUpDifficulty;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 아동의 다음 역할극 난이도(가장 최근 결정). 활동 만들기 확인 단계와 추천 화면이 쓴다.
 *
 * @param scenarioLevel 다음 역할극 레벨
 * @param initialSupportLevel 다음 역할극의 시작 지원
 * @param scoreNumerator 점수율 분자. 분모는 유효 목표 수의 6배다
 * @param sourceActivityId 이 결정을 낸 역할극 활동
 * @param sourceRecordId 그 활동의 학습 기록. AI 요약이 실패해 리포트가 보이지 않아도 이 ID 로 추천을 읽을 수 있다
 */
public record RoleplayDifficultyResponse(
        UUID childId,
        String scenarioLevel,
        String initialSupportLevel,
        String previousLevel,
        String direction,
        String reason,
        int scoreNumerator,
        int scoreDenominator,
        int consecutiveUpgradeCandidates,
        String policyVersion,
        UUID sourceActivityId,
        UUID sourceRecordId,
        OffsetDateTime decidedAt) {

    public static RoleplayDifficultyResponse from(FollowUpDifficulty decision, UUID recordId) {
        return new RoleplayDifficultyResponse(
                decision.getChildId(),
                decision.getNextLevel().name(),
                decision.getNextSupportLevel().name(),
                decision.getPreviousLevel().name(),
                decision.getDirection().name(),
                decision.getReason().name(),
                decision.getScoreNumerator(),
                decision.getScoreDenominator(),
                decision.getConsecutiveUpgradeCandidates(),
                decision.getPolicyVersion(),
                decision.getActivityId(),
                recordId,
                decision.getDecidedAt().atOffset(ZoneOffset.ofHours(9)));
    }
}
