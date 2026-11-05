package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.scenario.dto.ScenarioReviewResponse;
import com.neuringo.neuringobe.scenario.dto.ScenarioReviewSnapshot;
import com.neuringo.neuringobe.scenario.repository.ScenarioReviewViewRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ScenarioReviewViewService {
    private final InitialScenarioPreparationService preparations;
    private final ScenarioReviewViewRepository views;

    public ScenarioReviewViewService(
            InitialScenarioPreparationService preparations, ScenarioReviewViewRepository views) {
        this.preparations = preparations;
        this.views = views;
    }

    public ScenarioReviewResponse response(
            ScenarioReviewSnapshot snapshot, Authentication authentication) {
        var input = preparations.prepare(snapshot.activityId(), authentication);
        if (snapshot.candidate() == null) throw changed();
        var content =
                views.find(snapshot, input.learningGoal().instructorId())
                        .orElseThrow(ScenarioReviewViewService::changed);
        if (!content.inputFingerprint().equals(ScenarioInputFingerprint.of(input))) throw changed();
        var goal = input.learningGoal();
        var conditions =
                new ScenarioReviewResponse.Conditions(
                        goal.title(),
                        goal.category(),
                        goal.situationType(),
                        goal.characters(),
                        goal.requiredElements(),
                        goal.forbiddenExpressions(),
                        input.initialDifficulty().scenarioLevel());
        return new ScenarioReviewResponse(
                snapshot.reviewId(),
                snapshot.activityId(),
                snapshot.revision(),
                conditions,
                content.scenario());
    }

    private static ApiException changed() {
        return new ApiException(
                HttpStatus.CONFLICT, "SCENARIO_REVIEW_CHANGED", "검토 상태가 변경되었습니다. 다시 불러와 주세요.");
    }
}
