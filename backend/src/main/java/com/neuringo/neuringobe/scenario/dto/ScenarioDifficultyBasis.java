package com.neuringo.neuringobe.scenario.dto;

import com.neuringo.neuringobe.quiz.dto.InitialDifficultyEvidence;
import com.neuringo.neuringobe.scenario.domain.ScenarioDifficultySource;
import java.util.Objects;
import java.util.UUID;

/**
 * 시나리오 검토에 쓰는 이 활동의 난이도 근거. 어디서 정했든 레벨·시작 지원·정책 버전만 같은 뜻으로 담는다.
 *
 * <p>INITIAL_QUIZ 는 최초 결합 퀴즈 결과에 저장한 최초 난이도다. FOLLOW_UP_PINNED 는 역할극 단독 활동을 배정할 때
 * roleplay_activity_difficulty 에 고정한 다음 역할극 난이도이고, sourceDecisionId 가 그 값을 정한 결정이다. 고정값은 배정 뒤 바뀌지
 * 않으므로 다른 역할극이 끝나 새 결정이 생겨도 이 활동의 근거는 그대로다.
 */
public record ScenarioDifficultyBasis(
        UUID activityId,
        UUID childId,
        UUID goalId,
        String scenarioLevel,
        String initialSupportLevel,
        String policyVersion,
        ScenarioDifficultySource source,
        UUID sourceDecisionId) {

    public ScenarioDifficultyBasis {
        Objects.requireNonNull(activityId);
        Objects.requireNonNull(childId);
        Objects.requireNonNull(goalId);
        Objects.requireNonNull(scenarioLevel);
        Objects.requireNonNull(policyVersion);
        Objects.requireNonNull(source);
        if ((source == ScenarioDifficultySource.FOLLOW_UP_PINNED) != (sourceDecisionId != null)) {
            throw new IllegalArgumentException("고정 난이도만 결정 ID 를 가진다.");
        }
    }

    public static ScenarioDifficultyBasis initial(InitialDifficultyEvidence evidence) {
        return new ScenarioDifficultyBasis(
                evidence.activityId(),
                evidence.childId(),
                evidence.goalId(),
                evidence.scenarioLevel(),
                evidence.initialSupportLevel(),
                evidence.policyVersion(),
                ScenarioDifficultySource.INITIAL_QUIZ,
                null);
    }

    public static ScenarioDifficultyBasis followUp(
            UUID activityId,
            UUID childId,
            UUID goalId,
            String scenarioLevel,
            String initialSupportLevel,
            String policyVersion,
            UUID sourceDecisionId) {
        return new ScenarioDifficultyBasis(
                activityId,
                childId,
                goalId,
                scenarioLevel,
                initialSupportLevel,
                policyVersion,
                ScenarioDifficultySource.FOLLOW_UP_PINNED,
                Objects.requireNonNull(sourceDecisionId));
    }
}
