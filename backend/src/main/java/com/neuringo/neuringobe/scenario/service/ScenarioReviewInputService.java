package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.scenario.dto.ScenarioPreparationInput;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공통 시나리오 검토의 입력을 활동에 맞는 어댑터로 만든다(PR #101 통합 인계 "첫/후속 입력 어댑터 분리").
 *
 * <p>배정 때 다음 역할극 난이도를 고정한 역할극 단독(ROLEPLAY + roleplay_activity_difficulty)은 그 고정값을, 그 밖에는 퀴즈 결과의 최초
 * 난이도를 쓴다. 기준을 유형만이 아니라 고정 행으로 잡아서, 고정 근거가 없는 활동은 지금처럼 최초 어댑터가 퀴즈 근거로 판단하고 없으면 404·409 로 거절한다.
 * 검토·확정·생성 쪽은 이 서비스만 부르고 출처를 따로 보지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class ScenarioReviewInputService {
    private final ActivityRepository activities;
    private final InitialScenarioPreparationService initial;
    private final FollowUpScenarioPreparationService followUp;

    public ScenarioReviewInputService(
            ActivityRepository activities,
            InitialScenarioPreparationService initial,
            FollowUpScenarioPreparationService followUp) {
        this.activities = activities;
        this.initial = initial;
        this.followUp = followUp;
    }

    public ScenarioPreparationInput prepare(UUID activityId, Authentication authentication) {
        var pinned =
                activities
                        .findById(activityId)
                        .filter(found -> found.getActivityType() == ActivityType.ROLEPLAY)
                        .filter(found -> followUp.hasPinnedDifficulty(found.getActivityId()));
        if (pinned.isPresent()) {
            return followUp.prepare(pinned.get(), authentication);
        }
        return initial.prepare(activityId, authentication);
    }
}
